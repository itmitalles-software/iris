package io.wutz.apollo.shortcuts

import android.content.Context
import android.content.Intent
import io.wutz.apollo.data.local.DataScope
import java.security.MessageDigest

/** What a launcher shortcut, a session shortcut or the Quick Settings tile asks Iris to open. */
sealed interface ShortcutRequest {
    data object NewChat : ShortcutRequest

    data object VoiceChat : ShortcutRequest

    data object History : ShortcutRequest

    data class Session(
        val sessionId: String,
        val scopeKey: String?,
    ) : ShortcutRequest
}

/**
 * Entry points from outside the app UI: static launcher shortcuts (res/xml/shortcuts.xml), dynamic
 * session shortcuts ([SessionShortcuts]) and the Quick Settings tile ([NewChatTileService]).
 *
 * They all target the non-exported [SHORTCUT_ENTRY_ALIAS]. Launchers start shortcuts with Iris's
 * own identity, so the alias is reachable for them, while other apps can only reach the exported
 * launcher activity and cannot, for example, start a voice conversation through an explicit intent.
 */
object AppShortcuts {
    const val SHORTCUT_ENTRY_ALIAS = "io.wutz.apollo.ShortcutEntryAlias"

    const val ACTION_NEW_CHAT = "io.wutz.apollo.action.NEW_CHAT"
    const val ACTION_VOICE_CHAT = "io.wutz.apollo.action.VOICE_CHAT"
    const val ACTION_HISTORY = "io.wutz.apollo.action.HISTORY"
    const val ACTION_OPEN_SESSION = "io.wutz.apollo.action.OPEN_SESSION"

    const val EXTRA_SESSION_ID = "io.wutz.apollo.extra.SESSION_ID"
    const val EXTRA_SCOPE_KEY = "io.wutz.apollo.extra.SCOPE_KEY"

    /** Share-target category of session shortcuts; must match res/xml/shortcuts.xml. */
    const val SHARE_TARGET_CATEGORY = "io.wutz.apollo.category.SESSION_SHARE_TARGET"

    /** Prefix of dynamic session shortcut ids; the rest is the session id. */
    const val SESSION_SHORTCUT_PREFIX = "session:"

    /** Parses an intent delivered through [SHORTCUT_ENTRY_ALIAS]; anything else yields null. */
    fun parse(
        action: String?,
        componentClassName: String?,
        sessionId: String?,
        scopeKey: String?,
    ): ShortcutRequest? {
        if (componentClassName != SHORTCUT_ENTRY_ALIAS) return null
        return when (action) {
            ACTION_NEW_CHAT -> ShortcutRequest.NewChat
            ACTION_VOICE_CHAT -> ShortcutRequest.VoiceChat
            ACTION_HISTORY -> ShortcutRequest.History
            ACTION_OPEN_SESSION -> sessionId?.takeIf { it.isNotBlank() }?.let { ShortcutRequest.Session(it, scopeKey) }
            else -> null
        }
    }

    fun parse(intent: Intent): ShortcutRequest? =
        parse(
            action = intent.action,
            componentClassName = intent.component?.className,
            sessionId = intent.getStringExtra(EXTRA_SESSION_ID),
            scopeKey = intent.getStringExtra(EXTRA_SCOPE_KEY),
        )

    /** Session id of a direct-share shortcut id, or null for other ids. */
    fun sessionIdFromShortcutId(shortcutId: String?): String? =
        shortcutId
            ?.takeIf { it.startsWith(SESSION_SHORTCUT_PREFIX) }
            ?.removePrefix(SESSION_SHORTCUT_PREFIX)
            ?.takeIf { it.isNotBlank() }

    /**
     * Stable, non-reversible key of a connection + agent profile. Shortcuts store it instead of the
     * server URL, so a shortcut from another connection or profile is recognised without leaking it.
     */
    fun scopeKey(scope: DataScope?): String? {
        scope ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(scope.persistentKey().toByteArray())
        return digest.take(SCOPE_KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** Intent for the shortcut entry alias with [action]. */
    fun entryIntent(
        context: Context,
        action: String,
    ): Intent =
        Intent(action)
            .setClassName(context, SHORTCUT_ENTRY_ALIAS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private const val SCOPE_KEY_BYTES = 12
}
