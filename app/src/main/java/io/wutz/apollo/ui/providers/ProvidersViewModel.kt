package io.wutz.apollo.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.OAuthPollResponse
import io.wutz.apollo.data.model.OAuthProvider
import io.wutz.apollo.data.model.OAuthProvidersResponse
import io.wutz.apollo.data.model.OAuthStartResponse
import io.wutz.apollo.data.model.OAuthSubmitRequest
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkError
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.safeLaunchLoad
import io.wutz.apollo.ui.common.safeLaunchSwrLoad
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Re-auth flow phase shown in the OAuth dialog. */
enum class OAuthFlowPhase {
    IDLE,
    STARTING,

    /** pkce: browser opened, waiting for the user to paste the callback code. */
    WAITING_CODE,

    /** device_code: user_code + verification_url shown, background poll running. */
    POLLING,

    /** terminal: success — dialog shows a confirmation + Done button. */
    DONE,

    /** terminal: error — dialog stays mounted so the user can read flowErrorMessage + Close. */
    ERROR,

    /** external provider: show the CLI setup command (no network call). */
    SHOW_CLI,
}

data class ProvidersUiState(
    val isLoading: Boolean = false,
    val providers: List<OAuthProvider> = emptyList(),
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    val actingId: String? = null,
    // ── Re-auth flow ──
    val flowPhase: OAuthFlowPhase = OAuthFlowPhase.IDLE,
    val flowProvider: OAuthProvider? = null,
    val flowStart: OAuthStartResponse? = null,
    val flowSessionId: String = "",
    val flowProviderId: String = "",
    val flowCodeInput: String = "",
    val flowStatus: String = "pending",
    val flowErrorMessage: UiText? = null,
    val flowExpiresIn: UiText? = null,
)

class ProvidersViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    /** The in-flight `/oauth/{provider}/start` request; cancelled with the flow it belongs to. */
    private var startJob: Job? = null

    /** Bumped whenever a flow starts or ends, so a late start response of an older flow is ignored. */
    private var flowGeneration = 0

    /** Stops the start request and polling of the current flow. */
    private fun stopFlowJobs() {
        flowGeneration++
        startJob?.cancel()
        startJob = null
        pollJob?.cancel()
    }

    private val providersCache = SwrCache<String, OAuthProvidersResponse>()

    fun clearScopeOwnedState() {
        stopFlowJobs()
        _uiState.update {
            it.copy(
                isLoading = false,
                providers = emptyList(),
                errorMessage = null,
                flowPhase = OAuthFlowPhase.IDLE,
                flowProvider = null,
                flowErrorMessage = null,
            )
        }
    }

    fun load(forceRefresh: Boolean = false) {
        safeLaunchSwrLoad(
            cache = providersCache,
            forceRefresh = forceRefresh,
            onCacheHit = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        providers = data.providers.orEmpty(),
                        errorMessage = null,
                    )
                }
            },
            apiCall = { safeApiCall { ApiClient.hermesApi.getOAuthProviders() } },
            onStart = {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        providers = data.providers.orEmpty(),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiText.res(R.string.providers_msg_load_failed, errorMsg),
                    )
                }
            },
        )
    }

    /** Disconnect a provider whose credentials are app-managed. */
    fun disconnectProvider(providerId: String) {
        _uiState.update { it.copy(actingId = providerId) }
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.disconnectOAuthProvider(providerId) } },
            onStart = {},
            onSuccess = {
                _uiState.update {
                    it.copy(
                        actingId = null,
                        toastMessage = UiText.res(R.string.providers_msg_disconnected),
                    )
                }
                load(forceRefresh = true)
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        actingId = null,
                        toastMessage = UiText.res(R.string.providers_msg_disconnect_failed, error),
                    )
                }
            },
        )
    }

    // ── Re-auth flow ────────────────────────────────────────────────────

    fun startOAuthFlow(provider: OAuthProvider) {
        // External providers can't be started from the app (backend returns 400 from
        // /start — they must be configured via CLI). Show the CLI command directly instead
        // of firing a request that will just 400 and snap the dialog shut.
        if (provider.flow == "external") {
            _uiState.update {
                it.copy(
                    flowPhase = OAuthFlowPhase.SHOW_CLI,
                    flowProvider = provider,
                    flowProviderId = provider.id,
                    flowStart = null,
                    flowSessionId = "",
                    flowCodeInput = "",
                    flowStatus = "pending",
                    flowErrorMessage = null,
                )
            }
            return
        }
        val phase = _uiState.value.flowPhase
        if (phase == OAuthFlowPhase.STARTING || phase == OAuthFlowPhase.WAITING_CODE ||
            phase == OAuthFlowPhase.POLLING
        ) {
            return
        }
        stopFlowJobs()
        val generation = flowGeneration
        _uiState.update {
            it.copy(
                flowPhase = OAuthFlowPhase.STARTING,
                flowProvider = provider,
                flowProviderId = provider.id,
                flowStart = null,
                flowSessionId = "",
                flowCodeInput = "",
                flowStatus = "pending",
                flowErrorMessage = null,
            )
        }
        startJob =
            viewModelScope.launch {
                val result = safeApiCall { ApiClient.hermesApi.startOAuthLogin(provider.id) }
                // The flow was cancelled, dismissed or replaced meanwhile: its result must not revive it.
                if (generation != flowGeneration || _uiState.value.flowPhase != OAuthFlowPhase.STARTING) {
                    return@launch
                }
                when (result) {
                    is NetworkResult.Success -> {
                        val start = result.data
                        _uiState.update {
                            it.copy(
                                flowPhase =
                                    when (start.flow) {
                                        "pkce" -> OAuthFlowPhase.WAITING_CODE
                                        "device_code" -> OAuthFlowPhase.POLLING
                                        else -> OAuthFlowPhase.IDLE
                                    },
                                flowStart = start,
                                flowSessionId = start.sessionId,
                                flowExpiresIn = formatExpiry(start.expiresIn),
                            )
                        }
                        if (start.flow == "device_code") {
                            pollSession(provider.id, start.sessionId)
                        }
                    }

                    is NetworkResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                flowPhase = OAuthFlowPhase.IDLE,
                                flowErrorMessage = UiText.raw(result.error.message),
                            )
                        }
                    }
                }
            }
    }

    fun onCodeInputChange(code: String) {
        _uiState.update { it.copy(flowCodeInput = code) }
    }

    fun submitOAuthCode() {
        val state = _uiState.value
        val code = state.flowCodeInput.trim()
        if (code.isEmpty()) {
            _uiState.update { it.copy(flowErrorMessage = UiText.res(R.string.providers_msg_paste_code_first)) }
            return
        }
        _uiState.update { it.copy(flowStatus = "pending", flowErrorMessage = null) }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.submitOAuthCode(
                        state.flowProviderId,
                        OAuthSubmitRequest(sessionId = state.flowSessionId, code = code),
                    )
                }
            when (result) {
                is NetworkResult.Success -> {
                    val submit = result.data
                    if (submit.ok) {
                        finishFlow("approved")
                    } else {
                        _uiState.update {
                            it.copy(
                                flowStatus = submit.status ?: "error",
                                flowErrorMessage =
                                    submit.message?.let(UiText::raw)
                                        ?: UiText.res(R.string.providers_msg_submission_rejected),
                            )
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            flowStatus = "error",
                            flowErrorMessage = UiText.raw(result.error.message),
                        )
                    }
                }
            }
        }
    }

    private fun pollSession(
        providerId: String,
        sessionId: String,
    ) {
        pollJob?.cancel()
        pollJob =
            viewModelScope.launch {
                while (_uiState.value.flowPhase == OAuthFlowPhase.POLLING) {
                    delay((_uiState.value.flowStart?.pollInterval ?: 5).toLong() * 1000)
                    val result =
                        safeApiCall {
                            ApiClient.hermesApi.pollOAuthSession(providerId, sessionId)
                        }
                    when (result) {
                        is NetworkResult.Success -> {
                            val poll: OAuthPollResponse? = result.data
                            val status = poll?.status ?: "pending"
                            _uiState.update { it.copy(flowStatus = status) }
                            when (status) {
                                "approved" -> {
                                    finishFlow("approved")
                                    return@launch
                                }

                                "denied", "expired", "error" -> {
                                    _uiState.update {
                                        it.copy(
                                            flowPhase = OAuthFlowPhase.ERROR,
                                            flowStatus = status,
                                            flowErrorMessage =
                                                poll?.errorMessage?.let(UiText::raw)
                                                    ?: UiText.res(R.string.providers_msg_flow_ended, status),
                                        )
                                    }
                                    return@launch
                                }

                                else -> { /* still pending — keep polling */ }
                            }
                        }

                        is NetworkResult.Failure -> {
                            // Tokenless endpoint; a transient failure shouldn't kill the flow.
                            // Continue polling unless it's a hard 4xx.
                            val code = (result.error as? NetworkError.Http)?.code ?: -1
                            if (code in 400..499) {
                                _uiState.update {
                                    it.copy(
                                        flowPhase = OAuthFlowPhase.ERROR,
                                        flowErrorMessage = UiText.raw(result.error.message),
                                    )
                                }
                                return@launch
                            }
                        }
                    }
                }
            }
    }

    private fun finishFlow(status: String) {
        pollJob?.cancel()
        load(forceRefresh = true)
        // Stay on DONE so the dialog can show a success confirmation; the Screen's
        // Done button calls dismissFlow() to reset to IDLE. (Setting IDLE here would
        // conflate with DONE via StateFlow and unmount the dialog before the user sees it.)
        _uiState.update {
            it.copy(
                flowPhase = OAuthFlowPhase.DONE,
                flowStatus = status,
                flowErrorMessage = null,
                toastMessage = UiText.res(R.string.providers_msg_oauth_connected),
            )
        }
    }

    /** Reset the re-auth flow to IDLE, clearing all flow-specific state. */
    fun dismissFlow() {
        stopFlowJobs()
        _uiState.update {
            it.copy(
                flowPhase = OAuthFlowPhase.IDLE,
                flowProvider = null,
                flowStart = null,
                flowSessionId = "",
                flowCodeInput = "",
                flowStatus = "pending",
                flowErrorMessage = null,
            )
        }
    }

    fun cancelOAuthFlow() {
        val sessionId = _uiState.value.flowSessionId
        stopFlowJobs()
        _uiState.update {
            it.copy(
                flowPhase = OAuthFlowPhase.IDLE,
                flowStart = null,
                flowSessionId = "",
                flowCodeInput = "",
                flowStatus = "pending",
                flowErrorMessage = null,
            )
        }
        if (sessionId.isNotEmpty()) {
            viewModelScope.launch {
                safeApiCall { ApiClient.hermesApi.cancelOAuthSession(sessionId) }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun formatExpiry(seconds: Int?): UiText? {
        if (seconds == null) return null
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) {
            UiText.res(R.string.providers_msg_expiry_min_sec, m, s)
        } else {
            UiText.res(R.string.providers_msg_expiry_sec, s)
        }
    }
}
