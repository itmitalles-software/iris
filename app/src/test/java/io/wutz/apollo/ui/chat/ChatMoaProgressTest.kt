package io.wutz.apollo.ui.chat

import io.wutz.apollo.data.ws.WsEvent
import io.wutz.apollo.ui.chat.fullbleed.AgentStatus
import io.wutz.apollo.ui.chat.fullbleed.deriveAgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMoaProgressTest {
    private val busy = ChatUiState(isAgentTyping = true)
    private val user = listOf(ChatMessage(id = "u", role = MessageRole.USER, content = "q"))

    private fun progress(
        done: Int?,
        total: Int?,
        aggregating: Boolean = false,
    ) = WsEvent.MoaProgress(done, total, aggregating, aggregator = null, sessionId = "rt")

    @Test
    fun progressThenAggregationDriveTheStatusLine() {
        var streaming = ChatWsEventReducer.reduce(busy, StreamingState(), progress(1, 3), "rt").streamingState
        assertEquals(MoaProgressUi(1, 3, aggregating = false), streaming.moaProgress)
        assertEquals(AgentStatus.Moa(MoaProgressUi(1, 3, false)), deriveAgentStatus(true, streaming, user))

        streaming =
            ChatWsEventReducer.reduce(busy, streaming, progress(null, null, aggregating = true), "rt").streamingState
        assertEquals(MoaProgressUi(1, 3, aggregating = true), streaming.moaProgress)
    }

    @Test
    fun progressOutsideATurnOrForAnotherSessionIsIgnored() {
        val idle = ChatWsEventReducer.reduce(ChatUiState(), StreamingState(), progress(1, 3), "rt").streamingState
        assertNull(idle.moaProgress)
        val other =
            ChatWsEventReducer
                .reduce(busy, StreamingState(), progress(1, 3).copy(sessionId = "other"), "rt")
                .streamingState
        assertNull(other.moaProgress)
    }

    @Test
    fun toolStartEndsTheMoaStatus() {
        val streaming = StreamingState(moaProgress = MoaProgressUi(3, 3, aggregating = true))
        val after =
            ChatWsEventReducer
                .reduce(
                    busy,
                    streaming,
                    WsEvent.ToolStart("terminal", emptyMap(), "rt"),
                    "rt",
                ).streamingState
        assertNull(after.moaProgress)
    }
}
