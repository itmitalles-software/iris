package io.wutz.apollo.ui.settings.components

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wutz.apollo.R
import io.wutz.apollo.applock.AppLock
import io.wutz.apollo.ui.settings.SectionCard

/** Opt-in app lock: the switch and, when it is on, how long Apollo may stay in the background. */
@Composable
internal fun AppLockSection() {
    val context = LocalContext.current
    val settings by AppLock.settings.collectAsStateWithLifecycle()
    // The user may add or remove the screen lock in Android settings and come back.
    var deviceSecure by remember { mutableStateOf(context.isDeviceSecure()) }
    LifecycleResumeEffect(context) {
        deviceSecure = context.isDeviceSecure()
        onPauseOrDispose {}
    }
    val canToggle = deviceSecure || settings.enabled

    SectionCard {
        Row(
            modifier =
                Modifier.fillMaxWidth().toggleable(
                    value = settings.enabled,
                    enabled = canToggle,
                    role = Role.Switch,
                    onValueChange = AppLock::setEnabled,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_lock_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text =
                        stringResource(
                            if (deviceSecure) R.string.app_lock_desc else R.string.app_lock_needs_screen_lock,
                        ),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                )
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = null,
                enabled = canToggle,
            )
        }

        if (settings.enabled) {
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_lock_timeout_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.app_lock_timeout_desc),
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
            Spacer(modifier = Modifier.height(8.dp))
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(timeoutLabel(settings.timeoutSeconds))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.85f),
                ) {
                    AppLock.TIMEOUT_CHOICES_SECONDS.forEach { seconds ->
                        DropdownMenuItem(
                            text = { Text(timeoutLabel(seconds)) },
                            onClick = {
                                expanded = false
                                AppLock.setTimeoutSeconds(seconds)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun timeoutLabel(seconds: Int): String =
    stringResource(
        when {
            seconds <= 30 -> R.string.app_lock_timeout_30s
            seconds <= 60 -> R.string.app_lock_timeout_1m
            seconds <= 300 -> R.string.app_lock_timeout_5m
            else -> R.string.app_lock_timeout_15m
        },
    )

private fun Context.isDeviceSecure(): Boolean = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
