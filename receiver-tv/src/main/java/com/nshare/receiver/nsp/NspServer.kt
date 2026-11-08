package com.nshare.receiver.nsp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.nshare.airplay.AudioFormat
import com.nshare.airplay.AudioPacket
import com.nshare.airplay.ClientInfo
import com.nshare.airplay.SessionSinks
import com.nshare.airplay.VideoCodec
import com.nshare.airplay.VideoConfig
import com.nshare.airplay.VideoFrame
import com.nshare.airplay.time.ClockSync
import com.nshare.airplay.time.NtpClient
import com.nshare.airplay.util.H264Sps
import com.nshare.protocol.AudioConfigCodec
import com.nshare.protocol.Caps
import com.nshare.protocol.DeviceKey
import com.nshare.protocol.FrameReader
import com.nshare.protocol.FrameWriter
import com.nshare.protocol.Nsp
import com.nshare.protocol.NspRefusedException
import com.nshare.protocol.NspTls
import com.nshare.protocol.PinningTrustManager
import com.nshare.protocol.ServerHandshake
import com.nshare.protocol.ServerPolicy
import com.nshare.protocol.VideoConfigCodec
import com.nshare.receiver.PlayerHub
import com.nshare.receiver.R
import com.nshare.receiver.player.MirrorPlayer
import com.nshare.receiver.player.VideoRenderer
import com.nshare.receiver.security.SecuritySettings
import com.nshare.receiver.security.TrustedDevices
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Receives NShare protocol v2 streams from NShare sender apps: TLS 1.3 with mutual certificate
 * authentication, then pairing / trust / approval ([ServerHandshake]), then media.
 */
class NspServer(private val context: Context, private val caps: Caps) {
    private var server: ServerSocket? = null
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private val pendingHandshakes = AtomicInteger()
    private val scheduler = Executors.newScheduledThreadPool(2) { r -> Thread(r, "nsp-timer").apply { isDaemon = true } }
    private val security = SecuritySettings(context)
    private val trusted = TrustedDevices(context)
    private lateinit var deviceKey: DeviceKey
    private lateinit var tls: SSLContext
    @Volatile private var running = false

    fun start() {
        deviceKey = DeviceKey.load("nshare-receiver", "NShare TV")
        // Any well-formed self-signed client certificate may start the handshake; ServerHandshake
        // then decides trust (known device / PIN pairing / trust on first use).
        tls = NspTls.context(deviceKey.keyManagers(), PinningTrustManager { true })
        val ss = ServerSocket().apply {
            reuseAddress = true
            receiveBufferSize = 8 * 1024 * 1024
            try {
                bind(InetSocketAddress(Nsp.DEFAULT_PORT))
            } catch (e: BindException) {
                bind(InetSocketAddress(0))
            }
        }
        server = ss
        running = true
        Thread({ acceptLoop(ss) }, "nsp-accept").apply {
            isDaemon = true
            start()
        }
        register(ss.localPort)
        Log.i(TAG, "NShare v${Nsp.VERSION} receiver listening on ${ss.localPort}, key ${NspTls.shortFingerprint(deviceKey.fingerprint)}")
    }

