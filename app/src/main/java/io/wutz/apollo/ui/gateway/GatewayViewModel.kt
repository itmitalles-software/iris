package io.wutz.apollo.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.StatusResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.safeLaunchLoad
import io.wutz.apollo.ui.common.safeLaunchSwrLoad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GatewayUiState(
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val status: StatusResponse? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

class GatewayViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(GatewayUiState())
    val uiState: StateFlow<GatewayUiState> = _uiState.asStateFlow()

    private val statusCache = SwrCache<String, StatusResponse>()

    fun clearScopeOwnedState() {
        _uiState.update { it.copy(isLoading = false, status = null, errorMessage = null) }
    }

    fun loadStatus(forceRefresh: Boolean = false) {
        safeLaunchSwrLoad(
            cache = statusCache,
            forceRefresh = forceRefresh,
            onCacheHit = { data ->
                _uiState.update { it.copy(isLoading = false, status = data, errorMessage = null) }
            },
            apiCall = { safeApiCall { ApiClient.hermesApi.getStatus() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update { it.copy(isLoading = false, status = data) }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load status: $errorMsg",
                    )
                }
            },
        )
    }

    fun startGateway() {
        runGatewayAction("start") { safeApiCall { ApiClient.hermesApi.startGateway() } }
    }

    fun stopGateway() {
        runGatewayAction("stop") { safeApiCall { ApiClient.hermesApi.stopGateway() } }
    }

    fun restartGateway() {
        runGatewayAction("restart") { safeApiCall { ApiClient.hermesApi.restartGateway() } }
    }

    private fun runGatewayAction(
        actionName: String,
        apiCall: suspend () -> NetworkResult<Unit>,
    ) {
        _uiState.update { it.copy(isActionRunning = true) }
        viewModelScope.launch {
            val result = apiCall()
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Gateway ${actionName}ed successfully",
                        )
                    }
                    loadStatus(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            toastMessage = "Failed to $actionName gateway: ${result.error.message}",
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
