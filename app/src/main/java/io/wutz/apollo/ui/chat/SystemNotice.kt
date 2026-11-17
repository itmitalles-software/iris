package io.wutz.apollo.ui.chat

import androidx.annotation.StringRes
import io.wutz.apollo.R
import io.wutz.apollo.ui.common.UiText

/**
 * Fixed texts of local system rows (Iris, issue #8).
 *
 * The English text is what gets stored in Room and compared against (session start markers, tests,
 * transcript merge), so it must never be localized at creation time. [displayResOf] maps a known
 * constant to its string resource; the UI translates only when rendering.
 *
 * Parameterized rows (issue #10) are stored as English text too, built by the functions below
 * ([stoppedBackgroundProcesses], [stopBackgroundProcessesFailed], [rewoundTo], [noticeRewoundTo]).
 * [displayUiText] parses them back with anchored patterns into a resource plus the original
 * argument, so rows written by older app versions (same English wording) are translated as well,
 * and anything that does not match exactly is shown as stored. The argument (an error detail, the
 * rewound prompt or a server notice) is never translated.
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

    private val STOPPED_BACKGROUND_PROCESSES = Regex("""^Stopped (\d{1,9}) background process(?:es)?\.$""")
    private const val STOP_BACKGROUND_PROCESSES_FAILED_PREFIX = "Could not stop background processes: "
    private const val REWOUND_TO_PREFIX = "↶ Rewound to: \""
    private val NOTICE_REWOUND_TO = Regex("""^(.+?) \(Rewound to: "(.*)"\)$""", RegexOption.DOT_MATCHES_ALL)

    /** Stored text of the `/stop` row after [killed] (> 0) background processes were stopped. */
    fun stoppedBackgroundProcesses(killed: Int): String =
        "Stopped $killed background process${if (killed == 1) "" else "es"}."

    /** Stored text of the `/stop` row when `process.stop` failed; [reason] is the raw error detail. */
    fun stopBackgroundProcessesFailed(reason: String): String = STOP_BACKGROUND_PROCESSES_FAILED_PREFIX + reason

    /** Stored text of a rewind row that restored [message] into the composer. */
    fun rewoundTo(message: String): String = "$REWOUND_TO_PREFIX$message\""

    /** Stored text of a rewind row that carries a server [notice] and the restored [message]. */
    fun noticeRewoundTo(
        notice: String,
        message: String,
    ): String = "$notice (Rewound to: \"$message\")"

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
     * Returns the translatable form of a stored system row: a known constant or one of the parsed
     * parameterized variants (see the class KDoc). Returns null for any other content.
     */
    fun displayUiText(content: String): UiText? {
        displayResOf(content)?.let { return UiText.res(it) }
        STOPPED_BACKGROUND_PROCESSES.matchEntire(content)?.let { match ->
            val killed = match.groupValues[1].toIntOrNull() ?: return null
            return UiText.plural(R.plurals.system_notice_stopped_background_processes, killed)
        }
        if (content.startsWith(STOP_BACKGROUND_PROCESSES_FAILED_PREFIX)) {
            return UiText.res(
                R.string.system_notice_stop_background_processes_failed,
                content.removePrefix(STOP_BACKGROUND_PROCESSES_FAILED_PREFIX),
            )
        }
        if (content.startsWith(REWOUND_TO_PREFIX) && content.endsWith("\"") &&
            content.length >= REWOUND_TO_PREFIX.length + 1
        ) {
            return UiText.res(
                R.string.system_notice_rewound_to,
                content.substring(REWOUND_TO_PREFIX.length, content.length - 1),
            )
        }
        NOTICE_REWOUND_TO.matchEntire(content)?.let { match ->
            return UiText.res(
                R.string.system_notice_notice_rewound_to,
                match.groupValues[1],
                match.groupValues[2],
            )
        }
        return null
    }

    /**
     * Returns the display text for a stored system row: a known constant or parsed variant becomes its
     * translation, and the English approval heading is replaced by its translation. Anything else is shown
     * as stored.
     */
    fun displayText(
        content: String,
        resolve: (UiText) -> String,
    ): String {
        displayUiText(content)?.let { return resolve(it) }
        if (content.startsWith(APPROVAL_REQUIRED_HEADING)) {
            return "**" + resolve(UiText.res(R.string.system_notice_approval_required)) + "**" +
                content.removePrefix(APPROVAL_REQUIRED_HEADING)
        }
        return content
    }

    /**
     * Splits [text] at `**bold**` markers into (segment, isBold) pairs. System rows are shown as plain
     * text, so the approval heading's Markdown emphasis is turned into a bold span instead of showing
     * the asterisks. An unmatched `**` stays literal.
     */
    fun boldSegments(text: String): List<Pair<String, Boolean>> {
        val segments = mutableListOf<Pair<String, Boolean>>()
        var index = 0
        while (index < text.length) {
            val open = text.indexOf("**", index)
            val close = if (open >= 0) text.indexOf("**", open + 2) else -1
            if (open < 0 || close < 0) {
                segments += text.substring(index) to false
                break
            }
            if (open > index) segments += text.substring(index, open) to false
            if (close > open + 2) segments += text.substring(open + 2, close) to true
            index = close + 2
        }
        return segments
    }
}
