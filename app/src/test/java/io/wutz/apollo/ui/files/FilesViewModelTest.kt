package io.wutz.apollo.ui.files

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.model.ManagedFileActionResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.HermesApiService
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MultipartBody
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

/** Review U14/U15: uploads stream from the picked document, use its display name and never overwrite silently. */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val uri = mockk<Uri>(relaxed = true)
    private val paths = mutableListOf<RequestBody>()
    private val overwrites = mutableListOf<RequestBody>()
    private val parts = mutableListOf<MultipartBody.Part>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
        mockkObject(AuthManager)
        every { AuthManager.currentDataScope() } returns null

        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 1
        every { cursor.isNull(any()) } returns false
        every { cursor.getString(0) } returns "report.pdf"
        every { cursor.getLong(1) } returns 42L
        every { resolver.query(uri, any(), any(), any(), any()) } returns cursor
        every { resolver.getType(uri) } returns "application/pdf"
        every { uri.lastPathSegment } returns "msf:1000123"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun settle() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun RequestBody.text(): String = Buffer().also { writeTo(it) }.readUtf8()

    private fun stubUpload(vararg responses: Response<ManagedFileActionResponse>) {
        coEvery {
            mockApi.uploadManagedFileStream(capture(paths), capture(overwrites), capture(parts))
        } returnsMany responses.toList()
    }

    private fun conflict(): Response<ManagedFileActionResponse> =
        Response.error(409, """{"detail":"File already exists"}""".toResponseBody(null))

    @Test
    fun `upload uses the display name and does not overwrite an existing file`() {
        stubUpload(Response.success(ManagedFileActionResponse(ok = true)))
        val vm = FilesViewModel(ioDispatcher = testDispatcher)

        vm.uploadFile(resolver, uri)
        settle()

        assertEquals("report.pdf", paths.single().text())
        assertEquals("false", overwrites.single().text())
        val disposition =
            parts
                .single()
                .headers
                ?.get("Content-Disposition")
                .orEmpty()
        assertTrue(disposition, disposition.contains("filename=\"report.pdf\""))
        assertEquals(42L, parts.single().body.contentLength())
        assertEquals(UiText.res(R.string.files_msg_file_uploaded), vm.uiState.value.toastMessage)
        assertFalse(vm.uiState.value.isUploading)
    }

    @Test
    fun `a name conflict asks before replacing and replaces only after confirmation`() {
        stubUpload(conflict(), Response.success(ManagedFileActionResponse(ok = true)))
        val vm = FilesViewModel(ioDispatcher = testDispatcher)

        vm.uploadFile(resolver, uri)
        settle()

        assertEquals(
            "report.pdf",
            vm.uiState.value.uploadConflict
                ?.fileName,
        )
        assertFalse(vm.uiState.value.isUploading)
        assertNull(vm.uiState.value.toastMessage)

        vm.confirmUploadOverwrite()
        settle()

        assertEquals(listOf("false", "true"), overwrites.map { it.text() })
        assertEquals(listOf("report.pdf", "report.pdf"), paths.map { it.text() })
        assertNull(vm.uiState.value.uploadConflict)
        assertEquals(UiText.res(R.string.files_msg_file_uploaded), vm.uiState.value.toastMessage)
    }

    @Test
    fun `dismissing the conflict keeps the existing file`() {
        stubUpload(conflict())
        val vm = FilesViewModel(ioDispatcher = testDispatcher)

        vm.uploadFile(resolver, uri)
        settle()
        vm.dismissUploadConflict()
        settle()

        assertNull(vm.uiState.value.uploadConflict)
        coVerify(exactly = 1) { mockApi.uploadManagedFileStream(any(), any(), any()) }
    }

    @Test
    fun `unreadable document reports a read failure without uploading`() {
        every { resolver.query(uri, any(), any(), any(), any()) } throws SecurityException("no access")
        val vm = FilesViewModel(ioDispatcher = testDispatcher)

        vm.uploadFile(resolver, uri)
        settle()

        assertEquals(UiText.res(R.string.file_read_failed_reason, "no access"), vm.uiState.value.toastMessage)
        assertFalse(vm.uiState.value.isUploading)
        coVerify(exactly = 0) { mockApi.uploadManagedFileStream(any(), any(), any()) }
    }

    @Test
    fun `uploadFileName prefers the display name and never returns a path`() {
        assertEquals("report.pdf", uploadFileName("report.pdf", "msf:1000123"))
        assertEquals("msf:1000123", uploadFileName(null, "msf:1000123"))
        assertEquals("b.txt", uploadFileName("  ", "a/b.txt"))
        assertEquals("evil", uploadFileName("../evil", null))
        assertEquals("upload.bin", uploadFileName("..", null))
        assertEquals("upload.bin", uploadFileName(null, null))
    }
}
