package io.wutz.apollo.ui.bots

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.ActiveProfileResponse
import io.wutz.apollo.data.model.BotAvatarMeta
import io.wutz.apollo.data.model.BotRosterMeta
import io.wutz.apollo.data.model.CanonicalSessionInfo
import io.wutz.apollo.data.model.GroupChatRoomMeta
import io.wutz.apollo.data.model.GroupChatSyncSnapshot
import io.wutz.apollo.data.model.ProfileInfo
import io.wutz.apollo.data.model.ProfileWorkerSummary
import io.wutz.apollo.data.model.ProfilesResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.remote.NetworkError
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(AuthManager)
        every { AuthManager.getHiddenProfiles() } returns emptyList()
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(HermesWsClient)
        every { HermesWsClient.send(any(), any(), any()) } returns "req-1"
        val deferred = kotlinx.coroutines.CompletableDeferred<Any?>()
        deferred.complete(null)
        every { HermesWsClient.request(any(), any(), any(), any()) } returns deferred
    }

    @After
    fun tearDown() {
        unmockkObject(AuthManager)
        unmockkObject(ApiClient)
        unmockkObject(HermesWsClient)
        Dispatchers.resetMain()
    }

    @Test
    fun testLoadBotsAndFilterActiveNow() =
        runTest(testDispatcher) {
            val nowSeconds = (System.currentTimeMillis() / 1000).toDouble()

            val bot1Meta =
                BotRosterMeta(
                    title = "Literature Scout",
                    description = "Arxiv searcher",
                    avatar = BotAvatarMeta(shape = "hexagon", color = "#4A90E2", icon = "science"),
                    hidden = false,
                )
            val bot2Meta =
                BotRosterMeta(
                    title = "Code Reviewer",
                    description = "Kotlin linter",
                    avatar = BotAvatarMeta(shape = "square", color = "#FF5C5C", icon = "code"),
                    hidden = true,
                )

            val profiles =
                listOf(
                    ProfileInfo(
                        name = "default",
                        is_default = true,
                        canonical_session =
                            CanonicalSessionInfo(
                                id = "canon-def",
                                last_active = nowSeconds - 20,
                            ),
                    ),
                    ProfileInfo(
                        name = "scout",
                        ui_meta = mapOf("hermes-bots" to json.encodeToJsonElement(bot1Meta)),
                        worker_session =
                            ProfileWorkerSummary(
                                id = "worker-1",
                                source = "kanban",
                                last_active = nowSeconds - 10,
                            ),
                    ),
                    ProfileInfo(
                        name = "reviewer",
                        ui_meta = mapOf("hermes-bots" to json.encodeToJsonElement(bot2Meta)),
                        canonical_session =
                            CanonicalSessionInfo(
                                id = "canon-rev",
                                last_active = nowSeconds - 500,
                            ),
                    ),
                )

            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(profiles))
            coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "default"))

            val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)
            viewModel.loadBots()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals(3, state.profiles.size)
            assertEquals("default", state.activeProfileName)
            assertTrue(state.hasHiddenBots)

            // Active now includes default (active profile) and scout (active worker)
            val activeNowNames = state.activeNowBots.map { it.name }
            assertTrue(activeNowNames.contains("default"))
            assertTrue(activeNowNames.contains("scout"))
            assertFalse(activeNowNames.contains("reviewer"))

            // Hidden filtering: reviewer is hidden by default
            val displayed = state.displayProfiles.map { it.name }
            assertEquals(listOf("default", "scout"), displayed)

            // Toggle show hidden (sorted: default (active), scout (worker active now), reviewer (canonical last_active))
            viewModel.toggleShowHidden()
            val displayedWithHidden =
                viewModel.uiState.value.displayProfiles
                    .map { it.name }
            assertEquals(listOf("default", "reviewer", "scout"), displayedWithHidden)

            // Search filter
            viewModel.setSearchQuery("arxiv")
            val searchResults =
                viewModel.uiState.value.displayProfiles
                    .map { it.name }
            assertEquals(listOf("scout"), searchResults)
        }

    @Test
    fun testCreateBot_callsApiAndConfiguresMeta() =
        runTest {
            coEvery { mockApi.createProfile(any()) } returns Response.success(Unit)
            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(emptyList()))
            coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "default"))

            val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)
            var successCalled = false
            viewModel.createBot(
                name = "researcher",
                title = "Research Bot",
                description = "Deep research agent",
                shape = "hexagon",
                color = "#4F46E5",
                onSuccess = { successCalled = true },
            )
            advanceUntilIdle()

            assertTrue(successCalled)
            coVerify(exactly = 1) {
                mockApi.createProfile(
                    match {
                        it.name == "researcher" &&
                            it.clone_from_default == false &&
                            it.description == "Deep research agent"
                    },
                )
            }
        }

    @Test
    fun testCreateGroupChat_updatesMemberMeta() =
        runTest {
            val profiles =
                listOf(
                    ProfileInfo(name = "botA"),
                    ProfileInfo(name = "botB"),
                )
            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(profiles))
            coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "botA"))

            val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)
            viewModel.loadBots()
            advanceUntilIdle()

            var success = false
            viewModel.createGroupChat(
                groupName = "Dream Team",
                botNames = listOf("botA", "botB"),
                onSuccess = { success = true },
            )
            advanceUntilIdle()

            assertTrue(success)
        }

    @Test
    fun testDisbandGroupChat_removesGroupFromMembers() =
        runTest {
            val botMeta =
                BotRosterMeta(
                    groups = listOf("Dream Team", "Other Group"),
                )
            val uiMeta = mapOf("hermes-bots" to json.encodeToJsonElement(botMeta))
            val profiles =
                listOf(
                    ProfileInfo(name = "botA", ui_meta = uiMeta),
                    ProfileInfo(name = "botB", ui_meta = uiMeta),
                )
            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(profiles))
            coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "botA"))

            val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)
            viewModel.loadBots()
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.allGroups.size)
            assertEquals(
                "Dream Team",
                viewModel.uiState.value.allGroups[0]
                    .name,
            )
            assertEquals(
                2,
                viewModel.uiState.value.allGroups[0]
                    .members.size,
            )

            var success = false
            viewModel.disbandGroupChat("Dream Team") { success = true }
            advanceUntilIdle()

            assertTrue(success)
        }

    @Test
    fun testUpdateBotMeta_sendsRpcAndReloads() =
        runTest {
            val profiles = listOf(ProfileInfo(name = "scout"))
            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(profiles))
            coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "scout"))

            val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)
            viewModel.loadBots()
            advanceUntilIdle()

            var success = false
            viewModel.updateBotMeta(
                name = "scout",
                title = "Scout Lead",
                description = "Updated desc",
                shape = "square",
                color = "#2563EB",
                onSuccess = { success = true },
            )
            advanceUntilIdle()

            assertTrue(success)
        }

    @Test
    fun testDeleteBot_callsDeleteApiAndReloads() =
        runTest {
            coEvery { mockApi.deleteProfile("scout") } returns Response.success(Unit)
            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(emptyList()))
            coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "default"))

            val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)
            var success = false
            viewModel.deleteBot("scout") { success = true }
            advanceUntilIdle()

            assertTrue(success)
            coVerify(exactly = 1) { mockApi.deleteProfile("scout") }
        }

    @Test
    fun testAllGroups_includesDesktopSyncRoomsAndRespectsOrdering() =
        runTest {
            val syncSnapshot =
                GroupChatSyncSnapshot(
                    version = 3,
                    updatedAt = 1725000000L,
                    rooms =
                        mapOf(
                            "name:Desktop Room" to
                                GroupChatRoomMeta(
                                    name = "Desktop Room",
                                    members = listOf(JsonPrimitive("botA"), JsonPrimitive("remoteBot")),
                                    updatedAt = 1725000100L,
                                ),
                            "id:room-123" to
                                GroupChatRoomMeta(
                                    name = "Old Archived Room",
                                    members = listOf(JsonPrimitive("botA")),
                                    tombstone = true,
                                ),
                        ),
                    deleted = mapOf("name:deleted room" to 1725000000L),
                )

            val defaultProfile =
                ProfileInfo(
                    name = "default",
                    is_default = true,
                    ui_meta = mapOf("hermes-bots-groups" to json.encodeToJsonElement(syncSnapshot)),
                )

            val botA =
                ProfileInfo(
                    name = "botA",
                    ui_meta =
                        mapOf(
                            "hermes-bots" to
                                json.encodeToJsonElement(
                                    BotRosterMeta(groups = listOf("Local Only Group")),
                                ),
                        ),
                )

            coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(listOf(defaultProfile, botA)))
            coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "default"))

            val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)
            viewModel.loadBots()
            advanceUntilIdle()

            val groups = viewModel.uiState.value.allGroups
            assertEquals(2, groups.size)
            // "Desktop Room" has recent activity 1725000100L, so it should rank first
            assertEquals("Desktop Room", groups[0].name)
            assertEquals(2, groups[0].members.size)
            assertEquals("botA", groups[0].members[0].name)
            assertEquals("remoteBot", groups[0].members[1].name)

            assertEquals("Local Only Group", groups[1].name)
            assertEquals(1, groups[1].members.size)
        }

    private val storedBotMeta =
        buildJsonObject {
            put("title", "Old title")
            put("hidden", true)
            put("created", 1.7e9)
            put("group", "Legacy Team")
            put("desktop_only", "keep me")
            putJsonObject("avatar") {
                put("shape", "circle")
                put("color", "#000000")
                put("icon", "science")
                put("image_url", "https://example.invalid/a.png")
            }
        }

    private suspend fun loadedViewModel(vararg profiles: ProfileInfo): BotsViewModel {
        coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(profiles.toList()))
        coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "default"))
        val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)
        viewModel.loadBots()
        testDispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    @Test
    fun testMergeBotRosterMeta_keepsFieldsTheEditorDoesNotOwn() {
        val merged =
            mergeBotRosterMeta(
                storedBotMeta,
                BotRosterMeta(
                    title = "New title",
                    description = "New description",
                    avatar = BotAvatarMeta(shape = "square", color = "#FFFFFF"),
                    group = "Legacy Team",
                ),
            )

        assertEquals(JsonPrimitive("New title"), merged["title"])
        assertEquals(JsonPrimitive("New description"), merged["description"])
        assertEquals(JsonPrimitive(true), merged["hidden"])
        assertEquals(JsonPrimitive(1.7e9), merged["created"])
        assertEquals(JsonPrimitive("Legacy Team"), merged["group"])
        assertEquals(JsonPrimitive("keep me"), merged["desktop_only"])
        val avatar = merged["avatar"]!!.jsonObject
        assertEquals(JsonPrimitive("square"), avatar["shape"])
        assertEquals(JsonPrimitive("#FFFFFF"), avatar["color"])
        assertEquals(JsonPrimitive("science"), avatar["icon"])
        assertEquals(JsonPrimitive("https://example.invalid/a.png"), avatar["image_url"])
    }

    @Test
    fun testMergeBotRosterMeta_removesGroupsWhenTheLastGroupIsDisbanded() {
        val stored = buildJsonObject { put("groups", JsonArray(listOf(JsonPrimitive("Team")))) }
        val merged = mergeBotRosterMeta(stored, BotRosterMeta(groups = emptyList(), group = null))
        assertFalse(merged.containsKey("groups"))
        assertFalse(merged.containsKey("group"))
    }

    @Test
    fun testUpdateBotMeta_sendsMergedUiMeta() =
        runTest {
            val sent = mutableListOf<Map<String, Any>>()
            val ok = CompletableDeferred<Any?>().apply { complete(null) }
            every { HermesWsClient.request("profiles.configure", capture(sent), any()) } returns ok
            val viewModel =
                loadedViewModel(ProfileInfo(name = "scout", ui_meta = mapOf("hermes-bots" to storedBotMeta)))

            var success = false
            viewModel.updateBotMeta("scout", "Scout Lead", "", "square", "#2563EB") { success = true }
            advanceUntilIdle()

            assertTrue(success)
            val bots = (sent.single()["ui_meta"] as JsonObject)["hermes-bots"]!!.jsonObject
            assertEquals(JsonPrimitive("Scout Lead"), bots["title"])
            assertEquals(JsonPrimitive(true), bots["hidden"])
            assertEquals(JsonPrimitive("Legacy Team"), bots["group"])
            assertEquals(JsonPrimitive("https://example.invalid/a.png"), bots["avatar"]!!.jsonObject["image_url"])
        }

    @Test
    fun testUpdateBotMeta_failureKeepsEditorOpenAndShowsToast() =
        runTest {
            val failed =
                CompletableDeferred<Any?>().apply {
                    completeExceptionally(HermesWsClient.HermesRpcException("socket closed"))
                }
            every { HermesWsClient.request("profiles.configure", any(), any()) } returns failed
            val viewModel = loadedViewModel(ProfileInfo(name = "scout"))

            var success = false
            viewModel.updateBotMeta("scout", "Scout Lead", "", "square", "#2563EB") { success = true }
            advanceUntilIdle()

            assertFalse(success)
            assertEquals(
                UiText.res(R.string.bots_msg_meta_save_failed, UiText.raw("socket closed")),
                viewModel.uiState.value.toastMessage,
            )
        }

    @Test
    fun testCreateGroupChat_notAppliedIsReportedInsteadOfSuccess() =
        runTest {
            val notApplied =
                CompletableDeferred<Any?>().apply {
                    complete(
                        buildJsonObject {
                            put("ok", true)
                            putJsonObject("applied") { put("ui_meta", false) }
                        },
                    )
                }
            every { HermesWsClient.request("profiles.configure", any(), any()) } returns notApplied
            val viewModel = loadedViewModel(ProfileInfo(name = "default"), ProfileInfo(name = "botA"))

            var success = false
            viewModel.createGroupChat("Dream Team", listOf("botA")) { success = true }
            advanceUntilIdle()

            assertFalse(success)
            assertEquals(
                UiText.res(R.string.bots_msg_group_save_failed, UiText.res(R.string.bots_msg_meta_not_applied)),
                viewModel.uiState.value.toastMessage,
            )
        }

    @Test
    fun testDisbandGroupChat_rpcFailureIsReported() =
        runTest {
            val failed =
                CompletableDeferred<Any?>().apply {
                    completeExceptionally(HermesWsClient.HermesRpcException("timeout"))
                }
            every { HermesWsClient.request("profiles.configure", any(), any()) } returns failed
            val meta = mapOf("hermes-bots" to json.encodeToJsonElement(BotRosterMeta(groups = listOf("Dream Team"))))
            val viewModel = loadedViewModel(ProfileInfo(name = "botA", ui_meta = meta))

            var success = false
            viewModel.disbandGroupChat("Dream Team") { success = true }
            advanceUntilIdle()

            assertFalse(success)
            assertEquals(
                UiText.res(R.string.bots_msg_group_save_failed, UiText.raw("timeout")),
                viewModel.uiState.value.toastMessage,
            )
        }

    @Test
    fun testDeleteBot_failureIsShownAsToast() =
        runTest {
            coEvery { mockApi.deleteProfile("scout") } returns
                Response.error(500, "boom".toResponseBody("text/plain".toMediaType()))
            val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)

            var success = false
            viewModel.deleteBot("scout") { success = true }
            advanceUntilIdle()

            assertFalse(success)
            assertEquals(
                R.string.bots_msg_delete_failed_reason,
                (viewModel.uiState.value.toastMessage as UiText.Resource).id,
            )
        }

    @Test
    fun testSelectBot_failedSwitchReturnsFalseAndShowsToast() =
        runTest {
            mockkObject(ProfileSwitchCoordinator)
            try {
                coEvery { ProfileSwitchCoordinator.switchProfile("scout") } returns
                    NetworkResult.Failure(NetworkError.Http(503, "gateway down"))
                val viewModel = BotsViewModel(ioDispatcher = testDispatcher, autoLoad = false)

                assertFalse(viewModel.selectBot(ProfileInfo(name = "scout")))
                assertEquals(
                    UiText.res(R.string.profiles_msg_switch_failed, "gateway down"),
                    viewModel.uiState.value.toastMessage,
                )
            } finally {
                unmockkObject(ProfileSwitchCoordinator)
            }
        }
}
