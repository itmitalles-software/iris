package io.wutz.apollo.applock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.wutz.apollo.R
import io.wutz.apollo.ui.common.IrisBrandLockup

/**
 * Draws [content] and, while [AppLock] is locked, an opaque lock screen above it. The content
 * stays composed underneath, so navigation, drafts and running requests survive the lock; it is
 * hidden from touch, TalkBack and the back button until the user unlocks.
 */
@Composable
fun AppLockGate(
    onUnlock: () -> Unit,
    onBackWhileLocked: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Synchronous and idempotent: the lock state must be right before the first frame of content.
    AppLock.initializeFromSettings()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(locked) {
        // Also closes the keyboard of a focused text field below the lock screen.
        if (locked) focusManager.clearFocus(force = true)
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(if (locked) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            content()
        }
        if (locked) {
            BackHandler(onBack = onBackWhileLocked)
            AppLockScreen(onUnlock = onUnlock)
        }
    }
}

@Composable
private fun AppLockScreen(onUnlock: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Swallow every touch so nothing reaches the hidden content.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IrisBrandLockup()
            Text(
                text = stringResource(R.string.app_lock_locked_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Button(
                onClick = onUnlock,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.app_lock_unlock),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
