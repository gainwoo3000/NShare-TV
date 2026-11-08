package com.nshare.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.Icon
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.nshare.receiver.PlayerHub
import com.nshare.receiver.R
import kotlinx.coroutines.delay

/** Dimmed full-screen layer with a centered card. */
@Composable
private fun Scrim(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        colors = SurfaceDefaults.colors(containerColor = Color(0xFF1F232B), contentColor = Color.White),
        modifier = Modifier.widthIn(max = 640.dp),
    ) {
        Column(Modifier.padding(horizontal = 48.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

/** PIN the user types on the phone (NShare pairing) or on the Apple device (AirPlay screen PIN). */
@Composable
fun PinOverlay(title: String, body: String, pin: String) {
    Scrim {
        Card {
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFC4C6D0), textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Text(
                if (pin.length == 6) pin.chunked(3).joinToString("  ") else pin,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 72.sp,
                letterSpacing = 6.sp,
                color = Color(0xFFA8C7FA),
            )
        }
    }
}

/**
 * Top-right notification card: slides in from the right edge while fading in, stays for
 * [PlayerHub.Toast.durationMs] (codes show a progress bar filling left to right over that time),
 * then slides back out to the right while fading out. A new toast replaces the current one (the
 * old one leaves first); a null toast dismisses it early.
 */
@Composable
fun ToastHost(toast: PlayerHub.Toast?) {
    var current by remember { mutableStateOf<PlayerHub.Toast?>(null) }
    val visible = remember { MutableTransitionState(false) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(toast?.key) {
        if (visible.targetState) {
            visible.targetState = false
            snapshotFlow { visible.isIdle }.first { it }
        }
        if (toast == null) return@LaunchedEffect
        current = toast
        progress.snapTo(0f)
        visible.targetState = true
        progress.animateTo(1f, tween(toast.durationMs, easing = LinearEasing))
        visible.targetState = false
    }
    Box(Modifier.fillMaxSize().padding(top = 27.dp, end = 48.dp), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { it + 120 } + fadeIn(tween(380)),
            exit = slideOutHorizontally(tween(380, easing = FastOutSlowInEasing)) { it + 120 } + fadeOut(tween(380)),
        ) {
            current?.let { ToastCard(it) { progress.value } }
        }
    }
}

@Composable
private fun ToastCard(toast: PlayerHub.Toast, progress: () -> Float) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        colors = SurfaceDefaults.colors(containerColor = Color(0xF21F232B), contentColor = Color.White),
        modifier = Modifier.width(320.dp),
    ) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 20.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A73E8)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_cast_toast), null, Modifier.size(20.dp), tint = Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(toast.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    toast.code?.let { code ->
                        Text(
                            if (code.length == 6) code.chunked(3).joinToString(" ") else code,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                            letterSpacing = 3.sp,
                            color = Color(0xFFA8C7FA),
                        )
                    }
                }
            }
            if (toast.showProgress) {
                // Time left before the card goes away, filling from left to right.
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.12f))) {
                    Box(
                        Modifier.fillMaxHeight().drawBehind {
                            drawRect(Color(0xFFA8C7FA), size = size.copy(width = this.size.width * progress()))
                        }.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** "X wants to share its screen" with Allow / Decline; declines itself when the deadline passes. */
@Composable
fun ApprovalDialog(request: PlayerHub.ApprovalRequest) {
    val deny = remember { FocusRequester() }
    var remaining by remember(request) { mutableIntStateOf(secondsLeft(request)) }
    LaunchedEffect(request) {
        runCatching { deny.requestFocus() }
        while (remaining > 0) {
            delay(250)
            remaining = secondsLeft(request)
        }
    }
    Scrim {
        Card {
            Text(
                stringResource(R.string.approval_title, request.deviceName),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.approval_body, remaining),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFC4C6D0),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { request.respond(true) }) { Text(stringResource(R.string.approval_allow)) }
                // Declining is the safe default if the remote's OK is pressed by accident.
                OutlinedButton(onClick = { request.respond(false) }, modifier = Modifier.focusRequester(deny)) {
                    Text(stringResource(R.string.approval_deny))
                }
            }
        }
    }
}

private fun secondsLeft(r: PlayerHub.ApprovalRequest) =
    ((r.deadlineMs - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)

/** Shown over the last frame while a dropped NShare sender has a chance to reconnect. */
@Composable
fun ReconnectingBanner() {
    Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
        Surface(shape = CircleShape, colors = SurfaceDefaults.colors(containerColor = Color(0xE6202124), contentColor = Color.White)) {
            Text(
                stringResource(R.string.reconnecting),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
}
