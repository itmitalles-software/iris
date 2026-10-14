package io.wutz.apollo.ui.sessions.transfer

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.SessionImportResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTransferViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var api: HermesApiService
    private val labels = TranscriptLabels("Untitled", "You", "Assistant", "image", "Shortened.")
    private val exportJson =
        """{"id":"s1","title":"Trip","messages":[{"role":"user","content":"Hi","active":1}]}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient, AuthManager)
        api = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns api
        every { AuthManager.activeProfileId } returns MutableStateFlow("work")
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun exportResponse() = Response.success(exportJson.toResponseBody("application/json".toMediaType()))

    @Test
    fun `share turns the export into a markdown transcript`() =
        runTest(dispatcher) {
            coEvery { api.exportSession("s1") } returns exportResponse()
            val vm = SessionTransferViewModel(ioDispatcher = dispatcher)

            vm.share("s1", labels)
            dispatcher.scheduler.advanceUntilIdle()

            val shared = vm.uiState.value.pendingShare!!
            assertEquals("Trip", shared.title)
            assertTrue(shared.text.contains("**You:**\n\nHi"))
            assertFalse(vm.uiState.value.isBusy)
            vm.consumeShare()
            assertNull(vm.uiState.value.pendingShare)
        }

    @Test
    fun `share failure surfaces a localized message`() =
        runTest(dispatcher) {
            coEvery { api.exportSession("s1") } returns Response.error(404, "{}".toResponseBody())
            val vm = SessionTransferViewModel(ioDispatcher = dispatcher)

            vm.share("s1", labels)
            dispatcher.scheduler.advanceUntilIdle()

            val toast = vm.uiState.value.toastMessage as UiText.Resource
            assertEquals(R.string.session_transfer_share_failed, toast.id)
            assertNull(vm.uiState.value.pendingShare)
        }

    @Test
    fun `export streams the server json unchanged into the document`() =
        runTest(dispatcher) {
            coEvery { api.exportSession("s1") } returns exportResponse()
            val vm = SessionTransferViewModel(ioDispatcher = dispatcher)
            val out = ByteArrayOutputStream()

            vm.writeExport("s1") { out }

            assertEquals(exportJson, out.toString(Charsets.UTF_8.name()))
            assertEquals(UiText.res(R.string.session_transfer_export_done), vm.uiState.value.toastMessage)
        }

    @Test
    fun `export without a writable document fails`() =
        runTest(dispatcher) {
            coEvery { api.exportSession("s1") } returns exportResponse()
            val vm = SessionTransferViewModel(ioDispatcher = dispatcher)

            vm.writeExport("s1") { null }

            assertEquals(
                UiText.res(R.string.session_transfer_export_failed, ""),
                vm.uiState.value.toastMessage,
            )
        }

    @Test
    fun `import posts the sessions with the active profile in the body`() =
        runTest(dispatcher) {
            val body = slot<RequestBody>()
            coEvery { api.importSessions(capture(body)) } returns
                Response.success(SessionImportResponse(ok = true, imported = 1, importedIds = listOf("s1")))
            val vm = SessionTransferViewModel(ioDispatcher = dispatcher)

            vm.importText(exportJson)

            val sent = Json.parseToJsonElement(Buffer().also { body.captured.writeTo(it) }.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("work"), sent["profile"])
            assertEquals(1, sent["sessions"]!!.jsonArray.size)
            assertEquals(UiText.plural(R.plurals.session_transfer_imported, 1), vm.uiState.value.toastMessage)
            assertEquals(1, vm.uiState.value.importGeneration)
        }

    @Test
    fun `import of sessions that already exist does not reload the list`() =
        runTest(dispatcher) {
            coEvery { api.importSessions(any()) } returns
                Response.success(SessionImportResponse(ok = true, imported = 0, skipped = 2))
            val vm = SessionTransferViewModel(ioDispatcher = dispatcher)

            vm.importText("""[{"id":"a"},{"id":"b"}]""")

            assertEquals(
                UiText.plural(R.plurals.session_transfer_import_all_present, 2),
                vm.uiState.value.toastMessage,
            )
            assertEquals(0, vm.uiState.value.importGeneration)
        }

    @Test
    fun `import of a non json file never reaches the server`() =
        runTest(dispatcher) {
            val vm = SessionTransferViewModel(ioDispatcher = dispatcher)

            vm.importText("hello world")

            assertEquals(UiText.res(R.string.session_transfer_import_not_json), vm.uiState.value.toastMessage)
            coVerify(exactly = 0) { api.importSessions(any()) }
        }

    @Test
    fun `files above the server limit are refused while reading`() {
        val big = ByteArray((SessionTransferFormat.MAX_IMPORT_BYTES + 1).toInt())
        assertEquals(
            SessionTransferViewModel.TOO_LARGE,
            SessionTransferViewModel.readBounded(ByteArrayInputStream(big)),
        )
        assertEquals("{}", SessionTransferViewModel.readBounded(ByteArrayInputStream("{}".toByteArray())))
    }
}
