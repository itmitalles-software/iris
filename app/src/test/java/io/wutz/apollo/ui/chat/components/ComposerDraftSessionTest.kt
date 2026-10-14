package io.wutz.apollo.ui.chat.components

import io.wutz.apollo.data.local.ComposerDraftStorage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerDraftSessionTest {
    private val stored = HashMap<String, String>()
    private val storage =
        object : ComposerDraftStorage {
            override suspend fun load(key: String): String? = stored[key]

            override fun save(
                key: String,
                text: String,
            ) {
                if (text.isBlank()) stored.remove(key) else stored[key] = text
            }

            override fun remove(key: String) {
                stored.remove(key)
            }
        }
    private var composer = ""
    private val session = ComposerDraftSession(storage, { composer }, { composer = it })

    @Test
    fun switchingChatsParksAndRestoresDrafts() =
        runTest {
            stored["scope|B"] = "draft for B"
            session.onChat(1, "A", "scope")
            composer = "typed in A"
            session.onChat(2, "B", "scope")
            assertEquals("typed in A", stored["scope|A"])
            assertEquals("draft for B", composer)

            session.onChat(3, "A", "scope")
            assertEquals("typed in A", composer)
            assertEquals("draft for B", stored["scope|B"])
        }

    @Test
    fun aRestoredDraftNeverOverwritesExistingText() =
        runTest {
            stored["scope|A"] = "old draft"
            composer = "shared text"
            session.onChat(1, "A", "scope")
            assertEquals("shared text", composer)
        }

    @Test
    fun newChatKeepsItsDraftUntilTheFirstSendThenMovesToTheSession() =
        runTest {
            session.onChat(1, null, "scope")
            composer = "hello"
            session.persist(composer)
            assertEquals("hello", stored["scope|$NEW_CHAT_DRAFT_KEY"])

            // session.create landed: same chat, still the new-chat draft.
            session.onChat(1, "S", "scope")
            assertEquals("hello", composer)
            assertEquals("hello", stored["scope|$NEW_CHAT_DRAFT_KEY"])

            composer = ""
            session.onSendAccepted()
            assertNull(stored["scope|$NEW_CHAT_DRAFT_KEY"])

            composer = "follow-up"
            session.persist(composer)
            assertEquals("follow-up", stored["scope|S"])
        }

    @Test
    fun otherScopeIsAnotherDraft() =
        runTest {
            session.onChat(1, "A", "server1")
            composer = "one"
            session.onChat(1, "A", "server2")
            assertEquals("one", stored["server1|A"])
            assertEquals("", composer)
        }

    @Test
    fun noScopeMeansNothingIsStored() =
        runTest {
            session.onChat(1, "A", null)
            composer = "text"
            session.persist(composer)
            assertEquals(emptyMap<String, String>(), stored)
        }
}
