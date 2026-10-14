package io.wutz.apollo.data.ws.contract

import io.wutz.apollo.data.remote.OkHttpProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentTypesTest {
    @Test
    fun listEncodesOnlySessionId() {
        val encoded = OkHttpProvider.json.encodeToJsonElement(SessionIdParams.serializer(), SessionIdParams("s1"))
        assertEquals(buildJsonObject { put("session_id", "s1") }, encoded)
    }

    @Test
    fun tailEncodesExactlyTheTwoRequiredIds() {
        val encoded =
            OkHttpProvider.json.encodeToJsonElement(
                SubagentTailParams.serializer(),
                SubagentTailParams("s1", "sub-1"),
            )
        assertEquals(
            buildJsonObject {
                put("session_id", "s1")
                put("subagent_id", "sub-1")
            },
            encoded,
        )
    }

    @Test
    fun descriptorsAreRegisteredAndPreserveRawResults() {
        assertTrue(RpcMethods.SUBAGENT_LIST in RpcMethods.all)
        assertTrue(RpcMethods.SUBAGENT_TAIL in RpcMethods.all)
        assertEquals("subagent.list", RpcMethods.SUBAGENT_LIST.name)
        assertEquals("subagent.tail", RpcMethods.SUBAGENT_TAIL.name)
        val raw: JsonElement = buildJsonObject { put("future_field", "retained") }
        assertEquals(raw, OkHttpProvider.json.decodeFromJsonElement(RpcMethods.SUBAGENT_LIST.result, raw))
        assertEquals(raw, OkHttpProvider.json.decodeFromJsonElement(RpcMethods.SUBAGENT_TAIL.result, raw))
    }

    @Test
    fun steerEncodesSessionSubagentAndTextOnly() {
        val encoded =
            OkHttpProvider.json.encodeToJsonElement(
                SubagentSteerParams.serializer(),
                SubagentSteerParams("s1", "sub-1", "focus on tests"),
            )
        assertEquals(
            buildJsonObject {
                put("session_id", "s1")
                put("subagent_id", "sub-1")
                put("text", "focus on tests")
            },
            encoded,
        )
    }

    @Test
    fun controlResultsDecodeTheContractShapes() {
        val steer =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.SUBAGENT_STEER.result,
                buildJsonObject {
                    put("status", "rejected")
                    put("subagent_id", "sub-1")
                    put("text", "focus on tests")
                },
            )
        assertFalse(steer.queued)
        assertTrue(SubagentSteerResult(status = "queued").queued)
        val interrupt =
            OkHttpProvider.json.decodeFromJsonElement(
                RpcMethods.SUBAGENT_INTERRUPT.result,
                buildJsonObject {
                    put("found", true)
                    put("subagent_id", "sub-1")
                },
            )
        assertTrue(interrupt.found)
        assertEquals("sub-1", interrupt.subagentId)
        assertTrue(RpcMethods.SUBAGENT_STEER in RpcMethods.all)
        assertTrue(RpcMethods.SUBAGENT_INTERRUPT in RpcMethods.all)
    }
}
