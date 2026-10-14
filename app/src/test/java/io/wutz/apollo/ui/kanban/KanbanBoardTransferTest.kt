package io.wutz.apollo.ui.kanban

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.wutz.apollo.R
import io.wutz.apollo.data.model.BoardExportResult
import io.wutz.apollo.data.model.BoardImportResult
import io.wutz.apollo.data.model.ImportBoardBody
import io.wutz.apollo.data.model.KanbanBoard
import io.wutz.apollo.data.model.ManagedFileActionResponse
import io.wutz.apollo.data.model.ManagedFileDelete
import io.wutz.apollo.data.remote.GatewayFile
import io.wutz.apollo.data.remote.GatewayFileResult
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.data.remote.KanbanApiService
import io.wutz.apollo.data.remote.NetworkError
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.repository.KanbanRepository
import io.wutz.apollo.data.repository.KanbanRepositoryImpl
import io.wutz.apollo.data.ws.KanbanEventsClient
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Kanban board export/import I/O, owned by [KanbanViewModel] instead of the screen (code-health H9). */
@OptIn(ExperimentalCoroutinesApi::class)
class KanbanBoardTransferTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockRepository = mockk<KanbanRepository>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val source = mockk<Uri>(relaxed = true)
    private val createdViewModels = mutableListOf<KanbanViewModel>()
    private val tempDir: File = Files.createTempDirectory("kanban-transfer-test").toFile()
    private var exportDownload: GatewayFileResult = GatewayFileResult.Failure(IOException("not stubbed"))

    private fun createViewModel(repository: KanbanRepository = mockRepository): KanbanViewModel =
        KanbanViewModel(
            repository = repository,
            eventsClientProvider = { mockk<KanbanEventsClient>(relaxed = true) },
            endpointProvider = { "http://127.0.0.1:9119" },
            exportDownloader = { _, _ -> exportDownload },
        ).also {
            it.ioDispatcher = testDispatcher
            createdViewModels += it
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // No display name or size from the picker: the defaults apply.
        every { contentResolver.query(any(), any(), any(), any(), any()) } returns null
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    @Test
    fun `import uploads the archive, imports it and removes the upload`() =
        runTest(testDispatcher) {
            coEvery { mockRepository.uploadImportArchive(any(), any(), any()) } returns
                NetworkResult.Success("srv/kanban-imports/board.tar.gz")
            coEvery { mockRepository.importBoard(any()) } returns
                NetworkResult.Success(BoardImportResult(board = "ops", name = "Ops"))
            coEvery { mockRepository.deleteImportArchive(any()) } returns
                NetworkResult.Success(ManagedFileActionResponse(ok = true))

            val vm = createViewModel()
            vm.importBoardFrom(contentResolver, source)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify { mockRepository.uploadImportArchive("kanban-imports/board.tar.gz", "board.tar.gz", any()) }
            coVerify { mockRepository.importBoard(ImportBoardBody(archive = "srv/kanban-imports/board.tar.gz")) }
            coVerify { mockRepository.deleteImportArchive("srv/kanban-imports/board.tar.gz") }
            assertEquals(UiText.res(R.string.kanban_import_success, "Ops"), vm.uiState.value.toastMessage)
        }

    @Test
    fun `failed import still removes the uploaded archive`() =
        runTest(testDispatcher) {
            coEvery { mockRepository.uploadImportArchive(any(), any(), any()) } returns
                NetworkResult.Success("kanban-imports/board.tar.gz")
            coEvery { mockRepository.importBoard(any()) } returns
                NetworkResult.Failure(NetworkError.Http(422, "bad archive"))
            coEvery { mockRepository.deleteImportArchive(any()) } returns
                NetworkResult.Success(ManagedFileActionResponse(ok = true))

            val vm = createViewModel()
            vm.importBoardFrom(contentResolver, source)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 1) { mockRepository.deleteImportArchive("kanban-imports/board.tar.gz") }
            assertEquals(UiText.res(R.string.kanban_import_failed, "bad archive"), vm.uiState.value.toastMessage)
        }

    @Test
    fun `failed upload neither imports nor cleans up`() =
        runTest(testDispatcher) {
            coEvery { mockRepository.uploadImportArchive(any(), any(), any()) } returns
                NetworkResult.Failure(NetworkError.Http(413, "too large"))

            val vm = createViewModel()
            vm.importBoardFrom(contentResolver, source)
            testDispatcher.scheduler.advanceUntilIdle()

            coVerify(exactly = 0) { mockRepository.importBoard(any()) }
            coVerify(exactly = 0) { mockRepository.deleteImportArchive(any()) }
            assertEquals(UiText.res(R.string.kanban_import_upload_failed, "too large"), vm.uiState.value.toastMessage)
        }

    @Test
    fun `failed cleanup is reported with its HTTP status`() =
        runTest(testDispatcher) {
            coEvery { mockRepository.uploadImportArchive(any(), any(), any()) } returns
                NetworkResult.Success("kanban-imports/board.tar.gz")
            coEvery { mockRepository.importBoard(any()) } returns
                NetworkResult.Success(BoardImportResult(board = "ops", name = "Ops"))
            coEvery { mockRepository.deleteImportArchive(any()) } returns
                NetworkResult.Failure(NetworkError.Http(500, "boom"))

            val vm = createViewModel()
            vm.importBoardFrom(contentResolver, source)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                UiText.res(
                    R.string.kanban_import_cleanup_failed,
                    UiText.res(R.string.kanban_msg_archive_cleanup_failed_http, 500),
                ),
                vm.uiState.value.toastMessage,
            )
        }

    @Test
    fun `repository upload streams to the managed files endpoint and returns the stored path`() =
        runTest(testDispatcher) {
            val filesApi = mockk<HermesApiService>()
            coEvery { filesApi.uploadManagedFileStream(any(), any(), any()) } returns
                Response.success(ManagedFileActionResponse(ok = true, path = "/srv/kanban-imports/b.tar.gz"))
            coEvery { filesApi.deleteManagedFile(any()) } returns Response.error(404, "".toResponseBody())
            val repository =
                KanbanRepositoryImpl(
                    apiProvider = { mockk<KanbanApiService>(relaxed = true) },
                    ioDispatcher = testDispatcher,
                    filesApiProvider = { filesApi },
                )

            val upload = repository.uploadImportArchive("kanban-imports/b.tar.gz", "b.tar.gz", "x".toRequestBody())
            val cleanup = repository.deleteImportArchive("/srv/kanban-imports/b.tar.gz")

            assertEquals(NetworkResult.Success("/srv/kanban-imports/b.tar.gz"), upload)
            assertEquals(404, ((cleanup as NetworkResult.Failure).error as NetworkError.Http).code)
            coVerify { filesApi.deleteManagedFile(ManagedFileDelete("/srv/kanban-imports/b.tar.gz")) }
        }

    @Test
    fun `export downloads the archive and saves it to the picked destination`() =
        runTest(testDispatcher) {
            val cached = File(tempDir, "board.tar.gz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            coEvery { mockRepository.exportBoard("ops", any()) } returns
                NetworkResult.Success(BoardExportResult(board = "ops", archive = "/exports/ops.tar.gz"))
            exportDownload = GatewayFileResult.Success(GatewayFile("board.tar.gz", "application/gzip", cached))
            val destination = ByteArrayOutputStream()
            every { contentResolver.openOutputStream(source) } returns destination

            val vm = createViewModel()
            val prepared = vm.prepareBoardExport(KanbanBoard(id = "ops", name = "Ops"), tempDir)
            vm.saveBoardExport(contentResolver, prepared?.absolutePath, source)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(cached, prepared)
            assertArrayEquals(byteArrayOf(1, 2, 3), destination.toByteArray())
            assertEquals(UiText.res(R.string.kanban_export_success), vm.uiState.value.toastMessage)
        }

    @Test
    fun `export failures are reported without a destination prompt`() =
        runTest(testDispatcher) {
            coEvery { mockRepository.exportBoard("ops", any()) } returns
                NetworkResult.Failure(NetworkError.Http(500, "export broke"))

            val vm = createViewModel()
            val prepared = vm.prepareBoardExport(KanbanBoard(id = "ops", name = "Ops"), tempDir)

            assertNull(prepared)
            assertEquals(UiText.res(R.string.kanban_export_failed, "export broke"), vm.uiState.value.toastMessage)
        }

    @Test
    fun `saving a missing cached export reports it`() =
        runTest(testDispatcher) {
            val vm = createViewModel()
            vm.saveBoardExport(contentResolver, File(tempDir, "gone.tar.gz").absolutePath, source)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                UiText.res(R.string.kanban_export_save_failed, UiText.res(R.string.kanban_msg_cached_export_missing)),
                vm.uiState.value.toastMessage,
            )
        }
}
