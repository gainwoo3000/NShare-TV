package com.nshare.receiver.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nshare.receiver.PlayerHub
import com.nshare.receiver.R
import kotlinx.coroutines.delay

/**
 * "Now playing" for music sent over AirPlay (Apple Music and other apps): the cover, blurred and
 * darkened, fills the screen; the sharp cover sits on the left and a frosted-glass panel holds
 * the track, artist, album and progress.
 */
@Composable
fun NowPlayingScreen(np: PlayerHub.NowPlaying, deviceName: String) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(np.artwork, animationSpec = tween(700), label = "backdrop") { art ->
            Backdrop(art)
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Crossfade(np.artwork, animationSpec = tween(500), label = "cover") { art -> Cover(art) }
            Spacer(Modifier.width(48.dp))
            GlassPanel(Modifier.weight(1f)) {
                SourcePill(deviceName)
                Spacer(Modifier.height(28.dp))
                Text(
                    np.title ?: stringResource(R.string.now_playing_unknown),
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 46.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                np.artist?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = Color.White.copy(alpha = 0.78f), fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                np.album?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (np.durationMs > 0) {
                    Spacer(Modifier.height(40.dp))
                    val pos = np.positionMs(now)
                    ProgressLine(pos.toFloat() / np.durationMs)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(pos), color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                        Text("-" + formatTime(np.durationMs - pos), color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.height(28.dp))
                Controls(np.paused)
            }
        }
    }
}

/** Blurred cover filling the screen, darkened towards the bottom so text stays readable. */
@Composable
private fun Backdrop(art: ImageBitmap?) {
    Box(Modifier.fillMaxSize()) {
        if (art != null) {
            Image(
                art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                    .blur(90.dp, BlurredEdgeTreatment.Rectangle),
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF1B2A6B), Color(0xFF4A1F5E), Color(0xFF0E3A4F))),
                ),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.55f))),
            ),
        )
    }
}

@Composable
private fun Cover(art: ImageBitmap?) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .size(340.dp)
            .shadow(48.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(120.dp), tint = Color.White.copy(alpha = 0.5f))
        }
    }
}

/** Frosted glass: a translucent light layer with a hairline edge over the blurred backdrop. */
@Composable
private fun GlassPanel(modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Column(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.07f))),
            )
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.08f))), shape)
            .padding(horizontal = 36.dp, vertical = 36.dp),
    ) { content() }
}

@Composable
private fun SourcePill(deviceName: String) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_airplay_audio), null, Modifier.size(16.dp), tint = Color.White)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.now_playing_from, deviceName), color = Color.White, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun ProgressLine(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f)),
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth().drawBehind {
                drawRoundRect(
                    Color.White.copy(alpha = 0.92f),
                    size = size.copy(width = size.width * fraction.coerceIn(0f, 1f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                )
            },
        )
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 500) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Previous / play-pause / next, driven by the TV remote (◀ OK ▶ or the media keys). */
@Composable
private fun Controls(paused: Boolean) {
    val last = PlayerHub.lastMediaCommand
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ControlButton(R.drawable.ic_skip_previous, 52.dp, last, "previtem")
        Spacer(Modifier.width(28.dp))
        ControlButton(if (paused) R.drawable.ic_play_arrow else R.drawable.ic_pause, 64.dp, last, "playpause")
        Spacer(Modifier.width(28.dp))
        ControlButton(R.drawable.ic_skip_next, 52.dp, last, "nextitem")
    }
}

@Composable
private fun ControlButton(icon: Int, size: androidx.compose.ui.unit.Dp, last: PlayerHub.MediaCommand?, command: String) {
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(last?.atMs) {
        if (last?.command == command) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(450))
        }
    }
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + 0.12f * flash.value
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f + 0.3f * flash.value))
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(size * 0.5f), tint = Color.White)
    }
}
