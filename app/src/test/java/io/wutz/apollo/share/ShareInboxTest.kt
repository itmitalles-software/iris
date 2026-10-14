package io.wutz.apollo.share

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareInboxTest {
    private val content = SharedContent(text = "hello", attachments = emptyList())

    @After
    fun tearDown() {
        ShareInbox.clear()
    }

    @Test
    fun `a share aimed at a session is not staged in a new chat`() {
        ShareInbox.offer(content, sessionId = "s1")
        assertNull(ShareInbox.consume())
        assertNull(ShareInbox.consumeFor("other"))
        assertEquals(content, ShareInbox.consumeFor("s1"))
        assertNull(ShareInbox.consumeFor("s1"))
    }

    @Test
    fun `a new-chat share is not staged in an opened session`() {
        ShareInbox.offer(content)
        assertNull(ShareInbox.consumeFor("s1"))
        assertEquals(content, ShareInbox.consume())
    }

    @Test
    fun `clear drops any pending share`() {
        ShareInbox.offer(content, sessionId = "s1")
        ShareInbox.clear()
        assertNull(ShareInbox.consumeFor("s1"))
    }
}
