package io.wutz.apollo.share

import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIntentParserTest {
    private val ownAuthority = "io.wutz.apollo.fileprovider"

    private fun uri(
        scheme: String,
        authority: String?,
        path: String = "/doc",
    ): Uri =
        mockk {
            every { this@mockk.scheme } returns scheme
            every { this@mockk.authority } returns authority
            every { this@mockk.toString() } returns "$scheme://$authority$path"
        }

    private val describer = SharedUriResolver { uri -> Triple("shared.pdf", 42L, "application/pdf") }

    @Suppress("DEPRECATION")
    private fun sendIntent(
        stream: Uri?,
        text: String? = null,
        subject: String? = null,
    ): Intent =
        mockk {
            every { action } returns Intent.ACTION_SEND
            every { getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns stream
            every { getStringExtra(Intent.EXTRA_TEXT) } returns text
            every { getStringExtra(Intent.EXTRA_SUBJECT) } returns subject
        }

    @Test
    fun `accepts content uris from other apps`() {
        assertTrue(ShareIntentParser.isAcceptedUri(uri("content", "com.example.docs"), ownAuthority))
    }

    @Test
    fun `rejects file uris and apollo's own file provider`() {
        assertFalse(ShareIntentParser.isAcceptedUri(uri("file", null), ownAuthority))
        assertFalse(ShareIntentParser.isAcceptedUri(uri("content", ownAuthority), ownAuthority))
    }

    @Test
    fun `composes subject and text without duplicates`() {
        assertEquals("Title\n\nhttps://example.com", ShareIntentParser.composeText(" Title ", "https://example.com"))
        assertEquals("same", ShareIntentParser.composeText("same", "same"))
        assertNull(ShareIntentParser.composeText(" ", null))
    }

    @Test
    fun `parses a shared file and text`() {
        val content =
            ShareIntentParser.parse(
                sendIntent(uri("content", "com.example.docs"), text = "Please summarize"),
                ownAuthority,
                describer,
            )
        requireNotNull(content)
        assertEquals("Please summarize", content.text)
        assertEquals(1, content.attachments.size)
        assertEquals("shared.pdf", content.attachments.single().name)
        assertEquals("application/pdf", content.attachments.single().mimeType)
    }

    @Test
    fun `drops a self-referencing uri and returns null when nothing is left`() {
        assertNull(ShareIntentParser.parse(sendIntent(uri("content", ownAuthority)), ownAuthority, describer))
    }

    @Test
    fun `ignores non-share intents`() {
        val intent = mockk<Intent> { every { action } returns Intent.ACTION_VIEW }
        assertNull(ShareIntentParser.parse(intent, ownAuthority, describer))
    }

    @Test
    fun `inbox hands content over exactly once`() {
        val content = SharedContent(text = "hi", attachments = emptyList())
        ShareInbox.offer(content)
        assertTrue(ShareInbox.hasPending())
        assertEquals(content, ShareInbox.consume())
        assertNull(ShareInbox.consume())
    }
}
