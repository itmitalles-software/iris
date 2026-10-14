package io.wutz.apollo.ui.chat.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.wutz.apollo.ExternalActivityLifecycleGuard
import io.wutz.apollo.R

/**
 * Returns the action that starts a voice conversation: it asks for RECORD_AUDIO first when
 * needed (through [launchExternalActivity], like the dictation mic) and then calls [onStart].
 */
@Composable
fun rememberVoiceConversationStarter(
    onStart: () -> Unit,
    onPermissionDenied: () -> Unit,
    launchExternalActivity: (() -> Unit) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnDenied by rememberUpdatedState(onPermissionDenied)
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            if (granted) currentOnStart() else currentOnDenied()
        }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            currentOnStart()
        } else {
            launchExternalActivity { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }
}

/**
 * Clean exit of the voice conversation: it stops when the app goes to the background
 * (ON_STOP), when the chat screen leaves composition, and when the chat switches to another
 * session. A session id that appears for a chat still being created is not a switch.
 */
@Composable
fun VoiceConversationLifecycle(
    sessionId: String?,
    onStop: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val currentOnStop by rememberUpdatedState(onStop)
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                // A rotation stops and recreates the activity; the ViewModel keeps the conversation.
                if (event == Lifecycle.Event.ON_STOP && !context.isChangingConfigurations()) currentOnStop()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!context.isChangingConfigurations()) currentOnStop()
        }
    }
    var lastSessionId by remember { mutableStateOf(sessionId) }
    LaunchedEffect(sessionId) {
        val previous = lastSessionId
        lastSessionId = sessionId
        if (previous != null && previous != sessionId) currentOnStop()
    }
}

private fun Context.isChangingConfigurations(): Boolean =
    generateSequence(this) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>()
        .firstOrNull()
        ?.isChangingConfigurations == true

/** Top-bar toggle for the voice conversation; tinted while the conversation runs. */
@Composable
fun VoiceConversationButton(
    active: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = if (active) onEnd else onStart,
        enabled = active || enabled,
        colors =
            if (active) {
                IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            } else {
                IconButtonDefaults.iconButtonColors()
            },
        modifier = modifier.testTag("chat_voice_conversation_button"),
    ) {
        Icon(
            imageVector = Icons.Filled.RecordVoiceOver,
            contentDescription =
                stringResource(if (active) R.string.voice_conversation_end else R.string.voice_conversation_start),
        )
    }
}
