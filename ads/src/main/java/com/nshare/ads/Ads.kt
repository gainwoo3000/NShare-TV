package com.nshare.ads

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.unity3d.mediation.LevelPlay
import com.unity3d.mediation.LevelPlayAdError
import com.unity3d.mediation.LevelPlayAdInfo
import com.unity3d.mediation.LevelPlayAdSize
import com.unity3d.mediation.LevelPlayConfiguration
import com.unity3d.mediation.LevelPlayInitError
import com.unity3d.mediation.LevelPlayInitListener
import com.unity3d.mediation.LevelPlayInitRequest
import com.unity3d.mediation.banner.LevelPlayBannerAdView
import com.unity3d.mediation.banner.LevelPlayBannerAdViewListener
import kotlinx.coroutines.delay

/** Unity LevelPlay (ironSource) initialisation, shared by the phone and TV apps. */
object Ads {
    private const val TAG = "NShare.Ads"
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var initInFlight = false
    private var appContext: Context? = null
    private var key: String = ""
    private val retryInit = Runnable { appContext?.let { start(it, key, attempt) } }
    private var attempt = 0

    /** Bumped whenever a network becomes available, so failed banners retry right away. */
    var networkGeneration by mutableIntStateOf(0)
        private set

    /** Compose-observable: banners are only created once the SDK is ready. */
    var ready by mutableStateOf(false)
        private set

    fun init(context: Context, appKey: String) {
        if (started) return
        started = true
        appContext = context.applicationContext
        key = appKey
        watchNetwork(context.applicationContext)
        start(context.applicationContext, appKey, attempt = 0)
    }

    private fun start(context: Context, appKey: String, attempt: Int) {
        if (ready || initInFlight) return
        initInFlight = true
        this.attempt = attempt
        LevelPlay.init(context, LevelPlayInitRequest.Builder(appKey).build(), object : LevelPlayInitListener {
            override fun onInitSuccess(configuration: LevelPlayConfiguration) {
                Log.i(TAG, "LevelPlay ready")
                main.post {
                    initInFlight = false
                    ready = true
                }
            }

            override fun onInitFailed(error: LevelPlayInitError) {
                // Recommended by LevelPlay: retry later. A network change retries immediately (see watchNetwork).
                val delay = (30_000L shl attempt.coerceAtMost(4)).coerceAtMost(10 * 60_000L)
                Log.w(TAG, "LevelPlay init failed: ${error.errorMessage}; retrying in ${delay / 1000}s")
                main.post {
                    initInFlight = false
                    this@Ads.attempt = attempt + 1
                    main.removeCallbacks(retryInit)
                    main.postDelayed(retryInit, delay)
                }
            }
        })
    }

    /** E.g. switching from a network with an ad-blocking DNS to a phone hotspot. */
    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post {
                        networkGeneration++
                        if (!ready && !initInFlight) {
                            Log.i(TAG, "network changed, retrying LevelPlay init")
                            main.removeCallbacks(retryInit)
                            start(context, key, attempt)
                        }
                    }
                }
            })
        }.onFailure { Log.w(TAG, "cannot watch network changes", it) }
    }
}

enum class BannerSize { ADAPTIVE, STANDARD }

/**
 * Bottom banner. Takes no space until an ad has actually loaded, so a failed or unfilled
 * request never leaves an empty gap. [active] = false pauses refreshing (e.g. while hidden).
 */
@Composable
fun BannerAd(adUnitId: String, modifier: Modifier = Modifier, size: BannerSize = BannerSize.ADAPTIVE, active: Boolean = true) {
    if (!Ads.ready) return
    val context = LocalContext.current
    var loadedHeight by remember { mutableStateOf(0) }
    // Failed loads are retried with backoff (30 s … 5 min) and right away after a network change.
    var failures by remember { mutableIntStateOf(0) }
    val adSize = remember(size) {
        when (size) {
            BannerSize.ADAPTIVE -> LevelPlayAdSize.createAdaptiveAdSize(context) ?: LevelPlayAdSize.BANNER
            BannerSize.STANDARD -> LevelPlayAdSize.BANNER
        }
    }
    val view = remember(adUnitId, adSize) {
        LevelPlayBannerAdView(
            context,
            adUnitId,
            LevelPlayBannerAdView.Config.Builder().setAdSize(adSize).build(),
        ).apply {
            setBannerListener(object : LevelPlayBannerAdViewListener {
                override fun onAdLoaded(adInfo: LevelPlayAdInfo) {
                    loadedHeight = adSize.getHeight()
                    failures = 0
                }

                override fun onAdLoadFailed(error: LevelPlayAdError) {
                    Log.i("NShare.Ads", "banner not loaded: ${error.getErrorMessage()}")
                    if (loadedHeight == 0) failures++
                }
            })
            loadAd()
        }
    }
    DisposableEffect(view) { onDispose { view.destroy() } }
    LaunchedEffect(view, failures, active) {
        if (failures == 0 || !active) return@LaunchedEffect
        delay((30_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(5 * 60_000L))
        view.loadAd()
    }
    val network = Ads.networkGeneration
    LaunchedEffect(view, network) {
        if (failures > 0 && loadedHeight == 0) {
            delay(2_000) // let DNS / routes settle
            view.loadAd()
        }
    }
    LaunchedEffect(active, view) { if (active) view.resumeAutoRefresh() else view.pauseAutoRefresh() }

    Box(modifier.fillMaxWidth().height(loadedHeight.dp), contentAlignment = Alignment.Center) {
        AndroidView(factory = { view }, modifier = Modifier.width(adSize.getWidth().dp).height(loadedHeight.dp))
    }
}
