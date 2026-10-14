package io.wutz.apollo.ui.chat

import io.wutz.apollo.ui.chat.markdown.MdBlock
import io.wutz.apollo.ui.chat.markdown.coalesceBulletRuns
import io.wutz.apollo.ui.chat.markdown.parseBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The streaming caret blink must not re-parse the whole reply. */
class RecentMarkdownParsesTest {
    private val parsed = mutableListOf<String>()
    private val parses =
        RecentMarkdownParses { text ->
            parsed += text
            coalesceBulletRuns(parseBlocks(text))
        }

    @Test
    fun `caret blink alternates between two cached parses`() {
        val withCaret = parses.blocksFor("Hello **world**▍")
        val withoutCaret = parses.blocksFor("Hello **world**")

        repeat(5) {
            assertSame(withCaret, parses.blocksFor("Hello **world**▍"))
            assertSame(withoutCaret, parses.blocksFor("Hello **world**"))
        }
        assertEquals(listOf("Hello **world**▍", "Hello **world**"), parsed)
    }

    @Test
    fun `new text is parsed once and matches a direct parse`() {
        parses.blocksFor("a")
        parses.blocksFor("b")
        val blocks: List<MdBlock> = parses.blocksFor("- one\n- two")

        assertEquals(coalesceBulletRuns(parseBlocks("- one\n- two")), blocks)
        assertEquals(listOf("a", "b", "- one\n- two"), parsed)
        parses.blocksFor("a")
        assertEquals(4, parsed.size)
    }
}
