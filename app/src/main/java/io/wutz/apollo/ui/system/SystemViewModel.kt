package io.wutz.apollo.ui.system

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.BuildConfig
import io.wutz.apollo.R
import io.wutz.apollo.data.model.ActionResponse
import io.wutz.apollo.data.model.ActionStatusResponse
import io.wutz.apollo.data.model.BackupTriggerRequest
import io.wutz.apollo.data.model.CheckpointsResponse
import io.wutz.apollo.data.model.CredentialPoolAddRequest
import io.wutz.apollo.data.model.CredentialPoolProvider
import io.wutz.apollo.data.model.CuratorResponse
import io.wutz.apollo.data.model.DebugShareRequest
import io.wutz.apollo.data.model.DebugShareResponse
import io.wutz.apollo.data.model.DoctorResponse
import io.wutz.apollo.data.model.GatewayMigrationPlan
import io.wutz.apollo.data.model.HookCreateRequest
import io.wutz.apollo.data.model.HookDeleteRequest
import io.wutz.apollo.data.model.HookResponse
import io.wutz.apollo.data.model.PortalResponse
import io.wutz.apollo.data.model.StatusResponse
import io.wutz.apollo.data.model.SystemStatsResponse
import io.wutz.apollo.data.model.UpdateCheckResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkError
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ActionProgressController
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.resolve
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

data class SystemUiState(
    val isLoading: Boolean = false,
    val stats: SystemStatsResponse? = null,
    val portal: PortalResponse? = null,
    val curator: CuratorResponse? = null,
    val credentials: List<CredentialPoolProvider> = emptyList(),
    /**
     * True while a pool change is in flight or [credentials] has not been reloaded since one. Pool entries are
     * removed by their 1-based position, so removals stay blocked until the shown positions are current again.
     */
    val credentialsPending: Boolean = false,
    val checkpoints: CheckpointsResponse? = null,
    val hooks: HookResponse? = null,
    val updateInfo: UpdateCheckResponse? = null,
    val status: StatusResponse? = null,
    val doctorReport: DoctorResponse? = null,
    val migrationPlan: GatewayMigrationPlan? = null,
    val migrationSupported: Boolean? = null,
    val activeAction: String? = null,
    val actionLog: ActionStatusResponse? = null,
    val backupArchive: String? = null,
    val downloadableBackup: String? = null,
    val debugShare: DebugShareResponse? = null,
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    // Form states
    val credProvider: String = "openrouter",
    val credKey: String = "",
    val credLabel: String = "",
    val addingCred: Boolean = false,
    // Hook modal
    val hookModalOpen: Boolean = false,
    val hookEvent: String = "pre_tool_call",
    val hookCommand: String = "",
    val hookMatcher: String = "",
    val hookTimeout: String = "",
    val hookApprove: Boolean = true,
    val creatingHook: Boolean = false,
    // Import (issue #786): SAF-picked backup archive staged by the screen
    val importFileName: String? = null,
    val isImporting: Boolean = false,
    // True while the async backup-archive retry loop is running
    val isDownloading: Boolean = false,
    // Debug share
    val shareRedact: Boolean = true,
    val sharing: Boolean = false,
    // Update
    val checkingUpdate: Boolean = false,
    val updateConfirmOpen: Boolean = false,
)

