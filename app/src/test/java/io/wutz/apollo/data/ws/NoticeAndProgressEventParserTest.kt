package io.wutz.apollo.data.ws

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Wire shapes from hermes-agent v0.21.6 `tui_gateway/contracts/events.py`. */
class NoticeAndProgressEventParserTest {
    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkAll()

    private fun parse(
        type: String,
        payload: Map<String, Any?>,
        sessionId: String? = "s1",
    ): WsEvent = EventParser.parseParams(mapOf("type" to type, "session_id" to sessionId, "payload" to payload))

    @Test
    fun notificationShow_keyedTtlNotice() {
        val event =
            parse(
                "notification.show",
                mapOf(
                    "text" to "Could not switch model: boom",
                    "level" to "warn",
                    "kind" to "ttl",
                    "ttl_ms" to 12000,
                    "key" to "model_switch.dropped",
                    "id" to "model_switch.dropped",
                ),
            )
        assertEquals(
            WsEvent.NotificationShow(
                text = "Could not switch model: boom",
                level = "warn",
                kind = "ttl",
                ttlMs = 12_000L,
                key = "model_switch.dropped",
                sessionId = "s1",
            ),
            event,
        )
    }

    @Test
    fun notificationShow_stickyWithoutTtlOrKey() {
        val event =
            parse(
                "notification.show",
                mapOf(
                    "text" to "Still starting",
                    "level" to "info",
                    "kind" to "agent",
                    "ttl_ms" to null,
                ),
            )
                as WsEvent.NotificationShow
        assertEquals(null, event.ttlMs)
        assertEquals(null, event.key)
        assertEquals("agent", event.kind)
    }

    @Test
    fun notificationShow_withoutTextIsIgnored() {
        assertTrue(
            parse("notification.show", mapOf("text" to "  ", "level" to "info", "kind" to "ttl")) is WsEvent.Unknown,
        )
    }

    @Test
    fun notificationClear_needsKey() {
        assertEquals(WsEvent.NotificationClear("k", "s1"), parse("notification.clear", mapOf("key" to "k")))
        assertTrue(parse("notification.clear", emptyMap()) is WsEvent.Unknown)
    }

    @Test
    fun moaEvents_mapToProgress() {
        assertEquals(
            WsEvent.MoaProgress(refsDone = 2, refsTotal = 3, aggregating = false, aggregator = null, sessionId = "s1"),
            parse("moa.progress", mapOf("label" to "ref", "refs_done" to 2, "refs_total" to 3)),
        )
        assertEquals(
            WsEvent.MoaProgress(refsDone = 3, refsTotal = 3, aggregating = true, aggregator = "opus", sessionId = "s1"),
            parse(
                "moa.phase",
                mapOf(
                    "phase" to "aggregator",
                    "refs_done" to 3,
                    "refs_total" to 3,
                    "aggregator" to "opus",
                ),
            ),
        )
        assertEquals(
            WsEvent.MoaProgress(
                refsDone = null,
                refsTotal = null,
                aggregating = true,
                aggregator = "opus",
                sessionId = "s1",
            ),
            parse("moa.aggregating", mapOf("aggregator" to "opus")),
        )
    }

    @Test
    fun projectsChanged_isAChangeEvent() {
        val event = EventParser.parseParams(mapOf("type" to "projects.changed", "payload" to emptyMap<String, Any>()))
        assertEquals(ChangeEvents.PROJECTS, (event as WsEvent.ChangeEvent).type)
    }
}
