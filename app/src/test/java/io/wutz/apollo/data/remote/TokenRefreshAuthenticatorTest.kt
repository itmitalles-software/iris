package io.wutz.apollo.data.remote

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.wutz.apollo.data.local.AuthManager
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** [TokenRefreshAuthenticator] is installed on every REST client (ApiClient) and handles their 401s. */
class TokenRefreshAuthenticatorTest {
    @Before
    fun setUp() {
        mockkObject(AuthManager)
        mockkObject(DashboardSessionTokenRefresher)
        every { AuthManager.isGatedMode() } returns false
        every { AuthManager.getToken() } returns "current"
        every { DashboardSessionTokenRefresher.refresh() } returns null
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun unauthorized(
        authorization: String?,
        prior: Response? = null,
    ): Response {
        val request =
            Request
                .Builder()
                .url("http://127.0.0.1:9119/api/status")
                .apply { if (authorization != null) header("Authorization", authorization) }
                .build()
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .priorResponse(prior)
            .build()
    }

    @Test
    fun `a retried request is not retried again`() {
        val result =
            TokenRefreshAuthenticator.authenticate(
                null,
                unauthorized("Bearer old", prior = unauthorized(null)),
            )

        assertNull(result)
        verify(exactly = 0) { DashboardSessionTokenRefresher.refresh() }
    }

    @Test
    fun `gated mode gives up so the app asks the user to sign in again`() {
        every { AuthManager.isGatedMode() } returns true

        assertNull(TokenRefreshAuthenticator.authenticate(null, unauthorized("Bearer old")))
        verify(exactly = 0) { DashboardSessionTokenRefresher.refresh() }
    }

    @Test
    fun `a newer stored token is reused without refreshing`() {
        val retry = TokenRefreshAuthenticator.authenticate(null, unauthorized("Bearer old"))

        assertEquals("Bearer current", retry?.header("Authorization"))
        verify(exactly = 0) { DashboardSessionTokenRefresher.refresh() }
    }

    @Test
    fun `a request without credentials gets the stored token`() {
        val retry = TokenRefreshAuthenticator.authenticate(null, unauthorized(null))

        assertEquals("Bearer current", retry?.header("Authorization"))
    }

    @Test
    fun `a rejected current token is refreshed from the dashboard`() {
        every { DashboardSessionTokenRefresher.refresh() } returns "fresh"

        val retry = TokenRefreshAuthenticator.authenticate(null, unauthorized("Bearer current"))

        assertEquals("Bearer fresh", retry?.header("Authorization"))
        assertEquals("http://127.0.0.1:9119/api/status", retry?.url.toString())
    }

    @Test
    fun `no stored token falls back to the dashboard refresh`() {
        every { AuthManager.getToken() } returns null
        every { DashboardSessionTokenRefresher.refresh() } returns "fresh"

        val retry = TokenRefreshAuthenticator.authenticate(null, unauthorized(null))

        assertEquals("Bearer fresh", retry?.header("Authorization"))
    }

    @Test
    fun `a failed refresh gives up`() {
        assertNull(TokenRefreshAuthenticator.authenticate(null, unauthorized("Bearer current")))
        verify(exactly = 1) { DashboardSessionTokenRefresher.refresh() }
    }
}
