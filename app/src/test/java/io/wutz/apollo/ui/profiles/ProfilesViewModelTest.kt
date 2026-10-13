package io.wutz.apollo.ui.profiles

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.ActiveProfileResponse
import io.wutz.apollo.data.model.CreateProfileRequest
import io.wutz.apollo.data.model.ModelOptionsResponse
import io.wutz.apollo.data.model.ModelProvider
import io.wutz.apollo.data.model.PinnedModel
import io.wutz.apollo.data.model.ProfileDescribeAutoRequest
import io.wutz.apollo.data.model.ProfileDescribeAutoResponse
import io.wutz.apollo.data.model.ProfileInfo
import io.wutz.apollo.data.model.ProfileSetupCommandResponse
import io.wutz.apollo.data.model.ProfilesResponse
import io.wutz.apollo.data.model.RenameProfileRequest
import io.wutz.apollo.data.model.StatusResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.remote.NetworkError
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Issue #781 — Profiles screen delete/rename/auto-describe/setup-command.
 * Contracts verified against hermes-agent web_routers/profiles.py:
 * - PATCH  /api/profiles/{name} {new_name} -> {ok, name, path}
 * - DELETE /api/profiles/{name} -> {ok, path}
 * - POST   /api/profiles/{name}/describe-auto {overwrite} -> {ok, reason, description, description_auto}
 *   (generation failures come back as ok:false + reason, NOT an HTTP error)
 * - GET    /api/profiles/{name}/setup-command -> {command}
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private var storedPinnedModels: MutableList<PinnedModel> = mutableListOf()
    private var storedSelectedProfile: String? = null
    private var storedProfileToken: String? = null
    private var storedHiddenProfiles: MutableList<String> = mutableListOf()

    private fun stubProfilesLoad() {
        coEvery { mockApi.getProfiles() } returns
            Response.success(ProfilesResponse(listOf(ProfileInfo(name = "default", is_default = true))))
        coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = "default"))
        coEvery { mockApi.getStatus() } returns Response.success(StatusResponse())
    }

    @Before
    fun setUp() {
        // NOTE: no mockkStatic(Dispatchers) here — a static Dispatchers mock
        // bleeds into later test classes in the same JVM (see the same comment
        // in ProfileSwitchCoordinatorTest). setMain alone is safe: it only
        // affects Dispatchers.Main and is undone by resetMain in tearDown.
        Dispatchers.setMain(testDispatcher)

        mockkObject(AuthManager)
        storedPinnedModels = mutableListOf()
        storedSelectedProfile = null
        storedProfileToken = null
        every { AuthManager.getPinnedModels() } answers { storedPinnedModels.toList() }
        every { AuthManager.savePinnedModels(any()) } answers {
            storedPinnedModels = firstArg<List<PinnedModel>>().toMutableList()
        }
        every { AuthManager.getSelectedProfileId() } answers { storedSelectedProfile }
        every { AuthManager.setSelectedProfileId(any()) } answers {
            storedSelectedProfile = firstArg<String?>()
        }
        every { AuthManager.getToken() } answers { "tok-abc" }
        every { AuthManager.getProfileToken(any()) } answers { storedProfileToken }
        every { AuthManager.setProfileToken(any(), any()) } answers {
            storedProfileToken = secondArg<String?>()
        }
        every { AuthManager.getHiddenProfiles() } answers { storedHiddenProfiles.toList() }
        every { AuthManager.hideProfile(any()) } answers {
            val name = firstArg<String>()
            if (!storedHiddenProfiles.contains(name)) storedHiddenProfiles.add(name)
        }
        every { AuthManager.unhideProfile(any()) } answers {
            val name = firstArg<String>()
            storedHiddenProfiles.remove(name)
        }

        mockkObject(HermesWsClient)
        every { HermesWsClient.disconnect() } returns Unit
        every { HermesWsClient.connect() } returns Unit

        mockkObject(ProfileSwitchCoordinator)
        coEvery { ProfileSwitchCoordinator.switchProfile(any()) } returns NetworkResult.Success(Unit)

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        stubProfilesLoad()
    }

    private fun <T> errorResponse(code: Int): Response<T> = Response.error(code, "{}".toResponseBody(null))

    private companion object {
        // Fallback messages of mapHttpError for an empty "{}" error body.
        const val BAD_REQUEST = "Bad Request (HTTP 400): The server could not understand the request."
        const val NOT_FOUND = "Not Found (HTTP 404): The requested resource could not be found."
        const val SERVER_ERROR = "Server Error (HTTP 500): The server encountered an error."
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(): ProfilesViewModel {
        val vm = ProfilesViewModel(ioDispatcher = testDispatcher)
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Test
    fun `renameProfile success calls backend and reloads`() {
        coEvery { mockApi.renameProfile(any(), any()) } returns Response.success(Unit)

        val vm = createViewModel()
        vm.renameProfile("work", "play")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.renameProfile("work", RenameProfileRequest("play")) }
        assertEquals(
            UiText.res(R.string.profiles_msg_renamed, "work", "play"),
            vm.uiState.value.toastMessage,
        )
        // reload happened
        coVerify { mockApi.getProfiles() }
    }

    @Test
    fun `renameProfile to same name is a no-op`() {
        // If the VM ever fired the API with an unchanged name, this stub throws
        // and the test fails — no fragile exactly=0 verification needed.
        coEvery { mockApi.renameProfile(any(), any()) } throws IllegalStateException("should not be called")

        val vm = createViewModel()
        vm.renameProfile("work", "work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.toastMessage)
    }

    @Test
    fun `renameProfile failure surfaces toast and stops loading`() {
        coEvery { mockApi.renameProfile(any(), any()) } returns errorResponse(404)

        val vm = createViewModel()
        vm.renameProfile("work", "play")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            UiText.res(R.string.profiles_msg_rename_failed, "work", NOT_FOUND),
            vm.uiState.value.toastMessage,
        )
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `deleteProfile success calls backend and reloads`() {
        coEvery { mockApi.deleteProfile(any()) } returns Response.success(Unit)

        val vm = createViewModel()
        vm.deleteProfile("work")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.deleteProfile("work") }
        assertEquals(
            UiText.res(R.string.profiles_msg_deleted, "work"),
            vm.uiState.value.toastMessage,
        )
        coVerify { mockApi.getProfiles() }
    }

    @Test
    fun `deleteProfile failure surfaces toast`() {
        coEvery { mockApi.deleteProfile(any()) } returns errorResponse(404)

        val vm = createViewModel()
        vm.deleteProfile("work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            UiText.res(R.string.profiles_msg_delete_failed, "work", NOT_FOUND),
            vm.uiState.value.toastMessage,
        )
    }

    @Test
    fun `autoDescribeProfile success with ok true reloads`() {
        coEvery { mockApi.describeProfileAuto(any(), any()) } returns
            Response.success(ProfileDescribeAutoResponse(ok = true, description = "desc", description_auto = true))

        val vm = createViewModel()
        vm.autoDescribeProfile("work")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.describeProfileAuto("work", ProfileDescribeAutoRequest(overwrite = true)) }
        assertEquals(
            UiText.res(R.string.profiles_msg_auto_described, "work"),
            vm.uiState.value.toastMessage,
        )
        assertFalse(vm.uiState.value.isAutoDescribing)
        coVerify { mockApi.getProfiles() }
    }

    @Test
    fun `autoDescribeProfile ok false surfaces backend reason`() {
        coEvery { mockApi.describeProfileAuto(any(), any()) } returns
            Response.success(ProfileDescribeAutoResponse(ok = false, reason = "no aux client configured"))

        val vm = createViewModel()
        vm.autoDescribeProfile("work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            UiText.res(
                R.string.profiles_msg_auto_describe_failed,
                "work",
                UiText.raw("no aux client configured"),
            ),
            vm.uiState.value.toastMessage,
        )
        assertFalse(vm.uiState.value.isAutoDescribing)
    }

    @Test
    fun `fetchSetupCommand success stores command`() {
        coEvery { mockApi.getProfileSetupCommand(any()) } returns
            Response.success(ProfileSetupCommandResponse(command = "work setup"))

        val vm = createViewModel()
        vm.fetchSetupCommand("work")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.getProfileSetupCommand("work") }
        assertEquals("work setup", vm.uiState.value.setupCommand)
        assertFalse(vm.uiState.value.isLoadingSetupCommand)
    }

    @Test
    fun `fetchSetupCommand failure leaves command null and toasts`() {
        coEvery { mockApi.getProfileSetupCommand(any()) } returns errorResponse(404)

        val vm = createViewModel()
        vm.fetchSetupCommand("work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.setupCommand)
        assertEquals(
            UiText.res(R.string.profiles_msg_setup_command_failed, NOT_FOUND),
            vm.uiState.value.toastMessage,
        )
    }

    @Test
    fun `loadModelOptions success populates providers and pins`() {
        storedPinnedModels.add(PinnedModel("openai", "gpt-4"))
        coEvery { mockApi.getModelOptions() } returns
            Response.success(
                ModelOptionsResponse(
                    listOf(
                        ModelProvider(
                            slug = "openai",
                            name = "OpenAI",
                            models = listOf("gpt-4o", "gpt-4o-mini"),
                        ),
                    ),
                ),
            )

        val vm = createViewModel()
        vm.loadModelOptions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.uiState.value.modelProviders.size)
        assertEquals(
            "gpt-4o",
            vm.uiState.value.modelProviders[0]
                .models!![0],
        )
        assertEquals(listOf(PinnedModel("openai", "gpt-4")), vm.uiState.value.modelPickerPinned)
        assertFalse(vm.uiState.value.isLoadingBuilderData)
    }

    @Test
    fun `loadModelOptions failure toasts without blanking errorMessage`() {
        coEvery { mockApi.getModelOptions() } returns errorResponse(500)

        val vm = createViewModel()
        vm.loadModelOptions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            UiText.res(R.string.profiles_msg_load_models_failed, SERVER_ERROR),
            vm.uiState.value.toastMessage,
        )
        assertNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isLoadingBuilderData)
    }

    @Test
    fun `togglePinModel adds then removes pinned model`() {
        val vm = createViewModel()

        vm.togglePinModel("openai", "gpt-4")
        assertEquals(listOf(PinnedModel("openai", "gpt-4")), vm.uiState.value.modelPickerPinned)
        assertEquals(listOf(PinnedModel("openai", "gpt-4")), storedPinnedModels)

        vm.togglePinModel("openai", "gpt-4")
        assertTrue(
            vm.uiState.value.modelPickerPinned
                .isEmpty(),
        )
        assertTrue(storedPinnedModels.isEmpty())
    }

    @Test
    fun `selectActiveProfile success delegates to coordinator and reloads`() {
        coEvery { ProfileSwitchCoordinator.switchProfile("work") } returns NetworkResult.Success(Unit)

        val vm = createViewModel()
        vm.selectActiveProfile("work")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { ProfileSwitchCoordinator.switchProfile("work") }
        assertEquals(
            UiText.res(R.string.profiles_msg_switched, "work"),
            vm.uiState.value.toastMessage,
        )
        coVerify { mockApi.getProfiles() }
    }

    @Test
    fun `selectActiveProfile failure rolls back optimistic state`() {
        coEvery {
            ProfileSwitchCoordinator.switchProfile("work")
        } returns NetworkResult.Failure(NetworkError.Http(code = 500, message = "boom"))

        val vm = createViewModel()
        vm.selectActiveProfile("work")
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.activeProfileName)
        assertEquals(
            UiText.res(R.string.profiles_msg_switch_failed, "boom"),
            vm.uiState.value.toastMessage,
        )
    }

    @Test
    fun `loadProfiles displays normalized shared gateway profiles`() {
        coEvery { mockApi.getProfiles() } returns
            Response.success(
                ProfilesResponse(
                    listOf(
                        ProfileInfo(name = "default", is_default = true),
                        ProfileInfo(name = "coding"),
                    ),
                ),
            )
        coEvery { mockApi.getStatus() } returns
            Response.success(
                StatusResponse(
                    gatewaySharedWith = listOf("default", "coding", "coding", " ", ""),
                ),
            )

        val vm = createViewModel()
        vm.loadProfiles()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("default", "coding"), vm.uiState.value.sharedGatewayProfiles)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `loadProfiles keeps profiles usable when optional status request fails`() {
        coEvery { mockApi.getStatus() } returns errorResponse(404)

        val vm = createViewModel()
        vm.loadProfiles()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("default"),
            vm.uiState.value.profiles
                .map { it.name },
        )
        assertEquals("default", vm.uiState.value.activeProfileName)
        assertTrue(
            vm.uiState.value.sharedGatewayProfiles
                .isEmpty(),
        )
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `cloneProfile success calls createProfile with clone_from and reloads`() {
        coEvery { mockApi.createProfile(any()) } returns Response.success(Unit)

        val vm = createViewModel()
        vm.cloneProfile("default", "dev-copy")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            mockApi.createProfile(
                CreateProfileRequest(
                    name = "dev-copy",
                    clone_from = "default",
                ),
            )
        }
        assertEquals(
            UiText.res(R.string.profiles_msg_cloned, "default", "dev-copy"),
            vm.uiState.value.toastMessage,
        )
        assertFalse(vm.uiState.value.isLoading)
        coVerify { mockApi.getProfiles() }
    }

    @Test
    fun `cloneProfile failure surfaces toast and stops loading`() {
        coEvery { mockApi.createProfile(any()) } returns errorResponse(400)

        val vm = createViewModel()
        vm.cloneProfile("default", "dev-copy")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            UiText.res(R.string.profiles_msg_clone_failed, "default", BAD_REQUEST),
            vm.uiState.value.toastMessage,
        )
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `hideProfile and unhideProfile update local hidden state and display filtering`() {
        coEvery { mockApi.getProfiles() } returns
            Response.success(
                ProfilesResponse(
                    listOf(
                        ProfileInfo(name = "default", is_default = true),
                        ProfileInfo(name = "secret-bot"),
                    ),
                ),
            )

        val vm = createViewModel()
        vm.loadProfiles()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.uiState.value.displayProfiles.size)
        assertFalse(vm.uiState.value.hasHiddenProfiles)

        vm.hideProfile("secret-bot")
        assertEquals(listOf("secret-bot"), storedHiddenProfiles)
        assertTrue(vm.uiState.value.hasHiddenProfiles)
        assertEquals(
            listOf("default"),
            vm.uiState.value.displayProfiles
                .map { it.name },
        )

        // Toggle show hidden
        vm.toggleShowHidden()
        assertTrue(vm.uiState.value.showHidden)
        assertEquals(
            listOf("default", "secret-bot"),
            vm.uiState.value.displayProfiles
                .map { it.name },
        )

        // Unhide
        vm.unhideProfile("secret-bot")
        assertTrue(storedHiddenProfiles.isEmpty())
        assertFalse(vm.uiState.value.hasHiddenProfiles)
    }
}
