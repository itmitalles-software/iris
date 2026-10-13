package io.wutz.apollo.ui.chat.tool

import io.wutz.apollo.ui.chat.parseToolOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class ToolViewCacheTest {
    private val payload = """{"name":"terminal","args":{"command":"ls"},"result":{"output":"a","exit_code":0}}"""

    @Before
    fun reset() = ToolViewCache.clear()

    @Test
    fun `cached view equals a fresh parse`() {
        val cached = ToolViewCache.getOrParse(payload, "terminal", false, TestToolText.english)
        assertEquals(parseToolOutput(payload, "terminal", false, TestToolText.english), cached)
    }

    @Test
    fun `prewarm makes composition read a hit`() {
        ToolViewCache.prewarm(payload, "terminal", false, TestToolText.english)
        val first = ToolViewCache.getOrParse(payload, "terminal", false, TestToolText.english)
        assertSame(first, ToolViewCache.getOrParse(payload, "terminal", false, TestToolText.english))
        assertEquals(1, ToolViewCache.size())
    }

    @Test
    fun `running state and content changes are separate entries`() {
        val running = ToolViewCache.getOrParse(payload, "terminal", true, TestToolText.english)
        val done = ToolViewCache.getOrParse(payload, "terminal", false, TestToolText.english)
        assertNotSame(running, done)
        assertEquals(2, ToolViewCache.size())
    }

    @Test
    fun `a language switch never serves a view resolved in the other language`() {
        val english = ToolViewCache.getOrParse(payload, "terminal", false, TestToolText.english)
        val german = ToolViewCache.getOrParse(payload, "terminal", false, TestToolText.german)
        assertEquals("Ran ls", english?.title)
        assertEquals("Ausgeführt: ls", german?.title)
        assertEquals(2, ToolViewCache.size())
    }

    @Test
    fun `non json content is cached as null`() {
        assertNull(ToolViewCache.getOrParse("plain text", "terminal", false, TestToolText.english))
        assertNull(ToolViewCache.getOrParse("plain text", "terminal", false, TestToolText.english))
        assertEquals(1, ToolViewCache.size())
    }

    @Test
    fun `cache is bounded`() {
        repeat(600) { i -> ToolViewCache.getOrParse("""{"i":$i}""", "terminal", false, TestToolText.english) }
        assertEquals(512, ToolViewCache.size())
    }
}
