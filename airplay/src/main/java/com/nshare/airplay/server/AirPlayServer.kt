package com.nshare.airplay.server

import android.content.Context
import android.util.Log
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
) {
    private val identity = Identity(config.identitySecret)
    private val mdns = Mdns(context.applicationContext)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val sessions = CopyOnWriteArrayList<RtspSession>()
    @Volatile private var active: RtspSession? = null
    @Volatile private var running = false

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
        mdns.register(config, identity, port)
        Log.i(TAG, "AirPlay receiver '${config.name}' listening on $port")
    }

    fun stop() {
        running = false
        mdns.unregister()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        sessions.forEach { it.close() }
        sessions.clear()
        active = null
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
    }
}
