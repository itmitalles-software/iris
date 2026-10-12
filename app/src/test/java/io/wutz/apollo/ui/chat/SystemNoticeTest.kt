package io.wutz.apollo.ui.chat

import io.wutz.apollo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNoticeTest {
    private val resolve: (Int) -> String = { "res:$it" }

    @Test
    fun storedContentStaysEnglish() {
        // Persisted rows and identifier checks (#8) depend on these exact values.
        assertEquals("Session created", SystemNotice.SESSION_CREATED)
        assertEquals("Session branched", SystemNotice.SESSION_BRANCHED)
        assertEquals("Session interrupted", SystemNotice.SESSION_INTERRUPTED)
        assertEquals("Approval submitted", SystemNotice.APPROVAL_SUBMITTED)
        assertEquals("Clarify dismissed — no answer sent", SystemNotice.CLARIFY_DISMISSED)
        assertEquals(
            "Previous session is no longer available on the server — starting a new chat",
            SystemNotice.SESSION_EXPIRED_NEW_CHAT,
        )
        assertEquals(setOf("Session created", "Session branched"), SystemNotice.SESSION_START_MARKERS)
    }

    @Test
    fun knownNoticesMapToStringResources() {
        assertEquals(R.string.system_notice_session_created, SystemNotice.displayResOf(SystemNotice.SESSION_CREATED))
        assertEquals(R.string.system_notice_session_branched, SystemNotice.displayResOf(SystemNotice.SESSION_BRANCHED))
        assertEquals(
            R.string.system_notice_session_interrupted,
            SystemNotice.displayResOf(SystemNotice.SESSION_INTERRUPTED),
        )
        assertEquals(
            R.string.system_notice_session_expired_new_chat,
            SystemNotice.displayResOf(SystemNotice.SESSION_EXPIRED_NEW_CHAT),
        )
        assertEquals(
            R.string.system_notice_approval_submitted,
            SystemNotice.displayResOf(SystemNotice.APPROVAL_SUBMITTED),
        )
        assertEquals(
            R.string.system_notice_clarify_dismissed,
            SystemNotice.displayResOf(SystemNotice.CLARIFY_DISMISSED),
        )
        assertEquals(
            R.string.system_notice_no_background_processes,
            SystemNotice.displayResOf(SystemNotice.NO_BACKGROUND_PROCESSES),
        )
        assertEquals(R.string.system_notice_rewound, SystemNotice.displayResOf(SystemNotice.REWOUND))
    }

    @Test
    fun displayTextTranslatesOnlyExactKnownContent() {
        assertEquals(
            "res:${R.string.system_notice_session_created}",
            SystemNotice.displayText("Session created", resolve),
        )
        assertNull(SystemNotice.displayResOf("Session created!"))
        assertEquals(
            "Stopped 2 background processes.",
            SystemNotice.displayText("Stopped 2 background processes.", resolve),
        )
        assertEquals("", SystemNotice.displayText("", resolve))
    }

    @Test
    fun approvalHeadingIsTranslatedAndDescriptionKept() {
        val shown = SystemNotice.displayText("**Approval Required**\nrm -rf /tmp/x", resolve)
        assertEquals("**res:${R.string.system_notice_approval_required}**\nrm -rf /tmp/x", shown)
        assertTrue(SystemNotice.displayText("Approval Required later", resolve).startsWith("Approval"))
    }
}
