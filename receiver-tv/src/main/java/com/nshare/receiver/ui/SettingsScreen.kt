package com.nshare.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.nshare.receiver.R
import com.nshare.receiver.security.AirPlaySecurity
import com.nshare.receiver.security.SecuritySettings
import com.nshare.receiver.security.TrustedDevices
import java.text.DateFormat
import java.util.Date

/** TV settings: security options and trusted devices. Fully usable with the D-pad. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val security = remember { SecuritySettings(context) }
    val trustedStore = remember { TrustedDevices(context) }
    // Bumped on every change so the screen re-reads the stores.
    var version by remember { mutableIntStateOf(0) }
    var editingPassword by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    val pin = remember(version) { security.nspPinPairing }
    val approval = remember(version) { security.approveTakeover }
    val airplay = remember(version) { security.airPlaySecurity }
    val password = remember(version) { security.airPlayPassword }
    val trusted = remember(version) { trustedStore.all() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D12))
            .padding(horizontal = 96.dp, vertical = 40.dp),
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_applies_next),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                SectionTitle(stringResource(R.string.settings_security))
            }
            item {
                ToggleRow(
                    title = stringResource(R.string.settings_nsp_pin),
                    description = stringResource(if (pin) R.string.settings_nsp_pin_on else R.string.settings_nsp_pin_off),
                    checked = pin,
                    modifier = Modifier.focusRequester(first),
                ) {
                    security.nspPinPairing = !pin
                    version++
                }
            }
            item {
                ToggleRow(
                    title = stringResource(R.string.settings_approval),
                    description = stringResource(R.string.settings_approval_desc),
                    checked = approval,
                ) {
                    security.approveTakeover = !approval
                    version++
                }
            }
            item {
                ListItem(
                    selected = false,
                    onClick = {
                        security.airPlaySecurity = AirPlaySecurity.entries[(airplay.ordinal + 1) % AirPlaySecurity.entries.size]
                        version++
                    },
                    headlineContent = { Text(stringResource(R.string.settings_airplay)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                when (airplay) {
                                    AirPlaySecurity.NONE -> R.string.airplay_none_desc
                                    AirPlaySecurity.SCREEN_PIN -> R.string.airplay_pin_desc
                                    AirPlaySecurity.PASSWORD -> R.string.airplay_password_desc
                                },
                            ),
                        )
                    },
                    trailingContent = {
                        Text(
                            stringResource(
                                when (airplay) {
                                    AirPlaySecurity.NONE -> R.string.airplay_none
                                    AirPlaySecurity.SCREEN_PIN -> R.string.airplay_pin
                                    AirPlaySecurity.PASSWORD -> R.string.airplay_password
                                },
                            ),
                        )
                    },
                )
            }
            if (airplay == AirPlaySecurity.PASSWORD) {
                item {
                    if (editingPassword) {
                        PasswordEditor(
                            initial = password,
                            onSave = {
                                security.airPlayPassword = it
                                editingPassword = false
                                version++
                            },
                            onCancel = { editingPassword = false },
                        )
                    } else {
                        ListItem(
                            selected = false,
                            onClick = { editingPassword = true },
                            headlineContent = { Text(stringResource(R.string.settings_airplay_password)) },
                            trailingContent = {
                                Text(if (password.isEmpty()) stringResource(R.string.settings_password_unset) else "••••••")
                            },
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                SectionTitle(stringResource(R.string.settings_trusted))
            }
            if (trusted.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_trusted_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(trusted, key = { it.fingerprint }) { d ->
                    ListItem(
                        selected = false,
                        onClick = {
                            trustedStore.remove(d.fingerprint)
                            version++
                        },
                        headlineContent = { Text(d.name) },
                        supportingContent = {
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(d.addedAt)) + " · " + d.fingerprint.take(16))
                        },
                        trailingContent = { Text(stringResource(R.string.settings_trusted_remove)) },
                    )
                }
                item {
                    ListItem(
                        selected = false,
                        onClick = {
                            trustedStore.clear()
                            version++
                        },
                        headlineContent = { Text(stringResource(R.string.settings_trusted_clear), color = Color(0xFFF2B8B5)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onToggle,
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = { onToggle() }) },
        modifier = modifier,
    )
}

@Composable
private fun PasswordEditor(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(20.dp),
    ) {
        Text(stringResource(R.string.settings_password_hint), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = value,
            onValueChange = { value = it.take(64) },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(16.dp)
                .focusRequester(field),
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSave(value) }, enabled = value.length >= 4) { Text(stringResource(R.string.save)) }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}
