package io.wutz.apollo.data.remote

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

class LocalNetworkTest {
    @Test
    fun `loopback host is exempt`() {
        assertFalse(isLocalNetworkHost("127.0.0.1"))
        assertFalse(isLocalNetworkHost("localhost"))
        assertFalse(isLocalNetworkHost("::1"))
    }

    @Test
    fun `private rfc1918 ranges require permission`() {
        assertTrue(isLocalNetworkHost("192.168.1.50"))
        assertTrue(isLocalNetworkHost("10.0.0.5"))
        assertTrue(isLocalNetworkHost("10.255.255.254"))
        assertTrue(isLocalNetworkHost("172.16.0.1"))
        assertTrue(isLocalNetworkHost("172.31.255.255"))
    }

    @Test
    fun `link-local and cgnat require permission`() {
        assertTrue(isLocalNetworkHost("169.254.0.1"))
        assertTrue(isLocalNetworkHost("100.64.0.1"))
        assertTrue(isLocalNetworkHost("100.127.255.254"))
    }

    @Test
    fun `public remote hosts are exempt`() {
        assertFalse(isLocalNetworkHost("8.8.8.8"))
        assertFalse(isLocalNetworkHost("1.1.1.1"))
    }

    @Test
    fun `needsLocalNetworkPermission gates by base url host`() {
        assertFalse(needsLocalNetworkPermission("http://127.0.0.1:9119"))
        assertFalse(needsLocalNetworkPermission("https://gateway.example.com"))
        assertTrue(needsLocalNetworkPermission("http://192.168.1.50:9119"))
        assertTrue(needsLocalNetworkPermission("https://10.0.0.5:9119/api"))
    }

    @Test
    fun `malformed base url does not require permission`() {
        assertFalse(needsLocalNetworkPermission("not-a-url"))
        assertFalse(needsLocalNetworkPermission(""))
    }

    /** Runs blocks on Dispatchers.Default and records the thread that ran the last one. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        val thread = AtomicReference<Thread?>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            Dispatchers.Default.dispatch(context) {
                thread.set(Thread.currentThread())
                block.run()
            }
        }
    }

    @Test
    fun `permission gate resolves the host on the io dispatcher, not the caller thread`() {
        val io = RecordingDispatcher()
        val caller = Thread.currentThread()

        val needed = runBlocking { requiresLocalNetworkPermission(37, "http://10.0.0.5:9119", io) }

        assertTrue(needed)
        val lookupThread = io.thread.get()
        assertTrue(lookupThread != null && lookupThread != caller)
    }

    @Test
    fun `permission gate skips the lookup below API 37`() {
        val io = RecordingDispatcher()

        val needed = runBlocking { requiresLocalNetworkPermission(36, "http://10.0.0.5:9119", io) }

        assertFalse(needed)
        assertTrue(io.thread.get() == null)
    }

    // Gate decision matrix, moved here from the removed ConnectPermissionGateTest.

    @Test
    fun `permission gate needs the permission for a LAN host on API 37`() {
        assertTrue(runBlocking { requiresLocalNetworkPermission(37, "http://192.168.1.50:9119") })
    }

    @Test
    fun `permission gate skips a LAN host below API 37`() {
        assertFalse(runBlocking { requiresLocalNetworkPermission(36, "http://192.168.1.50:9119") })
    }

    @Test
    fun `permission gate skips loopback on API 37`() {
        assertFalse(runBlocking { requiresLocalNetworkPermission(37, "http://127.0.0.1:9119") })
    }

    @Test
    fun `permission gate skips a public host on API 37`() {
        assertFalse(runBlocking { requiresLocalNetworkPermission(37, "https://gateway.example.com") })
    }
}
