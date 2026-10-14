package io.wutz.apollo.ui.chat

import io.wutz.apollo.R
import io.wutz.apollo.data.model.MessageReaction
import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.data.ws.contract.RpcMethod
import io.wutz.apollo.data.ws.contract.TypedRpcCaller
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatReactionsDelegateTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val agent =
        ChatMessage(id = "a1", role = MessageRole.ASSISTANT, content = "Done", serverRowId = 42L)
    private val uiState = MutableStateFlow(ChatUiState(messages = listOf(agent), currentSessionId = "stored"))
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var response: (JsonObject) -> JsonElement = { error("no response") }

    private val caller =
        object : TypedRpcCaller {
            override suspend fun <P, R> call(
                method: RpcMethod<P, R>,
                params: P,
            ): R {
                val encoded = OkHttpProvider.json.encodeToJsonElement(method.params, params) as JsonObject
                calls += method.name to encoded
                return OkHttpProvider.json.decodeFromJsonElement(method.result, response(encoded))
            }
        }

    private fun delegate(runtimeId: String? = "rt-1") =
        ChatReactionsDelegate(scope, dispatcher, uiState, {
            runtimeId
        }, caller)

    @Test
    fun reactSendsTheContractShapeAndAppliesTheGatewayList() {
        response = {
            OkHttpProvider.json.parseToJsonElement(
                """{"row_id": 42, "reactions": [{"emoji": "👍", "author": "user", "at": 1.0, "seen": false}]}""",
            )
        }
        delegate().react("a1", "👍")
        // Optimistic paint before the gateway answers.
        assertEquals(listOf(MessageReaction("👍", "user")), uiState.value.messages[0].reactions)
        scope.advanceUntilIdle()

        val (method, params) = calls.single()
        assertEquals("message.react", method)
        assertEquals("rt-1", params["session_id"]?.jsonPrimitive?.content)
        assertEquals("42", params["row_id"]?.jsonPrimitive?.content)
        assertEquals("👍", params["emoji"]?.jsonPrimitive?.content)
        assertEquals("user", params["author"]?.jsonPrimitive?.content)
        assertEquals(listOf(MessageReaction("👍", "user")), uiState.value.messages[0].reactions)
    }

    @Test
    fun clearingSendsExplicitNull() {
        uiState.value =
            uiState.value.copy(messages = listOf(agent.copy(reactions = listOf(MessageReaction("❤️", "user")))))
        response = { OkHttpProvider.json.parseToJsonElement("""{"row_id": 42, "reactions": []}""") }
        delegate().react("a1", null)
        scope.advanceUntilIdle()
        assertTrue(calls.single().second.containsKey("emoji"))
        assertEquals("null", calls.single().second["emoji"].toString())
        assertTrue(
            uiState.value.messages[0]
                .reactions
                .isEmpty(),
        )
    }

    @Test
    fun failedWriteRollsBackAndReports() {
        val agentReaction = MessageReaction("😂", "agent")
        uiState.value = uiState.value.copy(messages = listOf(agent.copy(reactions = listOf(agentReaction))))
        response = { error("gateway said no") }
        delegate().react("a1", "👍")
        scope.advanceUntilIdle()
        assertEquals(listOf(agentReaction), uiState.value.messages[0].reactions)
        assertEquals(UiText.res(R.string.chat_reaction_failed), uiState.value.errorMessage)
    }

    @Test
    fun messagesWithoutRowIdOrRuntimeSessionAreNotSent() {
        uiState.value = uiState.value.copy(messages = listOf(agent.copy(serverRowId = null)))
        delegate().react("a1", "👍")
        scope.advanceUntilIdle()
        assertTrue(calls.isEmpty())
        assertNull(uiState.value.errorMessage)

        uiState.value = uiState.value.copy(messages = listOf(agent))
        delegate(runtimeId = null).react("a1", "👍")
        scope.advanceUntilIdle()
        assertTrue(calls.isEmpty())
        assertEquals(UiText.res(R.string.chat_reaction_failed), uiState.value.errorMessage)
    }

    @Test
    fun applyReactionHasTapbackSemantics() {
        val agentReaction = MessageReaction("😂", "agent")
        val start = listOf(agentReaction, MessageReaction("❤️", "user"))
        assertEquals(listOf(agentReaction, MessageReaction("👍", "user")), applyReaction(start, "👍"))
        assertEquals(listOf(agentReaction), applyReaction(start, "❤️"))
        assertEquals(listOf(agentReaction), applyReaction(start, null))
    }
}
