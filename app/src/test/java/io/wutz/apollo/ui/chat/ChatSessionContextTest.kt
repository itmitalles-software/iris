package io.wutz.apollo.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionContextTest {
    private var storedId: String? = "stored-a"
    private var scope = "server-a/profile-a"
    private val context = ChatSessionContext({ storedId }, { scope })

    @Test
    fun eventsAcceptBothSessionAliasesAndUnscopedLegacyEvents() {
        context.runtimeSessionId = "runtime-a"

        assertTrue(context.isCurrentEvent("stored-a"))
        assertTrue(context.isCurrentEvent("runtime-a"))
        assertTrue(context.isCurrentEvent(null))
        assertFalse(context.isCurrentEvent("other"))
    }

    @Test
    fun effectiveSessionFallsBackToLiveStorageWithoutCachingIt() {
        assertEquals("stored-a", context.effectiveSessionId)
        storedId = "stored-b"
        assertEquals("stored-b", context.effectiveSessionId)
        assertFalse(context.isCurrentEvent("stored-a"))
        context.runtimeSessionId = "runtime-b"
        assertEquals("runtime-b", context.effectiveSessionId)

        context.runtimeSessionId = null
        storedId = null
        assertNull(context.effectiveSessionId)
        assertTrue(context.isCurrentEvent(null))
        assertFalse(context.isCurrentEvent("runtime-b"))
    }

    @Test
    fun historyRequestsUseStorageIdentityRatherThanRuntimeAlias() {
        context.runtimeSessionId = "runtime-a"

        assertTrue(context.isCurrentRequest("stored-a", 0L))
        assertFalse(context.isCurrentRequest("runtime-a", 0L))
        assertFalse(context.isCurrentRequest("stored-a", 1L))
        storedId = "stored-b"
        assertFalse(context.isCurrentRequest("stored-a", 0L))
    }

    @Test
    fun createAndLegacyRequestsRetainOptionalIdentityFields() {
        assertTrue(context.isCurrentRequest(null, 0L))
        assertTrue(context.isCurrentRequest(null, 0L, scope))
        assertFalse(context.isCurrentRequest(null, 0L, "other-scope"))

        scope = "server-b/profile-b"
        assertTrue(context.isCurrentRequest("stored-a", 0L))
        assertFalse(context.isCurrentRequest("stored-a", 0L, "server-a/profile-a"))
        assertTrue(context.isCurrentRequest("stored-a", 0L, scope))
    }

    @Test
    fun sendOwnershipRequiresAllFourIdentityFields() {
        context.runtimeSessionId = "runtime-a"
        val owner = context.captureSendOwner("stored-a", "runtime-a")

        assertTrue(context.isCurrentSend(owner))
        assertFalse(context.isCurrentSend(owner.copy(scope = "other-scope")))
        assertFalse(context.isCurrentSend(owner.copy(storageSessionId = "other-storage")))
        assertFalse(context.isCurrentSend(owner.copy(agentSessionId = "other-runtime")))
        assertFalse(context.isCurrentSend(owner.copy(generation = 1L)))

        context.runtimeSessionId = null
        assertFalse(context.isCurrentSend(owner))
    }

    @Test
    fun capturedSendOwnerDoesNotFollowStorageScopeOrRuntimeChanges() {
        context.runtimeSessionId = "runtime-a"
        val owner = context.captureSendOwner("stored-a", "runtime-a")

        storedId = "stored-b"
        assertFalse(context.isCurrentSend(owner))
        storedId = "stored-a"
        scope = "server-a/profile-b"
        assertFalse(context.isCurrentSend(owner))
        scope = "server-a/profile-a"
        context.runtimeSessionId = "runtime-b"
        assertFalse(context.isCurrentSend(owner))
        assertEquals("runtime-a", owner.agentSessionId)
    }

    @Test
    fun invalidationRejectsOldWorkWithoutClearingIdsOrChangingLifecycleOrder() {
        context.runtimeSessionId = "runtime-a"
        val owner = context.captureSendOwner("stored-a", "runtime-a")

        assertEquals(1L, context.invalidate())
        assertFalse(context.isCurrentSend(owner))
        assertFalse(context.isCurrentRequest("stored-a", 0L))
        assertFalse(context.isCurrentRequest(null, 0L))
        assertTrue(context.isCurrentRequest("stored-a", 1L))
        assertEquals("runtime-a", context.runtimeSessionId)
        assertEquals("stored-a", storedId)
        assertEquals(2L, context.invalidate())
    }

    @Test
    fun explicitReservationIdentityIsPreservedWhenCapturingOwner() {
        context.invalidate()
        val owner = context.captureSendOwner("old-storage", "old-runtime", "old-scope", 0L)

        assertEquals(ChatSessionContext.SendOwner("old-scope", "old-storage", "old-runtime", 0L), owner)
        assertFalse(context.isCurrentSend(owner))
    }
}
