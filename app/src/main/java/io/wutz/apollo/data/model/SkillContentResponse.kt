package io.wutz.apollo.data.model
import kotlinx.serialization.Serializable

@Serializable
data class SkillContentResponse(
    val name: String,
    val content: String,
)
