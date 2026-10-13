package io.wutz.apollo.ui.toolsets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.model.EnvVarDeleteRequest
import io.wutz.apollo.data.model.EnvVarRevealRequest
import io.wutz.apollo.data.model.ToolsetConfigResponse
import io.wutz.apollo.data.model.ToolsetEnvUpdateRequest
import io.wutz.apollo.data.model.ToolsetPostSetupRequest
import io.wutz.apollo.data.model.ToolsetProvider
import io.wutz.apollo.data.model.ToolsetProviderSelectRequest
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Live state of a spawned post-setup install (mirrors the desktop log tail). */
data class PostSetupState(
    val key: String,
    val running: Boolean,
    val lines: List<String> = emptyList(),
    val exitCode: Int? = null,
)

data class ToolsetDetailUiState(
    val isLoading: Boolean = false,
    val config: ToolsetConfigResponse? = null,
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    val expandedProvider: String? = null,
    val selectingProvider: String? = null,
    val savingEnvKey: String? = null,
    val deletingEnvKey: String? = null,
    val revealedValues: Map<String, String> = emptyMap(),
    val postSetup: PostSetupState? = null,
)

/**
 * Per-toolset management — issue #782. Contracts verified against
 * hermes-agent web_routers/tools.py; mirrors the desktop
 * toolset-config-panel (provider pick, env-var save/clear/reveal, post-setup
 * spawn + action-log tail). Deliberately avoids an explicit IO hop: Retrofit
 * suspend calls already run off the caller thread, and skipping
 * `withContext(Dispatchers.IO)` keeps unit tests free of the static
 * Dispatchers mock that bleeds across test classes.
 *
 * NOTE: this ViewModel is shared across all toolset detail nav entries — the
 * app's NavDisplay uses the activity-level ViewModelStoreOwner, so
 * class-keyed `viewModel {}` returns the same instance for every entry.
 * The toolset name is therefore NOT a constructor arg (it would freeze the
 * first-opened toolset into every entry — bug found on-device 2026-08-06).
 * The screen drives [setToolset] + [loadConfig] per entry instead.
 */
class ToolsetDetailViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ToolsetDetailUiState())
    val uiState: StateFlow<ToolsetDetailUiState> = _uiState.asStateFlow()

    private var toolsetName: String = ""
    private var postSetupJob: Job? = null

    /**
     * Point the shared instance at [name] and drop all state from the
     * previously viewed toolset (config, revealed secrets, running
     * post-setup) so one toolset's data never bleeds into another.
     */
    fun setToolset(name: String) {
        if (name == toolsetName) return
        toolsetName = name
        postSetupJob?.cancel()
        _uiState.value = ToolsetDetailUiState()
    }

    fun loadConfig() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.getToolsetConfig(toolsetName) }
            _uiState.update { state ->
                when (result) {
                    is NetworkResult.Success -> {
                        val config = result.data
                        // Default the expanded provider to the one active in
                        // config (is_active / active_provider), else the first
                        // provider — claimed once so refreshes never yank the
                        // user's open/collapsed choice.
                        val defaultExpanded =
                            config.providers.firstOrNull { it.isActive || it.name == config.activeProvider }?.name
                                ?: config.providers.firstOrNull()?.name
                        state.copy(
                            isLoading = false,
                            config = config,
                            errorMessage = null,
                            expandedProvider = if (state.config == null) defaultExpanded else state.expandedProvider,
                        )
                    }

                    is NetworkResult.Failure -> {
                        state.copy(
                            isLoading = false,
                            errorMessage = UiText.res(R.string.toolsets_msg_config_load_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun toggleProviderExpanded(name: String) {
        _uiState.update {
            it.copy(expandedProvider = if (it.expandedProvider == name) null else name)
        }
    }

    fun selectProvider(provider: ToolsetProvider) {
        if (_uiState.value.selectingProvider != null) return
        _uiState.update { it.copy(selectingProvider = provider.name) }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.selectToolsetProvider(
                        toolsetName,
                        ToolsetProviderSelectRequest(provider = provider.name),
                    )
                }
            _uiState.update { state ->
                when (result) {
                    is NetworkResult.Success -> {
                        val message =
                            if (result.data.needsNousAuth) {
                                UiText.res(R.string.toolsets_msg_backend_set_needs_auth, provider.name)
                            } else {
                                UiText.res(R.string.toolsets_msg_backend_set, provider.name)
                            }
                        state.copy(
                            selectingProvider = null,
                            config =
                                state.config?.let { cfg ->
                                    cfg.copy(
                                        activeProvider = provider.name,
                                        providers = cfg.providers.map { it.copy(isActive = it.name == provider.name) },
                                    )
                                },
                            toastMessage = message,
                        )
                    }

                    is NetworkResult.Failure -> {
                        state.copy(
                            selectingProvider = null,
                            toastMessage =
                                UiText.res(
                                    R.string.toolsets_msg_backend_select_failed,
                                    result.error.message,
                                ),
                        )
                    }
                }
            }
        }
    }

    fun saveEnvVar(
        key: String,
        value: String,
    ) {
        if (value.isBlank()) return
        _uiState.update { it.copy(savingEnvKey = key) }
        viewModelScope.launch {
            val result =
                safeApiCall {
                    ApiClient.hermesApi.saveToolsetEnv(
                        toolsetName,
                        ToolsetEnvUpdateRequest(env = mapOf(key to value)),
                    )
                }
            _uiState.update { state ->
                when (result) {
                    is NetworkResult.Success -> {
                        state.copy(
                            savingEnvKey = null,
                            config = state.config?.let { patchEnvIsSet(it, key, true) },
                            toastMessage = UiText.res(R.string.toolsets_msg_env_saved, key),
                        )
                    }

                    is NetworkResult.Failure -> {
                        state.copy(
                            savingEnvKey = null,
                            toastMessage = UiText.res(R.string.toolsets_msg_env_save_failed, key, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun clearEnvVar(key: String) {
        _uiState.update { it.copy(deletingEnvKey = key) }
        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.deleteEnvVar(EnvVarDeleteRequest(key)) }
            _uiState.update { state ->
                when (result) {
                    is NetworkResult.Success -> {
                        state.copy(
                            deletingEnvKey = null,
                            config = state.config?.let { patchEnvIsSet(it, key, false) },
                            revealedValues = state.revealedValues - key,
                            toastMessage = UiText.res(R.string.toolsets_msg_env_cleared, key),
                        )
                    }

                    is NetworkResult.Failure -> {
                        state.copy(
                            deletingEnvKey = null,
                            toastMessage =
                                UiText.res(
                                    R.string.toolsets_msg_env_clear_failed,
                                    key,
                                    result.error.message,
                                ),
                        )
                    }
                }
            }
        }
    }

    fun revealEnvVar(key: String) {
        viewModelScope.launch {
            val result = safeApiCall { ApiClient.hermesApi.revealEnvVar(EnvVarRevealRequest(key)) }
            _uiState.update { state ->
                when (result) {
                    is NetworkResult.Success -> {
                        state.copy(revealedValues = state.revealedValues + (key to result.data.value))
                    }

                    is NetworkResult.Failure -> {
                        state.copy(
                            toastMessage =
                                UiText.res(
                                    R.string.toolsets_msg_env_reveal_failed,
                                    key,
                                    result.error.message,
                                ),
                        )
                    }
                }
            }
        }
    }

    fun hideEnvVar(key: String) {
        _uiState.update { state ->
            state.copy(revealedValues = state.revealedValues - key)
        }
    }

    /**
     * Spawn the provider's post-setup install hook and tail its action log
     * (the desktop PostSetupRunner pattern): poll
     * GET /api/actions/{name}/status every ~1.2s until it exits, then refresh
     * the config so readiness pills reflect the finished install.
     */
    fun runPostSetup(key: String) {
        if (_uiState.value.postSetup?.running == true) return
        postSetupJob?.cancel()
        _uiState.update { it.copy(postSetup = PostSetupState(key = key, running = true)) }
        postSetupJob =
            viewModelScope.launch {
                val spawn =
                    safeApiCall {
                        ApiClient.hermesApi.runToolsetPostSetup(
                            toolsetName,
                            ToolsetPostSetupRequest(key = key),
                        )
                    }
                when (spawn) {
                    is NetworkResult.Failure -> {
                        _uiState.update {
                            it.copy(
                                postSetup = null,
                                toastMessage =
                                    UiText.res(
                                        R.string.toolsets_msg_setup_start_failed,
                                        spawn.error.message,
                                    ),
                            )
                        }
                    }

                    is NetworkResult.Success -> {
                        if (spawn.data.ok != true) {
                            _uiState.update {
                                it.copy(
                                    postSetup = null,
                                    toastMessage = UiText.res(R.string.toolsets_msg_setup_spawn_rejected),
                                )
                            }
                            return@launch
                        }
                        val actionName = spawn.data.name ?: "tools-post-setup"
                        while (isActive) {
                            delay(1200)
                            val status = safeApiCall { ApiClient.hermesApi.getActionStatus(actionName) }
                            if (status is NetworkResult.Success) {
                                val data = status.data
                                val done = data.running != true
                                _uiState.update { state ->
                                    state.copy(
                                        postSetup =
                                            state.postSetup?.copy(
                                                lines = data.lines ?: state.postSetup.lines,
                                                running = data.running == true,
                                                exitCode = data.exit_code,
                                            ),
                                    )
                                }
                                if (done) {
                                    loadConfig()
                                    break
                                }
                            }
                        }
                    }
                }
            }
    }

    fun closePostSetup() {
        postSetupJob?.cancel()
        _uiState.update { it.copy(postSetup = null) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun patchEnvIsSet(
        config: ToolsetConfigResponse,
        key: String,
        isSet: Boolean,
    ): ToolsetConfigResponse =
        config.copy(
            providers =
                config.providers.map { provider ->
                    provider.copy(
                        envVars = provider.envVars.map { if (it.key == key) it.copy(isSet = isSet) else it },
                    )
                },
        )
}
