package io.wutz.apollo.notification

import io.wutz.apollo.data.ws.contract.ApprovalRespondParams
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalNotificationResponderTest {
    private var connected = true
    private var locked = false
    private val withdrawn = mutableSetOf<String>()
    private var frameSent = true
    private val frames = mutableListOf<Pair<String, JsonElement>>()
    private val legacyCalls = mutableListOf<ApprovalRespondParams>()
    private var legacyResult: () -> JsonElement = { buildJsonObject { put("resolved", 1) } }

    private val responder =
        ApprovalNotificationResponder(
            isConnected = { connected },
            isDeviceLocked = { locked },
            isRequestWithdrawn = { it in withdrawn },
            respondToServerRequest = { id, result ->
                frames += id to result
                frameSent
            },
            respondToLegacyApproval = { params ->
                legacyCalls += params
                legacyResult()
            },
        )

    private val serverRequest = ApprovalNotificationTarget("srq-1", "req-1", "rt-1")
    private val legacy = ApprovalNotificationTarget(null, "req-2", "rt-1")

    @Test
    fun serverRequestIsAnsweredWithAResponseFrame() =
        runTest {
            assertEquals(ApprovalNotificationOutcome.ANSWERED, responder.respond(serverRequest, ApprovalChoice.ONCE))
            val (id, result) = frames.single()
            assertEquals("srq-1", id)
            assertEquals(
                buildJsonObject {
                    put("choice", "once")
                    put("all", false)
                },
                result,
            )
            assertTrue(legacyCalls.isEmpty())
        }

    @Test
    fun legacyApprovalUsesApprovalRespond() =
        runTest {
            assertEquals(ApprovalNotificationOutcome.ANSWERED, responder.respond(legacy, ApprovalChoice.DENY))
            assertEquals(ApprovalRespondParams("rt-1", "deny", false, "req-2"), legacyCalls.single())
        }

    @Test
    fun legacyApprovalThatResolvedNothingExpired() =
        runTest {
            legacyResult = { buildJsonObject { put("resolved", 0) } }
            assertEquals(ApprovalNotificationOutcome.EXPIRED, responder.respond(legacy, ApprovalChoice.ONCE))
            legacyResult = { error("timeout") }
            assertEquals(ApprovalNotificationOutcome.FAILED, responder.respond(legacy, ApprovalChoice.ONCE))
        }

    @Test
    fun lockedDeviceNeverAnswers() =
        runTest {
            locked = true
            assertEquals(ApprovalNotificationOutcome.LOCKED, responder.respond(serverRequest, ApprovalChoice.ONCE))
            assertTrue(frames.isEmpty())
        }

    @Test
    fun disconnectedOrWithdrawnRequestsAreNotClaimedAsAnswered() =
        runTest {
            connected = false
            assertEquals(
                ApprovalNotificationOutcome.DISCONNECTED,
                responder.respond(serverRequest, ApprovalChoice.ONCE),
            )
            connected = true
            withdrawn += "srq-1"
            assertEquals(ApprovalNotificationOutcome.EXPIRED, responder.respond(serverRequest, ApprovalChoice.ONCE))
            withdrawn.clear()
            frameSent = false
            assertEquals(
                ApprovalNotificationOutcome.DISCONNECTED,
                responder.respond(serverRequest, ApprovalChoice.ONCE),
            )
        }

    @Test
    fun onlyOnceAndDenyAreAccepted() =
        runTest {
            assertEquals(ApprovalNotificationOutcome.FAILED, responder.respond(serverRequest, "always"))
            assertEquals(
                ApprovalNotificationOutcome.FAILED,
                responder.respond(ApprovalNotificationTarget(null, "req", null), ApprovalChoice.ONCE),
            )
            assertTrue(frames.isEmpty())
        }

    @Test
    fun targetAnswerability() {
        assertTrue(serverRequest.isAnswerable)
        assertTrue(legacy.isAnswerable)
        assertFalse(ApprovalNotificationTarget(null, null, "rt").isAnswerable)
        assertEquals("srq-1", serverRequest.cardId)
        assertEquals("req-2", legacy.cardId)
    }
}
