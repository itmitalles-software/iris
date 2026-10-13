package io.wutz.apollo.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.CreateProfileRequest
import io.wutz.apollo.data.model.HubSkill
import io.wutz.apollo.data.model.ModelProvider
import io.wutz.apollo.data.model.PinnedModel
import io.wutz.apollo.data.model.ProfileDescribeAutoRequest
import io.wutz.apollo.data.model.ProfileInfo
import io.wutz.apollo.data.model.ProfilesResponse
import io.wutz.apollo.data.model.RenameProfileRequest
import io.wutz.apollo.data.model.Skill
import io.wutz.apollo.data.model.UpdateProfileDescriptionRequest
import io.wutz.apollo.data.model.UpdateProfileModelRequest
import io.wutz.apollo.data.model.UpdateProfileSoulRequest
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProfilesUiState(
    val isLoading: Boolean = false,
    val profiles: List<ProfileInfo> = emptyList(),
    val activeProfileName: String? = null,
    val sharedGatewayProfiles: List<String> = emptyList(),
    val selectedSoulContent: String? = null,
    val isLoadingSoul: Boolean = false,
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    val showHidden: Boolean = false,
    val hiddenProfiles: Set<String> = emptySet(),
    // Rename / delete / auto-describe / setup-command states
    val isAutoDescribing: Boolean = false,
    val setupCommand: String? = null,
    val isLoadingSetupCommand: Boolean = false,
    // Model picker (issue #781 — Set Model uses the shared ModelPickerDialog)
    val modelPickerPinned: List<PinnedModel> = emptyList(),
    // Profile Builder states
    val modelProviders: List<ModelProvider> = emptyList(),
    val isLoadingBuilderData: Boolean = false,
    val availableSkills: List<Skill> = emptyList(),
    val hubSearchResults: List<HubSkill> = emptyList(),
    val isSearchingHub: Boolean = false,
) {
    val hasHiddenProfiles: Boolean
        get() = profiles.any { it.name in hiddenProfiles || it.botMeta()?.hidden == true }

    val displayProfiles: List<ProfileInfo>
        get() =
            profiles.filter { profile ->
                if (showHidden) {
                    true
                } else {
                    !(profile.name in hiddenProfiles || profile.botMeta()?.hidden == true)
                }
            }
}

private fun normalizeSharedGatewayProfiles(names: List<String>?): List<String> =
    names
        .orEmpty()
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

class ProfilesViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(hiddenProfiles = AuthManager.getHiddenProfiles().toSet()) }
    }

    fun toggleShowHidden() {
        _uiState.update { it.copy(showHidden = !it.showHidden) }
    }

    fun hideProfile(name: String) {
        AuthManager.hideProfile(name)
        _uiState.update {
            it.copy(
                hiddenProfiles = AuthManager.getHiddenProfiles().toSet(),
                toastMessage = UiText.res(R.string.profiles_msg_hidden, name),
            )
        }
    }

    fun unhideProfile(name: String) {
        AuthManager.unhideProfile(name)
        _uiState.update {
            it.copy(
                hiddenProfiles = AuthManager.getHiddenProfiles().toSet(),
                toastMessage = UiText.res(R.string.profiles_msg_unhidden, name),
            )
        }
    }

    private val profilesCache = SwrCache<String, ProfilesResponse>()

    /**
     * Reset state owned by the previous server/profile context (PR #1192 cache scoping).
     * Profiles, their soul content, setup commands, and builder data all belong to the
     * server that served them and must not be shown for a new context.
     */
    fun clearScopeOwnedState() {
        _uiState.update {
            it.copy(
                isLoading = false,
                profiles = emptyList(),
                activeProfileName = null,
                sharedGatewayProfiles = emptyList(),
                selectedSoulContent = null,
                isLoadingSoul = false,
                errorMessage = null,
                isAutoDescribing = false,
                setupCommand = null,
                isLoadingSetupCommand = false,
                modelPickerPinned = emptyList(),
                modelProviders = emptyList(),
                isLoadingBuilderData = false,
                availableSkills = emptyList(),
                hubSearchResults = emptyList(),
                isSearchingHub = false,
            )
        }
    }

    fun loadProfiles(forceRefresh: Boolean = false) {
        val requestScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
        val scopedKey = requestScope?.inMemoryKey("default")
        if (forceRefresh && scopedKey != null) profilesCache.remove(scopedKey)
        val cached = if (!forceRefresh && scopedKey != null) profilesCache.get(scopedKey) else null
        if (cached != null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    profiles = cached.profiles.orEmpty(),
                    hiddenProfiles = AuthManager.getHiddenProfiles().toSet(),
                    errorMessage = null,
                )
            }
        } else {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val profilesDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getProfiles() } }
                    val activeDeferred =
                        async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getActiveProfile() } }
                    val statusDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getStatus() } }

                    val profilesResult = profilesDeferred.await()
                    val activeResult = activeDeferred.await()

                    val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                    if (currentScope != requestScope) return@coroutineScope

                    if (profilesResult is NetworkResult.Success && activeResult is NetworkResult.Success) {
                        if (scopedKey != null) profilesCache.put(scopedKey, profilesResult.data)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                profiles = profilesResult.data.profiles.orEmpty(),
                                activeProfileName = activeResult.data.active,
                                sharedGatewayProfiles = emptyList(),
                                hiddenProfiles = AuthManager.getHiddenProfiles().toSet(),
                                errorMessage = null,
                            )
                        }

                        val statusResult = statusDeferred.await()
                        if (statusResult is NetworkResult.Success) {
                            _uiState.update {
                                it.copy(
                                    sharedGatewayProfiles =
                                        normalizeSharedGatewayProfiles(statusResult.data.gatewaySharedWith),
                                )
                            }
                        }
                    } else if (cached == null || forceRefresh) {
                        val profilesError = partialLoadStatus(profilesResult)
                        val activeError = partialLoadStatus(activeResult)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage =
                                    UiText.res(R.string.profiles_msg_load_partial_failed, profilesError, activeError),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                val currentScope = runCatching { AuthManager.currentDataScope() }.getOrNull()
                if (currentScope == requestScope && (cached == null || forceRefresh)) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = UiText.res(R.string.profiles_msg_load_failed, e.message.toString()),
                        )
                    }
                }
            }
        }
    }

    fun selectActiveProfile(name: String) {
        val originalActive = _uiState.value.activeProfileName
        // Optimistically update active profile name
        _uiState.update { it.copy(activeProfileName = name) }

        viewModelScope.launch {
            // The coordinator performs the whole re-home atomically: server
            // flip → local selection persist → switch broadcast → socket
            // re-dial (desktop's requestFreshSession + socket swap).
            when (val result = ProfileSwitchCoordinator.switchProfile(name)) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = UiText.res(R.string.profiles_msg_switched, name)) }
                    loadProfiles(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            activeProfileName = originalActive,
                            toastMessage = UiText.res(R.string.profiles_msg_switch_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun loadSoul(profileName: String) {
        _uiState.update { it.copy(isLoadingSoul = true, selectedSoulContent = null) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.getProfileSoul(profileName) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSoul = false,
                            selectedSoulContent = result.data.content,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSoul = false,
                            toastMessage = UiText.res(R.string.profiles_msg_load_soul_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun saveSoul(
        profileName: String,
        content: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.updateProfileSoul(
                            profileName,
                            UpdateProfileSoulRequest(content),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            selectedSoulContent = null,
                            toastMessage = UiText.res(R.string.profiles_msg_soul_updated),
                        )
                    }
                    loadProfiles(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.profiles_msg_soul_save_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun updateModel(
        profileName: String,
        provider: String,
        model: String,
    ) {
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.updateProfileModel(
                            profileName,
                            UpdateProfileModelRequest(provider, model),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = UiText.res(R.string.profiles_msg_model_settings_updated)) }
                    loadProfiles(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage =
                                UiText.res(
                                    R.string.profiles_msg_model_settings_failed,
                                    result.error.message,
                                ),
                        )
                    }
                }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    /** Server error of one half of a combined load, or "Success" when that half loaded. */
    private fun partialLoadStatus(result: NetworkResult<*>): UiText =
        (result as? NetworkResult.Failure)?.error?.message?.let { UiText.raw(it) }
            ?: UiText.res(R.string.profiles_msg_status_success)

    fun closeSoulDialog() {
        _uiState.update { it.copy(selectedSoulContent = null) }
    }

    fun cloneProfile(
        sourceProfileName: String,
        newProfileName: String,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.createProfile(
                            CreateProfileRequest(
                                name = newProfileName,
                                clone_from = sourceProfileName,
                            ),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.profiles_msg_cloned, sourceProfileName, newProfileName),
                        )
                    }
                    loadProfiles(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage =
                                UiText.res(R.string.profiles_msg_clone_failed, sourceProfileName, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun updateProfileDescription(
        profileName: String,
        description: String,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.updateProfileDescription(
                            profileName,
                            UpdateProfileDescriptionRequest(description),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.profiles_msg_description_updated, profileName),
                        )
                    }
                    loadProfiles(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage =
                                UiText.res(R.string.profiles_msg_description_failed, profileName, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun renameProfile(
        oldName: String,
        newName: String,
    ) {
        if (oldName == newName) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.renameProfile(
                            oldName,
                            RenameProfileRequest(newName),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.profiles_msg_renamed, oldName, newName),
                        )
                    }
                    loadProfiles(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage =
                                UiText.res(
                                    R.string.profiles_msg_rename_failed,
                                    oldName,
                                    result.error.message,
                                ),
                        )
                    }
                }
            }
        }
    }

    fun deleteProfile(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.deleteProfile(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.profiles_msg_deleted, name),
                        )
                    }
                    loadProfiles(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = UiText.res(R.string.profiles_msg_delete_failed, name, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun autoDescribeProfile(name: String) {
        _uiState.update { it.copy(isAutoDescribing = true) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall {
                        ApiClient.hermesApi.describeProfileAuto(
                            name,
                            ProfileDescribeAutoRequest(overwrite = true),
                        )
                    }
                }
            when (result) {
                is NetworkResult.Success -> {
                    val body = result.data
                    if (body.ok) {
                        _uiState.update {
                            it.copy(
                                isAutoDescribing = false,
                                toastMessage = UiText.res(R.string.profiles_msg_auto_described, name),
                            )
                        }
                        loadProfiles(forceRefresh = true)
                    } else {
                        // Backend returns generation failures as ok:false + reason,
                        // NOT an HTTP error — surface the reason to the user.
                        _uiState.update {
                            it.copy(
                                isAutoDescribing = false,
                                toastMessage =
                                    UiText.res(
                                        R.string.profiles_msg_auto_describe_failed,
                                        name,
                                        body.reason?.let { UiText.raw(it) }
                                            ?: UiText.res(R.string.profiles_msg_unknown_reason),
                                    ),
                            )
                        }
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isAutoDescribing = false,
                            toastMessage =
                                UiText.res(R.string.profiles_msg_auto_describe_error, name, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun fetchSetupCommand(name: String) {
        _uiState.update { it.copy(isLoadingSetupCommand = true, setupCommand = null) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.getProfileSetupCommand(name) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSetupCommand = false,
                            setupCommand = result.data.command,
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSetupCommand = false,
                            toastMessage = UiText.res(R.string.profiles_msg_setup_command_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun loadModelOptions() {
        _uiState.update { it.copy(isLoadingBuilderData = true, errorMessage = null) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.getModelOptions() }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoadingBuilderData = false,
                            modelProviders = result.data.providers,
                            modelPickerPinned = AuthManager.getPinnedModels(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    // Toast, not errorMessage — a picker-load failure must NOT
                    // blank the profiles list with the ErrorState branch.
                    _uiState.update {
                        it.copy(
                            isLoadingBuilderData = false,
                            toastMessage = UiText.res(R.string.profiles_msg_load_models_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun togglePinModel(
        providerSlug: String,
        modelName: String,
    ) {
        val target = PinnedModel(providerSlug, modelName)
        val current = AuthManager.getPinnedModels().toMutableList()
        if (target in current) {
            current.remove(target)
        } else {
            current.add(target)
        }
        AuthManager.savePinnedModels(current)
        _uiState.update { it.copy(modelPickerPinned = current) }
    }

    fun loadBuilderData() {
        _uiState.update { it.copy(isLoadingBuilderData = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                coroutineScope {
                    val modelsDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getModelOptions() } }
                    val skillsDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getSkills() } }

                    val modelsResult = modelsDeferred.await()
                    val skillsResult = skillsDeferred.await()

                    if (modelsResult is NetworkResult.Success && skillsResult is NetworkResult.Success) {
                        _uiState.update {
                            it.copy(
                                isLoadingBuilderData = false,
                                modelProviders = modelsResult.data.providers,
                                availableSkills = skillsResult.data,
                            )
                        }
                    } else {
                        val modelsError = partialLoadStatus(modelsResult)
                        val skillsError = partialLoadStatus(skillsResult)
                        _uiState.update {
                            it.copy(
                                isLoadingBuilderData = false,
                                errorMessage =
                                    UiText.res(R.string.profiles_msg_builder_partial_failed, modelsError, skillsError),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingBuilderData = false,
                        errorMessage = UiText.res(R.string.profiles_msg_builder_failed, e.message.toString()),
                    )
                }
            }
        }
    }

    fun searchHub(query: String) {
        if (query.isBlank()) return
        _uiState.update { it.copy(isSearchingHub = true) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.searchSkillsHub(query) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSearchingHub = false,
                            hubSearchResults = result.data.results.orEmpty(),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSearchingHub = false,
                            toastMessage = UiText.res(R.string.profiles_msg_skills_search_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }

    fun createProfile(
        request: CreateProfileRequest,
        onSuccess: () -> Unit,
    ) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.createProfile(request) }
                }
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.res(R.string.profiles_msg_created, request.name),
                        )
                    }
                    loadProfiles(forceRefresh = true)
                    onSuccess()
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            toastMessage = UiText.res(R.string.profiles_msg_create_failed, result.error.message),
                        )
                    }
                }
            }
        }
    }
}
