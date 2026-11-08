package com.nshare.receiver.ui

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nshare.ads.Ads
import com.nshare.common.AppLanguage
import com.nshare.receiver.R
import com.nshare.receiver.PlayerHub
import com.nshare.receiver.ReceiverService
import com.nshare.receiver.Settings
import java.net.Inet4Address

class MainActivity : ComponentActivity(), PlayerHub.Listener {
    private lateinit var settings: Settings
    private val handler = Handler(Looper.getMainLooper())

    private var hubState by mutableStateOf<PlayerHub.State>(PlayerHub.State.Idle)
    private var aspect by mutableStateOf(16f / 9f)
    private var statsText by mutableStateOf<String?>(null)
    private var hint by mutableStateOf<String?>(null)
    private var address by mutableStateOf<String?>(null)
    private var receiverName by mutableStateOf("")
    private var lastBackPress = 0L

    private val statsTicker = object : Runnable {
        override fun run() {
            statsText = buildStats()
            handler.postDelayed(this, 500)
        }
    }
    private val hideHint = Runnable { hint = null }
    private val refreshIdle = object : Runnable {
        override fun run() {
            address = localIpv4()
            receiverName = PlayerHub.receiverName
            handler.postDelayed(this, 1000)
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = PlayerHub.setSurface(holder.surface)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) = PlayerHub.setSurface(null)
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        ReceiverService.start(this)
        Ads.init(this, AdKeys.APP_KEY)
        setContent {
            NShareTvTheme {
                val s = hubState
                TvScreen(
                    TvUiState(
                        receiverName = receiverName,
                        address = address,
                        port = PlayerHub.receiverPort,
                        mirroring = s is PlayerHub.State.Mirroring,
                        clientName = (s as? PlayerHub.State.Mirroring)?.clientName ?: "",
                        videoActive = (s as? PlayerHub.State.Mirroring)?.videoActive ?: false,
                        videoAspect = aspect,
                        stats = statsText,
                        hint = hint,
                        language = AppLanguage.current(this),
                    ),
                    AdKeys.BANNER_AD_UNIT,
                    surfaceCallback,
                    TvActions(onDiagnostics = ::toggleStats, onLanguage = ::cycleLanguage),
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PlayerHub.addListener(this)
        handler.post(refreshIdle)
        if (settings.showStats) showStats(true)
    }

    override fun onStop() {
        PlayerHub.removeListener(this)
        handler.removeCallbacks(refreshIdle)
        handler.removeCallbacks(statsTicker)
        super.onStop()
    }

    // ------------------------------------------------------------------ PlayerHub.Listener

    override fun onStateChanged(state: PlayerHub.State) {
        hubState = state
        val mirroring = state is PlayerHub.State.Mirroring
        if (mirroring) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            aspect = 16f / 9f
        }
        // Ask the TV for its low-latency picture mode while mirroring (HDMI ALLM / game content type).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setPreferMinimalPostProcessing(mirroring)
    }

    override fun onVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) aspect = width.toFloat() / height
    }

    // ------------------------------------------------------------------ remote control

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    /** While mirroring the remote drives diagnostics; on the idle screen keys go to the focused buttons. */
    private fun handleKey(keyCode: Int): Boolean {
        val mirroring = PlayerHub.state is PlayerHub.State.Mirroring
        val statsVisible = statsText != null
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (mirroring) {
                toggleStats()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (mirroring && statsVisible) {
                val step = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10 else -10
                val v = (settings.avOffsetMs + step).coerceIn(-500, 500)
                settings.avOffsetMs = v
                PlayerHub.player?.timeline?.avOffsetNs = v * 1_000_000L
                showHint(getString(R.string.hint_av_offset, formatOffset(v)))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (mirroring && statsVisible) {
                val m = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) settings.latencyMode.next() else settings.latencyMode.previous()
                settings.latencyMode = m
                PlayerHub.player?.timeline?.mode = m
                showHint(getString(R.string.hint_mode, getString(m.labelRes)))
                return true
            }
            KeyEvent.KEYCODE_BACK -> if (PlayerHub.state is PlayerHub.State.Mirroring) {
                val now = System.currentTimeMillis()
                if (now - lastBackPress < 2500) PlayerHub.disconnect() else showHint(getString(R.string.hint_back_disconnect))
                lastBackPress = now
                return true
            }
        }
        return false
    }

    private fun toggleStats() {
        val show = statsText == null
        showStats(show)
        settings.showStats = show
    }

    private fun cycleLanguage() {
        val next = AppLanguage.current(this).next()
        AppLanguage.set(this, next)
    }

    private fun showStats(show: Boolean) {
        handler.removeCallbacks(statsTicker)
        if (show) handler.post(statsTicker) else statsText = null
    }

    private fun showHint(text: String) {
        hint = text
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, 2500)
    }

    private fun formatOffset(ms: Int) = if (ms > 0) "+$ms ms" else "$ms ms"

    // ------------------------------------------------------------------ content

    private fun buildStats(): String {
        val p = PlayerHub.player
            ?: return getString(R.string.stats_idle, PlayerHub.receiverName, localIpv4() ?: "-", PlayerHub.receiverPort)
        val s = p.stats
        val r = s.rates()
        val t = p.timeline
        val clock = if (p.clock.hasNtp) "NTP (RTT %.1f ms)".format(p.clock.lastRoundTripNs / 1e6) else getString(R.string.clock_arrival)
        return buildString {
            appendLine(getString(R.string.stats_title))
            appendLine(getString(R.string.stats_sender, p.client.name ?: "-", p.client.model ?: "-"))
            appendLine(getString(R.string.stats_video, s.videoWidth, s.videoHeight, s.codecName))
            appendLine(getString(R.string.stats_rates, r.inputFps, r.renderFps, r.mbps))
            appendLine(
                getString(
                    R.string.stats_latency,
                    (t.latencyNs / 1_000_000).toInt(), getString(t.mode.labelRes), t.videoTransitP95Ms(), t.decodeP95Ms(),
                ),
            )
            appendLine(
                getString(R.string.stats_frames, s.videoLate.get().toInt(), s.videoSkipped.get().toInt(), s.videoDecoderResets.get().toInt()),
            )
            appendLine(getString(R.string.stats_audio, s.audioSyncErrorMs, t.audioOutputLatencyNs / 1e6, s.audioPerfMode))
            appendLine(
                getString(
                    R.string.stats_audio2,
                    s.audioDropped.get().toInt(), s.audioSilenceMs.get().toInt(), s.audioUnderruns.get().toInt(),
                    s.audioSilentDropped.get().toInt(),
                ),
            )
            append(getString(R.string.stats_offset, formatOffset((t.avOffsetNs / 1_000_000).toInt()), clock))
        }
    }

    private fun localIpv4(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }
}

/** Unity LevelPlay identifiers for the TV app. */
object AdKeys {
    const val APP_KEY = "295bdef3d"
    const val BANNER_AD_UNIT = "sl7pajabx3sh799y"
}
