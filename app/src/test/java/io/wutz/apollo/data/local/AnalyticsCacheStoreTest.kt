package io.wutz.apollo.data.local

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.wutz.apollo.data.model.AnalyticsResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class AnalyticsCacheStoreTest {
    private val serverA =
        DataScope(connectionProfileId = "default", baseUrl = "http://a:9119", activeProfileId = "default")
    private val serverB =
        DataScope(connectionProfileId = "work", baseUrl = "http://b:9119", activeProfileId = "default")

    @Test
    fun `key differs per server connection and server-side profile`() {
        val keyA = analyticsCacheKey(serverA, 30, null)
        assertNotEquals(keyA, analyticsCacheKey(serverB, 30, null))
        assertNotEquals(keyA, analyticsCacheKey(serverA.copy(baseUrl = "http://c:9119"), 30, null))
        assertNotEquals(keyA, analyticsCacheKey(serverA.copy(activeProfileId = "research"), 30, null))
        assertNotEquals(keyA, analyticsCacheKey(serverA, 7, null))
    }

    @Test
    fun `key survives restarts and treats a null profile as default`() {
        assertEquals(
            analyticsCacheKey(serverA, 30, "default"),
            analyticsCacheKey(serverA.copy(inMemoryAuthGeneration = 5L), 30, null),
        )
    }

    @Test
    fun `nothing is cached without a server`() {
        assertNull(analyticsCacheKey(null, 30, null))
    }

    @Test
    fun `one server's cached usage is never served for another server`() =
        runBlocking {
            var current: DataScope? = serverA
            val store = AnalyticsCacheStore(context, scopeProvider = { current })
            store.save(30, null, AnalyticsResponse(period_days = 30), null)

            assertNotNull(store.load(30, null))
            current = serverB
            assertNull(store.load(30, null))
        }

    @Test
    fun `a response is not filed when the scope changed during the request`() =
        runBlocking {
            var current: DataScope? = serverA.copy(baseUrl = "http://inflight:9119")
            val store = AnalyticsCacheStore(context, scopeProvider = { current })
            val requestScope = store.currentScope()
            current = serverB

            store.save(7, null, AnalyticsResponse(period_days = 7), null, requestScope)

            assertNull(store.load(7, null, requestScope))
            assertNull(store.load(7, null, serverB))
        }

    companion object {
        // The dataStore delegate keeps one instance per process, so all tests share one directory.
        private val filesDir = Files.createTempDirectory("analytics-cache-test").toFile()
        private val context: Context =
            mockk<Context>(relaxed = true).also { ctx ->
                every { ctx.applicationContext } returns ctx
                every { ctx.filesDir } returns filesDir
            }
    }
}
