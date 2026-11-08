package com.nshare.receiver.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.nshare.ads.BannerAd
import com.nshare.ads.BannerSize
import com.nshare.common.AppLanguage
import com.nshare.receiver.PlayerHub
import com.nshare.receiver.R

private val Brand = Color(0xFF1A73E8)

private val TvColors = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFC2C7CF),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF1D2026),
    onSurfaceVariant = Color(0xFFC4C6D0),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE2E2E9),
    border = Color(0xFF44474F),
)

@Composable
fun NShareTvTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = TvColors) {
    // Text outside a Surface uses LocalContentColor, which defaults to black.
    CompositionLocalProvider(LocalContentColor provides TvColors.onBackground, content = content)
}

class TvUiState(
    val receiverName: String,
    val address: String?,
    val port: Int,
    val mirroring: Boolean,
    val clientName: String,
    val videoActive: Boolean,
    val videoAspect: Float,
    val stats: String?,
    val hint: String?,
    val language: AppLanguage,
    val reconnecting: Boolean = false,
    val settingsOpen: Boolean = false,
)

class TvActions(val onDiagnostics: () -> Unit, val onLanguage: () -> Unit, val onSettings: () -> Unit)

/** Overscan-safe margins recommended for TV layouts (5% of 960x540dp). */
private val SafeHorizontal = 48.dp
private val SafeVertical = 27.dp

@Composable
fun TvScreen(state: TvUiState, adUnitId: String, surfaceCallback: SurfaceHolder.Callback, actions: TvActions) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (state.mirroring) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(surfaceCallback) } },
                    modifier = Modifier.aspectRatio(state.videoAspect.coerceIn(0.2f, 5f)),
                )
            }
            val np = PlayerHub.nowPlaying
            when {
                // Music over AirPlay (no screen): cover, track and progress instead of "waiting".
                !state.videoActive && np != null -> NowPlayingScreen(np, state.clientName)
                !state.videoActive && !state.reconnecting -> WaitingForVideo(state.clientName)
            }
            if (state.reconnecting) ReconnectingBanner()
        }

        // Not composed under the settings screen: the idle layout and its ad view would keep drawing
        // behind an opaque page and slow down scrolling.
        AnimatedVisibility(visible = !state.mirroring && !state.settingsOpen, enter = fadeIn(), exit = fadeOut()) {
            IdleScreen(state, adUnitId, actions)
        }

        if (PlayerHub.demoNowPlaying) PlayerHub.nowPlaying?.let { NowPlayingScreen(it, "김강민의 iPhone") }

        // The banner lives outside the idle layout so it survives the settings page and mirroring
        // (hidden, refresh paused) instead of being destroyed and reloaded each time, which left
        // the slot empty for 20-30 s.
        val showAd = !state.mirroring && !state.settingsOpen
        val adAlpha by animateFloatAsState(if (showAd) 1f else 0f, label = "ad")
        Box(Modifier.fillMaxSize().padding(bottom = SafeVertical), contentAlignment = Alignment.BottomCenter) {
            BannerAd(adUnitId, Modifier.width(320.dp).alpha(adAlpha), size = BannerSize.STANDARD, active = showAd)
        }

        if (state.settingsOpen) SettingsScreen()

        state.stats?.let { if (!state.settingsOpen) StatsPanel(it, belowTopBar = !state.mirroring) }

        // Someone is watching: codes appear as a notification card instead of covering the picture.
        val pinToast = if (!state.mirroring) {
            null
        } else {
            PlayerHub.airPlayPin?.let { PlayerHub.Toast("airplay-pin:$it", stringResource(R.string.airplay_pin_title), it) }
                ?: PlayerHub.pinPrompt?.let { PlayerHub.Toast("pin:${it.pin}", stringResource(R.string.pin_title, it.deviceName), it.pin) }
        }
        if (!state.mirroring) {
            PlayerHub.pinPrompt?.let {
                PinOverlay(stringResource(R.string.pin_title, it.deviceName), stringResource(R.string.pin_body, it.deviceName), it.pin)
            }
            PlayerHub.airPlayPin?.let {
                PinOverlay(stringResource(R.string.airplay_pin_title), stringResource(R.string.airplay_pin_body), it)
            }
        }
        // One host for every notification, so a card keeps its animation when mirroring starts or ends.
        ToastHost(pinToast ?: PlayerHub.toast)
        PlayerHub.approval?.let { ApprovalDialog(it) }
        PlayerHub.notice?.let { notice ->
            Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) { Pill(notice) }
        }
        if (!state.mirroring && !state.settingsOpen && PlayerHub.nshareUnavailable) {
            Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
                Pill(stringResource(R.string.nshare_unavailable))
            }
        }

        state.hint?.let { hint ->
            Box(Modifier.fillMaxSize().padding(bottom = 56.dp), contentAlignment = Alignment.BottomCenter) {
                Pill(hint)
            }
        }
    }
}

