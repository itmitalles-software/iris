package io.wutz.apollo.ui.chat.fullbleed

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** The incremental word index must match the old split/join reveal exactly. */
class WordBoundariesTest {
    private fun expectedPrefix(
        text: String,
        words: Int,
    ) = text.split(" ").take(words).joinToString(" ")

    private fun assertMatchesSplitJoin(
        index: WordBoundaries,
        text: String,
    ) {
        val count = index.update(text)
        assertEquals("word count of '$text'", text.split(" ").size, count)
        for (n in 1..count + 1) {
            assertEquals("prefix $n of '$text'", expectedPrefix(text, n), index.prefix(text, n))
        }
    }

    @Test
    fun `prefixes match split and join for edge cases`() {
        listOf(
            "",
            "one",
            "one two",
            " leading",
            "trailing ",
            "double  space",
            "   ",
            "line\nbreak and\ttab",
            "```kotlin\nval x = 1\n```",
        ).forEach { assertMatchesSplitJoin(WordBoundaries(), it) }
    }

    @Test
    fun `growing stream is indexed incrementally and stays exact`() {
        val index = WordBoundaries()
        val random = Random(42)
        val text = StringBuilder()
        repeat(200) {
            repeat(random.nextInt(1, 6)) { text.append(if (random.nextInt(4) == 0) ' ' else 'a' + random.nextInt(26)) }
            assertMatchesSplitJoin(index, text.toString())
        }
    }

    @Test
    fun `replaced content is rescanned from the start`() {
        val index = WordBoundaries()
        assertMatchesSplitJoin(index, "first draft of the reply")
        assertMatchesSplitJoin(index, "rewritten")
        assertMatchesSplitJoin(index, "another longer rewritten reply text")
    }
}
