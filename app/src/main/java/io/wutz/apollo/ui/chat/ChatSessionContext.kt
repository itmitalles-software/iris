package io.wutz.apollo.ui.chat

/**
 * Session identity shared by event handling, history requests and send ownership.
 * The stored ID and connection/profile scope are read from their existing owners, without a second copy.
 * Lifecycle cleanup and invalidation ordering remain with [ChatViewModel].
 */
internal class ChatSessionContext(
    private val storageSessionId: () -> String?,
    private val sendScope: () -> String,
) {
    data class SendOwner(
        val scope: String,
        val storageSessionId: String,
        val agentSessionId: String,
        val generation: Long,
    )

    var generation = 0L
        private set

    /** Runtime TUI session returned by session.resume; Desktop storage keeps the original ID. */
    var runtimeSessionId: String? = null

    val effectiveSessionId: String?
        get() = runtimeSessionId ?: storageSessionId()

    /** Invalidate work without changing IDs or performing lifecycle cleanup. */
    fun invalidate(): Long = ++generation

    fun isCurrentEvent(eventSessionId: String?): Boolean =
        // Events without a session ID are still processed for legacy compatibility.
        eventSessionId == null || eventSessionId == runtimeSessionId || eventSessionId == storageSessionId()

    /** Create requests can lack a stored ID; older RPCs can lack a receipt scope. */
    fun isCurrentRequest(
        sessionId: String?,
        requestGeneration: Long,
        receiptScope: String? = null,
    ): Boolean =
        requestGeneration == generation &&
            (receiptScope == null || receiptScope == sendScope()) &&
            (sessionId == null || sessionId == storageSessionId())

    /** A send must still own the exact runtime, stored conversation, profile and generation. */
    fun isCurrentSend(
        scope: String,
        storageSessionId: String,
        agentSessionId: String,
        generation: Long,
    ): Boolean =
        generation == this.generation &&
            scope == sendScope() &&
            storageSessionId == this.storageSessionId() &&
            agentSessionId == runtimeSessionId

    fun isCurrentSend(owner: SendOwner): Boolean =
        isCurrentSend(owner.scope, owner.storageSessionId, owner.agentSessionId, owner.generation)

    fun captureSendOwner(
        storageSessionId: String,
        agentSessionId: String,
        scope: String = sendScope(),
        generation: Long = this.generation,
    ): SendOwner = SendOwner(scope, storageSessionId, agentSessionId, generation)
}
