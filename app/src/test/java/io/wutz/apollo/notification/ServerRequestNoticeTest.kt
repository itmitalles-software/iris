package io.wutz.apollo.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRequestNoticeTest {
    @Test
    fun approvalShowsDescriptionThenCommand() {
        assertEquals(
            ServerRequestNotice.Text("delete in root path"),
            serverRequestNotice("approval", mapOf("description" to "delete in root path", "command" to "rm -r /tmp/x")),
        )
        assertEquals(
            ServerRequestNotice.Text("rm -r /tmp/x"),
            serverRequestNotice("approval", mapOf("description" to " ", "command" to "rm -r /tmp/x")),
        )
        assertEquals(ServerRequestNotice.InputNeeded, serverRequestNotice("approval", emptyMap()))
    }

    @Test
    fun approvalTextIsCappedAt100Characters() {
        val notice = serverRequestNotice("approval", mapOf("command" to "x".repeat(250)))
        assertEquals(ServerRequestNotice.Text("x".repeat(100)), notice)
    }

    @Test
    fun promptsMapToGenericNotices() {
        assertEquals(ServerRequestNotice.ClarificationNeeded, serverRequestNotice("clarify", emptyMap()))
        assertEquals(ServerRequestNotice.InputNeeded, serverRequestNotice("sudo", emptyMap()))
        assertEquals(ServerRequestNotice.InputNeeded, serverRequestNotice("vault.unlock_prompt", emptyMap()))
        assertEquals(
            ServerRequestNotice.Text("GITHUB_TOKEN"),
            serverRequestNotice(
                "secret",
                mapOf("env_var" to "GITHUB_TOKEN"),
            ),
        )
    }

    @Test
    fun unknownMethodsAreIgnored() {
        assertNull(serverRequestNotice("something.else", emptyMap()))
    }

    @Test
    fun replayedRequestsAlertOnlyOnFirstSighting() {
        val sightings = ServerRequestSightings()
        assertTrue(sightings.onRequest("req-1"))
        assertTrue(sightings.isOpen("req-1"))
        // RpcChannel re-emits open_requests on every resume.
        assertFalse(sightings.onRequest("req-1"))
        assertTrue(sightings.onRequest("req-2"))
    }

    @Test
    fun cancelledRequestsAreNoLongerOpen() {
        val sightings = ServerRequestSightings()
        sightings.onRequest("req-1")
        sightings.onCancelled("req-1")
        assertFalse(sightings.isOpen("req-1"))
        assertFalse(sightings.onRequest("req-1"))
    }

    @Test
    fun sightingsForgetTheOldestBeyondCapacity() {
        val sightings = ServerRequestSightings(capacity = 2)
        sightings.onRequest("a")
        sightings.onRequest("b")
        sightings.onRequest("c")
        assertTrue(sightings.onRequest("a"))
        assertFalse(sightings.onRequest("c"))
    }
}