@Composable
private fun IdleScreen(state: TvUiState, adUnitId: String, actions: TvActions) {
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstButton.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF13284D), Color(0xFF0B0D12)),
                    center = androidx.compose.ui.geometry.Offset(0f, 0f),
                    radius = 2200f,
                ),
            ),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = SafeHorizontal, vertical = SafeVertical)) {
            // Top bar: brand + network status.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppMark(40.dp)
                Spacer(Modifier.width(14.dp))
                Text("NShare", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                NetworkChip(state.address, state.port)
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = actions.onSettings) {
                    Image(
                        painterResource(R.drawable.ic_settings), null,
                        colorFilter = ColorFilter.tint(LocalContentColor.current),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.button_settings))
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = actions.onDiagnostics, modifier = Modifier.focusRequester(firstButton)) {
                    Text(stringResource(R.string.button_diagnostics))
                }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = actions.onLanguage) {
                    Image(
                        painterResource(R.drawable.ic_language), null,
                        colorFilter = ColorFilter.tint(LocalContentColor.current),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (state.language) {
                            AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                            AppLanguage.KOREAN -> stringResource(R.string.language_korean)
                            AppLanguage.ENGLISH -> stringResource(R.string.language_english)
                        },
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                stringResource(R.string.idle_status),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                state.receiverName.ifEmpty { "NShare" },
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.idle_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                InstructionCard(
                    icon = R.drawable.ic_phone,
                    title = stringResource(R.string.apple_title),
                    steps = listOf(stringResource(R.string.apple_step1), stringResource(R.string.apple_step2), state.receiverName),
                    modifier = Modifier.weight(1f),
                )
                InstructionCard(
                    icon = R.drawable.ic_android,
                    title = "Android",
                    steps = listOf(stringResource(R.string.android_step1), stringResource(R.string.android_step2), state.receiverName),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.weight(1f))


        }
    }
}

@Composable
private fun AppMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brand),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.9f),
        )
    }
}

@Composable
private fun NetworkChip(address: String?, port: Int) {
    Surface(
        shape = CircleShape,
        colors = SurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f), contentColor = Color.White),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(if (address != null) R.drawable.ic_wifi else R.drawable.ic_wifi_off),
                contentDescription = null,
                colorFilter = ColorFilter.tint(if (address != null) MaterialTheme.colorScheme.primary else Color(0xFFF2B8B5)),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                address ?: stringResource(R.string.no_network),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun InstructionCard(icon: Int, title: String, steps: List<String>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.06f), contentColor = Color.White),
    ) {
        Column(
            Modifier
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painterResource(icon), null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                buildAnnotatedString {
                    val steps = steps.filter { it.isNotEmpty() }
                    steps.forEachIndexed { i, step ->
                        if (i > 0) withStyle(SpanStyle(color = Color.White.copy(alpha = 0.4f))) { append("  ›  ") }
                        if (i == steps.lastIndex) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFA8C7FA))) { append(step) }
                        } else {
                            append(step)
                        }
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaitingForVideo(clientName: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            colors = SurfaceDefaults.colors(containerColor = Color(0xE6202124), contentColor = Color.White),
        ) {
            Column(Modifier.padding(horizontal = 40.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppMark(48.dp)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.waiting_connected, clientName), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.waiting_video),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsPanel(text: String, belowTopBar: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = SafeHorizontal, vertical = SafeVertical)
            .padding(top = if (belowTopBar) 64.dp else 0.dp),
        // On the idle screen the left column holds the title, so the panel sits under the buttons.
        contentAlignment = if (belowTopBar) Alignment.TopEnd else Alignment.TopStart,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(containerColor = Color(0xD9000000)),
        ) {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(shape = CircleShape, colors = SurfaceDefaults.colors(containerColor = Color(0xF0303134), contentColor = Color.White)) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
        )
    }
}
