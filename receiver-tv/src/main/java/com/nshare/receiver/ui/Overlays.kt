package com.nshare.receiver.ui

import androidx.compose.foundation.background
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
                pin.chunked(3).joinToString("  "),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 72.sp,
                letterSpacing = 6.sp,
                color = Color(0xFFA8C7FA),
            )
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
