package io.wutz.apollo.ui.toolsets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.Toolset
import io.wutz.apollo.data.model.ToolsetToggleRequest
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.safeLaunchLoad
import io.wutz.apollo.ui.common.safeLaunchSwrLoad
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ToolsetsUiState(
    val isLoading: Boolean = false,
    val toolsets: List<Toolset> = emptyList(),
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
)

class ToolsetsViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ToolsetsUiState())
    val uiState: StateFlow<ToolsetsUiState> = _uiState.asStateFlow()

    private val toolsetsCache = SwrCache<String, List<Toolset>>()

    fun clearScopeOwnedState() {
        _uiState.update { it.copy(isLoading = false, toolsets = emptyList(), errorMessage = null) }
    }

    fun loadToolsets(forceRefresh: Boolean = false) {
        safeLaunchSwrLoad(
            cache = toolsetsCache,
            forceRefresh = forceRefresh,
            onCacheHit = { cached ->
                _uiState.update { it.copy(isLoading = false, toolsets = cached, errorMessage = null) }
            },
            apiCall = { safeApiCall { ApiClient.hermesApi.getToolsets() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                _uiState.update { it.copy(isLoading = false, toolsets = data.orEmpty()) }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiText.res(R.string.toolsets_msg_load_failed, errorMsg),
                    )
                }
            },
        )
    }

    fun toggleToolset(toolset: Toolset) {
        val originalEnabled = toolset.enabled
        val targetEnabled = !originalEnabled
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()

        // Optimistic UI update
        _uiState.update { state ->
            state.copy(
                toolsets =
                    state.toolsets.map {
                        if (it.name == toolset.name) it.copy(enabled = targetEnabled) else it
                    },
            )
        }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.toggleToolset(toolset.name, ToolsetToggleRequest(targetEnabled)) }
                }
            val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
            if (requestScope != null && currentScope != requestScope) return@launch
            if (result is NetworkResult.Success) {
                val updated = _uiState.value.toolsets
                if (requestScope != null) {
                    toolsetsCache.put(requestScope.scopedKey("default"), updated)
                }
            } else if (result is NetworkResult.Failure) {
                revertToggle(
                    toolset.name,
                    originalEnabled,
                    UiText.res(R.string.toolsets_msg_toggle_failed, result.error.message),
                )
                val reverted = _uiState.value.toolsets
                if (requestScope != null) {
                    toolsetsCache.put(requestScope.scopedKey("default"), reverted)
                }
            }
        }
    }

    private fun revertToggle(
        name: String,
        originalEnabled: Boolean,
        errorMsg: UiText,
    ) {
        _uiState.update { state ->
            state.copy(
                toolsets =
                    state.toolsets.map {
                        if (it.name == name) it.copy(enabled = originalEnabled) else it
                    },
                toastMessage = errorMsg,
            )
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
