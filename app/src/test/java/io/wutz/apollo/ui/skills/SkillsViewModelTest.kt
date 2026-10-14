package io.wutz.apollo.ui.skills

import android.app.Application
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.model.HubSkill
import io.wutz.apollo.data.model.SaveSkillContentRequest
import io.wutz.apollo.data.model.SkillContentResponse
import io.wutz.apollo.data.model.SkillHubSource
import io.wutz.apollo.data.model.SkillHubSourcesResponse
import io.wutz.apollo.data.model.SkillScanFinding
import io.wutz.apollo.data.model.SkillScanResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)
    private val app = mockk<Application>(relaxed = true)

    private fun createViewModel(): SkillsViewModel = SkillsViewModel(app).also { it.ioDispatcher = testDispatcher }

    private fun settle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private val sourcesResponse =
        SkillHubSourcesResponse(
            sources =
                listOf(
                    SkillHubSource(id = "hermes-index", label = "Hermes Index", available = true),
                    SkillHubSource(id = "github", label = "GitHub", rateLimited = true),
                ),
            indexAvailable = true,
            featured =
                listOf(
                    HubSkill(
                        name = "web-research",
                        description = "Find and verify current web facts",
                        source = "hermes-index",
                        identifier = "hermes-index:web-research",
                    ),
                ),
        )

    private val scanResponse =
        SkillScanResponse(
            identifier = "hermes-index:web-research",
            name = "web-research",
            source = "hermes-index",
            trustLevel = "trusted",
            verdict = "safe",
            summary = "No issues found",
            policy = "allow",
            policyReason = "No dangerous patterns detected.",
            findings =
                listOf(
                    SkillScanFinding(
                        severity = "low",
                        category = "network",
                        file = "scripts/fetch.py",
                        line = 12,
                        description = "Uses urllib without a timeout.",
                    ),
                ),
            severityCounts = mapOf("low" to 1),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadHubSources stores sources and featured skills`() {
        coEvery { mockApi.getSkillHubSources() } returns Response.success(sourcesResponse)
        val vm = createViewModel()
        vm.loadHubSources()
        settle()

        assertEquals(sourcesResponse.sources, vm.uiState.value.hubSources)
        assertEquals(sourcesResponse.featured, vm.uiState.value.hubFeatured)
        assertEquals(false, vm.uiState.value.isHubSourcesLoading)
        assertNull(vm.uiState.value.hubSourcesError)
    }

    @Test
    fun `loadHubSources surfaces an error`() {
        coEvery { mockApi.getSkillHubSources() } returns
            Response.error(
                404,
                """{"detail":"Hub sources unavailable"}""".toResponseBody("application/json".toMediaTypeOrNull()),
            )
        val vm = createViewModel()
        vm.loadHubSources()
        settle()

        assertEquals(
            UiText.res(R.string.skills_msg_hub_sources_failed, "Hub sources unavailable"),
            vm.uiState.value.hubSourcesError,
        )
        assertEquals(false, vm.uiState.value.isHubSourcesLoading)
    }

    @Test
    fun `setViewMode HUB triggers sources load when landing is empty`() {
        coEvery { mockApi.getSkillHubSources() } returns Response.success(sourcesResponse)
        val vm = createViewModel()
        vm.setViewMode(SkillsViewMode.HUB)
        settle()

        coVerify { mockApi.getSkillHubSources() }
        assertEquals(sourcesResponse.featured, vm.uiState.value.hubFeatured)
    }

    @Test
    fun `scanHubSkill stores the scan result for the identifier`() {
        coEvery { mockApi.scanHubSkill(identifier = "hermes-index:web-research") } returns
            Response.success(scanResponse)
        val vm = createViewModel()
        vm.scanHubSkill("hermes-index:web-research")
        settle()

        assertEquals("hermes-index:web-research", vm.uiState.value.hubScanIdentifier)
        assertEquals(scanResponse, vm.uiState.value.hubScanResult)
        assertEquals(false, vm.uiState.value.isHubScanning)
        assertNull(vm.uiState.value.hubScanError)
    }

    @Test
    fun `scanHubSkill surfaces an error`() {
        coEvery { mockApi.scanHubSkill(identifier = "broken:skill") } returns
            Response.error(
                404,
                """{"detail":"Skill not found"}""".toResponseBody("application/json".toMediaTypeOrNull()),
            )
        val vm = createViewModel()
        vm.scanHubSkill("broken:skill")
        settle()

        assertEquals("broken:skill", vm.uiState.value.hubScanIdentifier)
        assertNull(vm.uiState.value.hubScanResult)
        assertEquals(false, vm.uiState.value.isHubScanning)
        assertEquals("Skill not found", vm.uiState.value.hubScanError)
    }

    @Test
    fun `scanHubSkill ignores blank identifiers`() {
        val vm = createViewModel()
        vm.scanHubSkill(" ")
        settle()

        assertNull(vm.uiState.value.hubScanIdentifier)
        coVerify(exactly = 0) { mockApi.scanHubSkill(any()) }
    }

    @Test
    fun `clearHubScan resets scan state`() {
        coEvery { mockApi.scanHubSkill(identifier = "hermes-index:web-research") } returns
            Response.success(scanResponse)
        val vm = createViewModel()
        vm.scanHubSkill("hermes-index:web-research")
        settle()
        vm.clearHubScan()

        assertNull(vm.uiState.value.hubScanIdentifier)
        assertNull(vm.uiState.value.hubScanResult)
        assertNull(vm.uiState.value.hubScanError)
        assertEquals(false, vm.uiState.value.isHubScanning)
    }

    // ── Skill editor content (review U17) ──

    @Test
    fun `late content of a previously edited skill never fills another skill's editor`() {
        val gateA = CompletableDeferred<Unit>()
        coEvery { mockApi.getSkillContent("alpha") } coAnswers {
            gateA.await()
            Response.success(SkillContentResponse(name = "alpha", content = "alpha content"))
        }
        coEvery { mockApi.getSkillContent("beta") } returns
            Response.success(SkillContentResponse(name = "beta", content = "beta content"))
        coEvery { mockApi.saveSkillContent(any()) } returns Response.success(Unit)
        val vm = createViewModel()

        vm.loadSkillContent("alpha")
        settle()
        vm.clearEditor()
        vm.loadSkillContent("beta")
        settle()
        gateA.complete(Unit)
        settle()

        assertEquals("beta", vm.uiState.value.editingSkillName)
        assertEquals("beta content", vm.uiState.value.skillContent)

        vm.saveSkillContent("beta", "beta edited")
        settle()
        coVerify(
            exactly = 1,
        ) { mockApi.saveSkillContent(SaveSkillContentRequest(name = "beta", content = "beta edited")) }
    }

    @Test
    fun `late content failure does not close another skill's editor`() {
        val gateA = CompletableDeferred<Unit>()
        coEvery { mockApi.getSkillContent("alpha") } coAnswers {
            gateA.await()
            Response.error(500, "{}".toResponseBody("application/json".toMediaTypeOrNull()))
        }
        val gateB = CompletableDeferred<Unit>()
        coEvery { mockApi.getSkillContent("beta") } coAnswers {
            gateB.await()
            Response.success(SkillContentResponse(name = "beta", content = "beta content"))
        }
        val vm = createViewModel()

        vm.loadSkillContent("alpha")
        settle()
        vm.loadSkillContent("beta")
        settle()
        gateA.complete(Unit)
        settle()

        assertEquals("beta", vm.uiState.value.editingSkillName)
        assertNull(vm.uiState.value.toastMessage)

        gateB.complete(Unit)
        settle()
        assertEquals("beta content", vm.uiState.value.skillContent)
    }

    @Test
    fun `content arriving after the editor was closed does not reopen it`() {
        val gate = CompletableDeferred<Unit>()
        coEvery { mockApi.getSkillContent("alpha") } coAnswers {
            gate.await()
            Response.success(SkillContentResponse(name = "alpha", content = "alpha content"))
        }
        val vm = createViewModel()

        vm.loadSkillContent("alpha")
        settle()
        vm.clearEditor()
        gate.complete(Unit)
        settle()

        assertNull(vm.uiState.value.editingSkillName)
        assertNull(vm.uiState.value.skillContent)
    }
}
