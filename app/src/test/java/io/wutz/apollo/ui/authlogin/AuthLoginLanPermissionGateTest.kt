package io.wutz.apollo.ui.authlogin

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.data.remote.await
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The Android 17 (API 37) `ACCESS_LOCAL_NETWORK` gate in front of the login probe, the first request
 * to the entered host. Ported from the removed ConnectPermissionGateTest (the old, unrouted connect
 * screen had the only copy of this gate).
 *
 * The API level is injected through [AuthLoginViewModel.sdkVersion] because the final static
 * [android.os.Build.VERSION.SDK_INT] cannot be mocked in JVM tests. The permission check
 * ([ContextCompat.checkSelfPermission], a static method) is mocked. "Dialed out" means the probe
 * built a request on the probe client.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthLoginLanPermissionGateTest {
    private val testDispatcher = StandardTestDispatcher()
    private val app = mockk<Application>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val probeClient = mockk<OkHttpClient>(relaxed = true)
    private var permissionGranted = false
    private var baseUrl = "http://192.168.1.50:9119/"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(AuthManager)
        every { AuthManager.getBaseUrl() } answers { baseUrl }
        every { AuthManager.getConnectionProfiles() } returns emptyList()

        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any()) } returns 0

        // The probe itself fails fast: these tests only care whether it dialed out.
        mockkObject(OkHttpProvider)
        every { OkHttpProvider.probe } returns probeClient
        mockkStatic("io.wutz.apollo.data.remote.CallExtKt")
        val call = mockk<okhttp3.Call>()
        every { probeClient.newCall(any()) } returns call
        coEvery { call.await() } throws IOException("Connection refused")

        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_LOCAL_NETWORK)
        } answers { if (permissionGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun createViewModel(sdk: Int): AuthLoginViewModel =
        AuthLoginViewModel(app, ioDispatcher = testDispatcher).also { it.sdkVersion = sdk }

    private fun settle() = testDispatcher.scheduler.advanceUntilIdle()

    @Test
    fun `API 37 LAN host without permission asks for it instead of probing`() {
        val vm = createViewModel(sdk = 37)

        vm.requestProbe(context)
        settle()

        assertTrue(vm.uiState.value.lanPermissionNeeded)
        assertNull(vm.uiState.value.errorMessage)
        verify(exactly = 0) { probeClient.newCall(any()) }
    }

    @Test
    fun `API 37 LAN host with permission probes directly`() {
        permissionGranted = true
        val vm = createViewModel(sdk = 37)

        vm.requestProbe(context)
        settle()

        assertFalse(vm.uiState.value.lanPermissionNeeded)
        verify { probeClient.newCall(any()) }
    }

    @Test
    fun `API 37 loopback host probes without a permission prompt`() {
        baseUrl = "http://127.0.0.1:9119/"
        val vm = createViewModel(sdk = 37)

        vm.requestProbe(context)
        settle()

        assertFalse(vm.uiState.value.lanPermissionNeeded)
        verify { probeClient.newCall(any()) }
    }

    @Test
    fun `below API 37 a LAN host probes without a permission prompt`() {
        val vm = createViewModel(sdk = 36)

        vm.requestProbe(context)
        settle()

        assertFalse(vm.uiState.value.lanPermissionNeeded)
        verify { probeClient.newCall(any()) }
    }

    @Test
    fun `denied permission clears the request and explains it`() {
        val vm = createViewModel(sdk = 37)
        vm.requestProbe(context)
        settle()
        assertTrue(vm.uiState.value.lanPermissionNeeded)

        vm.onLanPermissionResult(false)
        settle()

        assertFalse(vm.uiState.value.lanPermissionNeeded)
        assertEquals(UiText.res(R.string.connect_error_lan_permission_denied), vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.connectionSuccess)
        verify(exactly = 0) { probeClient.newCall(any()) }
    }

    @Test
    fun `granted permission after the prompt probes`() {
        val vm = createViewModel(sdk = 37)
        vm.requestProbe(context)
        settle()
        assertTrue(vm.uiState.value.lanPermissionNeeded)

        permissionGranted = true
        vm.onLanPermissionResult(true)
        settle()

        assertFalse(vm.uiState.value.lanPermissionNeeded)
        verify { probeClient.newCall(any()) }
    }

    @Test
    fun `leaving the screen drops a pending permission request`() {
        val vm = createViewModel(sdk = 37)
        vm.requestProbe(context)
        settle()
        assertTrue(vm.uiState.value.lanPermissionNeeded)

        vm.clearConnectionState()

        assertFalse(vm.uiState.value.lanPermissionNeeded)
    }
}
