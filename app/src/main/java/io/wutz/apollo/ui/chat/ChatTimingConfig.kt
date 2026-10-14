package io.wutz.apollo.ui.chat

/**
 * Timers and streaming throttle of [ChatViewModel]. Production uses [DEFAULT]; unit tests inject [TEST] (or a
 * copy with selected timers enabled) so the same code path runs everywhere and the timeouts stay testable with
 * virtual time. A `null` timeout disables that timer.
 */
data class ChatTimingConfig(
    /** Clears the connect spinner when the WebSocket never reaches a usable state. */
    val connectSpinnerTimeoutMs: Long?,
    /** Marks a submitted prompt or hard interrupt UNKNOWN when its RPC never answers. */
    val outgoingRequestExpiryMs: Long?,
    /** Clears the loading flag when a session.create response never arrives. */
    val sessionCreateLoadingTimeoutMs: Long?,
    /** Coalesces streamed tokens into ~30 fps flushes; `false` flushes every delta immediately. */
    val throttleStreaming: Boolean,
) {
    companion object {
        val DEFAULT =
            ChatTimingConfig(
                connectSpinnerTimeoutMs = 10_000L,
                outgoingRequestExpiryMs = 30_000L,
                sessionCreateLoadingTimeoutMs = 10_000L,
                throttleStreaming = true,
            )

        /** Deterministic unit-test timing: no background timers, every streamed delta is flushed at once. */
        val TEST =
            ChatTimingConfig(
                connectSpinnerTimeoutMs = null,
                outgoingRequestExpiryMs = null,
                sessionCreateLoadingTimeoutMs = null,
                throttleStreaming = false,
            )
    }
}
