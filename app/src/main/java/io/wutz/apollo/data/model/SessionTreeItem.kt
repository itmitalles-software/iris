package io.wutz.apollo.data.model

/** Fallback title for sessions without title, name or preview; the UI shows a translated label for it. */
const val UNTITLED_SESSION_TITLE = "Untitled"

/** English fallback title of the [number]th untitled fork; see [SessionTreeItem.branchNumber]. */
fun branchFallbackTitle(number: Int): String = "branch $number"

data class SessionTreeItem(
    val session: SessionInfo,
    val depth: Int,
    val branchStem: String?,
    // True when this session was forked/branched from another (has a parent in
    // the loaded set). Drives the fork icon in SessionCard.
    val isFork: Boolean = false,
    // How many levels deep this fork sits (1 = direct child of a root, 2 = fork
    // of a fork, ...). Shown as the number next to the fork icon so a nested
    // chain reads 🍴1 → 🍴2 → 🍴3 and a branch-off pops out visually. 0 for
    // non-forked (root) sessions.
    val forkDepth: Int = 0,
    val displayTitle: String,
    /**
     * Set when [displayTitle] is the English "branch N" fallback for an untitled fork; the UI shows a
     * translated label for it (Iris, issue #10).
     */
    val branchNumber: Int? = null,
)
