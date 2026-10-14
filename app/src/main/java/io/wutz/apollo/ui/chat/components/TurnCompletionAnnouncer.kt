package io.wutz.apollo.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.ChatMessage
import io.wutz.apollo.ui.chat.MessageRole
import kotlinx.coroutines.delay

/** What a screen reader hears when an agent turn ends while the chat is on screen. */
internal enum class TurnCompletion { REPLIED, FAILED }

/**
 * Decides whether a busy → idle edge is a finished turn worth one announcement: the agent's reply
 * now ends the transcript (not an approval card or other prompt that paused it), and that reply was
 * not announced before. A failed turn is announced as such.
 */
internal fun turnCompletion(
    wasBusy: Boolean,
    busy: Boolean,
    replyFailed: Boolean,
    tail: ChatMessage?,
    lastAnnouncedId: String?,
): TurnCompletion? {
    if (!wasBusy || busy) return null
    if (replyFailed) return TurnCompletion.FAILED
    if (tail == null || tail.role != MessageRole.ASSISTANT || tail.isStreaming) return null
    return if (tail.id == lastAnnouncedId) null else TurnCompletion.REPLIED
}

/**
 * A polite live region that announces once when a turn completes, so a TalkBack user does not
 * have to keep swiping to find out whether the agent is done. Draws nothing.
 */
@Composable
internal fun TurnCompletionAnnouncer(
    busy: Boolean,
    tail: ChatMessage?,
    replyFailed: Boolean,
    enabled: Boolean,
    chatGeneration: Long,
    modifier: Modifier = Modifier,
) {
    val repliedText = stringResource(R.string.chat_a11y_reply_finished)
    val failedText = stringResource(R.string.chat_a11y_reply_failed)
    // A switch to another conversation is not a finished turn: start over from its current state.
    var wasBusy by remember(chatGeneration) { mutableStateOf(busy) }
    var lastAnnouncedId by remember(chatGeneration) { mutableStateOf<String?>(null) }
    var announcement by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(busy, tail?.id, tail?.isStreaming, replyFailed) {
        val completion = turnCompletion(wasBusy, busy, replyFailed, tail, lastAnnouncedId)
        wasBusy = busy
        if (completion == null || !enabled) return@LaunchedEffect
        lastAnnouncedId = tail?.id
        // Clear first so the same sentence twice in a row is still a change the screen reader hears.
        announcement = null
        delay(ANNOUNCE_RESET_DELAY_MS)
        announcement = if (completion == TurnCompletion.FAILED) failedText else repliedText
        delay(ANNOUNCEMENT_LIFETIME_MS)
        announcement = null
    }
    Box(
        modifier =
            modifier
                .size(1.dp)
                .testTag("turn_completion_announcer")
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    announcement?.let { contentDescription = it }
                },
    )
}

private const val ANNOUNCE_RESET_DELAY_MS = 50L
private const val ANNOUNCEMENT_LIFETIME_MS = 3_000L
