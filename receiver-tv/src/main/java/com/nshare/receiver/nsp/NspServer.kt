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
import com.nshare.protocol.FrameReader
import com.nshare.protocol.FrameWriter
import com.nshare.protocol.Hello
import com.nshare.protocol.Nsp
import com.nshare.protocol.VideoConfigCodec
import com.nshare.receiver.PlayerHub
import com.nshare.receiver.player.VideoRenderer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Receives NShare protocol streams from NShare sender apps (Android, later Windows). */
class NspServer(private val context: Context, private val caps: Caps) {
    private var server: ServerSocket? = null
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    private val pendingHandshakes = AtomicInteger()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nsp-watchdog").apply { isDaemon = true } }
    @Volatile private var running = false

    fun start() {
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
        Log.i(TAG, "NShare receiver listening on ${ss.localPort}")
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

    private inner class Session(private val socket: Socket) {
        private var ntp: NtpClient? = null
        private var controlExecutor: java.util.concurrent.ExecutorService? = null
        private var sinks: SessionSinks? = null
        private var codec = VideoCodec.H264
        private var parameterSets = ByteArray(0)

        private var handshakePending = true

        private fun handshakeDone() {
            if (handshakePending) {
                handshakePending = false
                pendingHandshakes.decrementAndGet()
            }
        }

        fun run() {
            val address = socket.inetAddress.hostAddress ?: "?"
            // The whole handshake must finish within 10 s, however slowly the peer trickles bytes.
            val deadline = watchdog.schedule({
                if (handshakePending) {
                    Log.w(TAG, "handshake from $address timed out")
                    close()
                }
            }, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt()
                val reader = FrameReader(DataInputStream(BufferedInputStream(socket.getInputStream(), 512 * 1024)))
                val writer = FrameWriter(DataOutputStream(BufferedOutputStream(socket.getOutputStream())))

                val first = reader.read(Nsp.MAX_HANDSHAKE_PAYLOAD)
                if (first.type != Nsp.Type.HELLO) throw IllegalStateException("expected HELLO, got ${first.type}")
                val hello = Hello.decode(first.payload)
                Log.i(TAG, "sender ${hello.deviceName} (${hello.model}) at $address, protocol v${hello.protocolVersion}")
                writer.write(Nsp.Type.CAPS, payload = caps.encode())
                handshakeDone()
                deadline.cancel(false)
                socket.soTimeout = 0

                val clock = ClockSync()
                ntp = NtpClient(socket.inetAddress, hello.timingPort, clock).also { it.start() }
                val client = ClientInfo(address, hello.deviceName, hello.model, null)
                // Control messages go out on their own thread so the video thread never blocks on the socket.
                val control = java.util.concurrent.Executors.newSingleThreadExecutor()
                controlExecutor = control
                val requestKeyFrame: () -> Unit = {
                    control.execute { runCatching { writer.write(Nsp.Type.KEYFRAME_REQUEST) } }
                }
                val s = PlayerHub.startSession(context, client, clock, requestKeyFrame) { close() }
                sinks = s

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
                        Nsp.Type.BYE -> break
                        else -> Unit
                    }
                }
            } catch (_: EOFException) {
            } catch (e: Exception) {
                Log.i(TAG, "session $address ended: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                deadline.cancel(false)
                handshakeDone()
                close()
                controlExecutor?.shutdownNow()
                ntp?.stop()
                sinks?.onSessionEnd()
                sinks = null
            }
        }

        fun close() {
            runCatching { socket.close() }
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
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L
        private val START = byteArrayOf(0, 0, 0, 1)
    }
}
