package io.wutz.apollo.ui.chat

import io.wutz.apollo.data.ws.WsEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatGatewayNoticesDelegateTest {
    private val scope = TestScope(StandardTestDispatcher())
    private var current = "rt-1"
    private val delegate =
        ChatGatewayNoticesDelegate(scope, { it == null || it == current }, defaultTtlMs = 1_000L)

    private fun show(
        text: String,
        key: String? = "k",
        kind: String = "ttl",
        ttlMs: Long? = null,
        level: String = "info",
        sessionId: String? = "rt-1",
    ) = delegate.onShow(WsEvent.NotificationShow(text, level, kind, ttlMs, key, sessionId))

    @Test
    fun sameKeyReplacesTheNotice() {
        show("Still starting", key = "agent.build", kind = "agent")
        show("Still starting (2)", key = "agent.build", kind = "agent")
        assertEquals(
            GatewayNoticeUi("agent.build", "Still starting (2)", GatewayNoticeLevel.INFO),
            delegate.notice.value,
        )
    }

    @Test
    fun clearWithdrawsTheNoticeAndShowsTheOlderOne() {
        show("first", key = "a", kind = "sticky")
        show("second", key = "b", kind = "sticky", level = "warn")
        assertEquals(GatewayNoticeLevel.WARNING, delegate.notice.value?.level)
        delegate.onClear(WsEvent.NotificationClear("b", "rt-1"))
        assertEquals("first", delegate.notice.value?.text)
    }

    @Test
    fun ttlNoticeExpires() {
        show("model switch failed", ttlMs = 12_000L, level = "warn")
        scope.advanceTimeBy(11_999L)
        scope.runCurrent()
        assertEquals("model switch failed", delegate.notice.value?.text)
        scope.advanceTimeBy(2L)
        scope.runCurrent()
        assertNull(delegate.notice.value)
    }

    @Test
    fun ttlKindWithoutTtlMsUsesDefault() {
        show("short")
        scope.advanceTimeBy(1_001L)
        scope.runCurrent()
        assertNull(delegate.notice.value)
    }

    @Test
    fun agentNoticeWithoutTtlStaysUntilCleared() {
        show("Still starting", key = "agent.build", kind = "agent")
        scope.advanceTimeBy(60_000L)
        scope.runCurrent()
        assertEquals("Still starting", delegate.notice.value?.text)
    }

    @Test
    fun noticesOfOtherSessionsAreIgnored() {
        show("other", sessionId = "rt-2")
        assertNull(delegate.notice.value)
    }

    @Test
    fun dismissAndResetClear() {
        show("one", key = "a", kind = "sticky")
        delegate.dismiss("a")
        assertNull(delegate.notice.value)
        show("two", key = "b", kind = "sticky")
        delegate.reset()
        assertNull(delegate.notice.value)
    }

    @Test
    fun levelsMapFromWire() {
        assertEquals(GatewayNoticeLevel.ERROR, GatewayNoticeLevel.fromWire("error"))
        assertEquals(GatewayNoticeLevel.SUCCESS, GatewayNoticeLevel.fromWire("success"))
        assertEquals(GatewayNoticeLevel.WARNING, GatewayNoticeLevel.fromWire("warn"))
        assertEquals(GatewayNoticeLevel.INFO, GatewayNoticeLevel.fromWire("whatever"))
    }
}
