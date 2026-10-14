package io.wutz.apollo.ui.channels

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.EnvVarField
import io.wutz.apollo.data.model.MessagingPlatform
import io.wutz.apollo.data.model.MessagingPlatformResponse
import io.wutz.apollo.data.model.MessagingPlatformUpdate
import io.wutz.apollo.data.model.MessagingPlatformUpdateResponse
import io.wutz.apollo.data.model.TelegramOnboardingStartResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.remote.mapHttpError
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.toUiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(AuthManager)
        every { AuthManager.currentDataScope() } returns null
        every { AuthManager.activeProfileId } returns MutableStateFlow("work")
        coEvery { mockApi.getMessagingPlatforms() } returns Response.success(platformsResponse())
        coEvery { mockApi.getMessagingPlatforms("work") } returns Response.success(platformsResponse())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `hot served update clears restart requirement and refreshes platforms`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(
                MessagingPlatformUpdateResponse(
                    ok = true,
                    platform = "telegram",
                    hotServed = true,
                ),
            )

        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.configurePlatform("telegram", MessagingPlatformUpdate(env = mapOf("token" to "redacted")))
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.restartNeeded)
        assertEquals(
            UiText.res(R.string.channels_msg_configured_live, "telegram"),
            viewModel.uiState.value.toastMessage,
        )
        coVerify { mockApi.configurePlatform("telegram", any()) }
        coVerify(atLeast = 2) { mockApi.getMessagingPlatforms() }
    }

    @Test
    fun `update without hot served preserves restart required behavior`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(MessagingPlatformUpdateResponse(ok = true, platform = "telegram"))

        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.configurePlatform("telegram", MessagingPlatformUpdate(enabled = true))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.restartNeeded)
        assertEquals(
            UiText.res(R.string.channels_msg_configured_restart, "telegram"),
            viewModel.uiState.value.toastMessage,
        )
    }

    private fun platformsResponse(): MessagingPlatformResponse =
        MessagingPlatformResponse(
            envPath = "/tmp/.env",
            gatewayStartCommand = "hermes gateway start",
            platforms =
                listOf(
                    MessagingPlatform(
                        id = "telegram",
                        name = "Telegram",
                        enabled = true,
                        configured = true,
                        envVars =
                            listOf(
                                EnvVarField("TELEGRAM_BOT_TOKEN", true, true, isPassword = true),
                                EnvVarField("TELEGRAM_ALLOWED_USERS", false, true, isPassword = false),
                            ),
                    ),
                ),
        )

    @Test
    fun `disconnect clears catalog keys in selected profile and retains platform`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(MessagingPlatformUpdateResponse(ok = true, hotServed = true))
        val viewModel = ChannelsViewModel()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            mockApi.configurePlatform(
                "telegram",
                MessagingPlatformUpdate(
                    enabled = false,
                    clearEnv = listOf("TELEGRAM_BOT_TOKEN", "TELEGRAM_ALLOWED_USERS"),
                    profile = "work",
                ),
            )
        }
        assertEquals(
            "telegram",
            viewModel.uiState.value.platforms
                .single()
                .id,
        )
        assertFalse(viewModel.uiState.value.restartNeeded)
        assertEquals(null, viewModel.uiState.value.removingId)
    }

    @Test
    fun `disconnect stops when profile changes during catalog read`() {
        val profile = MutableStateFlow<String?>("work")
        every { AuthManager.activeProfileId } returns profile
        coEvery { mockApi.getMessagingPlatforms("work") } answers {
            profile.value = "other"
            Response.success(platformsResponse())
        }
        val viewModel = ChannelsViewModel()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockApi.configurePlatform(any(), any()) }
        assertEquals(null, viewModel.uiState.value.removingId)
        assertEquals(
            UiText.failure(
                R.string.channels_msg_disconnect_failed,
                UiText.res(R.string.network_error_unexpected, "Connection or profile changed; retry disconnect"),
            ),
            viewModel.uiState.value.toastMessage,
        )
    }

    @Test
    fun `disconnect read failure does not write and exposes failure`() {
        coEvery { mockApi.getMessagingPlatforms("work") } returns
            Response.error(403, "Forbidden".toResponseBody())
        val viewModel = ChannelsViewModel()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockApi.configurePlatform(any(), any()) }
        assertEquals(null, viewModel.uiState.value.removingId)
        assertEquals(
            UiText.failure(R.string.channels_msg_disconnect_failed, mapHttpError(403, "Forbidden").toUiText()),
            viewModel.uiState.value.toastMessage,
        )
    }

    @Test
    fun `disconnect requires restart when gateway has not hot served changes`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.success(MessagingPlatformUpdateResponse(ok = true))
        val viewModel = ChannelsViewModel()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.restartNeeded)
    }

    @Test
    fun `disconnect refuses missing credential metadata`() {
        coEvery { mockApi.getMessagingPlatforms("work") } returns
            Response.success(
                platformsResponse().copy(
                    platforms = listOf(platformsResponse().platforms.single().copy(envVars = null)),
                ),
            )
        val viewModel = ChannelsViewModel()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockApi.configurePlatform(any(), any()) }
        assertEquals(
            UiText.failure(
                R.string.channels_msg_disconnect_failed,
                UiText.res(R.string.network_error_unexpected, "Platform credential metadata is unavailable"),
            ),
            viewModel.uiState.value.toastMessage,
        )
    }

    @Test
    fun `disconnect write failure keeps platform and clears progress`() {
        coEvery { mockApi.configurePlatform("telegram", any()) } returns
            Response.error(409, "Conflict".toResponseBody())
        val viewModel = ChannelsViewModel()
        viewModel.loadPlatforms()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.removePlatform("telegram")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "telegram",
            viewModel.uiState.value.platforms
                .single()
                .id,
        )
        assertEquals(null, viewModel.uiState.value.removingId)
        assertEquals(
            UiText.failure(R.string.channels_msg_disconnect_failed, mapHttpError(409, "Conflict").toUiText()),
            viewModel.uiState.value.toastMessage,
        )
        assertFalse(viewModel.uiState.value.restartNeeded)
    }

    // ── Telegram onboarding start (review U29) ──

    private val onboardingStart =
        TelegramOnboardingStartResponse(
            pairingId = "pair-1",
            suggestedUsername = "hermes_bot",
            deepLink = "https://t.me/newbot",
            qrPayload = "data:image/png;base64,AA==",
            expiresAt = "2099-01-01T00:00:00",
        )

    @Test
    fun `cancelling during STARTING stops the start request and its late response does not revive onboarding`() {
        val gate = CompletableDeferred<Unit>()
        coEvery { mockApi.startTelegramOnboarding(any()) } coAnswers {
            gate.await()
            Response.success(onboardingStart)
        }
        val viewModel = ChannelsViewModel()

        viewModel.startTelegramOnboarding()
        testDispatcher.scheduler.runCurrent()
        assertEquals(OnboardingPhase.STARTING, viewModel.uiState.value.onboardingPhase)

        viewModel.cancelTelegramOnboarding()
        gate.complete(Unit)
        testDispatcher.scheduler.runCurrent()

        assertEquals(OnboardingPhase.IDLE, viewModel.uiState.value.onboardingPhase)
        assertNull(viewModel.uiState.value.onboardingSetup)
        coVerify(exactly = 0) { mockApi.getTelegramOnboardingStatus(any()) }
    }

    @Test
    fun `start response moves onboarding to WAITING`() {
        coEvery { mockApi.startTelegramOnboarding(any()) } returns Response.success(onboardingStart)
        val viewModel = ChannelsViewModel()

        viewModel.startTelegramOnboarding()
        testDispatcher.scheduler.runCurrent()

        assertEquals(OnboardingPhase.WAITING, viewModel.uiState.value.onboardingPhase)
        assertEquals(
            "pair-1",
            viewModel.uiState.value.onboardingSetup
                ?.pairingId,
        )
        viewModel.cancelTelegramOnboarding()
    }
}
