package io.wutz.apollo.data.model
import kotlinx.serialization.Serializable

@Serializable
data class ToggleSkillRequest(
    val name: String,
    val enabled: Boolean,
)
