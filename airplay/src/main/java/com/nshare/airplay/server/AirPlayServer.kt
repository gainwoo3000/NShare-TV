package com.nshare.airplay.server

import android.content.Context
import android.util.Log
import com.nshare.airplay.AirPlayAccess
import com.nshare.airplay.OpenAirPlayAccess
import com.nshare.airplay.ReceiverConfig
import com.nshare.airplay.ReceiverListener
import com.nshare.airplay.crypto.Identity
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AirPlay screen-mirroring receiver: RTSP control server + mDNS advertisement.
 * Only one sender streams at a time; a new sender takes over from the previous one.
 */
class AirPlayServer(
    context: Context,
    private val config: ReceiverConfig,
    internal val listener: ReceiverListener,
    internal val access: AirPlayAccess = OpenAirPlayAccess,
) {
    private val identity = Identity(config.identitySecret)
    private val mdns = Mdns(context.applicationContext)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val sessions = CopyOnWriteArrayList<RtspSession>()
    @Volatile private var active: RtspSession? = null
    @Volatile private var running = false
    private var watchdog: java.util.concurrent.ScheduledExecutorService? = null

    var port: Int = 0
        private set

    fun start() {
        if (running) return
        val ss = bind(config.port)
        serverSocket = ss
        port = ss.localPort
        running = true
        acceptThread = Thread(::acceptLoop, "nshare-rtsp-accept").apply {
            isDaemon = true
            start()
        }
        mdns.register(config, identity, port, access.mode)
        watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "nshare-rtsp-watchdog").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::checkIdleSessions, 2, 2, java.util.concurrent.TimeUnit.SECONDS) }
        Log.i(TAG, "AirPlay receiver '${config.name}' listening on $port")
    }

    fun stop() {
        running = false
        watchdog?.shutdownNow()
        watchdog = null
        mdns.unregister()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        sessions.forEach { it.close() }
        sessions.clear()
        active = null
    }

    /**
     * iOS / macOS send POST /feedback about every 2 s while a session is up, even when the video
     * stream is paused (locked screen). Nothing at all for 8 s means the sender is gone (e.g. left
     * Wi-Fi without a TEARDOWN): release the session instead of freezing on the last frame.
     */
    private fun checkIdleSessions() {
        val now = System.nanoTime()
        for (s in sessions) {
            if (!s.isStreaming) continue
            val idleMs = (now - s.lastActivityNs()) / 1_000_000
            if (idleMs > SESSION_IDLE_TIMEOUT_MS) {
                Log.w(TAG, "sender ${s.remoteAddress} silent for $idleMs ms, closing session")
                s.close()
            }
        }
    }

    /** Re-advertises after the access mode changed (pw / status flags in the TXT records). */
    fun refreshAdvertisement() {
        if (running) mdns.register(config, identity, port, access.mode)
    }

    /** Drops the current sender, if any. */
    fun disconnectActive() {
        active?.close()
    }

    private fun bind(preferred: Int): ServerSocket {
        val ss = ServerSocket()
        ss.reuseAddress = true
        try {
            ss.bind(InetSocketAddress(preferred))
        } catch (e: BindException) {
            Log.w(TAG, "port $preferred busy, using an ephemeral port")
            ss.bind(InetSocketAddress(0))
        }
        return ss
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) Log.e(TAG, "accept failed", e)
                break
            }
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val session = RtspSession(socket, config, identity, this)
            sessions += session
            Thread({
                try {
                    session.run()
                } finally {
                    sessions -= session
                }
            }, "nshare-rtsp-${session.remoteAddress}").apply {
                isDaemon = true
                start()
            }
        }
    }

    internal fun onSessionActive(session: RtspSession) {
        val previous = active
        active = session
        if (previous != null && previous !== session) {
            Log.i(TAG, "new sender ${session.remoteAddress} replaces ${previous.remoteAddress}")
            previous.close()
        }
    }

    internal fun onSessionInactive(session: RtspSession) {
        if (active === session) {
            active = null
            listener.onSessionEnd()
        }
    }

    companion object {
        private const val TAG = "NShare.Server"
        private const val SESSION_IDLE_TIMEOUT_MS = 8_000L
    }
}
