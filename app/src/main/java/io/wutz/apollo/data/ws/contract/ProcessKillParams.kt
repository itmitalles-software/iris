package io.wutz.apollo.data.ws.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProcessKillParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("process_id") val processId: String,
)
