package io.wutz.apollo.ui.chat.fullbleed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.wutz.apollo.ui.chat.ChatMessage
import kotlinx.coroutines.delay

/**
 * Wraps a streaming [FullBleedAgentMessage] with a word-by-word typing reveal
 * effect (full-bleed counterpart to the removed bubble streaming wrapper).
 *
 * Shows words one at a time at [typingDelayMs] intervals while the message is
 * still streaming. When streaming completes the full text is shown
 * immediately. The underlying [ChatMessage.content] in state is never
 * modified — this is a display-only transformation.
 *
 * Deliberately a separate wrapper (not a genericized version of the bubble
 * one): the two renderers are parallel code paths by design (issue #866),
 * and the wrappers die together when the bubble path is removed.
 */
@Composable
internal fun StreamingFullBleedWithTypingEffect(
    streaming: ChatMessage,
    typingDelayMs: Int,
    showReasoning: Boolean = true,
    onAnimationComplete: () -> Unit = {},
) {
    var visibleWordCount by remember { mutableIntStateOf(0) }
    var caretVisible by remember { mutableStateOf(true) }
    val currentContent = rememberUpdatedState(streaming.content)
    val currentIsStreaming = rememberUpdatedState(streaming.isStreaming)
    val currentDelayMs = rememberUpdatedState(typingDelayMs)

    // Blinking typing caret while the message is still streaming.
    LaunchedEffect(Unit) {
        while (currentIsStreaming.value) {
            delay(400)
            caretVisible = !caretVisible
        }
        caretVisible = false
    }

    // Space positions of the content, extended incrementally as the stream grows
    // (splitting and re-joining the whole reply on every tick made the
    // work per reply quadratic in its length).
    val boundaries = remember { WordBoundaries() }

    // Timer that ticks at the configured delay, incrementing the visible word
    // count each tick. Stops ticking when streaming ends, then shows all words.
    LaunchedEffect(Unit) {
        var wordCount = 0
        while (true) {
            if (visibleWordCount < wordCount) {
                delay(currentDelayMs.value.toLong())
                visibleWordCount++
            } else {
                if (!currentIsStreaming.value) {
                    onAnimationComplete()
                    break
                }
                // Only rescan when waiting for new content to arrive
                wordCount = boundaries.update(currentContent.value)
                if (visibleWordCount < wordCount) continue
                delay(100)
            }
        }
        visibleWordCount = Int.MAX_VALUE
    }

    // Derive display text from the latest full content at each recomposition
    val wordCount = remember(streaming.content) { boundaries.update(streaming.content) }
    val visibleCount =
        if (visibleWordCount >= Int.MAX_VALUE / 2) {
            wordCount
        } else {
            visibleWordCount.coerceIn(0, wordCount)
        }
    val isStreaming = currentIsStreaming.value
    val displayText =
        remember(streaming.content, visibleCount, caretVisible, isStreaming) {
            boundaries.prefix(streaming.content, visibleCount.coerceAtLeast(1)) +
                if (caretVisible && isStreaming) "▍" else ""
        }

    val displayMessage =
        remember(streaming, displayText) {
            if (streaming.content == displayText) {
                streaming
            } else {
                streaming.copy(content = displayText)
            }
        }

    FullBleedAgentMessage(
        message = displayMessage,
        showReasoning = showReasoning,
        searchQuery = "",
        isCurrentMatch = false,
    )
}

/**
 * Space positions of a growing text, so the typing effect can show the first
 * `n` space-separated words without splitting the whole reply on every tick.
 *
 * [prefix] returns exactly `text.split(" ").take(n).joinToString(" ")`.
 * Streaming text only grows, so [update] scans just the new tail; any other
 * change is rescanned from the start.
 */
internal class WordBoundaries {
    private var text: String = ""
    private var spaces = IntArray(16)
    private var spaceCount = 0

    /** Index [content] and return its word count (`content.split(" ").size`). */
    fun update(content: String): Int {
        if (content !== text) {
            val from =
                if (content.length >= text.length && content.startsWith(text)) {
                    text.length
                } else {
                    spaceCount = 0
                    0
                }
            for (i in from until content.length) {
                if (content[i] == ' ') {
                    if (spaceCount == spaces.size) spaces = spaces.copyOf(spaces.size * 2)
                    spaces[spaceCount++] = i
                }
            }
            text = content
        }
        return spaceCount + 1
    }

    /** The first [words] words of [content], joined by their original single spaces. */
    fun prefix(
        content: String,
        words: Int,
    ): String {
        update(content)
        return if (words > spaceCount) content else content.substring(0, spaces[words - 1])
    }
}
