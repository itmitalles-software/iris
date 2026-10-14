package io.wutz.apollo.ui.chat

import io.wutz.apollo.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Code review 2026-10-14: C4 (no per-token transcript scan) and C10 (explicit clears stick). */
class ChatTodoHydrationTest {
    private val todoMessage =
        ChatMessage(
            id = "todo-1",
            role = MessageRole.TOOL,
            toolName = "todo",
            content = """{"todos":[{"id":"a","content":"Write tests","status":"pending"}]}""",
        )

    private fun reduce(
        state: ChatUiState,
        event: WsEvent,
    ): ChatUiState = ChatWsEventReducer.reduce(state, StreamingState(), event, SESSION).state

    @Test
    fun resumeHydrationRunsOnceThenStopsScanning() {
        val loaded = ChatUiState(messages = listOf(todoMessage), currentSessionId = SESSION)

        val first = reduce(loaded, WsEvent.MessageToken("a", SESSION))
        assertEquals(listOf("a"), first.todos.map { it.id })
        assertTrue(first.todosHydrated)

        // Once hydrated, a token event leaves the state object untouched (no transcript re-scan).
        val emptied = first.copy(todos = emptyList())
        val second = reduce(emptied, WsEvent.MessageToken("b", SESSION))
        assertTrue(second.todos.isEmpty())
    }

    @Test
    fun serverClearedTodoListIsNotRehydratedFromTheTranscript() {
        val initial = ChatUiState(messages = listOf(todoMessage), currentSessionId = SESSION)
        val loaded = reduce(initial, WsEvent.MessageStart(SESSION))
        assertEquals(1, loaded.todos.size)

        val cleared = reduce(loaded, WsEvent.TodoUpdated(todos = emptyList(), revision = 2, sessionId = SESSION))
        val afterToken = reduce(cleared, WsEvent.MessageToken("x", SESSION))

        assertTrue(cleared.todos.isEmpty())
        assertTrue(afterToken.todos.isEmpty())
    }

    @Test
    fun todoToolResultWithEmptyListClearsTheList() {
        val running =
            ChatMessage(
                id = "todo-2",
                role = MessageRole.TOOL,
                content = "",
                toolName = "todo",
                toolStatus = ToolStatus.RUNNING,
                toolCallId = "call-2",
            )
        val state =
            ChatUiState(
                messages = listOf(todoMessage, running),
                currentSessionId = SESSION,
                todos = listOf(TodoItem(id = "a", content = "Write tests")),
                todosHydrated = true,
            )

        val complete = WsEvent.ToolComplete("todo", mapOf("tool_id" to "call-2", "todos" to emptyList<Any>()), SESSION)
        val result = reduce(state, complete)

        assertTrue(result.todos.isEmpty())
    }

    @Test
    fun newestTodoToolMessageWinsEvenWhenEmpty() {
        val cleared = todoMessage.copy(id = "todo-3", content = """{"todos":[]}""")

        assertTrue(hydrateTodosFromMessages(listOf(todoMessage, cleared)).isEmpty())
        assertEquals(1, hydrateTodosFromMessages(listOf(cleared, todoMessage)).size)
    }

    @Test
    fun nonTodoToolWithEmptyTodosKeyDoesNotHideAnOlderPlan() {
        val other =
            ChatMessage(id = "read", role = MessageRole.TOOL, toolName = "read_file", content = """{"todos":[]}""")

        assertEquals(1, hydrateTodosFromMessages(listOf(todoMessage, other)).size)
    }

    private companion object {
        const val SESSION = "session-1"
    }
}
