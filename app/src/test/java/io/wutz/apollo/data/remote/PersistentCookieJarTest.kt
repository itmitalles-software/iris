package io.wutz.apollo.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PersistentCookieJar] — the issue #470 cookie stack.
 *
 * Uses [FakeEncryptedCookieStore] (no Android deps) and exercises:
 * - Set-Cookie capture + re-attachment on matching requests
 * - host/path/session-cookie matching rules
 * - atomic server-scope switching (multi-server isolation)
 * - async persistence round-trip through the fake store
 * - pruning of expired (but not session) cookies
 */
class PersistentCookieJarTest {
    private fun makeJar(serverHosts: Set<String> = emptySet()): PersistentCookieJar =
        PersistentCookieJar(
            store = FakeEncryptedCookieStore(),
            storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            isServerUrl = { it.host in serverHosts },
        )

    private fun sessionCookie(
        host: String,
        value: String,
    ): Cookie =
        Cookie
            .Builder()
            .name(SESSION_COOKIE_NAME)
            .value(value)
            .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
            .hostOnlyDomain(host)
            .path("/")
            .httpOnly()
            .build()

    @Test
    fun saveFromResponse_thenLoadForRequest_attachesCookie() =
        runTest {
            val jar = makeJar()
            jar.useStore("server-a")
            val url = "http://dashboard.local/".toHttpUrl()
            jar.saveFromResponse(url, listOf(sessionCookie("dashboard.local", "abc123")))

            val loaded = jar.loadForRequest("http://dashboard.local/api/status".toHttpUrl())
            assertEquals(1, loaded.size)
            assertEquals("abc123", loaded[0].value)
            assertEquals(SESSION_COOKIE_NAME, loaded[0].name)
        }

    @Test
    fun sessionCookie_isUsableOnEveryAddressOfTheActiveServer() =
        runTest {
            // The dashboard was signed in over its LAN address; the base URL now
            // points at its tailnet address (WiFi/Tailscale switch).
            val jar = makeJar(serverHosts = setOf("tailnet.example"))
            jar.useStore("server-a")
            jar.saveFromResponse(
                "http://tailnet.example/".toHttpUrl(),
                listOf(sessionCookie("dashboard.local", "abc123")),
            )

            val loaded = jar.loadForRequest("http://tailnet.example/api/status".toHttpUrl())
            assertEquals(1, loaded.size)
            assertEquals("abc123", loaded[0].value)
        }

    @Test
    fun sessionCookie_isNeverSentToAnotherHost() =
        runTest {
            val jar = makeJar(serverHosts = setOf("dashboard.local"))
            jar.useStore("server-a")
            jar.saveFromResponse(
                "http://dashboard.local/".toHttpUrl(),
                listOf(sessionCookie("dashboard.local", "abc123")),
            )

            // Image loads of tool output and the update check share this jar.
            assertTrue(jar.loadForRequest("https://cdn.example/img.png".toHttpUrl()).isEmpty())
            assertTrue(jar.loadForRequest("https://api.github.com/repos/x".toHttpUrl()).isEmpty())
        }

    @Test
    fun anotherHost_cannotPlantAHostAgnosticSessionCookie() =
        runTest {
            val jar = makeJar(serverHosts = setOf("dashboard.local"))
            jar.useStore("server-a")
            jar.saveFromResponse(
                "https://evil.example/pixel.png".toHttpUrl(),
                listOf(sessionCookie("evil.example", "attacker")),
            )

            assertTrue(jar.loadForRequest("http://dashboard.local/api/status".toHttpUrl()).isEmpty())
        }

    @Test
    fun secureCookie_isNotSentOverPlainHttp() =
        runTest {
            val jar = makeJar(serverHosts = setOf("dash.local"))
            jar.useStore("server-a")
            val secure =
                Cookie
                    .Builder()
                    .name(SESSION_COOKIE_NAME)
                    .value("tls-only")
                    .hostOnlyDomain("dash.local")
                    .path("/")
                    .secure()
                    .build()
            jar.saveFromResponse("https://dash.local/".toHttpUrl(), listOf(secure))

            assertTrue(jar.loadForRequest("http://dash.local/api/status".toHttpUrl()).isEmpty())
            assertEquals("tls-only", jar.loadForRequest("https://dash.local/api/status".toHttpUrl()).single().value)
        }

    @Test
    fun useStore_isolatesServers() =
        runTest {
            val jar = makeJar()
            jar.useStore("server-a")
            jar.saveFromResponse(
                "http://a.local/".toHttpUrl(),
                listOf(sessionCookie("a.local", "cookie-a")),
            )
            jar.useStore("server-b")
            jar.saveFromResponse(
                "http://b.local/".toHttpUrl(),
                listOf(sessionCookie("b.local", "cookie-b")),
            )

            // Active scope is server-b now — only b's cookie should load.
            val fromB = jar.loadForRequest("http://b.local/x".toHttpUrl())
            assertEquals("cookie-b", fromB.firstOrNull()?.value)

            // Switch back to server-a.
            jar.useStore("server-a")
            val fromA = jar.loadForRequest("http://a.local/x".toHttpUrl())
            assertEquals("cookie-a", fromA.firstOrNull()?.value)
        }

