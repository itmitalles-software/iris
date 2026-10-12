package io.wutz.apollo.data.model
import kotlinx.serialization.Serializable

@Serializable
data class DoctorResponse(
    val ok: Boolean,
    val pid: Int? = null,
    val name: String? = null,
)
