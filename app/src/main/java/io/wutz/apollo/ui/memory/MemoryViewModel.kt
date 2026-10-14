package io.wutz.apollo.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.LearningGraphResponse
import io.wutz.apollo.data.model.MemoryResetRequest
import io.wutz.apollo.data.model.MemoryResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryUiState(
    val isLoading: Boolean = false,
    val memory: MemoryResponse? = null,
    val learningGraph: LearningGraphResponse? = null,
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    val resetting: String? = null,
)

/**
 * Memory management home (moved out of the System tab) — active provider,
 * builtin memory files with reset, and the provider list that drills into
 * per-provider config/setup (issue #783). Deliberately no explicit IO hop:
 * Retrofit suspend calls already run off the caller thread, keeping tests
 * free of the static Dispatchers mock that bleeds across test classes.
 */
class MemoryViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        // Scope transitions are driven by ProfileSwitchCoordinator (profile switch and
        // connection switch). An additional AuthManager.dataScopeFlow collector would
        // double-fire on the same transition and issue a duplicate request, so the
        // coordinator flows remain the single trigger.
        viewModelScope.launch {
            ProfileSwitchCoordinator.switched.collect {
                onDataScopeChanged(AuthManager.currentDataScope())
            }
        }
        viewModelScope.launch {
            ProfileSwitchCoordinator.connectionSwitched.collect {
                onDataScopeChanged(AuthManager.currentDataScope())
            }
        }
    }

    private var loadJob: Job? = null
    private val memoryCache = SwrCache<String, MemoryResponse>()

    private fun onDataScopeChanged(newScope: DataScope?) {
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                isLoading = false,
                memory = null,
                learningGraph = null,
                resetting = null,
                errorMessage = null,
            )
        }
        // A scope switch must silently reload the new context's data. Clearing alone
        // would leave the screen empty when it is not the composition performing the load.
        load(silent = true)
    }

    fun load(
        silent: Boolean = false,
        forceRefresh: Boolean = false,
    ) {
        if (_uiState.value.isLoading) return
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
        val scopedKey = requestScope?.inMemoryKey("default")
        if (forceRefresh && scopedKey != null) memoryCache.remove(scopedKey)
        val cached = if (!forceRefresh && scopedKey != null) memoryCache.get(scopedKey) else null
        if (cached != null) {
            _uiState.update { it.copy(isLoading = false, memory = cached, errorMessage = null) }
        } else if (!silent) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        }
        loadJob =
            viewModelScope.launch {
                coroutineScope {
                    val memoryDeferred = async { safeApiCall { ApiClient.hermesApi.getMemory() } }
                    val graphDeferred = async { safeApiCall { ApiClient.hermesApi.getLearningGraph() } }

                    val memoryResult = memoryDeferred.await()
                    val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                    if (currentScope != requestScope) return@coroutineScope

                    when (memoryResult) {
                        is NetworkResult.Success -> {
                            if (scopedKey != null) memoryCache.put(scopedKey, memoryResult.data)
                            _uiState.update { it.copy(isLoading = false, memory = memoryResult.data) }
                        }

                        is NetworkResult.Failure -> {
                            if (cached == null || forceRefresh) {
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        errorMessage = memoryResult.error.toUiText(),
                                    )
                                }
                            }
                        }
                    }

                    val graphResult = graphDeferred.await()
                    if (graphResult is NetworkResult.Success) {
                        _uiState.update { it.copy(learningGraph = graphResult.data) }
                    }
                }
            }
    }

    fun resetMemory(target: String) {
        if (_uiState.value.resetting != null) return
        _uiState.update { it.copy(resetting = target) }
        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.resetMemory(MemoryResetRequest(target = target)) }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            resetting = null,
                            toastMessage = UiText.res(R.string.memory_msg_reset_done, target),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            resetting = null,
                            toastMessage = UiText.failure(R.string.memory_msg_reset_failed, result.error.toUiText()),
                        )
                    }
                }
            }
            load(forceRefresh = true)
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
