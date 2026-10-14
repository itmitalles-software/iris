package io.wutz.apollo.shortcuts

import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShortcutsTest {
    private val alias = AppShortcuts.SHORTCUT_ENTRY_ALIAS

    @Test
    fun `only intents through the private alias are shortcut requests`() {
        assertEquals(ShortcutRequest.NewChat, AppShortcuts.parse(AppShortcuts.ACTION_NEW_CHAT, alias, null, null))
        assertEquals(ShortcutRequest.VoiceChat, AppShortcuts.parse(AppShortcuts.ACTION_VOICE_CHAT, alias, null, null))
        assertEquals(ShortcutRequest.History, AppShortcuts.parse(AppShortcuts.ACTION_HISTORY, alias, null, null))
        assertEquals(
            ShortcutRequest.Session("s1", "k"),
            AppShortcuts.parse(AppShortcuts.ACTION_OPEN_SESSION, alias, "s1", "k"),
        )
        // The exported launcher activity must not honour these actions (another app could send them).
        assertNull(AppShortcuts.parse(AppShortcuts.ACTION_VOICE_CHAT, "io.wutz.apollo.MainActivity", null, null))
        assertNull(AppShortcuts.parse(AppShortcuts.ACTION_OPEN_SESSION, alias, " ", "k"))
        assertNull(AppShortcuts.parse("android.intent.action.MAIN", alias, null, null))
    }

    @Test
    fun `session id is read from direct share shortcut ids only`() {
        assertEquals("abc", AppShortcuts.sessionIdFromShortcutId("session:abc"))
        assertNull(AppShortcuts.sessionIdFromShortcutId("new_chat"))
        assertNull(AppShortcuts.sessionIdFromShortcutId("session:"))
        assertNull(AppShortcuts.sessionIdFromShortcutId(null))
    }

    @Test
    fun `scope key is stable per connection and profile and hides the url`() {
        val scope = DataScope("default", "https://hermes.example.ts.net", "work", inMemoryAuthGeneration = 1)
        val key = AppShortcuts.scopeKey(scope)!!
        assertEquals(key, AppShortcuts.scopeKey(scope.copy(inMemoryAuthGeneration = 7)))
        assertNotEquals(key, AppShortcuts.scopeKey(scope.copy(activeProfileId = "home")))
        assertNotEquals(key, AppShortcuts.scopeKey(scope.copy(baseUrl = "https://other.example")))
        assertFalse(key.contains("example"))
        assertNull(AppShortcuts.scopeKey(null))
        assertTrue(SessionShortcuts.matchesScope(key, scope))
        assertFalse(SessionShortcuts.matchesScope(key, scope.copy(activeProfileId = "home")))
        assertFalse(SessionShortcuts.matchesScope(null, scope))
        assertFalse(SessionShortcuts.matchesScope(key, null))
    }

    @Test
    fun `shortcut sessions are pinned first then most recent, without archived hidden or cron rows`() {
        val sessions =
            listOf(
                SessionInfo(id = "old", title = "Old", last_active = 10.0),
                SessionInfo(id = "new", title = "  New\n chat ", last_active = 30.0),
                SessionInfo(id = "pinned", title = "Pinned", last_active = 1.0, pinned = true),
                SessionInfo(id = "archived", title = "A", last_active = 50.0, archived = true),
                SessionInfo(id = "hidden", title = "H", last_active = 50.0, hidden = true),
                SessionInfo(id = "cron", title = "C", last_active = 50.0, source = "cron"),
                SessionInfo(id = "untitled", last_active = 20.0),
            )
        val selected = SessionShortcuts.select(sessions, untitled = "Untitled", max = 3)
        assertEquals(listOf("pinned", "new", "untitled"), selected.map { it.sessionId })
        assertEquals("New chat", selected[1].label)
        assertEquals("Untitled", selected[2].label)
        assertEquals(emptyList<SessionShortcutEntry>(), SessionShortcuts.select(sessions, "Untitled", max = 0))
    }
}
