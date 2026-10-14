package io.wutz.apollo.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerDraftsTest {
    private class MemoryEntries : DraftEntries {
        val map = LinkedHashMap<String, String>()

        override fun all(): Map<String, String> = map.toMap()

        override fun put(
            key: String,
            value: String,
        ) {
            map[key] = value
        }

        override fun remove(keys: Collection<String>) {
            keys.forEach(map::remove)
        }

        override fun clear() = map.clear()
    }

    private var now = 0L
    private val entries = MemoryEntries()
    private val drafts = ComposerDrafts(entries, clock = { ++now }, maxDrafts = 3, maxChars = 10)

    @Test
    fun saveLoadAndBlankRemoves() {
        drafts.save("a", "hello: world")
        assertEquals("hello: wor", drafts.load("a"))
        drafts.save("a", "   ")
        assertNull(drafts.load("a"))
    }

    @Test
    fun keepsOnlyTheNewestDrafts() {
        drafts.save("a", "1")
        drafts.save("b", "2")
        drafts.save("c", "3")
        drafts.save("a", "1 again")
        drafts.save("d", "4")
        assertEquals(setOf("a", "c", "d"), entries.map.keys)
    }

    @Test
    fun corruptEntriesReadAsMissing() {
        entries.map["x"] = "not-a-draft"
        assertNull(drafts.load("x"))
    }
}