class SystemViewModel(
    application: Application,
    private val migrationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AndroidViewModel(application),
    ToastHost {
    companion object {
        private const val TAG = "SystemViewModel"

        // The backup action is async — retry a 404 download until the zip
        // exists (up to ~3 minutes, 328MB backups took ~70s in testing).
        private const val DOWNLOAD_RETRY_DELAY_MS = 5_000L
        private const val MAX_DOWNLOAD_ATTEMPTS = 36

        internal const val ACTION_POLL_INTERVAL_MS = 1_200L

        /** Same cap as [io.wutz.apollo.ui.common.ActionProgressController]. */
        internal const val MAX_ACTION_POLL_FAILURES = 5
    }

    private val _uiState = MutableStateFlow(SystemUiState())
    val uiState: StateFlow<SystemUiState> = _uiState.asStateFlow()

    /**
     * Progress popup for the update flow (issue #863): after the trigger POST
     * returns, [ActionProgressController] polls the action status log until
     * the update exits, then refreshes the whole screen so the version row
     * and update badge reflect the new build.
     */
    val actionProgress =
        ActionProgressController(
            scope = viewModelScope,
            onFinished = { status -> onActionFinished(status) },
        )

    /** Progress popup for the optional gateway migration action. */
    val migrationProgress =
        ActionProgressController(
            scope = viewModelScope,
            onFinished = { status -> onMigrationFinished(status) },
        )

    private var actionPollingJob: Job? = null
    private var downloadJob: Job? = null

    /** Bumped on every credential pool change; a pool load started before the change is stale. */
    private var credentialsGeneration = 0

    // ── Full parallel data load ────────────────────────────────────────

    fun loadAll() {
        // Migration support is optional; probe it independently so an older or
        // slow backend cannot hold up the required System data.
        loadMigrationPlan()
        val credGeneration = credentialsGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            coroutineScope {
                val statsDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getSystemStats() } }
                val statusDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getStatus() } }
                val portalDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getPortal() } }
                val curatorDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getCurator() } }

                val credDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getCredentialPool() } }
                val checkpointsDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getCheckpoints() } }
                val hooksDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getHooks() } }
                val updateDeferred =
                    async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.checkHermesUpdate(false) } }
                val doctorDeferred = async(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.runDoctor() } }

                val statsResult = statsDeferred.await()
                val statusResult = statusDeferred.await()

                // Phase 1: Un-gate UI immediately with fast host stats & gateway status (~100-200ms)
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        stats = (statsResult as? NetworkResult.Success)?.data ?: state.stats,
                        status = (statusResult as? NetworkResult.Success)?.data ?: state.status,
                        errorMessage = null,
                    )
                }

                if (BuildConfig.DEBUG) {
                    if (statsResult is NetworkResult.Failure) {
                        Log.w(TAG, "stats endpoint: ${statsResult.error.message}")
                    }
                    if (statusResult is NetworkResult.Failure) {
                        Log.w(TAG, "status endpoint: ${statusResult.error.message}")
                    }
                }

                // Phase 2: Progressively hydrate remaining sections as each endpoint resolves
                launch {
                    val portalResult = portalDeferred.await()
                    if (portalResult is NetworkResult.Success) {
                        _uiState.update { it.copy(portal = portalResult.data) }
                    } else if (BuildConfig.DEBUG && portalResult is NetworkResult.Failure) {
                        Log.w(TAG, "portal endpoint: ${portalResult.error.message}")
                    }
                }

                launch {
                    val curatorResult = curatorDeferred.await()
                    if (curatorResult is NetworkResult.Success) {
                        _uiState.update { it.copy(curator = curatorResult.data) }
                    } else if (BuildConfig.DEBUG && curatorResult is NetworkResult.Failure) {
                        Log.w(TAG, "curator endpoint: ${curatorResult.error.message}")
                    }
                }

                launch {
                    val credResult = credDeferred.await()
                    if (credResult is NetworkResult.Success) {
                        applyCredentials(credGeneration, credResult.data.providers.orEmpty())
                    } else if (BuildConfig.DEBUG && credResult is NetworkResult.Failure) {
                        Log.w(TAG, "credentials endpoint: ${credResult.error.message}")
                    }
                }

                launch {
                    val checkpointsResult = checkpointsDeferred.await()
                    if (checkpointsResult is NetworkResult.Success) {
                        _uiState.update { it.copy(checkpoints = checkpointsResult.data) }
                    } else if (BuildConfig.DEBUG && checkpointsResult is NetworkResult.Failure) {
                        Log.w(TAG, "checkpoints endpoint: ${checkpointsResult.error.message}")
                    }
                }

                launch {
                    val hooksResult = hooksDeferred.await()
                    if (hooksResult is NetworkResult.Success) {
                        _uiState.update { it.copy(hooks = hooksResult.data) }
                    } else if (BuildConfig.DEBUG && hooksResult is NetworkResult.Failure) {
                        Log.w(TAG, "hooks endpoint: ${hooksResult.error.message}")
                    }
                }

                launch {
                    val updateResult = updateDeferred.await()
                    if (updateResult is NetworkResult.Success) {
                        _uiState.update { it.copy(updateInfo = updateResult.data) }
                    } else if (BuildConfig.DEBUG && updateResult is NetworkResult.Failure) {
                        Log.w(TAG, "update endpoint: ${updateResult.error.message}")
                    }
                }

                launch {
                    val doctorResult = doctorDeferred.await()
                    if (doctorResult is NetworkResult.Success) {
                        _uiState.update { it.copy(doctorReport = doctorResult.data) }
                    } else if (BuildConfig.DEBUG && doctorResult is NetworkResult.Failure) {
                        Log.w(TAG, "doctor endpoint: ${doctorResult.error.message}")
                    }
                }
            }
        }
    }

    // ── Targeted Data Reloads ──────────────────────────────────────────

    fun loadCredentials() {
        val generation = credentialsGeneration
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getCredentialPool() } }
            if (result is NetworkResult.Success) {
                applyCredentials(generation, result.data.providers.orEmpty())
            }
        }
    }

    /** Applies a pool listing unless a pool change happened after it was requested. */
    private fun applyCredentials(
        generation: Int,
        providers: List<CredentialPoolProvider>,
    ) {
        if (generation != credentialsGeneration) return
        _uiState.update { it.copy(credentials = providers, credentialsPending = false) }
    }

    /** Marks the shown pool positions as outdated until the next load after this change. */
    private fun beginCredentialChange() {
        credentialsGeneration++
        _uiState.update { it.copy(credentialsPending = true) }
    }

    fun loadHooks() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getHooks() } }
            if (result is NetworkResult.Success) {
                _uiState.update { it.copy(hooks = result.data) }
            }
        }
    }

    fun loadCurator() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getCurator() } }
            if (result is NetworkResult.Success) {
                _uiState.update { it.copy(curator = result.data) }
            }
        }
    }

    fun loadStatus() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { safeApiCall { ApiClient.hermesApi.getStatus() } }
            if (result is NetworkResult.Success) {
                _uiState.update { it.copy(status = result.data) }
            }
        }
    }

    /** Load optional migration capability state without affecting other System data. */
    fun loadMigrationPlan() {
        viewModelScope.launch {
            val result =
                withContext(migrationDispatcher) {
                    safeApiCall { ApiClient.hermesApi.getGatewayMigrationPlan() }
                }
            _uiState.update {
                it.copy(
                    migrationPlan = (result as? NetworkResult.Success)?.data,
                    migrationSupported = migrationSupport(result),
                )
            }
        }
    }

    /**
     * Start the backend migration only after the Gateway section's confirmation.
     * The POST starts an asynchronous `gateway-migrate` action; completion is
     * owned by [migrationProgress], not by the trigger response.
     */
    fun startMigration() {
        val plan = _uiState.value.migrationPlan ?: return
        if (plan.alreadyMultiplexed) {
            _uiState.update { it.copy(toastMessage = UiText.res(R.string.system_msg_migration_already_multiplexed)) }
            return
        }
        if (plan.blockers.isNotEmpty()) {
            _uiState.update { it.copy(toastMessage = UiText.res(R.string.system_msg_migration_blocked)) }
            return
        }

        migrationProgress.open()
        viewModelScope.launch {
            val result =
                withContext(migrationDispatcher) {
                    safeApiCall { ApiClient.hermesApi.startGatewayMigration() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val response = result.data
                    val actionName = response.name
                    if (!response.ok) {
                        migrationProgress.fail(text(UiText.res(R.string.system_msg_migration_rejected)))
                    } else if (actionName != null) {
                        migrationProgress.markStarted(actionName)
                    } else {
                        migrationProgress.fail(text(UiText.res(R.string.system_msg_migration_no_action_name)))
                    }
                }

                is NetworkResult.Failure -> {
                    migrationProgress.fail(
                        text(UiText.res(R.string.system_msg_migration_start_failed, result.error.message)),
                    )
                }
            }
        }
    }

    /**
     * Fired when [actionProgress] finishes tracking a backend action. For the
     * `hermes-update` action we additionally fetch the durable update receipt
     * (issue #958) and append its summary to the end of the progress popup,
     * then refresh the screen so the version row reflects the new build. Other
     * actions just refresh. A 404 / missing receipt is silently skipped — the
     * popup already shows success from the action status.
     */
    private fun onActionFinished(status: ActionStatusResponse?) {
        if (status?.name == "hermes-update") {
            viewModelScope.launch {
                val receipt = fetchUpdateReceiptLines()
                if (receipt.isNotEmpty()) actionProgress.pushTrailingLines(receipt.map(::text))
            }
        }
        loadAll()
    }

    private fun onMigrationFinished(status: ActionStatusResponse?) {
        if (status?.exit_code == 0) {
            loadAll()
        }
    }

    /**
     * Pull `GET /api/hermes/update/receipt` and render a compact summary block
     * for the popup. Returns an empty list when the endpoint is unavailable
     * (old backend, no receipt yet, or any error) so the caller appends nothing.
     */
    private suspend fun fetchUpdateReceiptLines(): List<UiText> {
        val result =
            withContext(Dispatchers.IO) {
                safeApiCall { ApiClient.hermesApi.getUpdateReceipt() }
            }
        val data = (result as? NetworkResult.Success)?.data ?: return emptyList()
        return formatUpdateReceiptLines(data)
    }

    // ── Gateway actions ────────────────────────────────────────────────

    fun startGateway() {
        runGatewayAction(R.string.system_msg_gateway_started, R.string.system_msg_gateway_start_failed) {
            safeApiCall { ApiClient.hermesApi.startGateway() }
        }
    }

    fun stopGateway() {
        runGatewayAction(R.string.system_msg_gateway_stopped, R.string.system_msg_gateway_stop_failed) {
            safeApiCall { ApiClient.hermesApi.stopGateway() }
        }
    }

    fun restartGateway() {
        runGatewayAction(R.string.system_msg_gateway_restarted, R.string.system_msg_gateway_restart_failed) {
            safeApiCall { ApiClient.hermesApi.restartGateway() }
        }
    }

    private fun runGatewayAction(
        @StringRes successRes: Int,
        @StringRes failureRes: Int,
        apiCall: suspend () -> NetworkResult<Unit>,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { apiCall() }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = UiText.res(successRes)) }
                    loadStatus()
                }

                is NetworkResult.Failure -> {
                    _uiState.update { it.copy(toastMessage = UiText.res(failureRes, result.error.message)) }
                }
            }
        }
    }

    // ── Update actions ─────────────────────────────────────────────────

    fun checkForUpdate(force: Boolean) {
        _uiState.update { it.copy(checkingUpdate = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.checkHermesUpdate(force) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(checkingUpdate = false, updateInfo = result.data) }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            checkingUpdate = false,
                            toastMessage = UiText.res(R.string.system_msg_update_check_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    /**
     * Run the update and surface its progress in [ActionProgressController]'s
     * popup: the trigger POST returns immediately (`{ok, name}`) while the
     * actual update runs in the background, so the dialog polls the action
     * status log until it exits (success/failure + tail shown to the user).
     */
    fun applyUpdate() {
        actionProgress.open()
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.updateHermes() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val name = result.data.name
                    if (name != null) {
                        actionProgress.markStarted(name)
                    } else {
                        actionProgress.fail(text(UiText.res(R.string.system_msg_update_no_action_name)))
                    }
                }

                is NetworkResult.Failure -> {
                    actionProgress.fail(text(UiText.res(R.string.system_msg_update_start_failed, result.error.message)))
                }
            }
        }
    }

    fun openUpdateConfirm() {
        _uiState.update { it.copy(updateConfirmOpen = true) }
    }

    fun closeUpdateConfirm() {
        _uiState.update { it.copy(updateConfirmOpen = false) }
    }

    // ── Curator actions ────────────────────────────────────────────────

    fun toggleCuratorPaused() {
        val currentlyPaused = _uiState.value.curator?.paused ?: return
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.setCuratorPaused(mapOf("paused" to !currentlyPaused)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val messageRes =
                        if (currentlyPaused) R.string.system_msg_curator_resumed else R.string.system_msg_curator_paused
                    _uiState.update { it.copy(toastMessage = UiText.res(messageRes)) }
                    loadCurator()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.system_msg_curator_toggle_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun runCuratorNow() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.runCurator() } },
            labelRes = R.string.system_curator_run,
        )
    }

    // ── Credential pool actions ────────────────────────────────────────

    fun updateCredProvider(v: String) {
        _uiState.update { it.copy(credProvider = v) }
    }

    fun updateCredKey(v: String) {
        _uiState.update { it.copy(credKey = v) }
    }

    fun updateCredLabel(v: String) {
        _uiState.update { it.copy(credLabel = v) }
    }

    fun addCredential() {
        val state = _uiState.value
        if (state.credKey.isBlank()) {
            _uiState.update { it.copy(toastMessage = UiText.res(R.string.system_msg_credential_key_required)) }
            return
        }
        _uiState.update { it.copy(addingCred = true) }
        // A new entry can shift the positions of existing ones (pool priority order).
        beginCredentialChange()
        viewModelScope.launch {
            val body =
                CredentialPoolAddRequest(
                    provider = state.credProvider,
                    apiKey = state.credKey,
                    label = state.credLabel.ifBlank { null },
                )
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.addCredentialPoolEntry(body) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            addingCred = false,
                            credKey = "",
                            credLabel = "",
                            toastMessage = UiText.res(R.string.system_msg_credential_added),
                        )
                    }
                    loadCredentials()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            addingCred = false,
                            toastMessage = UiText.res(R.string.system_msg_credential_add_failed, result.error.message),
                        )
                    }
                    loadCredentials()
                }
            }
        }
    }

    /**
     * Removes pool entry [index] (1-based, as listed by the server) of [provider]. The server only offers removal
     * by position, so a removal is ignored while the shown list may be outdated ([SystemUiState.credentialsPending]).
     */
    fun removeCredential(
        provider: String,
        index: Int,
    ) {
        if (provider.isBlank() || index < 1 || _uiState.value.credentialsPending) return
        beginCredentialChange()
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.removeCredentialPoolEntry(provider, index) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = UiText.res(R.string.system_msg_credential_removed)) }
                    loadCredentials()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage =
                                UiText.res(
                                    R.string.system_msg_credential_remove_failed,
                                    result.error.message,
                                ),
                        )
                    }
                    // A failure (e.g. 404 for a position that no longer exists) can mean the list was outdated.
                    loadCredentials()
                }
            }
        }
    }

    // ── Operation actions ──────────────────────────────────────────────

    fun runSecurityAudit() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.runSecurityAudit() } },
            labelRes = R.string.system_security_audit,
        )
    }

    fun runPromptSize() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.runPromptSize() } },
            labelRes = R.string.system_prompt_size_check,
        )
    }

    fun runDump() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.runDump() } },
            labelRes = R.string.system_dump,
        )
    }

    fun runConfigMigrate() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.runConfigMigrate() } },
            labelRes = R.string.system_config_migrate,
        )
    }

    fun runUpdateSkills() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.updateSkillsFromHub() } },
            labelRes = R.string.system_skills_update,
        )
    }

    // ── Backup actions ─────────────────────────────────────────────────

    fun triggerBackup() {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.triggerBackup(BackupTriggerRequest())
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.system_msg_backup_triggered),
                            backupArchive = result.data.archive,
                        )
                    }
                    loadAll()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.system_msg_backup_trigger_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    /**
     * Download the triggered backup archive. The backup action is ASYNC — the
     * trigger returns the path instantly, but the zip (can be hundreds of MB)
     * takes a while to write. The backend 404s ("Backup not found") until the
     * file exists, so a 404 is retried with a delay instead of failing the tap.
     * The @Streaming body is handed to [onBody] — the screen streams it to
     * Downloads via MediaImageStore (never buffered in memory).
     */
    fun downloadBackup(onBody: (body: okhttp3.ResponseBody, fileName: String) -> Unit) {
        val archive =
            _uiState.value.backupArchive ?: run {
                _uiState.update { it.copy(toastMessage = UiText.res(R.string.system_msg_backup_none)) }
                return
            }
        // Single-flight: a second tap while a retry loop is running must not
        // spawn a parallel loop (logcat showed interleaved duplicate requests).
        downloadJob?.cancel()
        downloadJob =
            viewModelScope.launch {
                _uiState.update { it.copy(isDownloading = true) }
                try {
                    var attempt = 0
                    while (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                        val result =
                            withContext(Dispatchers.IO) {
                                safeApiCall { ApiClient.hermesApi.downloadBackup(archive) }
                            }
                        when (result) {
                            is NetworkResult.Success -> {
                                // @Streaming body — hand it off as-is; the screen
                                // streams it to Downloads (never bytes() — OOM).
                                onBody(result.data, archive.substringAfterLast('/'))
                                return@launch
                            }

                            is NetworkResult.Failure -> {
                                val code = (result.error as? NetworkError.Http)?.code
                                if (code == 404) {
                                    // Live countdown so the wait is visible —
                                    // 328MB backups took ~60s in testing.
                                    val elapsed = attempt * DOWNLOAD_RETRY_DELAY_MS / 1000
                                    _uiState.update {
                                        it.copy(toastMessage = UiText.res(R.string.system_msg_backup_waiting, elapsed))
                                    }
                                    attempt++
                                    delay(DOWNLOAD_RETRY_DELAY_MS)
                                } else {
                                    _uiState.update {
                                        it.copy(
                                            toastMessage =
                                                UiText.res(
                                                    R.string.system_msg_backup_download_failed,
                                                    result.error.message,
                                                ),
                                        )
                                    }
                                    return@launch
                                }
                            }
                        }
                    }
                    _uiState.update { it.copy(toastMessage = UiText.res(R.string.system_msg_backup_still_creating)) }
                } finally {
                    _uiState.update { it.copy(isDownloading = false) }
                }
            }
    }

    fun setImportFile(name: String) {
        _uiState.update { it.copy(importFileName = name) }
    }

    fun clearImportFile() {
        _uiState.update { it.copy(importFileName = null) }
    }

    fun importArchive(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
    ) {
        // Mirrors FilesViewModel.uploadFile: multipart file + force (issue #786)
        val forceBody = "false".toRequestBody("text/plain".toMediaTypeOrNull())
        val part =
            MultipartBody.Part.createFormData(
                "file",
                fileName,
                bytes.toRequestBody(mimeType.toMediaTypeOrNull()),
            )
        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall {
                        ApiClient.hermesApi.importUpload(forceBody, part)
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    result.data.name?.let { pollActionStatus(it) }
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importFileName = null,
                            toastMessage = UiText.res(R.string.system_msg_import_started),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            toastMessage = UiText.res(R.string.system_msg_import_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun showToast(message: UiText) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    // ── Debug share actions ────────────────────────────────────────────

    fun toggleShareRedact() {
        _uiState.update { it.copy(shareRedact = !it.shareRedact) }
    }

    fun runDebugShare() {
        val shareRedact = _uiState.value.shareRedact
        _uiState.update { it.copy(sharing = true) }
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.runDebugShare(DebugShareRequest(redact = shareRedact)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            sharing = false,
                            debugShare = result.data,
                            toastMessage = UiText.res(R.string.system_msg_debug_share_created),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            sharing = false,
                            toastMessage = UiText.res(R.string.system_msg_debug_share_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    // ── Checkpoints actions ────────────────────────────────────────────

    fun pruneCheckpoints() {
        runOperation(
            apiCall = { safeApiCall { ApiClient.hermesApi.pruneCheckpoints() } },
            labelRes = R.string.system_checkpoint_prune,
        )
    }

    // ── Hook actions ───────────────────────────────────────────────────

    fun toggleHookModal() {
        _uiState.update { it.copy(hookModalOpen = !it.hookModalOpen) }
    }

    fun updateHookEvent(v: String) {
        _uiState.update { it.copy(hookEvent = v) }
    }

    fun updateHookCommand(v: String) {
        _uiState.update { it.copy(hookCommand = v) }
    }

    fun updateHookMatcher(v: String) {
        _uiState.update { it.copy(hookMatcher = v) }
    }

    fun updateHookTimeout(v: String) {
        _uiState.update { it.copy(hookTimeout = v) }
    }

    fun updateHookApprove(v: Boolean) {
        _uiState.update { it.copy(hookApprove = v) }
    }

    fun createHook() {
        val state = _uiState.value
        if (state.hookCommand.isBlank()) {
            _uiState.update { it.copy(toastMessage = UiText.res(R.string.system_msg_hook_command_required)) }
            return
        }
        _uiState.update { it.copy(creatingHook = true) }
        viewModelScope.launch {
            val body =
                HookCreateRequest(
                    event = state.hookEvent,
                    command = state.hookCommand,
                    matcher = state.hookMatcher.ifBlank { null },
                    timeout = if (state.hookTimeout.isBlank()) null else state.hookTimeout.toIntOrNull() ?: 30,
                    approve = state.hookApprove,
                )
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.createHook(body) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            creatingHook = false,
                            hookModalOpen = false,
                            hookCommand = "",
                            hookMatcher = "",
                            hookTimeout = "",
                            toastMessage = UiText.res(R.string.system_msg_hook_created),
                        )
                    }
                    loadHooks()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            creatingHook = false,
                            toastMessage = UiText.res(R.string.system_msg_hook_create_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun deleteHook(
        event: String,
        command: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    safeApiCall { ApiClient.hermesApi.deleteHook(HookDeleteRequest(event, command)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = UiText.res(R.string.system_msg_hook_deleted)) }
                    loadHooks()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.system_msg_hook_delete_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    // ── Action log polling ─────────────────────────────────────────────

    private fun pollActionStatus(name: String) {
        actionPollingJob?.cancel()
        _uiState.update { it.copy(activeAction = name, actionLog = null) }
        actionPollingJob =
            viewModelScope.launch {
                var consecutiveFailures = 0
                while (isActive) {
                    delay(ACTION_POLL_INTERVAL_MS)
                    val result =
                        withContext(Dispatchers.IO) {
                            safeApiCall { ApiClient.hermesApi.getActionStatus(name) }
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            consecutiveFailures = 0
                            _uiState.update { it.copy(actionLog = result.data) }
                            if (result.data.running != true) {
                                loadAll()
                                break
                            }
                        }

                        is NetworkResult.Failure -> {
                            // Transient failures are expected (the action may restart the
                            // gateway). The ViewModel outlives the screen, so stop after a
                            // run of failures instead of polling forever in the background.
                            consecutiveFailures++
                            if (consecutiveFailures >= MAX_ACTION_POLL_FAILURES) {
                                _uiState.update {
                                    it.copy(
                                        toastMessage =
                                            UiText.res(R.string.system_msg_action_status_lost, result.error.message),
                                    )
                                }
                                break
                            }
                        }
                    }
                }
            }
    }

    fun closeActionLog() {
        actionPollingJob?.cancel()
        _uiState.update { it.copy(activeAction = null, actionLog = null) }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun runOperation(
        apiCall: suspend () -> NetworkResult<ActionResponse>,
        @StringRes labelRes: Int,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { apiCall() }
            when (result) {
                is NetworkResult.Success -> {
                    result.data.name?.let { pollActionStatus(it) }
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.system_action_started, UiText.res(labelRes)),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage =
                                UiText.res(R.string.system_action_failed, UiText.res(labelRes), result.error.message),
                        )
                    }
                }
            }
        }
    }

    // ── Transient state ────────────────────────────────────────────────

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /** Resolves [message] now, for the shared progress dialog that only takes plain text. */
    private fun text(message: UiText): String = message.resolve(getApplication<Application>())
}

private fun migrationSupport(result: NetworkResult<GatewayMigrationPlan>): Boolean? =
    when (result) {
        is NetworkResult.Success -> {
            true
        }

        is NetworkResult.Failure -> {
            val code = (result.error as? NetworkError.Http)?.code
            if (code == 404 || code == 405) false else null
        }
    }
