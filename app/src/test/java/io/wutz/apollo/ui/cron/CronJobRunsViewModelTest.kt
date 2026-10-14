package io.wutz.apollo.ui.cron

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.model.CronJobRunsResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.remote.OkHttpProvider
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class CronJobRunsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: HermesApiService

    // Shape of hermes_cli/web_routers/cron.py: a session run and a script-only output-doc run.
    private val runsJson =
        """
        {"runs":[
          {"id":"cron_job1_20261014_0300","source":"cron","title":"Nightly digest","started_at":1760410800.0,
           "ended_at":null,"last_active":1760410900.0,"input_tokens":1200,"output_tokens":300,
           "message_count":4,"model":"m","is_active":true,"scheduler_owned":true,"archived":0,"profile":"work"},
          {"id":"cron_output:job1:2026-10-13_03-00-00","source":"cron_output","title":"FAILED · exit 1",
           "preview":"Traceback ...","started_at":1760324400.0,"last_active":1760324400.0,
           "ended_at":1760324400.0,"input_tokens":0,"output_tokens":0,"message_count":0,"model":null,
           "cwd":null,"archived":false,"is_active":false}
        ],"limit":20}
        """.trimIndent()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient)
        api = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns api
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `wire json maps session and script runs`() {
        val runs = OkHttpProvider.json.decodeFromString(CronJobRunsResponse.serializer(), runsJson).runs
        assertEquals(2, runs.size)
        assertTrue(runs[0].hasSession)
        assertEquals(true, runs[0].isActive)
        assertEquals(1500L, runs[0].inputTokens!! + runs[0].outputTokens!!)
        assertFalse(runs[1].hasSession)
        assertEquals("Traceback ...", runs[1].preview)
    }

    @Test
    fun `load shows the runs of the opened job`() {
        val response = OkHttpProvider.json.decodeFromString(CronJobRunsResponse.serializer(), runsJson)
        coEvery { api.getCronJobRuns("job1", CronJobRunsViewModel.RUN_LIMIT) } returns Response.success(response)
        val vm = CronJobRunsViewModel()

        vm.load("job1")
        assertTrue(vm.uiState.value.isLoading)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("job1", state.jobId)
        assertEquals(2, state.runs.size)
        assertFalse(state.isLoading)
    }

    @Test
    fun `another job starts empty and a failure is reported`() {
        val response = OkHttpProvider.json.decodeFromString(CronJobRunsResponse.serializer(), runsJson)
        coEvery { api.getCronJobRuns("job1", any()) } returns Response.success(response)
        coEvery { api.getCronJobRuns("job2", any()) } returns Response.error(404, "{}".toResponseBody())
        val vm = CronJobRunsViewModel()
        vm.load("job1")
        dispatcher.scheduler.advanceUntilIdle()

        vm.load("job2")
        assertEquals(emptyList<Any>(), vm.uiState.value.runs)
        dispatcher.scheduler.advanceUntilIdle()

        val error = vm.uiState.value.error as UiText.Resource
        assertEquals(R.string.cron_runs_load_failed, error.id)
    }
}
