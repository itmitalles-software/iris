package io.wutz.apollo.ui.chat

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.wutz.apollo.data.model.ProcessInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatProcessesDelegateTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val uiState = MutableStateFlow(ChatUiState(currentSessionId = "s1"))
    private var sessionId: String? = "s1"
    private var listed: List<ProcessInfo> = emptyList()
    private var listError: Exception? = null
    private var listCalls = 0
    private val killed = mutableListOf<Pair<String, String>>()

    private fun delegate() =
        ChatProcessesDelegate(
            uiState = uiState,
            scope = scope,
            ioDispatcher = dispatcher,
            runtimeSessionId = { sessionId },
            listProcesses = {
                listCalls++
                listError?.let { throw it } ?: listed
            },
            killProcess = { sid, pid -> killed += sid to pid },
        )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun proc(
        id: String,
        status: String = "running",
    ) = ProcessInfo(sessionId = id, command = "cmd $id", status = status)

    @Test
    fun `refresh keeps only running processes`() =
        scope.runTest {
            listed = listOf(proc("a"), proc("b", "exited"))
            delegate().refresh()
            advanceUntilIdle()
            assertEquals(listOf("a"), uiState.value.backgroundProcesses.map { it.sessionId })
        }

    @Test
    fun `refresh failure keeps the previous list`() =
        scope.runTest {
            val d = delegate()
            listed = listOf(proc("a"))
            d.refresh()
            advanceUntilIdle()
            listError = IllegalStateException("boom")
            d.refresh()
            advanceUntilIdle()
            assertEquals(listOf("a"), uiState.value.backgroundProcesses.map { it.sessionId })
        }

    @Test
    fun `refresh without a session does nothing`() =
        scope.runTest {
            sessionId = null
            listed = listOf(proc("a"))
            delegate().refresh()
            advanceUntilIdle()
            assertEquals(emptyList<ProcessInfo>(), uiState.value.backgroundProcesses)
        }

    @Test
    fun `result for a switched session is dropped`() =
        scope.runTest {
            val d = delegate()
            listed = listOf(proc("a"))
            d.refresh("s1")
            sessionId = "s2"
            advanceUntilIdle()
            assertEquals(emptyList<ProcessInfo>(), uiState.value.backgroundProcesses)
        }

    @Test
    fun `kill sends session scoped ids then re-syncs and clears the spinner`() =
        scope.runTest {
            listed = listOf(proc("a"))
            val d = delegate()
            d.refresh()
            advanceUntilIdle()
            listed = emptyList()
            d.kill("a")
            advanceUntilIdle()
            assertEquals(listOf("s1" to "a"), killed)
            assertNull(uiState.value.killingProcessId)
            assertEquals(emptyList<ProcessInfo>(), uiState.value.backgroundProcesses)
        }

    @Test
    fun `idle chat polls at the idle interval`() =
        scope.runTest {
            val poll = launch { delegate().pollWhileVisible() }
            runCurrent()
            assertEquals(1, listCalls)
            advanceTimeBy(ChatProcessesDelegate.ACTIVE_POLL_MS + 1)
            assertEquals(1, listCalls)
            advanceTimeBy(ChatProcessesDelegate.IDLE_POLL_MS - ChatProcessesDelegate.ACTIVE_POLL_MS)
            assertEquals(2, listCalls)
            poll.cancel()
        }

    @Test
    fun `busy turn or listed processes poll at the active interval`() =
        scope.runTest {
            uiState.value = uiState.value.copy(isMainTurnBusy = true)
            val poll = launch { delegate().pollWhileVisible() }
            runCurrent()
            advanceTimeBy(ChatProcessesDelegate.ACTIVE_POLL_MS + 1)
            assertEquals(2, listCalls)
            poll.cancel()

            uiState.value = uiState.value.copy(isMainTurnBusy = false)
            listed = listOf(proc("a"))
            listCalls = 0
            val idleWithProcess = launch { delegate().pollWhileVisible() }
            runCurrent()
            advanceTimeBy(ChatProcessesDelegate.ACTIVE_POLL_MS + 1)
            assertEquals(2, listCalls)
            idleWithProcess.cancel()
        }

    @Test
    fun `turn end and session switch refresh at once`() =
        scope.runTest {
            uiState.value = uiState.value.copy(isMainTurnBusy = true)
            val poll = launch { delegate().pollWhileVisible() }
            runCurrent()
            assertEquals(1, listCalls)

            advanceTimeBy(1_000)
            uiState.value = uiState.value.copy(isMainTurnBusy = false)
            runCurrent()
            assertEquals(2, listCalls)

            advanceTimeBy(1_000)
            sessionId = "s2"
            uiState.value = uiState.value.copy(currentSessionId = "s2")
            runCurrent()
            assertEquals(3, listCalls)
            poll.cancel()
        }

    @Test
    fun `pollIntervalMs adapts to turn and process state`() {
        val idle = ChatUiState(currentSessionId = "s1")
        assertEquals(ChatProcessesDelegate.IDLE_POLL_MS, ChatProcessesDelegate.pollIntervalMs(idle))
        assertEquals(
            ChatProcessesDelegate.ACTIVE_POLL_MS,
            ChatProcessesDelegate.pollIntervalMs(idle.copy(isMainTurnBusy = true)),
        )
        assertEquals(
            ChatProcessesDelegate.ACTIVE_POLL_MS,
            ChatProcessesDelegate.pollIntervalMs(idle.copy(backgroundProcesses = listOf(proc("a")))),
        )
    }

    @Test
    fun `parseProcessList skips entries without session_id`() {
        val parsed =
            ChatProcessesDelegate.parseProcessList(
                mapOf(
                    "processes" to
                        listOf(
                            mapOf("session_id" to "p1", "command" to "ls", "status" to "running", "pid" to 7.0),
                            mapOf("command" to "orphan"),
                        ),
                ),
            )
        assertEquals(1, parsed.size)
        assertEquals(7, parsed[0].pid)
    }
}
