package io.wutz.apollo.ui.common

import android.content.ClipData
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import io.wutz.apollo.R
import kotlinx.coroutines.launch

/**
 * The snackbar hosts of the [HermesScaffold]s that are currently composed, newest last.
 *
 * Screens report one-shot messages through [ToastEffect], which is called outside the scaffold's
 * content, so it cannot see a host created inside the scaffold. Every scaffold that uses the
 * default host registers it here while it is composed; messages go to the newest one, which is the
 * screen on top (during a navigation transition the incoming screen registers last).
 */
class HermesSnackbarHosts {
    /** A registered host and whether its window can show it (no dialog or sheet on top). */
    class Entry(
        val state: SnackbarHostState,
        val isWindowFocused: () -> Boolean,
    )

    private val hosts = mutableStateListOf<Entry>()

    /** The host of the screen on top, or null when no scaffold with the default host is composed. */
    val current: Entry? get() = hosts.lastOrNull()

    fun register(entry: Entry) {
        hosts.remove(entry)
        hosts.add(entry)
    }

    fun unregister(entry: Entry) {
        hosts.remove(entry)
    }
}

/**
 * The app has a single activity, so the default instance is shared by every screen; tests and
 * previews can provide their own.
 */
val LocalHermesSnackbarHosts = staticCompositionLocalOf { HermesSnackbarHosts() }

/** Visuals of a message shown by [ToastEffect]; failures get the error colors. */
internal data class HermesSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val withDismissAction: Boolean,
    override val duration: SnackbarDuration,
    val isFailure: Boolean,
) : SnackbarVisuals

/**
 * The default snackbar host of [HermesScaffold]: registers itself in [LocalHermesSnackbarHosts]
 * while composed and draws failure messages in the error container colors.
 */
@Composable
internal fun HermesScreenSnackbarHost() {
    val hosts = LocalHermesSnackbarHosts.current
    val hostState = remember { SnackbarHostState() }
    val view = LocalView.current
    DisposableEffect(hosts, hostState, view) {
        val entry = HermesSnackbarHosts.Entry(hostState) { view.hasWindowFocus() }
        hosts.register(entry)
        onDispose { hosts.unregister(entry) }
    }
    SnackbarHost(hostState, modifier = Modifier.testTag("hermes_snackbar_host")) { data ->
        val isFailure = (data.visuals as? HermesSnackbarVisuals)?.isFailure == true
        if (isFailure) {
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionColor = MaterialTheme.colorScheme.onErrorContainer,
                dismissActionContentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            Snackbar(snackbarData = data)
        }
    }
}

/** How a message reported through [ToastEffect] is shown. */
internal data class HermesMessageSpec(
    val isFailure: Boolean,
    val offersUndo: Boolean,
) {
    val offersCopy: Boolean get() = isFailure && !offersUndo

    val duration: SnackbarDuration
        get() = if (isFailure || offersUndo) SnackbarDuration.Long else SnackbarDuration.Short

    /** Messages with an action can also be closed right away. */
    val withDismissAction: Boolean get() = isFailure || offersUndo

    companion object {
        fun of(
            message: UiText,
            hasUndo: Boolean,
        ): HermesMessageSpec = HermesMessageSpec(isFailure = message is UiText.Failure, offersUndo = hasUndo)
    }
}

/**
 * Shows a one-shot screen message in the snackbar of the screen's [HermesScaffold] and clears it
 * in the ViewModel right away.
 *
 * - Failures ([UiText.Failure]) stay longer, use the error colors and offer "Copy", which puts the
 *   full text on the clipboard (server details included).
 * - With [onUndo] the snackbar offers "Undo", which runs the inverse action.
 * - A newer message replaces the one on screen. Leaving the screen dismisses it.
 * - It falls back to a toast without a registered host (a screen that does not use [HermesScaffold])
 *   and while a dialog or bottom sheet covers the screen, because a snackbar would sit behind it.
 */
@Composable
fun ToastEffect(
    toastMessage: UiText?,
    onClearToast: () -> Unit,
    onUndo: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val hosts = LocalHermesSnackbarHosts.current
    // Not the effect's own scope: clearing the message changes the effect key, which would cancel
    // a snackbar that is still showing. This scope ends only when the screen leaves composition.
    val scope = rememberCoroutineScope()
    LaunchedEffect(toastMessage) {
        val message = toastMessage ?: return@LaunchedEffect
        val text = message.resolve(resources)
        val undo = onUndo
        onClearToast()
        val spec = HermesMessageSpec.of(message, hasUndo = undo != null)
        val host = hosts.current?.takeIf { it.isWindowFocused() }?.state
        if (host == null) {
            val length = if (spec.isFailure) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            Toast.makeText(context, text, length).show()
            return@LaunchedEffect
        }
        val actionLabel =
            when {
                spec.offersUndo -> resources.getString(R.string.feedback_action_undo)
                spec.offersCopy -> resources.getString(R.string.feedback_action_copy)
                else -> null
            }
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result =
                host.showSnackbar(
                    HermesSnackbarVisuals(
                        message = text,
                        actionLabel = actionLabel,
                        withDismissAction = spec.withDismissAction,
                        duration = spec.duration,
                        isFailure = spec.isFailure,
                    ),
                )
            if (result == SnackbarResult.ActionPerformed) {
                when {
                    undo != null -> undo()
                    spec.offersCopy -> clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
                }
            }
        }
    }
}
