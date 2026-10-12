package io.wutz.apollo.data.local

import io.wutz.apollo.ui.chat.ChatMessage
import io.wutz.apollo.ui.chat.MessageProvenance
import io.wutz.apollo.ui.chat.MessageRole
import io.wutz.apollo.ui.chat.ToolStatus
import io.wutz.apollo.ui.chat.normalizedGatewayNotice

/**
 * Converts between the Room [ChatMessageEntity] and the UI [ChatMessage].
 *
 * Keep this as a pure mapping — no I/O, no side effects.
 */

fun ChatMessageEntity.toUiModel(): ChatMessage =
    ChatMessage(
        id = id,
        role =
            when (role) {
                "USER" -> MessageRole.USER
                "ASSISTANT" -> MessageRole.ASSISTANT
                "SYSTEM" -> MessageRole.SYSTEM
                "TOOL" -> MessageRole.TOOL
                else -> MessageRole.ASSISTANT
            },
        content = content,
        reasoningText = reasoningText,
        timestamp = timestamp,
        isStreaming = isStreaming,
        toolName = toolName,
        toolCallId = toolCallId,
        toolStatus =
            when (toolStatus) {
                "RUNNING" -> ToolStatus.RUNNING
                "COMPLETED" -> ToolStatus.COMPLETED
                "FAILED" -> ToolStatus.FAILED
                else -> null
            },
        displayKind = displayKind,
        tokenCount = tokenCount,
        tps = tps,
        completionId = completionId,
        restId = restId,
        localOrder = sortOrder.takeIf { sortGroup == 1 && it > 0 && !isSessionStartMarker() },
        localAnchorOrder = localAnchorOrder,
        localPredecessorId = localPredecessorId,
        messageProvenance =
            MessageProvenance.entries.firstOrNull { it.name == messageProvenance }
                ?: MessageProvenance.UNKNOWN,
    ).normalizedGatewayNotice()

fun ChatMessage.toEntity(sessionId: String): ChatMessageEntity =
    ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        role = role.name,
        content = content,
        reasoningText = reasoningText,
        timestamp = timestamp,
        toolName = toolName,
        toolCallId = toolCallId,
        toolStatus = toolStatus?.name,
        isStreaming = isStreaming,
        displayKind = displayKind,
        tokenCount = tokenCount,
        tps = tps,
        completionId = completionId,
        restId = restId,
        messageProvenance = messageProvenance.name,
        localAnchorOrder = localAnchorOrder,
        localPredecessorId = localPredecessorId,
    )
