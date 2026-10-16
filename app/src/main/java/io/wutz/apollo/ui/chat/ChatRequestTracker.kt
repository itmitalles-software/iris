package io.wutz.apollo.ui.chat

import io.wutz.apollo.data.ws.WsMethods
import io.wutz.apollo.data.ws.contract.SessionBranchWholeParams
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers which RPC each outgoing request id belongs to and which session, generation, resume attempt and send
 * receipt it was sent for, so a response that arrives after a session switch, profile change or newer resume is
 * recognised as stale instead of being applied to the current chat.
 *
 * Extracted behaviour-preservingly from [ChatViewModel].
 */
internal class ChatRequestTracker(
    private val sessionContext: ChatSessionContext,
    private val activeResumeSequence: () -> Long,
) {
    data class SessionRequest(
        val generation: Long,
        val resumeSequence: Long = 0L,
        val sessionId: String? = null,
        val connectionCheckpoint: ConnectionResumeCheckpoint? = null,
        val receiptScope: String? = null,
        val receiptAttempt: Int? = null,
    )

    data class PendingBranchRequest(
        val generation: Long,
        val params: SessionBranchWholeParams,
    )

    /** Everything tracked for one request id, removed from the tracker by [complete]. */
    class Completed(
        val method: String,
        val request: SessionRequest?,
        val pendingBranch: PendingBranchRequest?,
    )

    private val idToMethod = ConcurrentHashMap<String, String>()
    private val sessionRequestById = ConcurrentHashMap<String, SessionRequest>()
    private val branchWholeRequests = ConcurrentHashMap<String, PendingBranchRequest>()

    fun track(
        id: String,
        method: String,
    ) {
        idToMethod[id] = method
    }

    fun trackSession(
        id: String,
        method: String,
        generation: Long,
        resumeSequence: Long = 0L,
        sessionId: String? = null,
        connectionCheckpoint: ConnectionResumeCheckpoint? = null,
        receiptScope: String? = null,
        receiptAttempt: Int? = null,
    ) {
        sessionRequestById[id] =
            SessionRequest(
                generation = generation,
                resumeSequence = resumeSequence,
                sessionId = sessionId,
                connectionCheckpoint = connectionCheckpoint,
                receiptScope = receiptScope,
                receiptAttempt = receiptAttempt,
            )
        track(id, method)
    }

    fun trackBranchWhole(
        id: String,
        request: PendingBranchRequest,
    ) {
        branchWholeRequests[id] = request
    }

    fun sessionRequest(id: String): SessionRequest? = sessionRequestById[id]

    /**
     * Removes and returns everything tracked for [id]. Returns null and leaves the tracker untouched when no
     * method was tracked for it (an untracked or already completed response).
     */
    fun complete(id: String): Completed? {
        val method = idToMethod.remove(id) ?: return null
        return Completed(method, sessionRequestById.remove(id), branchWholeRequests.remove(id))
    }

    fun forget(id: String) {
        idToMethod.remove(id)
        sessionRequestById.remove(id)
        branchWholeRequests.remove(id)
    }

    /** True while a session.create of the current session context is still awaiting its result. */
    fun hasLiveSessionCreate(): Boolean =
        idToMethod.any { (id, method) ->
            method == WsMethods.SESSION_CREATE &&
                sessionRequestById[id]?.let { !isStale(it) } == true
        }

    fun isCurrentSessionRequest(
        sessionId: String,
        generation: Long,
    ): Boolean = sessionContext.isCurrentRequest(sessionId, generation)

    fun isStale(id: String): Boolean = sessionRequestById[id]?.let(::isStale) == true

    fun isStale(request: SessionRequest): Boolean =
        !sessionContext.isCurrentRequest(request.sessionId, request.generation, request.receiptScope) ||
            (request.resumeSequence != 0L && request.resumeSequence != activeResumeSequence())
}
