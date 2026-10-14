package io.wutz.apollo.ui.sessions.transfer

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A Markdown transcript ready for the share sheet. */
data class SharedTranscript(
    val title: String,
    val text: String,
)

data class SessionTransferUiState(
    val isBusy: Boolean = false,
    val toastMessage: UiText? = null,
    val pendingShare: SharedTranscript? = null,
    /** Increments after every import that added sessions, so the history list reloads. */
    val importGeneration: Int = 0,
)

/**
 * Conversation transfer from the history screen: share a session as Markdown text, save its
 * lossless JSON export to a user-picked document, and import exported sessions.
 * File I/O runs on [ioDispatcher]; the jobs live in [viewModelScope] so they finish after the
 * picker returns even when the screen recomposes.
 */
class SessionTransferViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(SessionTransferUiState())
    val uiState: StateFlow<SessionTransferUiState> = _uiState.asStateFlow()

    private val exportJson = Json { ignoreUnknownKeys = true }

    /** Loads the export of [sessionId] and turns it into a Markdown transcript for the share sheet. */
    fun share(
        sessionId: String,
        labels: TranscriptLabels,
    ) {
        if (!begin()) return
        viewModelScope.launch {
            val text =
                fetchExport(sessionId) { body ->
                    val export = exportJson.parseToJsonElement(body.string()) as? JsonObject
                    export?.let {
                        val title =
                            (it["title"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                                ?: labels.untitled
                        SharedTranscript(title = title, text = SessionTransferFormat.toMarkdown(it, labels))
                    }
                }
            when (text) {
                is TransferOutcome.Done -> {
                    _uiState.update { it.copy(isBusy = false, pendingShare = text.value) }
                }

                is TransferOutcome.Failed -> {
                    finish(UiText.res(R.string.session_transfer_share_failed, text.reason))
                }
            }
        }
    }

    fun consumeShare() {
        _uiState.update { it.copy(pendingShare = null) }
    }

    /** Streams the JSON export of [sessionId] into [destination] (a document picked by the user). */
    fun exportTo(
        contentResolver: ContentResolver,
        sessionId: String,
        destination: Uri,
    ) {
        if (!begin()) return
        viewModelScope.launch {
            writeExport(sessionId) { contentResolver.openOutputStream(destination, "wt") }
        }
    }

    internal suspend fun writeExport(
        sessionId: String,
        openOutput: () -> OutputStream?,
    ) {
        val outcome =
            fetchExport(sessionId) { body ->
                val output = openOutput() ?: return@fetchExport null
                output.use { out -> body.byteStream().use { it.copyTo(out) } }
                Unit
            }
        finish(
            when (outcome) {
                is TransferOutcome.Done -> UiText.res(R.string.session_transfer_export_done)
                is TransferOutcome.Failed -> UiText.res(R.string.session_transfer_export_failed, outcome.reason)
            },
        )
    }

    /** Imports the sessions in [source] (an export file picked by the user). */
    fun importFrom(
        contentResolver: ContentResolver,
        source: Uri,
    ) {
        if (!begin()) return
        viewModelScope.launch {
            val text =
                try {
                    withContext(ioDispatcher) {
                        contentResolver.openInputStream(source)?.use(::readBounded)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    finish(UiText.res(R.string.session_transfer_import_failed, e.message.orEmpty()))
                    return@launch
                }
            when (text) {
                null -> finish(UiText.res(R.string.session_transfer_import_failed, ""))
                TOO_LARGE -> finish(UiText.res(R.string.session_transfer_import_too_large))
                else -> importText(text)
            }
        }
    }

    internal suspend fun importText(text: String) {
        val sessions =
            withContext(ioDispatcher) { SessionTransferFormat.parseImportSessions(text) }.getOrElse { error ->
                val reason = (error as? ImportPayloadException)?.error
                finish(
                    if (reason == ImportPayloadError.NotJson) {
                        UiText.res(R.string.session_transfer_import_not_json)
                    } else {
                        UiText.res(R.string.session_transfer_import_no_sessions)
                    },
                )
                return
            }
        val body =
            SessionTransferFormat
                .importBody(sessions, AuthManager.activeProfileId.value)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
        when (val result = safeApiCall { ApiClient.hermesApi.importSessions(body) }) {
            is NetworkResult.Success -> {
                val data = result.data
                val message =
                    when {
                        data.imported == 0 && data.skipped > 0 -> {
                            UiText.plural(R.plurals.session_transfer_import_all_present, data.skipped)
                        }

                        data.skipped > 0 -> {
                            UiText.plural(
                                R.plurals.session_transfer_imported_with_skipped,
                                data.imported,
                                data.imported,
                                data.skipped,
                            )
                        }

                        else -> {
                            UiText.plural(R.plurals.session_transfer_imported, data.imported)
                        }
                    }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        toastMessage = message,
                        importGeneration = if (data.imported > 0) it.importGeneration + 1 else it.importGeneration,
                    )
                }
            }

            is NetworkResult.Failure -> {
                finish(UiText.res(R.string.session_transfer_import_failed, result.error.message))
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun begin(): Boolean {
        if (_uiState.value.isBusy) return false
        _uiState.update { it.copy(isBusy = true) }
        return true
    }

    private fun finish(message: UiText) {
        _uiState.update { it.copy(isBusy = false, toastMessage = message) }
    }

    /** Fetches the export and hands its body to [consume] on [ioDispatcher]; null from it is a failure. */
    private suspend fun <T> fetchExport(
        sessionId: String,
        consume: (ResponseBody) -> T?,
    ): TransferOutcome<T> =
        when (val result = safeApiCall { ApiClient.hermesApi.exportSession(sessionId) }) {
            is NetworkResult.Success -> {
                try {
                    withContext(ioDispatcher) { result.data.use(consume) }
                        ?.let { TransferOutcome.Done(it) }
                        ?: TransferOutcome.Failed("")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TransferOutcome.Failed(e.message.orEmpty())
                }
            }

            is NetworkResult.Failure -> {
                TransferOutcome.Failed(result.error.message)
            }
        }

    private sealed interface TransferOutcome<out T> {
        data class Done<T>(
            val value: T,
        ) : TransferOutcome<T>

        data class Failed(
            val reason: String,
        ) : TransferOutcome<Nothing>
    }

    internal companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Sentinel returned by [readBounded] for files above the server's import limit. */
        const val TOO_LARGE = "\u0000too-large"

        fun readBounded(input: InputStream): String {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > SessionTransferFormat.MAX_IMPORT_BYTES) return TOO_LARGE
                buffer.write(chunk, 0, read)
            }
            return buffer.toString(Charsets.UTF_8.name())
        }
    }
}
