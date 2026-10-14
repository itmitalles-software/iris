package io.wutz.apollo.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data object SessionListParams

@Serializable
data class SessionIdParams(
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class SessionBranchParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class SessionBranchWholeParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("name") val name: String? = null,
)

@Serializable
data class SessionCompressParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("focus_topic") val focusTopic: String? = null,
)

@Serializable
data class PromptBtwParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("text") val text: String,
)

@Serializable
data class PromptBtwResult(
    @SerialName("task_id") val taskId: String? = null,
)

/**
 * `message.react`: set or clear the user's tapback on the stored row [rowId] (`messages.id`). Tapback
 * semantics on the gateway: one reaction per author, the same emoji again retracts, null clears.
 */
@Serializable
data class MessageReactParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("row_id") val rowId: Long,
    @SerialName("emoji") val emoji: String?,
    @SerialName("author") val author: String,
)

@Serializable
data class MessageReactResult(
    @SerialName("row_id") val rowId: Long,
    @SerialName("reactions") val reactions: List<MessageReactionWire> = emptyList(),
)

@Serializable
data class MessageReactionWire(
    @SerialName("emoji") val emoji: String,
    @SerialName("author") val author: String = "",
)
