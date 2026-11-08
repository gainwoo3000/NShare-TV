package com.nshare.receiver

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nshare.airplay.ClientInfo
import com.nshare.airplay.SessionSinks
import com.nshare.airplay.time.ClockSync
import com.nshare.common.WifiLatencyLock
import com.nshare.protocol.Nsp
import com.nshare.receiver.player.MirrorPlayer
import com.nshare.receiver.security.SecuritySettings
import com.nshare.receiver.ui.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Glue between the receiver services (sessions) and the activity (surface + UI).
 * Listener callbacks are delivered on the main thread; overlay state is Compose-observable.
 */
object PlayerHub {
    sealed interface State {
        data object Idle : State
        data class Mirroring(val clientName: String, val videoActive: Boolean, val reconnecting: Boolean = false) : State
    }

    interface Listener {
        fun onStateChanged(state: State)
        fun onVideoSize(width: Int, height: Int)
    }

    /** PIN the TV shows while an NShare sender pairs. */
    class PinPrompt(val pin: String, val deviceName: String)

    /** A second sender wants the screen; [respond] answers it (true = allow). */
    class ApprovalRequest(val deviceName: String, val deadlineMs: Long, val respond: (Boolean) -> Unit)

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

    var pinPrompt by mutableStateOf<PinPrompt?>(null)
        private set
    var approval by mutableStateOf<ApprovalRequest?>(null)
        private set
    /** 4-digit code an Apple device must type (AirPlay "PIN on screen" security). */
    var airPlayPin by mutableStateOf<String?>(null)
        private set
    /**
     * Notification card shown in the top-right corner. Codes stay 15 s with a progress bar; plain
     * notices (e.g. "iPhone connected") 5 s without one.
     */
    class Toast(
        val key: String,
        val title: String,
        val code: String? = null,
        val durationMs: Int = if (code != null) 15_000 else 5_000,
        val showProgress: Boolean = code != null,
    )

    var toast by mutableStateOf<Toast?>(null)
        private set
    private var toastSeq = 0
    /**
     * When each toast was first shown (by key). Lives here, not in the UI, so a recreated screen
     * (e.g. after switching the language) continues a card or skips it instead of replaying it.
     */
    private val toastShownAt = HashMap<String, Long>()