    fun stop() {
        running = false
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        runCatching { server?.close() }
        server = null
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = caps.receiverName
            serviceType = Nsp.SERVICE_TYPE
            this.port = port
            setAttribute("v", Nsp.VERSION.toString())
            setAttribute("fp", NspTls.shortFingerprint(deviceKey.fingerprint))
            setAttribute("w", caps.maxWidth.toString())
            setAttribute("h", caps.maxHeight.toString())
            setAttribute("codecs", if (caps.hevc) "h265,h264" else "h264")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                Log.i(TAG, "registered ${i.serviceName} ${Nsp.SERVICE_TYPE}")
            }

            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Log.e(TAG, "registration failed $code")
            }

            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        registration = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) Log.e(TAG, "accept failed", e)
                break
            }
            // Unauthenticated peers get a bounded share of the receiver: at most 4 handshakes at once.
            if (pendingHandshakes.get() >= MAX_PENDING_HANDSHAKES) {
                Log.w(TAG, "too many pending handshakes, refusing ${s.inetAddress.hostAddress}")
                runCatching { s.close() }
                continue
            }
            pendingHandshakes.incrementAndGet()
            Thread({ Session(s).run() }, "nsp-${s.inetAddress.hostAddress}").apply {
                isDaemon = true
                start()
            }
        }
    }

    private inner class Session(private val plain: Socket) {
        @Volatile private var socket: Socket = plain
        private var ntp: NtpClient? = null
        private var control: java.util.concurrent.ExecutorService? = null
        private var pinger: ScheduledFuture<*>? = null
        private var player: MirrorPlayer? = null
        private var codec = VideoCodec.H264
        private var parameterSets = ByteArray(0)
        private var handshakePending = true
        @Volatile private var goodbye = false
        @Volatile private var writer: FrameWriter? = null

        private fun handshakeDone() {
            if (handshakePending) {
                handshakePending = false
                pendingHandshakes.decrementAndGet()
            }
        }

        fun run() {
            val address = plain.inetAddress.hostAddress ?: "?"
            // TLS + HELLO must finish within 10 s however slowly the peer trickles bytes; PIN
            // pairing gets its own, longer limit once HELLO arrived.
            var deadline: ScheduledFuture<*>? = scheduler.schedule({
                if (handshakePending) {
                    Log.w(TAG, "handshake from $address timed out")
                    close()
                }
            }, Nsp.HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            var established = false
            try {
                plain.tcpNoDelay = true
                plain.keepAlive = true
                plain.soTimeout = Nsp.HANDSHAKE_TIMEOUT_MS.toInt()
                val ssl = NspTls.server(tls, plain)
                socket = ssl
                try {
                    ssl.startHandshake()
                } catch (e: SSLException) {
                    // Version 1 senders speak plain TCP ("NS" magic) and fail here.
                    Log.w(TAG, "TLS handshake with $address failed: ${e.message}")
                    PlayerHub.showNotice(context.getString(R.string.notice_old_sender))
                    return
                }
                val peerFp = NspTls.peerFingerprint(ssl)
                val key = NspTls.hex(peerFp)
                val reader = FrameReader(DataInputStream(BufferedInputStream(ssl.inputStream, 512 * 1024)))
                val writer = FrameWriter(DataOutputStream(BufferedOutputStream(ssl.outputStream, 64 * 1024)))
                this.writer = writer

                val handshake = ServerHandshake(reader, writer, caps, Policy(key), deviceKey.fingerprint, peerFp)
                handshake.onHello = {
                    deadline?.cancel(false)
                    deadline = null
                    ssl.soTimeout = Nsp.PAIRING_TIMEOUT_MS
                }
                val hello = try {
                    handshake.run()
                } catch (e: NspRefusedException) {
                    Log.i(TAG, "handshake with $address refused: ${e.code} ${e.message}")
                    if (e.code == Nsp.Error.VERSION) PlayerHub.showNotice(context.getString(R.string.notice_old_sender))
                    return
                }
                handshakeDone()
                established = true
                Log.i(TAG, "sender ${hello.deviceName} (${hello.model}) at $address, key ${key.take(16)}")

                ssl.soTimeout = Nsp.IDLE_TIMEOUT_MS
                // Control messages (PING, key frame requests) go out on their own thread so the
                // video thread never blocks on the socket.
                val ctl = Executors.newSingleThreadExecutor()
                control = ctl
                val requestKeyFrame: () -> Unit = {
                    ctl.execute { runCatching { writer.write(Nsp.Type.KEYFRAME_REQUEST) } }
                }
                pinger = scheduler.scheduleWithFixedDelay({
                    ctl.execute { runCatching { writer.write(Nsp.Type.PING) } }
                }, Nsp.PING_INTERVAL_MS, Nsp.PING_INTERVAL_MS, TimeUnit.MILLISECONDS)

                // Same device reconnecting after a drop: continue the session that is still on screen.
                val resumed = PlayerHub.resumeSession(key, requestKeyFrame) { hangUp() }
                val clock = resumed?.clock ?: ClockSync()
                ntp = NtpClient(plain.inetAddress, hello.timingPort, clock).also { it.start() }
                val sinks: SessionSinks = resumed ?: PlayerHub.startSession(
                    context,
                    ClientInfo(address, hello.deviceName, hello.model, null),
                    clock,
                    requestKeyFrame,
                    key,
                ) { hangUp() }
                player = sinks as MirrorPlayer
                stream(reader, sinks, clock)
            } catch (e: Exception) {
                if (!goodbye) Log.i(TAG, "session $address ended: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                deadline?.cancel(false)
                handshakeDone()
                pinger?.cancel(false)
                close()
                control?.shutdownNow()
                ntp?.stop()
                val p = player
                if (p != null) {
                    // A broken connection (no BYE) may be the phone briefly losing Wi-Fi: keep the
                    // picture for a few seconds so it can resume.
                    if (established && !goodbye) PlayerHub.sessionDropped(p) else p.onSessionEnd()
                }
            }
        }

        private fun stream(reader: FrameReader, s: SessionSinks, clock: ClockSync) {
            while (!socket.isClosed) {
                val f = reader.read()
                val arrival = System.nanoTime()
                when (f.type) {
                    Nsp.Type.VIDEO_CONFIG -> {
                        val cfg = VideoConfigCodec.parse(f.payload)
                        codec = if (cfg.codec == Nsp.VideoCodecId.H265) VideoCodec.H265 else VideoCodec.H264
                        parameterSets = lowLatencyParameterSets(cfg.parameterSets)
                        Log.i(TAG, "video $codec ${cfg.width}x${cfg.height}")
                        s.video.onVideoConfig(VideoConfig(codec, parameterSets, cfg.width, cfg.height))
                    }
                    Nsp.Type.VIDEO_FRAME -> {
                        val data = if (f.isKeyFrame) withParameterSets(f.payload) else f.payload
                        clock.observeArrival(f.timeNs, arrival)
                        s.video.onVideoFrame(VideoFrame(data, f.timeNs, arrival, f.isKeyFrame))
                    }
                    Nsp.Type.AUDIO_CONFIG -> {
                        val a = AudioConfigCodec.parse(f.payload)
                        Log.i(TAG, "audio AAC ${a.sampleRate} Hz ${a.channels} ch")
                        s.audio.onAudioStart(AudioFormat(4, 1024, a.sampleRate, a.channels, a.csd))
                    }
                    Nsp.Type.AUDIO_FRAME -> s.audio.onAudioPacket(AudioPacket(f.payload, f.timeNs, arrival))
                    Nsp.Type.BYE -> {
                        goodbye = true
                        return
                    }
                    else -> Unit // PING keeps the idle timeout from firing
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
            runCatching { plain.close() }
        }

        /** Ends the session on purpose (TV user, or another sender took over): the sender must not reconnect. */
        fun hangUp() {
            goodbye = true
            runCatching { writer?.write(Nsp.Type.BYE) }
            close()
        }

        /** Trust decisions for this connection, backed by the TV settings. */
        private inner class Policy(private val key: String) : ServerPolicy {
            override val pinPairing: Boolean get() = security.nspPinPairing
            override fun isTrusted(fingerprintHex: String) = trusted.isTrusted(fingerprintHex)
            override fun trust(fingerprintHex: String, deviceName: String) = trusted.trust(fingerprintHex, deviceName)
            override fun showPin(pin: String, deviceName: String) = PlayerHub.showPin(pin, deviceName)
            override fun hidePin() = PlayerHub.hidePin()
            override fun needsApproval() = PlayerHub.needsApproval(context, key)
            override fun approve(deviceName: String, fingerprintHex: String) =
                PlayerHub.requestApproval(context, deviceName, fingerprintHex)
        }

        /** Rewrites H.264 SPS so the decoder outputs frames immediately (see [H264Sps]). */
        private fun lowLatencyParameterSets(ps: ByteArray): ByteArray {
            if (codec != VideoCodec.H264) return ps
            return VideoRenderer.splitAnnexB(ps).map { nal ->
                if (nal.size > 4 && (nal[4].toInt() and 0x1f) == 7) {
                    runCatching { START + H264Sps.patchLowLatency(nal.copyOfRange(4, nal.size)) }.getOrDefault(nal)
                } else {
                    nal
                }
            }.fold(ByteArray(0)) { acc, b -> acc + b }
        }

        /** Replaces in-band parameter sets of a key frame with our (patched) ones. */
        private fun withParameterSets(frame: ByteArray): ByteArray {
            if (parameterSets.isEmpty()) return frame
            val nals = VideoRenderer.splitAnnexB(frame)
            if (nals.isEmpty()) return parameterSets + frame
            val body = nals.filterNot { isParameterSet(it) }.fold(ByteArray(0)) { acc, b -> acc + b }
            return parameterSets + body
        }

        private fun isParameterSet(nal: ByteArray): Boolean {
            if (nal.size <= 4) return false
            val h = nal[4].toInt() and 0xff
            return when (codec) {
                VideoCodec.H264 -> (h and 0x1f) in 7..8
                VideoCodec.H265 -> ((h ushr 1) and 0x3f) in 32..34
            }
        }
    }

    companion object {
        private const val TAG = "NShare.Nsp"
        private const val MAX_PENDING_HANDSHAKES = 4
        private val START = byteArrayOf(0, 0, 0, 1)
    }
}
