package io.wutz.apollo.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class BusySendMode {
    CORRECT,
    QUEUE,
    GUIDE,
    INTERRUPT,
}
