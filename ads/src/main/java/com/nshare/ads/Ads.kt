package com.nshare.ads

import android.content.Context
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

/** Unity LevelPlay (ironSource) initialisation, shared by the phone and TV apps. */
object Ads {
    private const val TAG = "NShare.Ads"
    private val main = Handler(Looper.getMainLooper())
    private var started = false

    /** Compose-observable: banners are only created once the SDK is ready. */
    var ready by mutableStateOf(false)
        private set

    fun init(context: Context, appKey: String) {
        if (started) return
        started = true
        start(context.applicationContext, appKey, attempt = 0)
    }

    private fun start(context: Context, appKey: String, attempt: Int) {
        LevelPlay.init(context, LevelPlayInitRequest.Builder(appKey).build(), object : LevelPlayInitListener {
            override fun onInitSuccess(configuration: LevelPlayConfiguration) {
                Log.i(TAG, "LevelPlay ready")
                main.post { ready = true }
            }

            override fun onInitFailed(error: LevelPlayInitError) {
                // Recommended by LevelPlay: retry later (network may come up after the app starts).
                val delay = (30_000L shl attempt.coerceAtMost(4)).coerceAtMost(10 * 60_000L)
                Log.w(TAG, "LevelPlay init failed: ${error.errorMessage}; retrying in ${delay / 1000}s")
                main.postDelayed({ start(context, appKey, attempt + 1) }, delay)
            }
        })
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
                }

                override fun onAdLoadFailed(error: LevelPlayAdError) {
                    Log.i("NShare.Ads", "banner not loaded: ${error.getErrorMessage()}")
                }
            })
            loadAd()
        }
    }
    DisposableEffect(view) { onDispose { view.destroy() } }
    LaunchedEffect(active, view) { if (active) view.resumeAutoRefresh() else view.pauseAutoRefresh() }

    Box(modifier.fillMaxWidth().height(loadedHeight.dp), contentAlignment = Alignment.Center) {
        AndroidView(factory = { view }, modifier = Modifier.width(adSize.getWidth().dp).height(loadedHeight.dp))
    }
}
