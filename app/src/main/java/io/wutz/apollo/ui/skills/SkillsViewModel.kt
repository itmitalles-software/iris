package io.wutz.apollo.ui.skills

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.HubSkill
import io.wutz.apollo.data.model.SaveSkillContentRequest
import io.wutz.apollo.data.model.Skill
import io.wutz.apollo.data.model.SkillHubInstallRequest
import io.wutz.apollo.data.model.SkillHubSource
import io.wutz.apollo.data.model.SkillHubUninstallRequest
import io.wutz.apollo.data.model.SkillScanResponse
import io.wutz.apollo.data.model.ToggleSkillRequest
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
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

enum class SkillsViewMode {
    INSTALLED,
    HUB,
}

enum class SkillFilter(
    val labelRes: Int,
) {
    ALL_STATUSES(R.string.skills_status_all),
    ENABLED(R.string.skills_status_enabled),
    DISABLED(R.string.skills_status_disabled),
}

data class SkillsUiState(
    val isLoading: Boolean = false,
    val skills: List<Skill> = emptyList(),
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    val isLoadingContent: Boolean = false,
    val editingSkillName: String? = null,
    val skillContent: String? = null,
    val isSavingContent: Boolean = false,
    val saveContentSuccess: Boolean = false,
    // View mode
    val viewMode: SkillsViewMode = SkillsViewMode.INSTALLED,
    // Hub search
    val hubQuery: String = "",
    val hubResults: List<HubSkill> = emptyList(),
    val isHubSearching: Boolean = false,
    val hubSearchError: UiText? = null,
    // Hub sources + featured (landing, before any search)
    val hubSources: List<SkillHubSource> = emptyList(),
    val hubFeatured: List<HubSkill> = emptyList(),
    val isHubSourcesLoading: Boolean = false,
    val hubSourcesError: UiText? = null,
    // Hub security scan
    val hubScanIdentifier: String? = null,
    val hubScanResult: SkillScanResponse? = null,
    val isHubScanning: Boolean = false,
    val hubScanError: String? = null,
    // Hub detail preview (full description/content via preview endpoint)
    val hubPreviewIdentifier: String? = null,
    val hubPreviewContent: String? = null,
    val isHubPreviewing: Boolean = false,
    val hubPreviewError: String? = null,
    // Hub install
    val isInstalling: Boolean = false,
    val installingSkillName: String? = null,
    val isUninstalling: Boolean = false,
    val uninstallingSkillName: String? = null,
    // Preview
    val previewSkillName: String? = null,
    val previewSkillContent: String? = null,
    val isLoadingPreview: Boolean = false,
    // Source filter for installed tab
    val sourceFilter: String? = null,
)

