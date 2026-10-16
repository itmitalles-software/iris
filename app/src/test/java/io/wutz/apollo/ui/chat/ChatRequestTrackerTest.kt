package io.wutz.apollo.ui.chat

import io.wutz.apollo.data.ws.WsMethods
import io.wutz.apollo.data.ws.contract.SessionBranchWholeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestTrackerTest {
    private var sessionId: String? = "session-a"
    private var scope = "scope-a"
    private var resumeSequence = 0L
    private val sessionContext = ChatSessionContext({ sessionId }, { scope }).apply { invalidate() }

    private val tracker =
        ChatRequestTracker(
            sessionContext = sessionContext,
            activeResumeSequence = { resumeSequence },
        )

    @Test
    fun completeReturnsAndRemovesEverythingTrackedForTheId() {
        val params = SessionBranchWholeParams(sessionId = "runtime-a", name = "fork")
        tracker.trackBranchWhole("req-1", ChatRequestTracker.PendingBranchRequest(1L, params))
        tracker.trackSession("req-1", WsMethods.SESSION_BRANCH_WHOLE, generation = 1L, sessionId = "session-a")

        val completed = tracker.complete("req-1")

        assertEquals(WsMethods.SESSION_BRANCH_WHOLE, completed?.method)
        assertEquals("session-a", completed?.request?.sessionId)
        assertEquals(params, completed?.pendingBranch?.params)
        assertNull(tracker.complete("req-1"))
        assertNull(tracker.sessionRequest("req-1"))
    }

    @Test
    fun completeOfAnUntrackedIdKeepsOtherEntries() {
        tracker.trackBranchWhole(
            "req-2",
            ChatRequestTracker.PendingBranchRequest(1L, SessionBranchWholeParams(sessionId = "runtime-a")),
        )

        assertNull(tracker.complete("req-2"))

        tracker.track("req-2", WsMethods.SESSION_BRANCH_WHOLE)
        assertTrue(tracker.complete("req-2")?.pendingBranch != null)
    }

    @Test
    fun requestBecomesStaleWhenGenerationSessionScopeOrResumeChanges() {
        tracker.trackSession(
            "req-3",
            WsMethods.PROMPT_SUBMIT,
            generation = 1L,
            resumeSequence = 4L,
            sessionId = "session-a",
            receiptScope = "scope-a",
        )
        resumeSequence = 4L
        assertFalse(tracker.isStale("req-3"))

        sessionId = "session-b"
        assertTrue(tracker.isStale("req-3"))
        sessionId = "session-a"

        scope = "scope-b"
        assertTrue(tracker.isStale("req-3"))
        scope = "scope-a"

        resumeSequence = 5L
        assertTrue(tracker.isStale("req-3"))
        resumeSequence = 4L

        sessionContext.invalidate()
        assertTrue(tracker.isStale("req-3"))
    }

    @Test
    fun liveSessionCreateIgnoresStaleCreates() {
        tracker.trackSession("create-1", WsMethods.SESSION_CREATE, generation = 1L)
        assertTrue(tracker.hasLiveSessionCreate())

        sessionContext.invalidate()
        assertFalse(tracker.hasLiveSessionCreate())

        tracker.trackSession("create-2", WsMethods.SESSION_CREATE, generation = 2L)
        assertTrue(tracker.hasLiveSessionCreate())

        tracker.forget("create-2")
        assertFalse(tracker.hasLiveSessionCreate())
    }

    @Test
    fun currentSessionRequestNeedsTheSameGenerationAndSession() {
        assertTrue(tracker.isCurrentSessionRequest("session-a", 1L))
        assertFalse(tracker.isCurrentSessionRequest("session-a", 2L))
        assertFalse(tracker.isCurrentSessionRequest("session-b", 1L))
    }
}
