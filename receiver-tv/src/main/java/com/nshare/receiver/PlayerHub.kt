package com.nshare.receiver

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.nshare.airplay.ClientInfo
import com.nshare.airplay.SessionSinks
import com.nshare.airplay.time.ClockSync
import com.nshare.common.WifiLatencyLock
import com.nshare.receiver.player.MirrorPlayer
import com.nshare.receiver.ui.MainActivity

/**
 * Glue between the receiver service (sessions) and the activity (surface + UI).
 * All listener callbacks are delivered on the main thread.
 */
object PlayerHub {
    sealed interface State {
        data object Idle : State
        data class Mirroring(val clientName: String, val videoActive: Boolean) : State
    }

    interface Listener {
        fun onStateChanged(state: State)
        fun onVideoSize(width: Int, height: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    @Volatile var state: State = State.Idle
        private set
    @Volatile var player: MirrorPlayer? = null
        private set
    private var surface: Surface? = null
    var videoWidth = 0
        private set
    var videoHeight = 0
        private set

    /** Receiver name / address for the idle screen, set by the service. */
    @Volatile var receiverName: String = ""
    @Volatile var receiverPort: Int = 0
    private var disconnectCurrent: (() -> Unit)? = null
    private var wifiLock: WifiLatencyLock? = null

    fun addListener(l: Listener) {
        listeners += l
        l.onStateChanged(state)
        if (videoWidth > 0) l.onVideoSize(videoWidth, videoHeight)
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    fun setSurface(s: Surface?) {
        surface = s
        player?.setSurface(s)
    }

    fun disconnect() {
        disconnectCurrent?.invoke()
    }

    @Synchronized
    fun startSession(context: Context, client: ClientInfo, clock: ClockSync, disconnect: () -> Unit): SessionSinks {
        val settings = Settings(context)
        val p = MirrorPlayer(client, clock, settings.latencyMode, settings.avOffsetMs, events)
        (wifiLock ?: WifiLatencyLock(context, "nshare-receiver").also { wifiLock = it }).acquire()
        val previousDisconnect = disconnectCurrent
        disconnectCurrent = disconnect
        player?.onSessionEnd()
        // The newest sender wins, whichever protocol either of them uses.
        if (previousDisconnect != null) Thread { previousDisconnect() }.start()
        player = p
        p.setSurface(surface)
        val name = client.name ?: client.model ?: client.address
        Log.i(TAG, "session started: $name")
        post { setState(State.Mirroring(name, videoActive = false)) }
        bringToFront(context)
        return p
    }

    private fun bringToFront(context: Context) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not bring receiver to front: ${e.message}")
        }
    }

    private val events = object : MirrorPlayer.Events {
        override fun onVideoSize(player: MirrorPlayer, width: Int, height: Int) = post {
            if (player !== this@PlayerHub.player) return@post
            videoWidth = width
            videoHeight = height
            listeners.forEach { it.onVideoSize(width, height) }
        }

        override fun onVideoActive(player: MirrorPlayer, active: Boolean) = post {
            if (player !== this@PlayerHub.player) return@post
            val s = state
            if (s is State.Mirroring && s.videoActive != active) setState(s.copy(videoActive = active))
        }

        override fun onEnded(player: MirrorPlayer) = post {
            if (player !== this@PlayerHub.player) return@post
            this@PlayerHub.player = null
            wifiLock?.release()
            videoWidth = 0
            videoHeight = 0
            setState(State.Idle)
        }
    }

    private fun setState(s: State) {
        state = s
        listeners.forEach { it.onStateChanged(s) }
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private const val TAG = "NShare.Hub"
}
