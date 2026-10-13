package io.wutz.apollo.ui.common

import android.content.res.Resources
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UiTextTest {
    private val resources =
        mockk<Resources> {
            every { getString(1) } returns "Plain"
            every { getString(2, *anyVararg()) } answers {
                val args = secondArg<Array<Any>>()
                "Failed: ${args.joinToString("|")}"
            }
            every { getQuantityString(3, any(), *anyVararg()) } answers {
                val count = secondArg<Int>()
                val args = thirdArg<Array<Any>>()
                (if (count == 1) "one:" else "other:") + args.joinToString("|")
            }
        }

    @Test
    fun `raw text is returned unchanged`() {
        assertEquals("server says no", UiText.raw("server says no").resolve(resources))
    }

    @Test
    fun `resource without args uses plain getString`() {
        assertEquals("Plain", UiText.res(1).resolve(resources))
    }

    @Test
    fun `resource args are passed through and nested UiText is resolved first`() {
        val text = UiText.res(2, "boom", UiText.res(1), 7)
        assertEquals("Failed: boom|Plain|7", text.resolve(resources))
    }

    @Test
    fun `plural defaults to the count as only argument`() {
        assertEquals(UiText.Plural(3, 4, listOf(4)), UiText.plural(3, 4))
        assertEquals("one:1", UiText.plural(3, 1).resolve(resources))
        assertEquals("other:5|x", UiText.plural(3, 5, 5, UiText.raw("x")).resolve(resources))
    }

    @Test
    fun `equality compares id and args so tests can assert messages`() {
        assertEquals(UiText.res(2, "a"), UiText.res(2, "a"))
        assertNotEquals(UiText.res(2, "a"), UiText.res(2, "b"))
        assertNotEquals(UiText.res(1), UiText.res(2))
    }
}
