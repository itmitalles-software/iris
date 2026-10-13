package io.wutz.apollo.ui.sessions

import io.wutz.apollo.R
import io.wutz.apollo.data.model.SessionSearchResult
import io.wutz.apollo.ui.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSearchFormattingTest {
    @Test
    fun `wire title stays distinct from excerpt and preserves metadata`() {
        val hit =
            kotlinx.serialization.json.Json
                .decodeFromString<SessionSearchResult>(
                    """{"session_id":"s","title":"Launch","snippet":"match","last_active":42,"message_count":7}""",
                ).toSessionInfo()
        assertEquals("Launch", hit.title)
        assertEquals("match", hit.preview)
        assertEquals(42.0, hit.last_active)
        assertEquals(7, hit.message_count)
    }

    @Test
    fun `highlight phrases and words without Unicode offset drift`() {
        val text = "İ 🌙 launch notes and deploy"
        val result =
            highlightSearchText(
                text,
                "\"launch notes\" deploy",
                androidx.compose.ui.graphics.Color.Transparent,
                androidx.compose.ui.graphics.Color.Unspecified,
            )
        assertEquals(text, result.text)
        assertEquals(listOf("launch notes", "deploy"), result.spanStyles.map { text.substring(it.start, it.end) })
    }

    @Test
    fun `clean search snippet strips highlight markers from plain text`() {
        assertEquals(
            "Find the deployment logs",
            cleanSearchSnippet(">>>Find<<< the deployment logs"),
        )
    }

    @Test
    fun `clean search snippet extracts text from JSON payload`() {
        assertEquals(
            "Find the deployment logs",
            cleanSearchSnippet("{\"role\":\"user\",\"content\":\">>>Find<<< the deployment logs\"}"),
        )
    }

    @Test
    fun `clean search snippet extracts nested text array`() {
        assertEquals(
            "First part second part",
            cleanSearchSnippet("{\"parts\":[\">>>First<<< part\",\"second part\"]}"),
        )
    }

    @Test
    fun `clean search snippet normalizes whitespace`() {
        assertEquals(
            "hello world",
            cleanSearchSnippet("  hello    world  \n "),
        )
    }

    @Test
    fun `toSessionInfo leaves title null for match layout`() {
        val searchResult =
            SessionSearchResult(
                session_id = "test-123",
                session_started = 1700000000.0,
                snippet = "matched query excerpt",
                source = "cli",
                model = "test-model",
            )
        val info = searchResult.toSessionInfo()
        assertEquals("test-123", info.id)
        assertNull(info.title)
        assertEquals("matched query excerpt", info.preview)
        assertEquals("cli", info.source)
        assertEquals("test-model", info.model)
    }

    @Test
    fun `formatPlayedAt handles null and negative epoch`() {
        assertNull(formatPlayedAt(null))
        assertNull(formatPlayedAt(0.0))
        assertNull(formatPlayedAt(-100.0))
    }

    @Test
    fun `formatPlayedAt returns formatted string for valid timestamp`() {
        val result = formatPlayedAt(1700000000.0)
        assertNotNull(result)
    }

    @Test
    fun `formatPlayedAt uses relative resources for recent timestamps`() {
        val now = 1_700_000_000L
        assertEquals(UiText.res(R.string.sessions_played_just_now), formatPlayedAt(now - 30.0, now))
        assertEquals(UiText.res(R.string.sessions_played_minutes_ago, 5), formatPlayedAt(now - 300.0, now))
        assertEquals(UiText.res(R.string.sessions_played_hours_ago, 2), formatPlayedAt(now - 7_200.0, now))
        assertEquals(UiText.res(R.string.sessions_played_days_ago, 3), formatPlayedAt(now - 3 * 86_400.0, now))
        assertTrue(formatPlayedAt(now - 30 * 86_400.0, now) is UiText.Raw)
    }
}
