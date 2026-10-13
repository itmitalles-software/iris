package io.wutz.apollo.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.PairingApproveRequest
import io.wutz.apollo.data.model.PairingResponse
import io.wutz.apollo.data.model.PairingRevokeRequest
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.data.ws.ChangeEvents
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.refreshOnChange
import io.wutz.apollo.ui.common.safeLaunchLoad
import io.wutz.apollo.ui.common.safeLaunchSwrLoad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PairingUiState(
    val isLoading: Boolean = false,
    val pairing: PairingResponse? = null,
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    /** Key of the item whose approve/revoke/clear action is in flight. */
    val actionKey: String? = null,
)

class PairingViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private var launchJob: Job? = null
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val pairingCache = SwrCache<String, PairingResponse>()

    fun clearScopeOwnedState() {
        _uiState.update { it.copy(isLoading = false, pairing = null, errorMessage = null, actionKey = null) }
    }

    init {
        // Issue #784: gateway broadcasts pairing.changed — refresh silently
        // (no spinner) instead of waiting for a manual pull.
        refreshOnChange(
            eventType = ChangeEvents.PAIRING,
            apiCall = { safeApiCall { ApiClient.hermesApi.getPairing() } },
            onSuccess = { data ->
                val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                if (requestScope != null) pairingCache.put(requestScope.inMemoryKey("default"), data)
                _uiState.update { it.copy(pairing = data) }
            },
        )
    }

    fun loadPairing(forceRefresh: Boolean = false) {
        launchJob =
            safeLaunchSwrLoad(
                cache = pairingCache,
                forceRefresh = forceRefresh,
                currentJob = launchJob,
                onCacheHit = { cached ->
                    _uiState.update { it.copy(isLoading = false, pairing = cached, errorMessage = null) }
                },
                apiCall = { safeApiCall { ApiClient.hermesApi.getPairing() } },
                onStart = {
                    _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                },
                onSuccess = { data ->
                    _uiState.update { it.copy(isLoading = false, pairing = data) }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = UiText.res(R.string.pairing_msg_load_failed, errorMsg),
                        )
                    }
                },
            )
    }

    /** Approve a pending pairing request by its server-side `request_id`. */
    fun approvePairing(
        platform: String,
        requestId: String,
    ) {
        runAction(
            actionKey = "approve:$requestId",
            onSuccess = { it.copy(toastMessage = UiText.res(R.string.pairing_msg_approved)) },
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.approvePairing(
                        PairingApproveRequest(platform = platform, requestId = requestId),
                    )
                }
            },
        )
    }

    /** Remove a user from the approved whitelist. */
    fun revokePairing(
        platform: String,
        userId: String,
    ) {
        runAction(
            actionKey = "revoke:$platform:$userId",
            onSuccess = { it.copy(toastMessage = UiText.res(R.string.pairing_msg_revoked, userId)) },
            apiCall = {
                safeApiCall {
                    ApiClient.hermesApi.revokePairing(
                        PairingRevokeRequest(platform = platform, userId = userId),
                    )
                }
            },
        )
    }

    /** Clear every pending pairing request. */
    fun clearPending() {
        runAction(
            actionKey = "clear",
            onSuccess = { it.copy(toastMessage = UiText.res(R.string.pairing_msg_pending_cleared)) },
            apiCall = { safeApiCall { ApiClient.hermesApi.clearPendingPairing() } },
        )
    }

    private fun runAction(
        actionKey: String,
        onSuccess: (PairingUiState) -> PairingUiState,
        apiCall: suspend () -> NetworkResult<Unit>,
    ) {
        if (_uiState.value.actionKey != null) return
        _uiState.update { it.copy(actionKey = actionKey) }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { apiCall() }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { onSuccess(it.copy(actionKey = null)) }
                    loadPairing(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            actionKey = null,
                            toastMessage = UiText.res(R.string.pairing_msg_action_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