    @Test
    fun persistence_roundTripThroughStore() =
        runTest {
            val store = FakeEncryptedCookieStore()
            val jar =
                PersistentCookieJar(
                    store = store,
                    storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                )
            jar.useStore("persist-scope")
            jar.saveFromResponse(
                "http://p.local/".toHttpUrl(),
                listOf(sessionCookie("p.local", "persisted-value")),
            )

            // The fake store should now hold the serialized cookie.
            assertTrue(store.storedHolders("persist-scope").any { it.value == "persisted-value" })

            // A fresh jar sharing the same store must reload it.
            val jar2 =
                PersistentCookieJar(
                    store = store,
                    storeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                )
            jar2.useStore("persist-scope")
            val reloaded = jar2.loadForRequest("http://p.local/".toHttpUrl())
            assertEquals("persisted-value", reloaded.firstOrNull()?.value)
        }

    @Test
    fun pruneServerCache_removesExpiredKeepsSession() =
        runTest {
            val jar = makeJar()
            jar.useStore("prune-scope")
            val url = "http://p.local/".toHttpUrl()
            val expired =
                Cookie
                    .Builder()
                    .name("expired_cookie")
                    .value("x")
                    .expiresAt(System.currentTimeMillis() - 1000)
                    .hostOnlyDomain("p.local")
                    .path("/")
                    .build()
            jar.saveFromResponse(url, listOf(expired, sessionCookie("p.local", "live")))

            jar.pruneServerCache()

            val remaining = jar.loadForRequest(url)
            assertEquals(1, remaining.size)
            assertEquals(SESSION_COOKIE_NAME, remaining[0].name)
        }

    @Test
    fun saveReplacesCookieWithSameNameAndPath() =
        runTest {
            val jar = makeJar()
            jar.useStore("dup-scope")
            val url = "http://d.local/".toHttpUrl()
            jar.saveFromResponse(url, listOf(sessionCookie("d.local", "v1")))
            jar.saveFromResponse(url, listOf(sessionCookie("d.local", "v2")))

            val loaded = jar.loadForRequest(url)
            assertEquals(1, loaded.size)
            assertEquals("v2", loaded[0].value)
        }

    @Test
    fun clearActive_dropsScopeCookies() =
        runTest {
            val jar = makeJar()
            jar.useStore("clear-scope")
            jar.saveFromResponse(
                "http://c.local/".toHttpUrl(),
                listOf(sessionCookie("c.local", "bye")),
            )
            jar.clearActive()

            assertTrue(jar.loadForRequest("http://c.local/".toHttpUrl()).isEmpty())
        }

    @Test
    fun loadForRequest_returnsEmptyWhenNoCookies() {
        val jar = makeJar()
        assertTrue(jar.loadForRequest("http://empty.local/".toHttpUrl()).isEmpty())
        assertNull(jar.getSessionCookieValue())
    }

    @Test
    fun getSessionCookieValue_findsPrefixedHostCookie() =
        runTest {
            val jar = makeJar()
            jar.useStore("prefixed-scope")
            val url = "https://dash.local/".toHttpUrl()
            val prefixed =
                Cookie
                    .Builder()
                    .name("__Host-hermes_session_at")
                    .value("host-prefixed-value")
                    .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
                    .hostOnlyDomain("dash.local")
                    .path("/")
                    .secure()
                    .httpOnly()
                    .build()
            jar.saveFromResponse(url, listOf(prefixed))

            assertEquals("host-prefixed-value", jar.getSessionCookieValue())
        }

    @Test
    fun getSessionCookieValue_prefersStrictestVariant() =
        runTest {
            val jar = makeJar()
            jar.useStore("multi-scope")
            val url = "https://dash.local/".toHttpUrl()
            // Both a bare and a __Secure- prefixed cookie present; the strictest
            // (__Host- / __Secure- first in SESSION_COOKIE_NAMES) must win.
            val bare =
                Cookie
                    .Builder()
                    .name(SESSION_COOKIE_NAME)
                    .value("bare-value")
                    .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
                    .hostOnlyDomain("dash.local")
                    .path("/")
                    .httpOnly()
                    .build()
            val secure =
                Cookie
                    .Builder()
                    .name("__Secure-hermes_session_at")
                    .value("secure-value")
                    .expiresAt(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)
                    .hostOnlyDomain("dash.local")
                    .path("/")
                    .secure()
                    .httpOnly()
                    .build()
            jar.saveFromResponse(url, listOf(bare, secure))

            assertEquals("secure-value", jar.getSessionCookieValue())
        }

    @Test
    fun loadForRequest_awaitsAsyncScopeHydration() =
        runTest {
            val fakeStore = FakeEncryptedCookieStore()
            fakeStore.save("cold-server", listOf(sessionCookie("dash.local", "cold-cookie-xyz")))

            val store =
                object : CookieStore by fakeStore {
                    override suspend fun load(serverId: String): List<Cookie> {
                        delay(50)
                        return fakeStore.load(serverId)
                    }
                }

            val testScope = CoroutineScope(Dispatchers.IO)
            val jar =
                PersistentCookieJar(
                    store = store,
                    storeScope = testScope,
                    initialServerId = "cold-server",
                )

            val job =
                testScope.launch {
                    jar.useStore("cold-server")
                }

            // Wait for useStore to complete hydration
            job.join()

            val loaded = jar.loadForRequest("http://dash.local/api/status".toHttpUrl())
            assertEquals(1, loaded.size)
            assertEquals("cold-cookie-xyz", loaded[0].value)
        }
}
