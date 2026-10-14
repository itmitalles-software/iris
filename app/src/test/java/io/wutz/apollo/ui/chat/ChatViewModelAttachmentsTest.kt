package io.wutz.apollo.ui.chat

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import io.mockk.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.local.HermesDatabase
import io.wutz.apollo.data.model.Attachment
import io.wutz.apollo.data.model.AttachmentSource
import io.wutz.apollo.data.model.AudioTranscriptionResponse
import io.wutz.apollo.data.model.BusySendMode
import io.wutz.apollo.data.model.PaginationInfo
import io.wutz.apollo.data.model.SessionMessage
import io.wutz.apollo.data.model.SessionMessagesAroundPagination
import io.wutz.apollo.data.model.SessionMessagesAroundResponse
import io.wutz.apollo.data.model.SessionMessagesResponse
import io.wutz.apollo.data.model.SessionTimelineEntry
import io.wutz.apollo.data.model.SessionTimelinePagination
import io.wutz.apollo.data.model.SessionTimelineResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.GatewayFile
import io.wutz.apollo.data.remote.GatewayFileClient
import io.wutz.apollo.data.remote.GatewayFileResult
import io.wutz.apollo.data.session.ActiveSessionHolder
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.data.ws.ConnectionOperationParser
import io.wutz.apollo.data.ws.ConnectionStatus
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.JsonRpcError
import io.wutz.apollo.data.ws.ModelCatalogStore
import io.wutz.apollo.data.ws.WsEvent
import io.wutz.apollo.data.ws.WsMethods
import io.wutz.apollo.data.ws.contract.ConfigSetParams
import io.wutz.apollo.data.ws.contract.ProcessStopParams
import io.wutz.apollo.data.ws.contract.ProcessStopResult
import io.wutz.apollo.data.ws.contract.RpcMethods
import io.wutz.apollo.data.ws.contract.SubagentInterruptResult
import io.wutz.apollo.data.ws.contract.SubagentSteerParams
import io.wutz.apollo.data.ws.contract.SubagentSteerResult
import io.wutz.apollo.data.ws.contract.SubagentTailParams
import io.wutz.apollo.notification.TurnCorrelationTracker
import io.wutz.apollo.ui.chat.fakes.FakeChatPersistenceRepository
import io.wutz.apollo.ui.chat.fakes.FakeSlashUsageStore
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** ChatViewModel behaviour: Staging, pasting, opening and saving attachments. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelAttachmentsTest : ChatViewModelTestBase() {
    @Test
    fun testAddAttachment() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment(
                uri = "content://dummy/1",
                name = "dummy.txt",
                mimeType = "text/plain",
                size = 1024L,
            )
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("content://dummy/1", pending[0].uri)
            assertEquals("dummy.txt", pending[0].name)
        }

    @Test
    fun addAttachments_keepsEverySelectedImageInOrder() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            val selected =
                listOf(
                    Attachment("content://image/1", "one.jpg", "image/jpeg", 100L),
                    Attachment("content://image/2", "two.png", "image/png", 200L),
                    Attachment("content://image/3", "three.webp", "image/webp", 300L),
                )

            viewModel.addAttachments(selected)
            advanceUntilIdle()

            assertEquals(selected, viewModel.uiState.value.pendingAttachments)
        }

    @Test
    fun testRemoveAttachment_validIndex() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            viewModel.addAttachment("uri2", "file2.txt", "text/plain", 200)
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            viewModel.removeAttachment(0)
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri2", pending[0].uri)
        }

    @Test
    fun testRemoveAttachment_invalidIndex() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.pendingAttachments.size)

            // Out of bounds index should not crash or change list
            viewModel.removeAttachment(5)
            viewModel.removeAttachment(-1)
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri1", pending[0].uri)
        }

    @Test
    fun testRemoveAttachment_mixedValidAndInvalidSequence() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()
            addDummyAttachments(viewModel, 3) // [uri0, uri1, uri2]

            // 1. Remove a valid index (the middle item) → list shrinks correctly.
            viewModel.removeAttachment(1)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)
            assertEquals(
                "uri0",
                viewModel.uiState.value.pendingAttachments[0]
                    .uri,
            )
            assertEquals(
                "uri2",
                viewModel.uiState.value.pendingAttachments[1]
                    .uri,
            )

            // 2. Fire invalid removals (out of bounds + negative) — must be no-ops.
            viewModel.removeAttachment(99)
            viewModel.removeAttachment(-1)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            // 3. Another valid removal on the shifted list → still consistent.
            viewModel.removeAttachment(1)
            advanceUntilIdle()
            val pending = viewModel.uiState.value.pendingAttachments
            assertEquals(1, pending.size)
            assertEquals("uri0", pending[0].uri)
        }

    @Test
    fun testClearAttachments() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.addAttachment("uri1", "file1.txt", "text/plain", 100)
            viewModel.addAttachment("uri2", "file2.txt", "text/plain", 200)
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.pendingAttachments.size)

            viewModel.clearAttachments()
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingAttachments
            assertTrue(pending.isEmpty())
        }

    // ── Attachment open (issue #724) ─────────────────────────────────────

    @Test
    fun `openAttachment GATEWAY success fires ACTION_VIEW with FileProvider uri`() =
        runTest {
            val cacheDir =
                java.io.File(System.getProperty("java.io.tmpdir"), "hermes_open_test_${System.nanoTime()}")
            cacheDir.mkdirs()
            every { app.cacheDir } returns cacheDir

            mockkObject(GatewayFileClient)
            val file = java.io.File(cacheDir, "note.txt").apply { writeBytes("hello".toByteArray()) }
            coEvery {
                GatewayFileClient.fetch(any(), any())
            } returns GatewayFileResult.Success(GatewayFile("note.txt", "text/plain", file))

            val intentSlot = slot<Intent>()
            // android.jar stubs Intent ctor/setters to throw "not mocked" in unit tests;
            // mock the Intent constructor so openWithView can build + deliver it,
            // and capture the constructed instance to assert on its setters.
            mockkConstructor(Intent::class)
            every { anyConstructed<Intent>().setDataAndType(any(), any()) } answers { self as Intent }
            every { anyConstructed<Intent>().addFlags(any()) } answers { self as Intent }
            mockkStatic(FileProvider::class)
            every {
                FileProvider.getUriForFile(any(), any(), any())
            } returns mockk(relaxed = true)
            every { app.getApplicationContext() } returns app
            every { app.applicationContext } returns app
            every { app.startActivity(capture(intentSlot)) } returns Unit

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "note.txt",
                    mimeType = "text/plain",
                    gatewayUrl = "https://gw/api/files/download?path=%2Ftmp%2Fnote.txt&token=t",
                    source = AttachmentSource.GATEWAY,
                )

            vm.openAttachment(attachment)
            advanceUntilIdle()

            // fetch was invoked (proves we entered the IO launch)
            coVerify { GatewayFileClient.fetch(any(), any()) }
            // ACTION_VIEW intent delivered, with the right type + grant flag.
            verify { app.startActivity(any()) }
            verify { intentSlot.captured.setDataAndType(any(), eq("text/plain")) }
            verify { intentSlot.captured.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            assertNull(vm.uiState.value.openError)
            cacheDir.deleteRecursively()
        }

    @Test
    fun `saveAttachment writes gateway file to selected document`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val file =
                java.io
                    .File(System.getProperty("java.io.tmpdir"), "note_${System.nanoTime()}.txt")
                    .apply { writeText("downloaded") }
            coEvery { GatewayFileClient.fetch("/tmp/note.txt", any()) } returns
                GatewayFileResult.Success(GatewayFile("note.txt", "text/plain", file))
            // copyChunked is mocked with the object — restore real copy so the
            // saved document actually receives the bytes.
            coEvery { GatewayFileClient.copyChunked(any(), any()) } coAnswers {
                firstArg<java.io.InputStream>().copyTo(secondArg<java.io.OutputStream>())
            }
            val resolver = mockk<android.content.ContentResolver>()
            val output = java.io.ByteArrayOutputStream()
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            every { resolver.openOutputStream(destination, "wt") } returns output

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "note.txt",
                    mimeType = "text/plain",
                    gatewayUrl = "https://gw/api/files/download?path=%2Ftmp%2Fnote.txt&token=t",
                    source = AttachmentSource.GATEWAY,
                )

            vm.saveAttachment(attachment, destination)
            advanceUntilIdle()

            assertArrayEquals("downloaded".toByteArray(), output.toByteArray())
            assertNull(vm.uiState.value.savingAttachmentPath)
            assertEquals(
                UiText.res(io.wutz.apollo.R.string.chat_msg_file_saved, "note.txt"),
                vm.uiState.value.openError,
            )
        }

    @Test
    fun `saveAttachment never deletes the selected document when download fails`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            coEvery {
                GatewayFileClient.fetch("/tmp/missing.pdf", any())
            } returns GatewayFileResult.NotFound

            val (viewModel, _) = createViewModelWithSession()
            viewModel.saveAttachment(
                attachment =
                    Attachment(
                        uri = "gateway:/tmp/missing.pdf",
                        name = "missing.pdf",
                        mimeType = "application/pdf",
                        size = 0,
                        source = AttachmentSource.GATEWAY,
                        gatewayUrl = "https://host/files/download?path=%2Ftmp%2Fmissing.pdf",
                    ),
                destination = destination,
            )
            advanceUntilIdle()

            verify(exactly = 0) { resolver.delete(any(), any(), any()) }
            assertNull(viewModel.uiState.value.savingAttachmentPath)
        }

    @Test
    fun `saveAttachment never deletes the selected document when writing fails`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            val destination = mockk<android.net.Uri>()
            every { app.contentResolver } returns resolver
            every { resolver.openOutputStream(destination, "wt") } throws IllegalStateException("write failed")
            coEvery { GatewayFileClient.fetch("/tmp/report.pdf", any()) } returns
                GatewayFileResult.Success(
                    GatewayFile(
                        "report.pdf",
                        "application/pdf",
                        java.io
                            .File(System.getProperty("java.io.tmpdir"), "report_${System.nanoTime()}.pdf")
                            .apply { writeBytes(byteArrayOf(1)) },
                    ),
                )

            val viewModel = createViewModel()
            viewModel.saveAttachment(
                Attachment(
                    uri = "gateway:/tmp/report.pdf",
                    name = "report.pdf",
                    mimeType = "application/pdf",
                    source = AttachmentSource.GATEWAY,
                ),
                destination,
            )
            advanceUntilIdle()

            verify(exactly = 0) { resolver.delete(any(), any(), any()) }
            assertNull(viewModel.uiState.value.savingAttachmentPath)
            assertTrue(
                viewModel.uiState.value.openError
                    ?.en()
                    .orEmpty()
                    .startsWith("Could not save"),
            )
        }

    @Test
    fun `saveAttachment ignores overlapping saves`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_save_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val firstResult = CompletableDeferred<GatewayFileResult>()
            val fetchedPaths = mutableListOf<String>()
            coEvery { GatewayFileClient.fetch(capture(fetchedPaths), any()) } coAnswers { firstResult.await() }
            val resolver = mockk<android.content.ContentResolver>(relaxed = true)
            every { app.contentResolver } returns resolver
            val viewModel = createViewModel()

            viewModel.saveAttachment(
                Attachment("gateway:/tmp/first.pdf", "first.pdf", "application/pdf", source = AttachmentSource.GATEWAY),
                mockk(),
            )
            runCurrent()
            viewModel.saveAttachment(
                Attachment(
                    "gateway:/tmp/second.pdf",
                    "second.pdf",
                    "application/pdf",
                    source = AttachmentSource.GATEWAY,
                ),
                mockk(),
            )

            assertEquals("/tmp/first.pdf", viewModel.uiState.value.savingAttachmentPath)
            assertEquals(listOf("/tmp/first.pdf"), fetchedPaths)

            firstResult.complete(GatewayFileResult.NotFound)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.savingAttachmentPath)
        }

    @Test
    fun `openAttachment GATEWAY not-found surfaces openError`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_open_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            coEvery {
                GatewayFileClient.fetch(any(), any())
            } returns GatewayFileResult.NotFound

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "unused",
                    name = "missing.pdf",
                    mimeType = "application/pdf",
                    gatewayUrl = "https://gw/api/files/download?path=%2Ftmp%2Fmissing.pdf&token=t",
                    source = AttachmentSource.GATEWAY,
                )

            vm.openAttachment(attachment)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.openError)
            assertTrue(
                vm.uiState.value.openError!!
                    .en()
                    .contains("missing.pdf"),
            )
        }

    @Test
    fun `openAttachment shows opening state while fetch is in flight and clears it after`() =
        runTest {
            mockkObject(GatewayFileClient)
            val cacheDir =
                java.io
                    .File(
                        System.getProperty("java.io.tmpdir"),
                        "gw_open_${System.nanoTime()}",
                    ).apply { mkdirs() }
            every { app.cacheDir } returns cacheDir
            every { app.applicationContext } returns app
            every { app.getApplicationContext() } returns app
            val firstResult = CompletableDeferred<GatewayFileResult>()
            val fetchedPaths = mutableListOf<String>()
            coEvery { GatewayFileClient.fetch(capture(fetchedPaths), any()) } coAnswers { firstResult.await() }

            val vm = createViewModel()
            val attachment =
                Attachment(
                    uri = "gateway:/tmp/big.pdf",
                    name = "big.pdf",
                    mimeType = "application/pdf",
                    source = AttachmentSource.GATEWAY,
                )

            vm.openAttachment(attachment)
            runCurrent()

            // Indicator visible while the download is in flight…
            assertEquals(listOf("/tmp/big.pdf"), fetchedPaths)
            assertEquals("/tmp/big.pdf", vm.uiState.value.openingAttachmentPath)

            firstResult.complete(GatewayFileResult.NotFound)
            advanceUntilIdle()

            // …and cleared in all outcomes.
            assertNull(vm.uiState.value.openingAttachmentPath)
            assertTrue(
                vm.uiState.value.openError
                    ?.en()
                    .orEmpty()
                    .contains("big.pdf"),
            )
        }

    @Test
    fun `session creation failure clears agent typing and surfaces error for queued prompt`() =
        runTest {
            val vm = createViewModel()
            advanceUntilIdle()

            mockConnectionStatus.value = ConnectionStatus.CONNECTED
            mockEventsFlow.emit(WsEvent.GatewayReady(null))
            advanceUntilIdle()

            vm.sendMessage("Prompt before failure")
            advanceUntilIdle()

            assertTrue(vm.uiState.value.isSending)

            val createReqId = sentRequestMethods.last { it.first == WsMethods.SESSION_CREATE }.second
            // Emit RPC error for SESSION_CREATE
            mockEventsFlow.emit(WsEvent.RpcError(createReqId, JsonRpcError(code = 500, message = "Backend exploded")))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isAgentTyping)
            assertNotNull(vm.uiState.value.errorMessage)
            assertTrue(
                vm.uiState.value.errorMessage!!
                    .en()
                    .contains("Backend exploded"),
            )
        }

    // ── Attachments ──────────────────────────────────────────────────────────

    /** Add [count] dummy attachments so a test starts with a populated list. */
    private fun TestScope.addDummyAttachments(
        viewModel: ChatViewModel,
        count: Int,
    ) {
        repeat(count) { i ->
            viewModel.addAttachment("uri$i", "file$i.txt", "text/plain", (i + 1) * 100L)
        }
        advanceUntilIdle()
    }
}
