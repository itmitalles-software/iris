package io.wutz.apollo.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import io.wutz.apollo.theme.HermesStatusColors
import io.wutz.apollo.theme.StatusBlue
import io.wutz.apollo.theme.StatusBlueContainer
import io.wutz.apollo.theme.StatusGreen
import io.wutz.apollo.theme.StatusGreenContainer
import io.wutz.apollo.theme.StatusRed
import io.wutz.apollo.theme.StatusRedContainer
import io.wutz.apollo.theme.StatusYellow
import io.wutz.apollo.theme.StatusYellowContainer
import io.wutz.apollo.theme.searchHighlightColors
import io.wutz.apollo.ui.chat.markdown.MarkdownMediaResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 2026-10-14: link scheme allowlist (S6), external image gate (S5), delimiter flanking (C13). */
class MarkdownInlineSafetyTest {
    private val highlights =
        searchHighlightColors(
            HermesStatusColors(
                success = StatusGreen,
                successContainer = StatusGreenContainer,
                onSuccess = Color.White,
                warning = StatusYellow,
                warningContainer = StatusYellowContainer,
                onWarning = Color.White,
                error = StatusRed,
                errorContainer = StatusRedContainer,
                onError = Color.White,
                info = StatusBlue,
                infoContainer = StatusBlueContainer,
                onInfo = Color.White,
            ),
        )

    private fun inline(text: String): AnnotatedString =
        parseInline(text, Color.Black, "", false, Color.Blue, highlights)

    private fun AnnotatedString.linkUrls(): List<String> =
        getLinkAnnotations(0, length).mapNotNull { (it.item as? LinkAnnotation.Url)?.url }

    @Test
    fun webAndMailLinksStayClickable() {
        assertEquals(listOf("https://example.com/a"), inline("[site](https://example.com/a)").linkUrls())
        assertEquals(listOf("http://example.com"), inline("[site](http://example.com)").linkUrls())
        assertEquals(listOf("mailto:a@example.com"), inline("[mail](mailto:a@example.com)").linkUrls())
    }

    @Test
    fun otherSchemesRenderAsPlainLabel() {
        listOf(
            "file:///data/data/io.wutz.apollo/shared_prefs/x.xml",
            "intent://scan/#Intent;scheme=zxing;end",
            "tel:+491234",
            "market://details?id=x",
            "content://io.wutz.apollo.fileprovider/x",
            "javascript:alert",
            "/relative/path",
        ).forEach { url ->
            val parsed = inline("[tap me]($url)")
            assertEquals(url, "tap me", parsed.toString())
            assertTrue(url, parsed.linkUrls().isEmpty())
        }
    }

    @Test
    fun spacedSingleTildesAreNotSubscript() {
        val parsed = inline("~5 min, then ~10 min")

        assertEquals("~5 min, then ~10 min", parsed.toString())
        assertFalse(parsed.spanStyles.any { it.item.baselineShift != null })
    }

    @Test
    fun spacedCaretsAreNotSuperscript() {
        val parsed = inline("press ^ then ^ again")

        assertEquals("press ^ then ^ again", parsed.toString())
        assertFalse(parsed.spanStyles.any { it.item.baselineShift != null })
    }

    @Test
    fun emphasisRequiresNonWhitespaceFlanking() {
        assertEquals("2 * 3 * 4", inline("2 * 3 * 4").toString())
        assertEquals("a == b and c == d", inline("a == b and c == d").toString())
        assertEquals("x ~~ y ~~ z", inline("x ~~ y ~~ z").toString())
        assertEquals("a ** b ** c", inline("a ** b ** c").toString())
    }

    @Test
    fun flankedEmphasisStillParses() {
        assertEquals("italic", inline("*italic*").toString())
        assertEquals("bold", inline("**bold**").toString())
        assertEquals("H2O", inline("H~2~O").toString())
        assertEquals("x2", inline("x^2^").toString())
        assertEquals("mark", inline("==mark==").toString())
    }

    @Test
    fun externalImageHostGatesOnlyThirdPartyAbsoluteUrls() {
        val server = "https://hermes.example.ts.net:8443"

        assertEquals("evil.example", MarkdownMediaResolver.externalImageHost("https://evil.example/p.png?d=x", server))
        assertEquals(
            "hermes.example.ts.net",
            MarkdownMediaResolver.externalImageHost("https://hermes.example.ts.net/p.png", server),
        )
        assertNull(MarkdownMediaResolver.externalImageHost("https://HERMES.example.ts.net:8443/api/media?x=1", server))
        assertNull(MarkdownMediaResolver.externalImageHost("/api/media?path=a.png", server))
        assertNull(MarkdownMediaResolver.externalImageHost("data:image/png;base64,AAAA", server))
        assertEquals("evil.example", MarkdownMediaResolver.externalImageHost("http://evil.example/x.png", ""))
    }
}
