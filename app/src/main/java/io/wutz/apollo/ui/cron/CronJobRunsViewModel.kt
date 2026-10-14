package io.wutz.apollo.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.model.CronJobRun
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CronJobRunsUiState(
    val jobId: String? = null,
    val runs: List<CronJobRun> = emptyList(),
    val isLoading: Boolean = false,
    val error: UiText? = null,
)

/**
 * Run history of the cron job opened in the detail dialog: agent sessions and script-only fires,
 * newest first (`GET /api/cron/jobs/{id}/runs`). Only one job is shown at a time.
 */
class CronJobRunsViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(CronJobRunsUiState())
    val uiState: StateFlow<CronJobRunsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun load(jobId: String) {
        loadJob?.cancel()
        _uiState.update { state ->
            // Keep the rows of the same job while refreshing; another job starts empty.
            val runs = if (state.jobId == jobId) state.runs else emptyList()
            CronJobRunsUiState(jobId = jobId, runs = runs, isLoading = true)
        }
        loadJob =
            viewModelScope.launch {
                val result = safeApiCall { ApiClient.hermesApi.getCronJobRuns(jobId, limit = RUN_LIMIT) }
                _uiState.update { state ->
                    if (state.jobId != jobId) return@update state
                    when (result) {
                        is NetworkResult.Success -> {
                            state.copy(runs = result.data.runs, isLoading = false, error = null)
                        }

                        is NetworkResult.Failure -> {
                            state.copy(
                                isLoading = false,
                                error = UiText.res(R.string.cron_runs_load_failed, result.error.message),
                            )
                        }
                    }
                }
            }
    }

    companion object {
        const val RUN_LIMIT = 20
    }
}
