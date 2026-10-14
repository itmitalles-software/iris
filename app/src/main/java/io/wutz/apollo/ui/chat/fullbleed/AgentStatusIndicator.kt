package io.wutz.apollo.ui.chat.fullbleed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.ui.chat.ChatMessage
import io.wutz.apollo.ui.chat.MessageRole
import io.wutz.apollo.ui.chat.MoaProgressUi
import io.wutz.apollo.ui.chat.StreamingState
import io.wutz.apollo.ui.chat.ToolSchemaRegistry
import io.wutz.apollo.ui.chat.components.TypingIndicator
import io.wutz.apollo.ui.chat.isToolRunning
import io.wutz.apollo.ui.chat.isUserTurnBoundary

/** Live status for an agent turn before visible assistant prose is available. */
internal sealed interface AgentStatus {
    data object Typing : AgentStatus

    data object Thinking : AgentStatus

    data class Tool(
        val name: String?,
    ) : AgentStatus

    /** Mixture of agents: reference models answering, then the aggregator combining them. */
    data class Moa(
        val progress: MoaProgressUi,
    ) : AgentStatus
}

/**
 * Derives the one status that should occupy the agent turn's live tail.
 *
 * Tool execution wins over generic states, and visible streaming prose wins
 * over waiting/reasoning states. This keeps one indicator on screen at a time
 * without introducing a second generation state store.
 */
internal fun deriveAgentStatus(
    isAgentTyping: Boolean,
    streamingState: StreamingState,
    messages: List<ChatMessage>,
): AgentStatus? {
    if (!isAgentTyping) return null

    val currentTurnStart = messages.indexOfLast { it.isUserTurnBoundary() }
    val currentTurnMessages =
        if (currentTurnStart >= 0) {
            messages.drop(currentTurnStart + 1)
        } else {
            emptyList()
        }
    val activeTool =
        currentTurnMessages
            .asReversed()
            .firstOrNull {
                it.role == MessageRole.TOOL &&
                    it.isToolRunning
            }
    val hasVisibleStreamingContent = streamingState.streamingMessage?.content?.isNotBlank() == true

    return when {
        activeTool != null -> AgentStatus.Tool(activeTool.toolName)
        hasVisibleStreamingContent -> null
        streamingState.moaProgress != null -> AgentStatus.Moa(streamingState.moaProgress)
        streamingState.isThinking || streamingState.isReasoning -> AgentStatus.Thinking
        isAgentTyping -> AgentStatus.Typing
        else -> null
    }
}

/** Maps existing canonical tool names to compact, user-facing activity text. */
internal fun toolStatusLabelRes(toolName: String?): Int =
    when (toolName?.lowercase()) {
        "web_search",
        "search_files",
        "session_search",
        "tool_search",
        "x_search",
        -> R.string.chat_agent_status_searching

        "read_file",
        -> R.string.chat_agent_status_reading

        "web_extract",
        "browser_navigate",
        "browser_click",
        "browser_type",
        "browser_scroll",
        "browser_back",
        "browser_press",
        "browser_snapshot",
        "browser_get_images",
        "browser_console",
        "browser_cdp",
        "browser_dialog",
        "browser_vision",
        -> R.string.chat_agent_status_browsing

        "terminal",
        "execute_code",
        "process",
        "process_manage",
        -> R.string.chat_agent_status_running_command

        else -> R.string.chat_agent_status_using_tool
    }

@Composable
internal fun AgentStatusIndicator(
    status: AgentStatus,
    modifier: Modifier = Modifier,
) {
    when (status) {
        AgentStatus.Typing -> {
            TypingIndicator(modifier = modifier)
        }

        AgentStatus.Thinking -> {
            StatusText(
                text = stringResource(R.string.chat_agent_status_thinking),
                icon = null,
                modifier = modifier.testTag("agent_status_thinking"),
            )
        }

        is AgentStatus.Moa -> {
            val progress = status.progress
            StatusText(
                text =
                    if (progress.aggregating) {
                        stringResource(R.string.chat_agent_status_moa_aggregating)
                    } else {
                        pluralStringResource(
                            R.plurals.chat_agent_status_moa_references,
                            progress.refsTotal,
                            progress.refsDone,
                            progress.refsTotal,
                        )
                    },
                icon = null,
                modifier = modifier.testTag("agent_status_moa"),
            )
        }

        is AgentStatus.Tool -> {
            val config = ToolSchemaRegistry.getDisplayConfig(status.name)
            val labelRes = toolStatusLabelRes(status.name)
            StatusText(
                text = stringResource(labelRes),
                icon = config.icon,
                modifier = modifier.testTag("agent_status_tool"),
            )
        }
    }
}

@Composable
private fun StatusText(
    text: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
