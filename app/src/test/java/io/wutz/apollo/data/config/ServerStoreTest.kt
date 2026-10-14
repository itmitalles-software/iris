package io.wutz.apollo.data.config

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class ServerStoreTest {
    @Test
    fun testDefaultValues() {
        val state = ServerStoreState()
        assertEquals("127.0.0.1", state.host)
        assertEquals(9119, state.port)
        assertTrue(state.autoReconnect)
        assertEquals("token", state.wsAuthParam)
        assertEquals(1.0f, state.chatFontScale)
        assertFalse(state.showModelProvider)
        assertFalse(state.keepConnectedInBackground)
        assertTrue(state.connectionProfiles.isEmpty())
        assertNull(state.selectedProfileId)
    }

    @Test
    fun testPureOps_addOrUpdate() {
        val state = ServerStoreState()
        val profile = ConnectionProfile(id = "1", name = "Test", host = "10.0.0.1", port = 8080)

        val newState = state.addOrUpdate(profile)
        assertEquals(1, newState.connectionProfiles.size)
        assertEquals(profile, newState.connectionProfiles[0])

        // Replace
        val updatedProfile = profile.copy(host = "10.0.0.2")
        val finalState = newState.addOrUpdate(updatedProfile)
        assertEquals(1, finalState.connectionProfiles.size)
        assertEquals("10.0.0.2", finalState.connectionProfiles[0].host)
    }

    @Test
    fun testPureOps_selfHealed() {
        // Test orphaned selected profile ID
        val state =
            ServerStoreState(
                connectionProfiles = emptyList(),
                selectedProfileId = "orphaned-id",
            )
        val healed = state.selfHealed()
        assertNull(healed.selectedProfileId)
    }

    @Test
    fun testResolvedHostAndPort() {
        val profile = ConnectionProfile(id = "prof-1", name = "Custom", host = "10.0.0.5", port = 8000)
        val state =
            ServerStoreState(
                host = "127.0.0.1",
                port = 9119,
                connectionProfiles = listOf(profile),
                selectedProfileId = "prof-1",
            )

        assertEquals("10.0.0.5", state.resolvedHost)
        assertEquals(8000, state.resolvedPort)

        // Unselected
        val unselectedState = state.copy(selectedProfileId = null)
        assertEquals("127.0.0.1", unselectedState.resolvedHost)
        assertEquals(9119, unselectedState.resolvedPort)
    }

    @Test
    fun testShowModelProviderUpdate() {
        val state = ServerStoreState()
        assertFalse(state.showModelProvider)
        val updated = state.copy(showModelProvider = true)
        assertTrue(updated.showModelProvider)
    }

    @Test
    fun testKeepConnectedInBackgroundUpdate() {
        val state = ServerStoreState()
        assertFalse(state.keepConnectedInBackground)
        val updated = state.copy(keepConnectedInBackground = true)
        assertTrue(updated.keepConnectedInBackground)
    }

    @Test
    fun testKeepConnectedInBackground_deserializationDefault() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<ServerStoreState>("{}")
        assertFalse(decoded.keepConnectedInBackground)
    }

    @Test
    fun testCheckReleaseCandidateUpdatesDefaultsToStableOnly() {
        val state = ServerStoreState()
        assertFalse(state.checkReleaseCandidateUpdates)
        assertTrue(state.copy(checkReleaseCandidateUpdates = true).checkReleaseCandidateUpdates)
    }

    @Test
    fun testCheckReleaseCandidateUpdates_deserializationDefault() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<ServerStoreState>("{}")
        assertFalse(decoded.checkReleaseCandidateUpdates)
    }

    /** Serial like the real DataStore; the first write is slow to expose out-of-order persists. */
    private class FakeDataStore(
        initial: ServerStoreState,
    ) : DataStore<ServerStoreState> {
        private val mutex = Mutex()
        private val writes = AtomicInteger(0)
        val state = MutableStateFlow(initial)
        override val data: StateFlow<ServerStoreState> = state

        override suspend fun updateData(
            transform: suspend (t: ServerStoreState) -> ServerStoreState,
        ): ServerStoreState =
            mutex.withLock {
                val next = transform(state.value)
                if (writes.incrementAndGet() == 1) delay(100)
                state.value = next
                next
            }
    }

    @Test
    fun concurrentUpdatesAreNotLostAndTheLatestStateIsPersisted() =
        runBlocking {
            val dataStore = FakeDataStore(ServerStoreState())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = ServerStore.create(dataStore, scope)
            val threads = 8
            val perThread = 100
            val start = CountDownLatch(1)
            val workers =
                (0 until threads).map { t ->
                    thread {
                        start.await()
                        repeat(perThread) { i ->
                            store.update { it.copy(lastOpenedSessionIds = it.lastOpenedSessionIds + ("$t-$i" to "s")) }
                        }
                    }
                }
            start.countDown()
            workers.forEach { it.join() }

            assertEquals(threads * perThread, store.getLatestState().lastOpenedSessionIds.size)
            withTimeout(10_000) { scope.coroutineContext[Job]!!.children.forEach { it.join() } }
            assertEquals(store.getLatestState(), dataStore.state.value)
        }
}
