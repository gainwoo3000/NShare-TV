package com.nshare.airplay.server

import android.util.Log
import com.nshare.airplay.AirPlayAccessMode
import com.nshare.airplay.AudioFormat
import com.nshare.airplay.ClientInfo
import com.nshare.airplay.ReceiverConfig
import com.nshare.airplay.SessionSinks
import com.nshare.airplay.crypto.DigestAuth
import com.nshare.airplay.crypto.FairPlay
import com.nshare.airplay.crypto.Identity
import com.nshare.airplay.crypto.PairVerify
import com.nshare.airplay.crypto.SrpServer
import com.nshare.airplay.plist.BinaryPlist
import com.nshare.airplay.plist.data
import com.nshare.airplay.plist.list
import com.nshare.airplay.plist.long
import com.nshare.airplay.plist.string
import com.nshare.airplay.rtsp.RtspReader
import com.nshare.airplay.rtsp.RtspRequest
import com.nshare.airplay.rtsp.RtspResponse
import com.nshare.airplay.stream.AudioReceiver
import com.nshare.airplay.stream.MirrorReceiver
import com.nshare.airplay.time.ClockSync
import com.nshare.airplay.time.NtpClient
import com.nshare.airplay.util.Bytes
import java.net.Socket

/** One RTSP control connection from a sender, and the media streams it sets up. */
internal class RtspSession(
    private val socket: Socket,
    private val config: ReceiverConfig,
    private val identity: Identity,
    private val server: AirPlayServer,
) {
    val remoteAddress: String = socket.inetAddress.hostAddress ?: "?"

    private val fairPlay = FairPlay()
    private var pairVerify: PairVerify? = null

    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private var clock: ClockSync? = null
    private var ntp: NtpClient? = null
    private var mirror: MirrorReceiver? = null
    private var audio: AudioReceiver? = null
    private var sinks: SessionSinks? = null
    private var client: ClientInfo? = null

    @Volatile private var generation = 0
    @Volatile var isStreaming = false

    /** Last time anything arrived from the sender (requests incl. /feedback, video or audio data). */
    fun lastActivityNs(): Long = maxOf(lastRequestNs, mirror?.lastDataNs ?: 0L, audio?.lastDataNs ?: 0L)

    @Volatile private var lastRequestNs = System.nanoTime()
        private set

    fun run() {
        val reader = RtspReader(socket.getInputStream().buffered())
        val out = socket.getOutputStream()
        try {
            while (!socket.isClosed) {
                val req = reader.read() ?: break
                lastRequestNs = System.nanoTime()
                val res = RtspResponse(req.protocol)
                try {
                    handle(req, res)
                } catch (e: Exception) {
                    Log.e(TAG, "handler failed for $req", e)
                    res.status(500, "Internal Server Error")
                }
                if (req.cseq != null) {
                    res.header("CSeq", req.cseq!!)
                    if (req.method != "RECORD") res.header("Audio-Jack-Status", "connected; type=digital")
                }
                res.header("Server", "AirTunes/${ReceiverConfig.SOURCE_VERSION}")
                if (req.path != "/feedback") Log.d(TAG, "$req -> ${res.status}")
                res.writeTo(out)
                if (res.disconnect) break
            }
        } catch (e: Exception) {
            Log.i(TAG, "connection $remoteAddress closed: ${e.message}")
        } finally {
            close()
        }
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
        stopStreams()
    }

    @Synchronized
    private fun handle(req: RtspRequest, res: RtspResponse) {
        when (req.method) {
            "GET" -> if (req.path.startsWith("/info")) handleInfo(req, res) else res.status(404, "Not Found")
            "POST" -> when (req.path) {
                "/pair-setup" -> handlePairSetup(req, res)
                "/pair-pin-start" -> handlePairPinStart(res)
                "/pair-setup-pin" -> handlePairSetupPin(req, res)
                "/pair-verify" -> handlePairVerify(req, res)
                "/fp-setup" -> handleFpSetup(req, res)
                "/feedback", "/audioMode", "/command" -> Unit
                else -> res.status(404, "Not Found")
            }
            "OPTIONS" -> res.header(
                "Public",
                "SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET",
            )
            "SETUP" -> handleSetup(req, res)
            "RECORD" -> {
                res.header("Audio-Latency", "11025")
                res.header("Audio-Jack-Status", "connected; type=analog")
            }
            "GET_PARAMETER" -> handleGetParameter(req, res)
            "SET_PARAMETER" -> handleSetParameter(req)
            "FLUSH" -> audio?.flush()
            "TEARDOWN" -> handleTeardown(req, res)
            else -> res.status(501, "Not Implemented")
        }
    }

    // ------------------------------------------------------------------ discovery / pairing

    private fun handleInfo(req: RtspRequest, res: RtspResponse) {
        val qualifier = if (BinaryPlist.isBinaryPlist(req.body)) {
            ((BinaryPlist.decode(req.body) as? Map<*, *>)?.get("qualifier") as? List<*>)?.firstOrNull() as? String
        } else {
            null
        }
        val info = LinkedHashMap<String, Any?>()
        if (qualifier == "txtAirPlay") {
            info["txtAirPlay"] = Mdns.txtRecordBytes(Mdns.airplayTxt(config, identity, server.access.mode))
            res.body(PLIST, BinaryPlist.encode(info))
            return
        }
        if (qualifier == "txtRAOP") {
            info["txtRAOP"] = Mdns.txtRecordBytes(Mdns.raopTxt(config, identity, server.access.mode))
            res.body(PLIST, BinaryPlist.encode(info))
            return
        }
        info["deviceID"] = config.deviceId
        info["macAddress"] = config.deviceId
        info["pk"] = identity.publicKey
        info["features"] = config.features
        info["name"] = config.name
        info["pi"] = config.pairingId
        info["vv"] = 2
        info["statusFlags"] = 68
        info["keepAliveLowPower"] = true
        info["sourceVersion"] = ReceiverConfig.SOURCE_VERSION
        info["keepAliveSendStatsAsBody"] = true
        info["model"] = ReceiverConfig.MODEL
        info["initialVolume"] = -15.0
        info["audioLatencies"] = listOf(
            mapOf("type" to 100, "inputLatencyMicros" to 0, "audioType" to "default", "outputLatencyMicros" to false),
            mapOf("type" to 101, "audioType" to "default", "inputLatencyMicros" to 0, "outputLatencyMicros" to false),
        )
        info["audioFormats"] = listOf(
            mapOf("audioOutputFormats" to 0x3fffffc, "type" to 100, "audioInputFormats" to 0x3fffffc),
            mapOf("audioOutputFormats" to 0x3fffffc, "type" to 101, "audioInputFormats" to 0x3fffffc),
        )
        info["displays"] = listOf(
            linkedMapOf(
                "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925",
                "widthPhysical" to 0,
                "heightPhysical" to 0,
                "width" to config.displayWidth,
                "height" to config.displayHeight,
                "widthPixels" to config.displayWidth,
                "heightPixels" to config.displayHeight,
                "rotation" to false,
                "refreshRate" to 1.0 / 60.0,
                "maxFPS" to config.maxFps,
                "overscanned" to false,
                "features" to 14,
            ),
        )
        res.body(PLIST, BinaryPlist.encode(info))
    }

    private fun handlePairSetup(req: RtspRequest, res: RtspResponse) {
        if (req.body.size != 32) {
            res.status(400, "Bad Request")
            return
        }
        res.body(OCTET, identity.publicKey)
    }

    private fun handlePairVerify(req: RtspRequest, res: RtspResponse) {
        val b = req.body
        if (b.size < 4) {
            res.status(400, "Bad Request")
            return
        }
        when (b[0].toInt()) {
            1 -> {
                if (b.size != 4 + 32 + 32) {
                    res.status(400, "Bad Request")
                    return
                }
                val clientEd = b.copyOfRange(36, 68)
                if (server.access.mode == AirPlayAccessMode.SCREEN_PIN) {
                    // Only Apple devices that completed PIN pairing (now or earlier) may continue.
                    val hex = Bytes.hex(clientEd)
                    pinAuthorized = pinPairedKey?.contentEquals(clientEd) == true || server.access.isPairedClient(hex)
                    if (!pinAuthorized) {
                        Log.i(TAG, "pair-verify from an unpaired device in PIN mode")
                        res.status(470, "Connection Authorization Required")
                        return
                    }
                }
                val pv = PairVerify(identity)
                pairVerify = pv
                res.body(OCTET, pv.handshake(b.copyOfRange(4, 36), clientEd))
            }
            0 -> {
                val pv = pairVerify
                if (pv == null || b.size != 4 + 64) {
                    res.status(400, "Bad Request")
                    return
                }
                if (!pv.finish(b.copyOfRange(4, 68))) {
                    Log.w(TAG, "pair-verify signature mismatch")
                    res.status(470, "Connection Authorization Required")
                    res.disconnect = true
                    return
                }
                res.body(OCTET, ByteArray(0))
            }
        }
    }

    // ------------------------------------------------------------------ access control (PIN / password)

    /** Ed25519 key of the device that completed pair-setup-pin on this connection. */
    private var pinPairedKey: ByteArray? = null
    private var pinAuthorized = false
    private var digestNonce: String? = null
    private var passwordOk = false

    private fun handlePairPinStart(res: RtspResponse) {
        // A repeated request while the code is on screen keeps the same code.
        if (server.pinPairing == null) server.startPinPairing()
        Log.i(TAG, "pair-pin-start: showing a PIN on screen")
    }

    /** SRP-6a pairing in three steps (method/user → salt+B, A+M1 → M2, encrypted Ed25519 keys). */
    private fun handlePairSetupPin(req: RtspRequest, res: RtspResponse) {
        @Suppress("UNCHECKED_CAST")
        val body = (if (BinaryPlist.isBinaryPlist(req.body)) BinaryPlist.decode(req.body) else null) as? Map<String, Any?>
        val pairing = server.pinPairing
        if (body == null || pairing == null) {
            res.status(470, "Connection Authorization Required")
            return
        }
        when {
            body.string("user") != null -> {
                val s = SrpServer(body.string("user")!!, pairing.pin)
                pairing.srp = s
                res.body(PLIST, BinaryPlist.encode(linkedMapOf("pk" to s.publicKey, "salt" to s.salt)))
            }
            body.data("proof") != null && body.data("pk") != null -> {
                val proof = pairing.srp?.verify(body.data("pk")!!, body.data("proof")!!)
                if (proof == null) {
                    Log.i(TAG, "pair-setup-pin: wrong PIN")
                    res.status(470, "Connection Authorization Required")
                    return
                }
                res.body(PLIST, BinaryPlist.encode(mapOf("proof" to proof)))
            }
            body.data("epk") != null && body.data("authTag") != null -> {
                val key = pairing.srp?.sessionKey ?: run {
                    res.status(470, "Connection Authorization Required")
                    return
                }
                val aesKey = Bytes.sha512("Pair-Setup-AES-Key".toByteArray(), key).copyOf(16)
                val iv = Bytes.sha512("Pair-Setup-AES-IV".toByteArray(), key).copyOf(16)
                iv[15]++
                val clientKey = try {
                    gcm(javax.crypto.Cipher.DECRYPT_MODE, aesKey, iv, body.data("epk")!! + body.data("authTag")!!)
                } catch (e: Exception) {
                    Log.w(TAG, "pair-setup-pin: client key did not authenticate")
                    res.status(470, "Connection Authorization Required")
                    return
                }
                iv[15]++ // the reply uses the next nonce
                val sealed = gcm(javax.crypto.Cipher.ENCRYPT_MODE, aesKey, iv, identity.publicKey)
                pinPairedKey = clientKey
                // Remember right away: the device may continue with pair-verify on a new connection.
                server.access.rememberClient(Bytes.hex(clientKey), "Apple device")
                server.endPinPairing()
                Log.i(TAG, "pair-setup-pin: paired")
                res.body(PLIST, BinaryPlist.encode(linkedMapOf("epk" to sealed.copyOf(32), "authTag" to sealed.copyOfRange(32, 48))))
            }
            else -> res.status(400, "Bad Request")
        }
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray =
        javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
            doFinal(input)
        }

    /** SETUP gate: PIN mode needs a paired device; password mode needs RTSP Digest authentication. */
    private fun checkAccess(req: RtspRequest, res: RtspResponse): Boolean {
        when (server.access.mode) {
            AirPlayAccessMode.OPEN -> return true
            AirPlayAccessMode.SCREEN_PIN -> {
                if (pinAuthorized) return true
                res.status(470, "Connection Authorization Required")
                return false
            }
            AirPlayAccessMode.PASSWORD -> {
                if (passwordOk) return true
                val auth = req.header("Authorization")
                val password = server.access.password
                if (auth != null && password.isNotEmpty() && DigestAuth.verify(req.method, auth, password, digestNonce)) {
                    passwordOk = true
                    return true
                }
                if (auth != null) Log.i(TAG, "AirPlay password rejected")
                val nonce = DigestAuth.newNonce()
                digestNonce = nonce
                res.status(401, "Unauthorized")
                res.header("WWW-Authenticate", DigestAuth.challenge(nonce))
                return false
            }
        }
    }

    private fun handleFpSetup(req: RtspRequest, res: RtspResponse) {
        val reply = when (req.body.size) {
            16 -> fairPlay.setup(req.body)
            164 -> fairPlay.handshake(req.body)
            else -> null
        }
        if (reply == null) {
            Log.w(TAG, "unsupported fp-setup (${req.body.size} bytes)")
            res.status(501, "Not Implemented")
            return
        }
        res.body(OCTET, reply)
    }

    // ------------------------------------------------------------------ streaming

    private fun handleSetup(req: RtspRequest, res: RtspResponse) {
        if (!checkAccess(req, res)) return
        @Suppress("UNCHECKED_CAST")
        val body = (if (BinaryPlist.isBinaryPlist(req.body)) BinaryPlist.decode(req.body) else null)
            as? Map<String, Any?> ?: emptyMap()
        val response = LinkedHashMap<String, Any?>()

        val ekey = body.data("ekey")
        val eiv = body.data("eiv")
        if (ekey != null && eiv != null) {
            if (!setupSession(req, body, ekey, eiv, res)) return
            response["eventPort"] = 0
            response["timingPort"] = ntp?.localPort ?: 0
        }

        val streams = body.list("streams")
        if (streams != null) {
            val out = ArrayList<Any?>()
            for (s in streams) {
                @Suppress("UNCHECKED_CAST")
                val stream = s as? Map<String, Any?> ?: continue
                when (stream.long("type")?.toInt()) {
                    110 -> out += setupMirror(stream) ?: run {
                        res.status(500, "Mirror setup failed")
                        return
                    }
                    96 -> out += setupAudio(stream) ?: run {
                        res.status(500, "Audio setup failed")
                        return
                    }
                    else -> Log.w(TAG, "unknown stream type ${stream["type"]}")
                }
            }
            response["streams"] = out
        }
        res.body(PLIST, BinaryPlist.encode(response))
    }

    private fun setupSession(
        req: RtspRequest,
        body: Map<String, Any?>,
        ekey: ByteArray,
        eiv: ByteArray,
        res: RtspResponse,
    ): Boolean {
        if (ekey.size != 72 || eiv.size != 16) {
            res.status(400, "Bad Request")
            return false
        }
        var key = fairPlay.decryptKey(ekey) ?: run {
            Log.e(TAG, "FairPlay key decryption failed (fp-setup not completed)")
            res.status(400, "Bad Request")
            return false
        }
        // With legacy pairing the stream key is hashed with the pair-verify shared secret.
        pairVerify?.sharedSecret?.let { shared ->
            key = Bytes.sha512(key, shared).copyOf(16)
        }
        stopStreams()
        aesKey = key
        aesIv = eiv

        val info = ClientInfo(
            address = remoteAddress,
            name = body.string("name"),
            model = body.string("model"),
            deviceId = body.string("deviceID"),
        )
        client = info
        Log.i(TAG, "session from ${info.name} (${info.model}) at $remoteAddress, timing=${body.string("timingProtocol")}")
        if (!server.listener.approveSession(info)) {
            Log.i(TAG, "session from ${info.name} declined on the receiver")
            res.status(403, "Forbidden")
            res.disconnect = true
            return false
        }

        val clock = ClockSync()
        this.clock = clock
        val timingPort = body.long("timingPort")?.toInt() ?: 0
        ntp = NtpClient(socket.inetAddress, timingPort, clock).also { it.start() }

        // Remember Apple devices that just completed PIN pairing so they connect directly next time.
        pinPairedKey?.let { server.access.rememberClient(Bytes.hex(it), info.name ?: info.model ?: remoteAddress) }
        server.onSessionActive(this)
        val gen = ++generation
        // A later SETUP on this same connection must not be torn down by its predecessor's callback.
        sinks = server.listener.onSessionStart(info, clock) { if (generation == gen) close() }
        isStreaming = true
        return true
    }

    private fun setupMirror(stream: Map<String, Any?>): Map<String, Any?>? {
        val key = aesKey ?: return null
        val sinks = sinks ?: return null
        val clock = clock ?: return null
        val id = stream.long("streamConnectionID") ?: 0L
        mirror?.stop()
        val m = MirrorReceiver(key, id, clock, sinks.video)
        m.start()
        mirror = m
        Log.i(TAG, "mirror stream on port ${m.dataPort}")
        return mapOf("type" to 110, "dataPort" to m.dataPort)
    }

    private fun setupAudio(stream: Map<String, Any?>): Map<String, Any?>? {
        val key = aesKey ?: return null
        val iv = aesIv ?: return null
        val sinks = sinks ?: return null
        val ct = stream.long("ct")?.toInt() ?: 8
        val spf = stream.long("spf")?.toInt() ?: 480
        audio?.stop()
        val a = AudioReceiver(key, iv, AudioFormat(ct, spf), sinks.audio)
        a.start()
        audio = a
        Log.i(TAG, "audio stream ct=$ct spf=$spf data=${a.dataPort} control=${a.controlPort}")
        return mapOf("type" to 96, "dataPort" to a.dataPort, "controlPort" to a.controlPort)
    }

    private fun handleGetParameter(req: RtspRequest, res: RtspResponse) {
        val text = String(req.body)
        if (text.startsWith("volume")) {
            res.body("text/parameters", "volume: %.6f\r\n".format(java.util.Locale.US, lastVolume).toByteArray())
        }
    }

    private var lastVolume = -15f

    private fun handleSetParameter(req: RtspRequest) {
        if (req.contentType?.startsWith("text/parameters") != true) return
        val text = String(req.body)
        if (text.startsWith("volume:")) {
            val v = text.substringAfter(':').trim().toFloatOrNull() ?: return
            lastVolume = v
            sinks?.audio?.onVolume(v)
        }
    }

    private fun handleTeardown(req: RtspRequest, res: RtspResponse) {
        @Suppress("UNCHECKED_CAST")
        val body = (if (BinaryPlist.isBinaryPlist(req.body)) BinaryPlist.decode(req.body) else null)
            as? Map<String, Any?>
        val types = body?.list("streams")?.mapNotNull { (it as? Map<*, *>)?.get("type") as? Number }
            ?.map { it.toInt() } ?: emptyList()
        if (types.isNotEmpty()) {
            if (96 in types) {
                audio?.stop()
                audio = null
            }
            if (110 in types) {
                mirror?.stop()
                mirror = null
                sinks?.video?.onVideoStopped()
            }
        } else {
            res.header("Connection", "close")
            stopStreams()
        }
    }

    @Synchronized
    private fun stopStreams() {
        audio?.stop()
        audio = null
        mirror?.stop()
        mirror = null
        ntp?.stop()
        ntp = null
        val s = sinks
        sinks = null
        if (isStreaming) {
            isStreaming = false
            s?.onSessionEnd()
            server.onSessionInactive(this)
        }
    }

    companion object {
        private const val TAG = "NShare.Rtsp"
        private const val PLIST = "application/x-apple-binary-plist"
        private const val OCTET = "application/octet-stream"
    }
}