    /** Remaining time for [t] in ms; starts its clock on the first call. */
    fun toastRemainingMs(t: Toast): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        val start = synchronized(toastShownAt) {
            if (toastShownAt.size > 64) toastShownAt.clear()
            toastShownAt.getOrPut(t.key) { now }
        }
        return t.durationMs - (now - start)
    }

    /**
     * Music the sender is playing (AirPlay audio from Apple Music etc.): track, cover and position.
     * [elapsedMs] was valid at [atMs] (elapsedRealtime); while not [paused] it advances in real time.
     */
    data class NowPlaying(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val artwork: androidx.compose.ui.graphics.ImageBitmap? = null,
        val elapsedMs: Long = 0,
        val durationMs: Long = 0,
        val atMs: Long = 0,
        val paused: Boolean = false,
    ) {
        fun positionMs(nowMs: Long): Long =
            (if (paused) elapsedMs else elapsedMs + (nowMs - atMs)).coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }

    var nowPlaying by mutableStateOf<NowPlaying?>(null)
        private set
    /** Sends playback commands to the current AirPlay sender (DACP); false if it has no remote control. */
    @Volatile var mediaRemote: ((String) -> Boolean)? = null

    /** Last remote command, for a short highlight of the matching on-screen button. */
    class MediaCommand(val command: String, val atMs: Long)

    var lastMediaCommand by mutableStateOf<MediaCommand?>(null)
        private set

    /** playpause / nextitem / previtem from the TV remote while music is shown. */
    fun mediaCommand(context: Context, command: String) {
        val sent = if (demoNowPlaying) true else mediaRemote?.invoke(command) ?: false
        Log.i(TAG, "remote $command: ${if (sent) "sent" else "not available"}")
        if (!sent) {
            showNotice(context.getString(R.string.now_playing_no_remote))
            return
        }
        lastMediaCommand = MediaCommand(command, android.os.SystemClock.elapsedRealtime())
        if (command == "playpause") {
            // Show the new state right away; the sender confirms with a flush (pause) or progress (play).
            val now = android.os.SystemClock.elapsedRealtime()
            nowPlaying = nowPlaying?.let { it.copy(elapsedMs = it.positionMs(now), atMs = now, paused = !it.paused) }
        }
    }

    /** Debug builds: the music screen with sample data, for checking the layout on a TV. */
    var demoNowPlaying by mutableStateOf(false)
        private set

    fun showDemoNowPlaying(show: Boolean) {
        demoNowPlaying = show
        if (!show) {
            if (player == null) nowPlaying = null
            return
        }
        val bmp = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 600f, 600f, 0xFFFF5E62.toInt(), 0xFF6A3FD8.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 600f, 600f, p)
        p.shader = null
        p.color = 0x55FFFFFF
        c.drawCircle(420f, 200f, 150f, p)
        p.color = 0x33000000
        c.drawCircle(180f, 430f, 120f, p)
        nowPlaying = NowPlaying(
            title = "Supernova", artist = "aespa", album = "Armageddon - The 1st Album",
            artwork = bmp.asImageBitmap(), elapsedMs = 74_000, durationMs = 178_000,
            atMs = android.os.SystemClock.elapsedRealtime(),
        )
    }

    private fun updateNowPlaying(player: MirrorPlayer, change: (NowPlaying) -> NowPlaying) = post {
        if (player === this.player) nowPlaying = change(nowPlaying ?: NowPlaying())
    }

    /** The NShare (Android sender) receiver could not start; shown on the idle screen. */
    var nshareUnavailable by mutableStateOf(false)
    /** Short message for the user (e.g. an outdated sender app tried to connect). */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Receiver name / address for the idle screen, set by the service. */
    /** Observable so the idle screen follows a rename. */
    var receiverName by mutableStateOf("")
    @Volatile var receiverPort: Int = 0
    private var disconnectCurrent: (() -> Unit)? = null
    private var wifiLock: WifiLatencyLock? = null
    /** Identity of the current sender (NShare certificate fingerprint), used to resume after a drop. */
    private var currentKey: String? = null
    private var dropTimeout: Runnable? = null

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
        // A dropped session waiting for its sender has no connection left to close.
        if (dropTimeout != null) endDropped()
    }

    @Synchronized
    fun startSession(
        context: Context,
        client: ClientInfo,
        clock: ClockSync,
        requestKeyFrame: (() -> Unit)? = null,
        key: String? = null,
        disconnect: () -> Unit,
    ): SessionSinks {
        val settings = Settings(context)
        val p = MirrorPlayer(client, clock, settings.latencyMode, settings.avOffsetMs, events, requestKeyFrame)
        (wifiLock ?: WifiLatencyLock(context, "nshare-receiver").also { wifiLock = it }).acquire()
        cancelDropTimeout()
        val previousDisconnect = disconnectCurrent
        disconnectCurrent = disconnect
        currentKey = key
        player?.onSessionEnd()
        // The newest sender wins, whichever protocol either of them uses.
        if (previousDisconnect != null) Thread { previousDisconnect() }.start()
        player = p
        nowPlaying = null
        p.setSurface(surface)
        val name = client.name ?: client.model ?: client.address
        Log.i(TAG, "session started: $name")
        post { setState(State.Mirroring(name, videoActive = false)) }
        toast = Toast("connected:${++toastSeq}", context.getString(R.string.toast_connected, name))
        bringToFront(context)
        return p
    }

    /**
     * The same NShare sender reconnected while its dropped session was still on screen: keep the
     * player (decoder, timeline, clock) and continue where it stopped.
     */
    @Synchronized
    fun resumeSession(key: String, requestKeyFrame: (() -> Unit)?, disconnect: () -> Unit): MirrorPlayer? {
        val p = player ?: return null
        if (dropTimeout == null || key != currentKey) return null
        cancelDropTimeout()
        disconnectCurrent = disconnect
        p.setKeyFrameRequester(requestKeyFrame)
        Log.i(TAG, "session resumed: ${p.client.name}")
        post {
            val s = state
            if (s is State.Mirroring) setState(s.copy(reconnecting = false))
        }
        return p
    }

    /**
     * The connection to [p] broke without a goodbye. Keep its last frame on screen with a
     * "reconnecting" overlay for a few seconds in case the sender comes back.
     */
    @Synchronized
    fun sessionDropped(p: MirrorPlayer) {
        if (p !== player) {
            p.onSessionEnd()
            return
        }
        disconnectCurrent = null
        p.setKeyFrameRequester(null)
        val timeout = Runnable { endDropped() }
        dropTimeout = timeout
        main.postDelayed(timeout, RECONNECT_GRACE_MS)
        post {
            val s = state
            if (s is State.Mirroring) setState(s.copy(reconnecting = true))
        }
    }

    @Synchronized
    private fun endDropped() {
        cancelDropTimeout()
        player?.onSessionEnd()
    }

    private fun cancelDropTimeout() {
        dropTimeout?.let { main.removeCallbacks(it) }
        dropTimeout = null
    }

    // ------------------------------------------------------------------ security prompts

    fun showPin(pin: String, deviceName: String) {
        pinPrompt = PinPrompt(pin, deviceName)
        // While mirroring the receiver is already in front and shows the PIN as a small card.
        if (player == null) bringToFrontFromBackground()
    }

    fun hidePin() {
        pinPrompt = null
    }

    fun showAirPlayPin(pin: String?) {
        airPlayPin = pin
        if (pin != null && player == null) bringToFrontFromBackground()
    }

    /**
     * Asks the TV user whether [deviceName] may take over the screen. Only asked when the setting is
     * on and someone else is mirroring; blocks the calling session thread for up to 10 s.
     */
    fun needsApproval(context: Context, key: String): Boolean =
        SecuritySettings(context).approveTakeover && player != null && key != currentKey

    fun requestApproval(context: Context, deviceName: String, key: String): Boolean {
        if (!needsApproval(context, key)) return true
        val latch = CountDownLatch(1)
        var allowed = false
        val req = ApprovalRequest(deviceName, System.currentTimeMillis() + Nsp.APPROVAL_TIMEOUT_MS) { ok ->
            allowed = ok
            latch.countDown()
        }
        synchronized(this) {
            // One question at a time: a second request must not replace (and silently time out) the first.
            if (approval != null) {
                Log.i(TAG, "takeover by $deviceName refused: another request is pending")
                return false
            }
            approval = req
        }
        val answered = latch.await(Nsp.APPROVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (approval === req) approval = null
        Log.i(TAG, "takeover by $deviceName ${if (answered && allowed) "allowed" else "refused"}")
        return answered && allowed
    }

    fun showNotice(text: String) {
        notice = text
        main.postDelayed({ if (notice == text) notice = null }, 6_000)
    }

    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun bringToFrontFromBackground() {
        appContext?.let { bringToFront(it) }
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

        override fun onTrackInfo(player: MirrorPlayer, info: com.nshare.airplay.TrackInfo) =
            updateNowPlaying(player) { it.copy(title = info.title, artist = info.artist, album = info.album) }

        override fun onArtwork(player: MirrorPlayer, image: ByteArray?) {
            // Decoding a large cover takes a while: off the main thread, scaled to what the screen shows.
            Thread({
                val bitmap = image?.let { decodeArtwork(it) }
                updateNowPlaying(player) { it.copy(artwork = bitmap) }
            }, "nshare-artwork").start()
        }

        override fun onProgress(player: MirrorPlayer, elapsedMs: Long, durationMs: Long) = updateNowPlaying(player) {
            it.copy(elapsedMs = elapsedMs, durationMs = durationMs, atMs = android.os.SystemClock.elapsedRealtime(), paused = false)
        }

        override fun onAudioPaused(player: MirrorPlayer) = updateNowPlaying(player) {
            val now = android.os.SystemClock.elapsedRealtime()
            it.copy(elapsedMs = it.positionMs(now), atMs = now, paused = true)
        }

        override fun onEnded(player: MirrorPlayer) = post {
            if (player !== this@PlayerHub.player) return@post
            this@PlayerHub.player = null
            nowPlaying = null
            currentKey = null
            cancelDropTimeout()
            wifiLock?.release()
            videoWidth = 0
            videoHeight = 0
            setState(State.Idle)
        }
    }

    private fun decodeArtwork(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 800) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()

    private fun setState(s: State) {
        state = s
        listeners.forEach { it.onStateChanged(s) }
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private const val TAG = "NShare.Hub"
    private const val RECONNECT_GRACE_MS = 10_000L
}
