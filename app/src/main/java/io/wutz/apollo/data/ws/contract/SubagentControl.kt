package io.wutz.apollo.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `subagent.steer` (contract `SubagentSteerParams`): queue course-correction text into one live child
 * owned by [sessionId]. Extra keys are forbidden by the backend.
 */
@Serializable
data class SubagentSteerParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("subagent_id") val subagentId: String,
    @SerialName("text") val text: String,
)

/** `subagent.steer` result. `queued` is not "delivered"; `rejected` means no live owned child took it. */
@Serializable
data class SubagentSteerResult(
    @SerialName("status") val status: String = "",
    @SerialName("subagent_id") val subagentId: String = "",
) {
    val queued: Boolean get() = status == STATUS_QUEUED

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_REJECTED = "rejected"
    }
}

/** `subagent.interrupt` result. `found` is false when the child had already finished. */
@Serializable
data class SubagentInterruptResult(
    @SerialName("found") val found: Boolean = false,
    @SerialName("subagent_id") val subagentId: String = "",
)
