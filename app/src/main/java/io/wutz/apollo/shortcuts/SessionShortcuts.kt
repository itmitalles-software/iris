package io.wutz.apollo.shortcuts

import android.content.Context
import android.os.PersistableBundle
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.wutz.apollo.R
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.model.SessionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A session chosen for a dynamic shortcut. */
data class SessionShortcutEntry(
    val sessionId: String,
    val label: String,
)

/**
 * Dynamic shortcuts for the pinned and most recent conversations. They appear in the launcher's
 * long-press menu and, through the share-target category, as direct-share targets in the system
 * share sheet (Android 10+). Each shortcut carries the [AppShortcuts.scopeKey] of the connection
 * and profile it belongs to; shortcuts of another scope are removed as soon as the scope changes
 * (logout, connection or profile switch), so conversation titles never outlive their account.
 */
object SessionShortcuts {
    private const val TAG = "SessionShortcuts"
    private const val STATIC_SHORTCUT_COUNT = 3
    private const val MAX_SESSION_SHORTCUTS = 4
    private const val SHORT_LABEL_MAX = 25
    private const val LONG_LABEL_MAX = 60

    @Volatile
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastPublished: List<SessionShortcutEntry>? = null

    /**
     * Starts pruning shortcuts of a stale scope; [signedInScopes] emits the current scope, or null
     * when signed out. Without this call every operation is a no-op (unit tests).
     */
    fun attach(
        context: Context,
        signedInScopes: Flow<DataScope?>,
    ) {
        appContext = context.applicationContext
        scope.launch {
            // Null means signed out: every session shortcut goes.
            signedInScopes
                .map(AppShortcuts::scopeKey)
                .distinctUntilChanged()
                .collect(::prune)
        }
    }

    /**
     * Picks the shortcut sessions from a freshly loaded history page: pinned first, then by latest
     * activity; archived, hidden and automation sessions are skipped.
     */
    fun select(
        sessions: List<SessionInfo>,
        untitled: String,
        max: Int = MAX_SESSION_SHORTCUTS,
    ): List<SessionShortcutEntry> =
        sessions
            .asSequence()
            .filter { it.archived != true && it.hidden != true && it.source != "cron" && it.id.isNotBlank() }
            .sortedWith(
                compareByDescending<SessionInfo> { it.pinned == true }
                    .thenByDescending { it.last_active ?: it.started_at ?: 0.0 },
            ).distinctBy { it.id }
            .take(max.coerceAtLeast(0))
            .map { session ->
                val label =
                    listOf(session.title, session.display_name, session.preview)
                        .firstOrNull { !it.isNullOrBlank() }
                        ?.trim()
                        ?.replace(Regex("\\s+"), " ")
                        ?: untitled
                SessionShortcutEntry(sessionId = session.id, label = label)
            }.toList()

    /** Replaces the session shortcuts with the top entries of [sessions] for [dataScope]. */
    fun publish(
        dataScope: DataScope?,
        sessions: List<SessionInfo>,
    ) {
        val context = appContext ?: return
        val scopeKey = AppShortcuts.scopeKey(dataScope) ?: return
        scope.launch {
            runCatching {
                val room =
                    (ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) - STATIC_SHORTCUT_COUNT)
                        .coerceIn(0, MAX_SESSION_SHORTCUTS)
                val entries = select(sessions, context.getString(R.string.history_untitled), room)
                if (entries == lastPublished) return@runCatching
                val shortcuts = entries.mapIndexed { rank, entry -> build(context, entry, scopeKey, rank) }
                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
                lastPublished = entries
            }.onFailure { Log.w(TAG, "Could not publish session shortcuts", it) }
        }
    }

    private fun build(
        context: Context,
        entry: SessionShortcutEntry,
        scopeKey: String,
        rank: Int,
    ): ShortcutInfoCompat {
        val intent =
            AppShortcuts
                .entryIntent(context, AppShortcuts.ACTION_OPEN_SESSION)
                .putExtra(AppShortcuts.EXTRA_SESSION_ID, entry.sessionId)
                .putExtra(AppShortcuts.EXTRA_SCOPE_KEY, scopeKey)
        return ShortcutInfoCompat
            .Builder(context, AppShortcuts.SESSION_SHORTCUT_PREFIX + entry.sessionId)
            .setShortLabel(entry.label.ellipsize(SHORT_LABEL_MAX))
            .setLongLabel(entry.label.ellipsize(LONG_LABEL_MAX))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_session))
            .setIntent(intent)
            .setRank(rank)
            .setLongLived(true)
            .setCategories(setOf(AppShortcuts.SHARE_TARGET_CATEGORY))
            .setExtras(
                PersistableBundle().apply {
                    putString(AppShortcuts.EXTRA_SCOPE_KEY, scopeKey)
                },
            ).build()
    }

    /** Removes dynamic shortcuts that do not belong to [scopeKey] (all of them when signed out). */
    private fun prune(scopeKey: String?) {
        val context = appContext ?: return
        runCatching {
            val stale =
                ShortcutManagerCompat
                    .getDynamicShortcuts(context)
                    .filter { it.extras?.getString(AppShortcuts.EXTRA_SCOPE_KEY) != scopeKey || scopeKey == null }
                    .map { it.id }
            if (stale.isNotEmpty()) {
                ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
                lastPublished = null
            }
        }.onFailure { Log.w(TAG, "Could not prune session shortcuts", it) }
    }

    /** True when a shortcut of [shortcutScopeKey] may act in the [current] scope. */
    fun matchesScope(
        shortcutScopeKey: String?,
        current: DataScope?,
    ): Boolean {
        val currentKey = AppShortcuts.scopeKey(current) ?: return false
        return shortcutScopeKey == currentKey
    }

    /**
     * True when the dynamic shortcut of [sessionId] still exists and belongs to [current]. The share
     * sheet may offer a target from before a scope change; such a share falls back to a new chat.
     */
    fun isCurrent(
        context: Context,
        sessionId: String,
        current: DataScope?,
    ): Boolean =
        runCatching {
            val shortcutId = AppShortcuts.SESSION_SHORTCUT_PREFIX + sessionId
            val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context).firstOrNull { it.id == shortcutId }
            matchesScope(shortcut?.extras?.getString(AppShortcuts.EXTRA_SCOPE_KEY), current)
        }.getOrDefault(false)

    private fun String.ellipsize(max: Int): String = if (length <= max) this else take(max - 1).trimEnd() + "…"
}
