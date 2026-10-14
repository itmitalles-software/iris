package io.wutz.apollo.ui.webhooks

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.WebhookSubscription
import io.wutz.apollo.data.model.WebhooksResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.remote.mapHttpError
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.toUiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class WebhooksViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>()
    private val subscription =
        WebhookSubscription(name = "deploys", events = listOf("push"), url = "https://hooks/deploys")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient, AuthManager)
        every { ApiClient.hermesApi } returns api
        every { AuthManager.currentDataScope() } returns null
        coEvery { api.getWebhooks() } returns
            Response.success(
                WebhooksResponse(enabled = true, base_url = "https://hooks", subscriptions = listOf(subscription)),
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel() = WebhooksViewModel(ioDispatcher = dispatcher)

    private fun settle() = dispatcher.scheduler.advanceUntilIdle()

    @Test
    fun `load shows the global switch and subscriptions`() {
        val vm = createViewModel()
        vm.loadWebhooks()
        settle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.enabled)
        assertEquals("https://hooks", state.baseUrl)
        assertEquals(listOf(subscription), state.subscriptions)
        assertNull(state.errorMessage)
    }

    @Test
    fun `load failure shows a localized error with the server detail`() {
        coEvery { api.getWebhooks() } returns Response.error(403, "".toResponseBody())
        val vm = createViewModel()
        vm.loadWebhooks()
        settle()

        assertFalse(vm.uiState.value.isLoading)
        assertEquals(
            UiText.res(R.string.webhooks_msg_load_failed, mapHttpError(403).toUiText()),
            vm.uiState.value.errorMessage,
        )
    }

    @Test
    fun `failed global toggle reverts the switch`() {
        coEvery { api.toggleWebhooks(any()) } returns Response.error(403, "".toResponseBody())
        val vm = createViewModel()
        vm.loadWebhooks()
        settle()

        vm.toggleWebhooks(false)
        assertFalse(vm.uiState.value.enabled)
        settle()

        assertTrue(vm.uiState.value.enabled)
        assertEquals(
            UiText.failure(R.string.webhooks_msg_toggle_global_failed, mapHttpError(403).toUiText()),
            vm.uiState.value.toastMessage,
        )
    }

    @Test
    fun `a subscription needs a name before anything is sent`() {
        val vm = createViewModel()
        vm.showCreateDialog()
        vm.updateCreateFormName("   ")
        vm.createSubscription()
        settle()

        assertEquals(UiText.res(R.string.webhooks_msg_name_required), vm.uiState.value.createFormError)
        assertTrue(vm.uiState.value.showCreateDialog)
        coVerify(exactly = 0) { api.createWebhook(any()) }
    }
}