class SkillsViewModel(
    application: Application,
) : AndroidViewModel(application),
    ToastHost {
    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private val skillsCache = SwrCache<String, List<Skill>>()
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    fun clearScopeOwnedState() {
        _uiState.update {
            it.copy(
                isLoading = false,
                skills = emptyList(),
                errorMessage = null,
                editingSkillName = null,
                skillContent = null,
                isLoadingContent = false,
                isSavingContent = false,
                saveContentSuccess = false,
                previewSkillName = null,
                previewSkillContent = null,
                isLoadingPreview = false,
            )
        }
    }

    fun loadSkills(forceRefresh: Boolean = false) {
        loadJob =
            safeLaunchSwrLoad(
                cache = skillsCache,
                forceRefresh = forceRefresh,
                currentJob = loadJob,
                onCacheHit = { cached ->
                    _uiState.update { it.copy(isLoading = false, skills = cached, errorMessage = null) }
                },
                apiCall = { safeApiCall { ApiClient.hermesApi.getSkills() } },
                onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
                onSuccess = { data ->
                    _uiState.update { it.copy(isLoading = false, skills = data.orEmpty()) }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = UiText.res(R.string.skills_msg_load_failed, errorMsg),
                        )
                    }
                },
            )
    }

    fun setViewMode(mode: SkillsViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
        if (mode == SkillsViewMode.INSTALLED && _uiState.value.skills.isEmpty()) {
            loadSkills()
        }
        if (mode == SkillsViewMode.HUB && _uiState.value.hubSources.isEmpty()) {
            loadHubSources()
        }
    }

    fun setSourceFilter(source: String?) {
        _uiState.update { it.copy(sourceFilter = source) }
    }

    fun setHubQuery(query: String) {
        _uiState.update { it.copy(hubQuery = query) }
    }

    // ── Hub search ──────────────────────────────────────────────────────────

    fun searchHub(query: String) {
        _uiState.update { it.copy(hubQuery = query, hubSearchError = null) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.update { it.copy(hubResults = emptyList(), isHubSearching = false) }
            return
        }
        searchJob =
            safeLaunchLoad(
                currentJob = searchJob,
                apiCall = {
                    safeApiCall { ApiClient.hermesApi.searchSkillsHub(q = query) }
                },
                onStart = { _uiState.update { it.copy(isHubSearching = true) } },
                onSuccess = { data ->
                    _uiState.update { it.copy(isHubSearching = false, hubResults = data.results.orEmpty()) }
                },
                onError = { errorMsg ->
                    _uiState.update {
                        it.copy(
                            isHubSearching = false,
                            hubSearchError = UiText.res(R.string.skills_msg_search_failed, errorMsg),
                        )
                    }
                },
            )
    }

    fun clearHubSearch() {
        _uiState.update {
            it.copy(
                hubQuery = "",
                hubResults = emptyList(),
                hubSearchError = null,
            )
        }
    }

    // ── Hub sources + featured landing ────────────────────────────────────

    /**
     * Loads the configured hub sources and featured skills (the browse-hub
     * landing state shown before any search runs).
     */
    fun loadHubSources() {
        safeLaunchLoad(
            apiCall = { safeApiCall { ApiClient.hermesApi.getSkillHubSources() } },
            onStart = {
                _uiState.update {
                    it.copy(isHubSourcesLoading = true, hubSourcesError = null)
                }
            },
            onSuccess = { data ->
                _uiState.update {
                    it.copy(
                        isHubSourcesLoading = false,
                        hubSources = data.sources,
                        hubFeatured = data.featured,
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isHubSourcesLoading = false,
                        hubSourcesError = UiText.res(R.string.skills_msg_hub_sources_failed, errorMsg),
                    )
                }
            },
        )
    }

    // ── Hub security scan ─────────────────────────────────────────────────

    /**
     * Runs the install-time security scan on a hub skill WITHOUT installing
     * it, so the user sees the safety verdict before deciding.
     */
    fun scanHubSkill(identifier: String) {
        if (identifier.isBlank()) return
        _uiState.update {
            it.copy(
                hubScanIdentifier = identifier,
                isHubScanning = true,
                hubScanResult = null,
                hubScanError = null,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.scanHubSkill(identifier = identifier) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isHubScanning = false,
                            hubScanResult = result.data,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isHubScanning = false,
                            hubScanError = result.error.message,
                        )
                    }
                }
            }
        }
    }

    fun clearHubScan() {
        _uiState.update {
            it.copy(
                hubScanIdentifier = null,
                hubScanResult = null,
                isHubScanning = false,
                hubScanError = null,
            )
        }
    }

    // ── Hub detail preview ──────────────────────────────────────────────────

    /**
     * Fetches the full skill content via the preview endpoint so the detail
     * dialog can show the complete description (search results only carry a
     * truncated snippet).
     */
    fun previewHubSkill(identifier: String) {
        if (identifier.isBlank()) return
        _uiState.update {
            it.copy(
                hubPreviewIdentifier = identifier,
                isHubPreviewing = true,
                hubPreviewError = null,
                hubPreviewContent = null,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.previewHubSkill(identifier = identifier) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isHubPreviewing = false,
                            hubPreviewContent = result.data.content,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isHubPreviewing = false,
                            hubPreviewError = result.error.message,
                        )
                    }
                }
            }
        }
    }

    // ── Hub install ─────────────────────────────────────────────────────────

    fun installSkill(identifier: String) {
        _uiState.update {
            it.copy(
                isInstalling = true,
                installingSkillName = identifier,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.installHubSkill(SkillHubInstallRequest(identifier = identifier)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            installingSkillName = null,
                            toastMessage = UiText.res(R.string.skills_msg_installed, identifier),
                        )
                    }
                    // Refresh installed skills
                    loadSkills(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isInstalling = false,
                            installingSkillName = null,
                            toastMessage =
                                UiText.res(
                                    R.string.skills_msg_install_failed,
                                    identifier,
                                    result.error.message,
                                ),
                        )
                    }
                }
            }
        }
    }

    fun uninstallSkill(name: String) {
        _uiState.update {
            it.copy(
                isUninstalling = true,
                uninstallingSkillName = name,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.uninstallHubSkill(SkillHubUninstallRequest(name = name)) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isUninstalling = false,
                            uninstallingSkillName = null,
                            toastMessage = UiText.res(R.string.skills_msg_uninstalled, name),
                        )
                    }
                    loadSkills(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isUninstalling = false,
                            uninstallingSkillName = null,
                            toastMessage = UiText.res(R.string.skills_msg_uninstall_failed, name, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun clearPreview() {
        _uiState.update {
            it.copy(
                previewSkillName = null,
                previewSkillContent = null,
                isLoadingPreview = false,
            )
        }
    }

    // ── Existing functionality ──────────────────────────────────────────────

    fun toggleSkill(skill: Skill) {
        val originalEnabled = skill.enabled
        val targetEnabled = !originalEnabled
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()

        // Optimistically update
        _uiState.update { state ->
            state.copy(
                skills =
                    state.skills.map {
                        if (it.name == skill.name) it.copy(enabled = targetEnabled) else it
                    },
            )
        }

        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.toggleSkill(ToggleSkillRequest(skill.name, targetEnabled)) }
                }
            val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
            if (requestScope != null && currentScope != requestScope) return@launch
            if (result is NetworkResult.Success) {
                val updated = _uiState.value.skills
                if (requestScope != null) {
                    skillsCache.put(requestScope.scopedKey("default"), updated)
                }
            } else if (result is NetworkResult.Failure) {
                revertSkillToggle(
                    skill.name,
                    originalEnabled,
                    UiText.res(R.string.skills_msg_toggle_failed, result.error.message),
                )
                val reverted = _uiState.value.skills
                if (requestScope != null) {
                    skillsCache.put(requestScope.scopedKey("default"), reverted)
                }
            }
        }
    }

    private fun revertSkillToggle(
        name: String,
        originalEnabled: Boolean,
        errorMsg: UiText,
    ) {
        _uiState.update { state ->
            state.copy(
                skills =
                    state.skills.map {
                        if (it.name == name) it.copy(enabled = originalEnabled) else it
                    },
                toastMessage = errorMsg,
            )
        }
    }

    fun loadSkillContent(skillName: String) {
        _uiState.update {
            it.copy(
                isLoadingContent = true,
                editingSkillName = skillName,
                skillContent = null,
                saveContentSuccess = false,
            )
        }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.getSkillContent(skillName) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingContent = false,
                            skillContent = result.data.content.orEmpty(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingContent = false,
                            editingSkillName = null,
                            toastMessage = UiText.res(R.string.skills_msg_content_load_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun saveSkillContent(
        skillName: String,
        content: String,
    ) {
        _uiState.update { it.copy(isSavingContent = true, saveContentSuccess = false) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.saveSkillContent(
                            SaveSkillContentRequest(
                                name = skillName,
                                content = content,
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSavingContent = false,
                            saveContentSuccess = true,
                            skillContent = content,
                            toastMessage = UiText.res(R.string.skills_msg_saved),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSavingContent = false,
                            toastMessage = UiText.res(R.string.skills_msg_save_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun updateSkillsFromHub() {
        viewModelScope.launch {
            _uiState.update { it.copy(toastMessage = UiText.res(R.string.skills_msg_hub_updating)) }
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.updateSkillsFromHub() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = UiText.res(R.string.skills_msg_hub_updated)) }
                    loadSkills(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.skills_msg_hub_update_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun clearEditor() {
        _uiState.update {
            it.copy(
                editingSkillName = null,
                skillContent = null,
                isLoadingContent = false,
                isSavingContent = false,
                saveContentSuccess = false,
            )
        }
    }

    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveContentSuccess = false) }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
