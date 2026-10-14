package io.wutz.apollo.ui.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.ManagedDirectoryCreate
import io.wutz.apollo.data.model.ManagedFileActionResponse
import io.wutz.apollo.data.model.ManagedFileDelete
import io.wutz.apollo.data.model.ManagedFileEntry
import io.wutz.apollo.data.model.ManagedFilesListResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkError
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.safeLaunchLoad
import io.wutz.apollo.ui.common.safeLaunchSwrLoad
import io.wutz.apollo.util.StreamingUriRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

data class FilesUiState(
    val currentPath: String = "",
    val parentPath: String? = null,
    /** Breadcrumb parts: pair of (absolute path, display name). */
    val crumbs: List<Pair<String, String>> = emptyList(),
    val entries: List<ManagedFileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: UiText? = null,
    // ── Transient action state ──
    val busyPaths: Set<String> = emptySet(),
    val isCreatingDir: Boolean = false,
    val newDirName: String = "",
    val isUploading: Boolean = false,
    /** Upload the server refused because the file exists; shown as a replace confirmation. */
    val uploadConflict: UploadConflict? = null,
    val deleteTarget: ManagedFileEntry? = null,
    val openingPath: String? = null,
    val toastMessage: UiText? = null,
)

/**
 * An upload rejected with HTTP 409 because [fileName] already exists at [target]. [body] streams from the picked
 * document again when the user confirms replacing it.
 */
class UploadConflict(
    val fileName: String,
    val target: String,
    val mimeType: String,
    val body: RequestBody,
)

/** Decoded file ready to be written to disk and opened on-device. */
data class DownloadedFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadedFile) return false
        return name == other.name &&
            mimeType == other.mimeType &&
            (bytes?.contentEquals(other.bytes) == true)
    }

    override fun hashCode(): Int {
        var r = name.hashCode()
        r = 31 * r + mimeType.hashCode()
        r = 31 * r + (bytes?.contentHashCode() ?: 0)
        return r
    }
}

/** Map a successful [NetworkResult] to a new value; pass failures through. */
inline fun <T, R> NetworkResult<T>.map(transform: (T) -> R): NetworkResult<R> =
    when (this) {
        is NetworkResult.Success -> NetworkResult.Success(transform(this.data))
        is NetworkResult.Failure -> this
    }

class FilesViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private val filesCache = SwrCache<String, ManagedFilesListResponse>()

    /**
     * Reset state owned by the previous server/profile context (issue: PR #1192 cache scoping).
     * The current directory path belongs to the old server and must not leak into the new one.
     */
    fun clearScopeOwnedState() {
        _uiState.update {
            it.copy(
                currentPath = "",
                parentPath = null,
                crumbs = emptyList(),
                entries = emptyList(),
                isLoading = false,
                errorMessage = null,
                busyPaths = emptySet(),
                isCreatingDir = false,
                newDirName = "",
                isUploading = false,
                uploadConflict = null,
                deleteTarget = null,
                openingPath = null,
            )
        }
    }

    fun load(
        path: String? = null,
        forceRefresh: Boolean = false,
    ) {
        val cacheKey = path ?: ""
        safeLaunchSwrLoad(
            cache = filesCache,
            cacheKey = cacheKey,
            forceRefresh = forceRefresh,
            onCacheHit = { data ->
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        currentPath = data.path,
                        parentPath = data.parent,
                        crumbs = buildCrumbs(data.path),
                        entries = data.entries,
                        errorMessage = null,
                    )
                }
            },
            apiCall = {
                safeApiCall { ApiClient.hermesApi.listManagedFiles(path) }
            },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        currentPath = data.path,
                        parentPath = data.parent,
                        crumbs = buildCrumbs(data.path),
                        entries = data.entries,
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiText.res(R.string.files_msg_list_failed, errorMsg),
                    )
                }
            },
        )
    }

    fun refresh() = load(_uiState.value.currentPath.ifBlank { null }, forceRefresh = true)

    fun navigateTo(entry: ManagedFileEntry) {
        if (!entry.isDirectory) return
        load(entry.path)
    }

    fun navigateUp() {
        _uiState.value.parentPath?.let { load(it) }
    }

    fun navigateToCrumb(path: String) = load(path)

    // ── Create directory ──────────────────────────────────────────────────
    fun openCreateDir() {
        _uiState.update { it.copy(isCreatingDir = true, newDirName = "") }
    }

    fun dismissCreateDir() {
        _uiState.update { it.copy(isCreatingDir = false, newDirName = "") }
    }

    fun setNewDirName(name: String) {
        _uiState.update { it.copy(newDirName = name) }
    }

    fun createDir() {
        val name = _uiState.value.newDirName.trim()
        val base = _uiState.value.currentPath
        if (name.isBlank()) {
            _uiState.update { it.copy(toastMessage = UiText.res(R.string.files_msg_folder_name_required)) }
            return
        }
        if (name.contains("/")) {
            _uiState.update { it.copy(toastMessage = UiText.res(R.string.files_msg_folder_name_slash)) }
            return
        }
        val target = if (base.isBlank()) name else "$base/$name"
        _uiState.update { it.copy(isCreatingDir = false, newDirName = "", isLoading = true) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.createManagedDirectory(ManagedDirectoryCreate(target))
                    }
                }
            handleActionResult(result, R.string.files_msg_folder_created, R.string.files_msg_create_folder_failed)
        }
    }

    // ── Upload (streaming multipart) ──────────────────────────────────────

    /**
     * Uploads the document at [uri] into the current directory. Name and size come from the document's
     * [OpenableColumns] (resolved off the main thread) and the bytes stream straight from the
     * [contentResolver] into the request, so large files never sit in memory. An existing file is never
     * replaced silently: the server's 409 becomes [FilesUiState.uploadConflict] for the user to confirm.
     */
    fun uploadFile(
        contentResolver: ContentResolver,
        uri: Uri,
    ) {
        val base = _uiState.value.currentPath
        _uiState.update { it.copy(isUploading = true) }
        viewModelScope.launch {
            val picked =
                try {
                    withContext(ioDispatcher) { readPickedDocument(contentResolver, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isUploading = false,
                            toastMessage = UiText.res(R.string.file_read_failed_reason, e.message.orEmpty()),
                        )
                    }
                    return@launch
                }
            val target = if (base.isBlank()) picked.name else "$base/${picked.name}"
            val body =
                StreamingUriRequestBody(
                    contentResolver = contentResolver,
                    uri = uri,
                    contentType = picked.mimeType.toMediaTypeOrNull(),
                    contentLength = picked.size,
                )
            sendUpload(UploadConflict(picked.name, target, picked.mimeType, body), overwrite = false)
        }
    }

    /** Replace the existing file after the user confirmed [FilesUiState.uploadConflict]. */
    fun confirmUploadOverwrite() {
        val conflict = _uiState.value.uploadConflict ?: return
        _uiState.update { it.copy(uploadConflict = null, isUploading = true) }
        viewModelScope.launch { sendUpload(conflict, overwrite = true) }
    }

    fun dismissUploadConflict() {
        _uiState.update { it.copy(uploadConflict = null) }
    }

    private suspend fun sendUpload(
        upload: UploadConflict,
        overwrite: Boolean,
    ) {
        val pathBody = upload.target.toRequestBody("text/plain".toMediaTypeOrNull())
        val overwriteBody = overwrite.toString().toRequestBody("text/plain".toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("file", upload.fileName, upload.body)
        // Retrofit streams the body on OkHttp's worker; no withContext, so a confirmed response is not lost
        // to its prompt cancellation on the return hop (same as the Kanban import, #1137).
        val result =
            safeApiCall {
                ApiClient.hermesApi.uploadManagedFileStream(pathBody, overwriteBody, part)
            }
        if (!overwrite && (result as? NetworkResult.Failure)?.error.let { it is NetworkError.Http && it.code == 409 }) {
            _uiState.update { it.copy(isUploading = false, uploadConflict = upload) }
            return
        }
        handleActionResult(result, R.string.files_msg_file_uploaded, R.string.files_msg_upload_failed)
    }

    // ── Delete ────────────────────────────────────────────────────────────
    fun requestDelete(entry: ManagedFileEntry) {
        _uiState.update { it.copy(deleteTarget = entry) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update {
            it.copy(
                deleteTarget = null,
                busyPaths = it.busyPaths + target.path,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.deleteManagedFile(
                            ManagedFileDelete(target.path, recursive = target.isDirectory),
                        )
                    }
                }
            val cleared =
                _uiState.value.busyPaths - target.path
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            busyPaths = cleared,
                            toastMessage = UiText.res(R.string.files_msg_deleted, target.name),
                        )
                    }
                    refresh()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            busyPaths = cleared,
                            toastMessage = UiText.res(R.string.files_msg_delete_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    // ── Read / open (in-app download + base64 decode) ───────────────────────
    // The backend returns file bytes as a base64 `data_url` (GET /api/files/read).
    // We decode in-app and hand the raw bytes to the Screen, which writes them
    // to cacheDir and opens via FileProvider — reusing the normal Bearer/session
    // auth instead of the browser-only `?token=` escape hatch.
    fun downloadFile(
        entry: ManagedFileEntry,
        onResult: (NetworkResult<DownloadedFile>) -> Unit,
    ) {
        _uiState.update { it.copy(openingPath = entry.path) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.readManagedFile(entry.path) }
                        .map { read ->
                            val bytes = decodeDataUrl(read.dataUrl)
                            DownloadedFile(
                                name = entry.name,
                                mimeType = read.mimeType.ifBlank { "application/octet-stream" },
                                bytes = bytes,
                            )
                        }
                }
            _uiState.update { it.copy(openingPath = null) }
            onResult(result)
        }
    }

    private fun decodeDataUrl(dataUrl: String): ByteArray? {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return null
        val payload = dataUrl.substring(comma + 1)
        return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    }

    private fun handleActionResult(
        result: NetworkResult<ManagedFileActionResponse>,
        @StringRes successMsg: Int,
        @StringRes errorMsg: Int,
    ) {
        when (result) {
            is NetworkResult.Success -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isUploading = false,
                        toastMessage = UiText.res(successMsg),
                    )
                }
                refresh()
            }

            is NetworkResult.Failure -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isUploading = false,
                        toastMessage = UiText.res(errorMsg, result.error.message),
                    )
                }
            }
        }
    }

    fun showToast(message: UiText) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /** Reset transient state on screen re-entry (issue #619 pattern). */
    fun clearTransientState() {
        _uiState.update {
            it.copy(
                errorMessage = null,
                toastMessage = null,
                isCreatingDir = false,
                newDirName = "",
                isUploading = false,
                uploadConflict = null,
                deleteTarget = null,
            )
        }
    }

    private fun buildCrumbs(path: String): List<Pair<String, String>> {
        if (path.isBlank()) return emptyList()
        val parts = path.split("/").filter { it.isNotBlank() }
        val crumbs = mutableListOf<Pair<String, String>>()
        var acc = ""
        for (part in parts) {
            acc += "/$part"
            crumbs.add(acc to part)
        }
        return crumbs
    }
}

/** Name, MIME type and size (-1 when unknown) of a document picked for upload. */
internal data class PickedDocument(
    val name: String,
    val mimeType: String,
    val size: Long,
)

/** Reads [uri]'s [OpenableColumns] metadata. Blocking ContentResolver I/O: call off the main thread. */
internal fun readPickedDocument(
    contentResolver: ContentResolver,
    uri: Uri,
): PickedDocument {
    var displayName: String? = null
    var size = -1L
    contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).coerceAtLeast(-1L)
            }
        }
    return PickedDocument(
        name = uploadFileName(displayName, uri.lastPathSegment),
        mimeType = contentResolver.getType(uri) ?: "application/octet-stream",
        size = size,
    )
}

/**
 * The file name to upload under: the document's display name, else the last URI segment (which for SAF
 * documents is an opaque id such as "msf:1000123"), else "upload.bin". Path separators are stripped so the
 * name cannot point outside the current directory.
 */
internal fun uploadFileName(
    displayName: String?,
    lastPathSegment: String?,
): String =
    listOf(displayName, lastPathSegment)
        .firstNotNullOfOrNull { candidate ->
            candidate
                ?.substringAfterLast('/')
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        } ?: "upload.bin"
