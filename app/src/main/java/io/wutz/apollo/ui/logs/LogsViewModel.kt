package io.wutz.apollo.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.LogResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.safeLaunchSwrLoad
import io.wutz.apollo.ui.common.toUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Server-side log filters, mirroring the desktop dashboard's LogsPage
 * (`GET /api/logs?file=&lines=&level=&component=`).
 */
data class LogsFilters(
    val file: String = "agent",
    val level: String = "ALL",
    val component: String = "all",
    val lines: Int = 100,
)

data class LogsUiState(
    val isLoading: Boolean = false,
    val logs: List<String> = emptyList(),
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    val filters: LogsFilters = LogsFilters(),
)

class LogsViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var autoRefreshJob: Job? = null
    private val logsCache = SwrCache<String, LogResponse>()

    fun clearScopeOwnedState() {
        _uiState.update { it.copy(isLoading = false, logs = emptyList(), errorMessage = null) }
    }

    fun loadLogs(forceRefresh: Boolean = false) {
        val filters = _uiState.value.filters
        val cacheKey = "${filters.file}:${filters.level}:${filters.component}:${filters.lines}"
        loadJob =
            safeLaunchSwrLoad(
                cache = logsCache,
                cacheKey = cacheKey,
                forceRefresh = forceRefresh,
                currentJob = loadJob,
                onCacheHit = { data ->
                    val logsList = data.lines ?: data.logs ?: emptyList()
                    _uiState.update { it.copy(isLoading = false, logs = logsList, errorMessage = null) }
                },
                apiCall = {
                    safeApiCall {
                        ApiClient.hermesApi.getLogs(
                            file = filters.file,
                            lines = filters.lines,
                            level = filters.level,
                            component = filters.component,
                        )
                    }
                },
                onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
                onSuccess = { data ->
                    val logsList = data.lines ?: data.logs ?: emptyList()
                    _uiState.update { it.copy(isLoading = false, logs = logsList) }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = UiText.res(R.string.logs_msg_load_failed, errorMsg.toUiText()),
                        )
                    }
                },
            )
    }

    /** Update the server-side filters and immediately reload. */
    fun setFilters(filters: LogsFilters) {
        _uiState.update { it.copy(filters = filters) }
        loadLogs()
    }

    /** Start auto-refreshing logs every 5 seconds. */
    fun startAutoRefresh() {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(5_000)
                    loadLogs()
                }
            }
    }

    /** Stop auto-refreshing logs. */
    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
