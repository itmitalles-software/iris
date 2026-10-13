package io.wutz.apollo.ui.chat

import io.wutz.apollo.R
import io.wutz.apollo.ui.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNoticeTest {
    private val resolve: (UiText) -> String = { "res:$it" }

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
            "res:${UiText.res(R.string.system_notice_session_created)}",
            SystemNotice.displayText("Session created", resolve),
        )
        assertNull(SystemNotice.displayResOf("Session created!"))
        assertEquals("Session created!", SystemNotice.displayText("Session created!", resolve))
        assertEquals("", SystemNotice.displayText("", resolve))
    }

    @Test
    fun approvalHeadingIsTranslatedAndDescriptionKept() {
        val shown = SystemNotice.displayText("**Approval Required**\nrm -rf /tmp/x", resolve)
        assertEquals("**res:${UiText.res(R.string.system_notice_approval_required)}**\nrm -rf /tmp/x", shown)
        assertTrue(SystemNotice.displayText("Approval Required later", resolve).startsWith("Approval"))
    }

    @Test
    fun parameterizedRowsKeepTheirStoredEnglishText() {
        // Old app versions wrote exactly these strings; the parser below depends on them.
        assertEquals("Stopped 1 background process.", SystemNotice.stoppedBackgroundProcesses(1))
        assertEquals("Stopped 3 background processes.", SystemNotice.stoppedBackgroundProcesses(3))
        assertEquals(
            "Could not stop background processes: boom",
            SystemNotice.stopBackgroundProcessesFailed("boom"),
        )
        assertEquals("↶ Rewound to: \"fix the bug\"", SystemNotice.rewoundTo("fix the bug"))
        assertEquals(
            "Undid 1 turn (Rewound to: \"fix the bug\")",
            SystemNotice.noticeRewoundTo("Undid 1 turn", "fix the bug"),
        )
    }

    @Test
    fun parameterizedRowsMapToResourcesWithTheirArgument() {
        assertEquals(
            UiText.plural(R.plurals.system_notice_stopped_background_processes, 1),
            SystemNotice.displayUiText("Stopped 1 background process."),
        )
        assertEquals(
            UiText.plural(R.plurals.system_notice_stopped_background_processes, 12),
            SystemNotice.displayUiText(SystemNotice.stoppedBackgroundProcesses(12)),
        )
        assertEquals(
            UiText.res(R.string.system_notice_stop_background_processes_failed, "socket closed: 1006"),
            SystemNotice.displayUiText("Could not stop background processes: socket closed: 1006"),
        )
        assertEquals(
            UiText.res(R.string.system_notice_rewound_to, "say \"hi\"\nand more"),
            SystemNotice.displayUiText("↶ Rewound to: \"say \"hi\"\nand more\""),
        )
        assertEquals(
            UiText.res(R.string.system_notice_notice_rewound_to, "Undid 2 turns", "first prompt"),
            SystemNotice.displayUiText("Undid 2 turns (Rewound to: \"first prompt\")"),
        )
        assertEquals(
            UiText.res(R.string.system_notice_rewound_to, ""),
            SystemNotice.displayUiText(SystemNotice.rewoundTo("")),
        )
    }

    @Test
    fun nearMissesOfParameterizedRowsStayAsStored() {
        assertNull(SystemNotice.displayUiText("Stopped 2 background processes"))
        assertNull(SystemNotice.displayUiText("Stopped two background processes."))
        assertNull(SystemNotice.displayUiText("Stopped 2 background processes. Extra"))
        assertNull(SystemNotice.displayUiText("↶ Rewound to: \"unterminated"))
        assertNull(SystemNotice.displayUiText("Rewound to: \"x\""))
        assertNull(SystemNotice.displayUiText("agent said Could not stop background processes: x"))
        assertEquals(
            "Stopped 2 background processes",
            SystemNotice.displayText("Stopped 2 background processes", resolve),
        )
    }

    @Test
    fun parameterizedRowsResolveInEnglishLikeTheStoredText() {
        val stored =
            listOf(
                SystemNotice.stoppedBackgroundProcesses(1),
                SystemNotice.stoppedBackgroundProcesses(4),
                SystemNotice.stopBackgroundProcessesFailed("boom"),
                SystemNotice.rewoundTo("hello"),
                SystemNotice.noticeRewoundTo("Undid 1 turn", "hello"),
            )
        stored.forEach { content ->
            assertEquals(content, SystemNotice.displayText(content, EnglishTestResources::resolve))
        }
    }
}
