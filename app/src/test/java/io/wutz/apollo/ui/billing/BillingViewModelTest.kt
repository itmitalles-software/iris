package io.wutz.apollo.ui.billing

import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.model.SubscriptionStateResponse
import io.wutz.apollo.data.model.UsageBarsResponse
import io.wutz.apollo.data.ws.BillingRepository
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val subscription = SubscriptionStateResponse(ok = true, org_name = "Acme")
    private val usage = UsageBarsResponse(ok = true, status = "active")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(BillingRepository)
        coEvery { BillingRepository.getSubscriptionState() } returns subscription
        coEvery { BillingRepository.getUsageBars() } returns usage
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun loaded(): BillingViewModel =
        BillingViewModel().also {
            it.load()
            dispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `load shows the plan and usage`() {
        val state = loaded().uiState.value

        assertFalse(state.isLoading)
        assertEquals(subscription, state.subscription)
        assertEquals(usage, state.usage)
        assertFalse(state.featureUnavailable)
        assertNull(state.errorMessage)
    }

    @Test
    fun `a backend without the billing RPCs is reported as unavailable`() {
        coEvery { BillingRepository.getSubscriptionState() } throws
            HermesWsClient.HermesRpcException("Method not found", code = -32601)
        coEvery { BillingRepository.getUsageBars() } throws
            HermesWsClient.HermesRpcException("Method not found", code = -32601)

        val state = loaded().uiState.value

        assertTrue(state.featureUnavailable)
        assertNull(state.subscription)
        assertEquals(UiText.raw("Method not found"), state.errorMessage)
    }

    @Test
    fun `a refused subscription state shows the server reason`() {
        coEvery { BillingRepository.getSubscriptionState() } returns
            SubscriptionStateResponse(ok = false, error = "Not logged in to the portal")

        val state = loaded().uiState.value

        assertTrue(state.featureUnavailable)
        // The response is kept; the error only shows when there is no subscription at all.
        assertEquals(false, state.subscription?.ok)
        assertNull(state.errorMessage)
    }

    @Test
    fun `missing data and failures without a message get localized texts`() {
        coEvery { BillingRepository.getSubscriptionState() } returns null
        assertEquals(UiText.res(R.string.billing_msg_no_data), loaded().uiState.value.errorMessage)

        coEvery { BillingRepository.getSubscriptionState() } throws IllegalStateException()
        assertEquals(
            UiText.res(R.string.billing_msg_load_subscription_failed),
            loaded().uiState.value.errorMessage,
        )
    }

    @Test
    fun `usage problems do not hide the plan`() {
        coEvery { BillingRepository.getUsageBars() } throws
            HermesWsClient.HermesRpcException("Method not found", code = -32601)

        val state = loaded().uiState.value

        assertEquals(subscription, state.subscription)
        assertNull(state.usage)
        assertFalse(state.featureUnavailable)
        assertNull(state.errorMessage)
    }
}
