package io.wutz.apollo.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesSnackbarTest {
    @Test
    fun `plain messages are short and have no action`() {
        val spec = HermesMessageSpec.of(UiText.res(1), hasUndo = false)
        assertFalse(spec.isFailure)
        assertFalse(spec.offersCopy)
        assertFalse(spec.offersUndo)
        assertFalse(spec.withDismissAction)
        assertEquals(SnackbarDuration.Short, spec.duration)
    }

    @Test
    fun `failures stay longer and offer copy`() {
        val spec = HermesMessageSpec.of(UiText.failure(1, "boom"), hasUndo = false)
        assertTrue(spec.isFailure)
        assertTrue(spec.offersCopy)
        assertTrue(spec.withDismissAction)
        assertEquals(SnackbarDuration.Long, spec.duration)
    }

    @Test
    fun `undo wins over copy and stays longer`() {
        val spec = HermesMessageSpec.of(UiText.res(1), hasUndo = true)
        assertTrue(spec.offersUndo)
        assertFalse(spec.offersCopy)
        assertEquals(SnackbarDuration.Long, spec.duration)
    }

    @Test
    fun `messages go to the newest registered host`() {
        val hosts = HermesSnackbarHosts()
        assertNull(hosts.current)
        val previous = HermesSnackbarHosts.Entry(SnackbarHostState()) { true }
        val incoming = HermesSnackbarHosts.Entry(SnackbarHostState()) { true }
        hosts.register(previous)
        hosts.register(incoming)
        assertSame(incoming, hosts.current)

        // Navigating back re-registers the previous screen on top; the leaving one goes away later.
        hosts.register(previous)
        assertSame(previous, hosts.current)
        hosts.unregister(incoming)
        assertSame(previous, hosts.current)
        hosts.unregister(previous)
        assertNull(hosts.current)
    }
}
