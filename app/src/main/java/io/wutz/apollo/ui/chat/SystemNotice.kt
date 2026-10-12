package io.wutz.apollo.ui.chat

import androidx.annotation.StringRes
import io.wutz.apollo.R

/**
 * Fixed texts of local system rows (Apollo, issue #8).
 *
 * The English text is what gets stored in Room and compared against (session start markers, tests,
 * transcript merge), so it must never be localized at creation time. [displayResOf] maps a known
 * constant to its string resource; the UI translates only when rendering.
 */
internal object SystemNotice {
    const val SESSION_CREATED = "Session created"
    const val SESSION_BRANCHED = "Session branched"
    const val SESSION_INTERRUPTED = "Session interrupted"
    const val SESSION_EXPIRED_NEW_CHAT =
        "Previous session is no longer available on the server — starting a new chat"
    const val APPROVAL_SUBMITTED = "Approval submitted"
    const val CLARIFY_DISMISSED = "Clarify dismissed — no answer sent"
    const val NO_BACKGROUND_PROCESSES = "No background processes to stop."
    const val REWOUND = "↶ Rewound"

    /** Default compression progress text (also sent verbatim by the gateway); shown translated. */
    const val COMPRESSING_CONTEXT = "⏳ Compressing context..."

    /** Heading line of an approval request row; the command description follows on the next line. */
    const val APPROVAL_REQUIRED_HEADING = "**Approval Required**"

    /** Rows that mark the start of a session transcript. */
    val SESSION_START_MARKERS: Set<String> = setOf(SESSION_CREATED, SESSION_BRANCHED)

    @StringRes
    fun displayResOf(content: String): Int? =
        when (content) {
            SESSION_CREATED -> R.string.system_notice_session_created
            SESSION_BRANCHED -> R.string.system_notice_session_branched
            SESSION_INTERRUPTED -> R.string.system_notice_session_interrupted
            SESSION_EXPIRED_NEW_CHAT -> R.string.system_notice_session_expired_new_chat
            APPROVAL_SUBMITTED -> R.string.system_notice_approval_submitted
            CLARIFY_DISMISSED -> R.string.system_notice_clarify_dismissed
            NO_BACKGROUND_PROCESSES -> R.string.system_notice_no_background_processes
            REWOUND -> R.string.system_notice_rewound
            else -> null
        }

    /**
     * Returns the display text for a stored system row: a known constant becomes its translation, and the
     * English approval heading is replaced by its translation. Anything else is shown as stored.
     */
    fun displayText(
        content: String,
        resolve: (Int) -> String,
    ): String {
        displayResOf(content)?.let { return resolve(it) }
        if (content.startsWith(APPROVAL_REQUIRED_HEADING)) {
            return "**" + resolve(R.string.system_notice_approval_required) + "**" +
                content.removePrefix(APPROVAL_REQUIRED_HEADING)
        }
        return content
    }
}
