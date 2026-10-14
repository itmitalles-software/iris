package io.wutz.apollo.ui.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.EnvVarConfig
import io.wutz.apollo.data.model.EnvVarDeleteRequest
import io.wutz.apollo.data.model.EnvVarRevealRequest
import io.wutz.apollo.data.model.EnvVarUpdate
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.safeLaunchLoad
import io.wutz.apollo.ui.common.safeLaunchSwrLoad
import io.wutz.apollo.ui.common.toUiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CategorySection(
    val name: String,
    val vars: Map<String, EnvVarConfig>,
    val expanded: Boolean,
)

data class KeysUiState(
    val isLoading: Boolean = false,
    val categories: List<CategorySection> = emptyList(),
    val revealedValues: Map<String, String> = emptyMap(),
    val newKeyName: String = "",
    val newKeyValue: String = "",
    val isAddingKey: Boolean = false,
    val showAddDialog: Boolean = false,
    val deleteTargetKey: String? = null,
    val deletingKeys: Set<String> = emptySet(),
    val keysChanged: Boolean = false,
    val isRestartingGateway: Boolean = false,
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
)

/** Section for keys without a server category; only its label is localized (see KeysScreen). */
internal const val OTHER_KEYS_CATEGORY = "Other"

private val CATEGORY_ORDER =
    listOf(
        "LLM Providers",
        "Tool API Keys",
        "Messaging Platforms",
        "Agent Settings",
    )

class KeysViewModel :
    ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(KeysUiState())
    val uiState: StateFlow<KeysUiState> = _uiState.asStateFlow()

    private val keysCache = SwrCache<String, Map<String, EnvVarConfig>>()
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Bumped by [clearScopeOwnedState]. A key change, reveal or restart that completes after a server/profile
     * switch belongs to the previous server and must not mark the new one as changed (its restart banner would
     * restart the wrong gateway) or show the old server's values.
     */
    private var scopeGeneration = 0

    fun clearScopeOwnedState() {
        scopeGeneration++
        _uiState.update {
            it.copy(
                isLoading = false,
                categories = emptyList(),
                revealedValues = emptyMap(),
                deleteTargetKey = null,
                deletingKeys = emptySet(),
                isAddingKey = false,
                keysChanged = false,
                isRestartingGateway = false,
                errorMessage = null,
            )
        }
    }

    private fun buildCategoryList(
        envVars: Map<String, EnvVarConfig>,
        expandedCategories: Set<String> =
            _uiState.value.categories
                .map { it.name }
                .toSet(),
    ): List<CategorySection> {
        // Group by category, uncategorized go to OTHER_KEYS_CATEGORY
        val grouped = mutableMapOf<String, MutableMap<String, EnvVarConfig>>()
        envVars.forEach { (key, config) ->
            val category = config.category?.ifBlank { null } ?: OTHER_KEYS_CATEGORY
            grouped.getOrPut(category) { mutableMapOf() }[key] = config
        }

        // Sort categories: known order first, then alphabetically
        val knownCategories = CATEGORY_ORDER.filter { it in grouped }
        val otherCategories = (grouped.keys - CATEGORY_ORDER.toSet()).sorted()

        return (knownCategories + otherCategories).map { name ->
            val currentExpanded =
                if (expandedCategories.isEmpty()) {
                    // First load — expand all by default
                    true
                } else {
                    name in expandedCategories
                }
            CategorySection(
                name = name,
                vars = grouped[name] ?: emptyMap(),
                expanded = currentExpanded,
            )
        }
    }

    fun loadKeys(forceRefresh: Boolean = false) {
        safeLaunchSwrLoad(
            cache = keysCache,
            forceRefresh = forceRefresh,
            onCacheHit = { envVars ->
                val currentCategories = _uiState.value.categories
                val expandedCategories = currentCategories.map { it.name to it.expanded }.toMap()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        categories = buildCategoryList(envVars, expandedCategories.keys),
                        errorMessage = null,
                    )
                }
            },
            apiCall = { safeApiCall { ApiClient.hermesApi.getEnvVars() } },
            onStart = { _uiState.update { it.copy(isLoading = true, errorMessage = null) } },
            onSuccess = { data ->
                val envVars = data.orEmpty()
                val currentCategories = _uiState.value.categories
                val expandedCategories = currentCategories.map { it.name to it.expanded }.toMap()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        categories = buildCategoryList(envVars, expandedCategories.keys),
                    )
                }
            },
            onError = { errorMsg ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiText.res(R.string.keys_msg_load_failed, errorMsg.toUiText()),
                    )
                }
            },
        )
    }

    fun toggleCategory(category: String) {
        _uiState.update { state ->
            state.copy(
                categories =
                    state.categories.map { section ->
                        if (section.name == category) {
                            section.copy(expanded = !section.expanded)
                        } else {
                            section
                        }
                    },
            )
        }
    }

    fun openAddDialog() {
        _uiState.update { it.copy(showAddDialog = true) }
    }

    fun dismissAddDialog() {
        _uiState.update {
            it.copy(
                showAddDialog = false,
                newKeyName = "",
                newKeyValue = "",
            )
        }
    }

    fun setNewKeyName(name: String) {
        _uiState.update { it.copy(newKeyName = name) }
    }

    fun setNewKeyValue(value: String) {
        _uiState.update { it.copy(newKeyValue = value) }
    }

    fun addKey() {
        val key = _uiState.value.newKeyName.trim()
        val value = _uiState.value.newKeyValue.trim()
        if (key.isBlank() || value.isBlank()) {
            _uiState.update { it.copy(toastMessage = UiText.res(R.string.keys_msg_key_value_required)) }
            return
        }

        _uiState.update { it.copy(isAddingKey = true) }
        val generation = scopeGeneration
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.updateEnvVar(EnvVarUpdate(key, value)) }
                }
            if (generation != scopeGeneration) return@launch
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isAddingKey = false,
                            showAddDialog = false,
                            newKeyName = "",
                            newKeyValue = "",
                            keysChanged = true,
                            toastMessage = UiText.res(R.string.keys_msg_added),
                        )
                    }
                    loadKeys(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isAddingKey = false,
                            toastMessage = UiText.failure(R.string.keys_msg_add_failed, result.error.toUiText()),
                        )
                    }
                }
            }
        }
    }

    fun requestDeleteKey(key: String) {
        _uiState.update { it.copy(deleteTargetKey = key) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(deleteTargetKey = null) }
    }

    fun confirmDeleteKey() {
        val key = _uiState.value.deleteTargetKey ?: return
        _uiState.update { it.copy(deleteTargetKey = null) }
        deleteKey(key)
    }

    private fun deleteKey(key: String) {
        _uiState.update { it.copy(deletingKeys = it.deletingKeys + key) }
        val generation = scopeGeneration
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.deleteEnvVar(EnvVarDeleteRequest(key)) }
                }
            if (generation != scopeGeneration) return@launch
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            deletingKeys = it.deletingKeys - key,
                            keysChanged = true,
                            toastMessage = UiText.res(R.string.keys_msg_deleted),
                        )
                    }
                    loadKeys(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            deletingKeys = it.deletingKeys - key,
                            toastMessage = UiText.failure(R.string.keys_msg_delete_failed, result.error.toUiText()),
                        )
                    }
                }
            }
        }
    }

    fun revealKey(key: String) {
        val generation = scopeGeneration
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.revealEnvVar(EnvVarRevealRequest(key)) }
                }
            if (generation != scopeGeneration) return@launch
            when (result) {
                is NetworkResult.Success -> {
                    val body = result.data
                    _uiState.update { state ->
                        state.copy(
                            revealedValues = state.revealedValues + (body.key to body.value),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.failure(R.string.keys_msg_reveal_failed, result.error.toUiText()),
                        )
                    }
                }
            }
        }
    }

    fun hideKey(key: String) {
        _uiState.update { state ->
            state.copy(
                revealedValues = state.revealedValues.toMutableMap().apply { remove(key) },
            )
        }
    }

    fun updateKey(
        key: String,
        value: String,
    ) {
        val generation = scopeGeneration
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.updateEnvVar(EnvVarUpdate(key, value)) }
                }
            if (generation != scopeGeneration) return@launch
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            keysChanged = true,
                            toastMessage = UiText.res(R.string.keys_msg_updated),
                        )
                    }
                    loadKeys(forceRefresh = true)
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            toastMessage = UiText.failure(R.string.keys_msg_update_failed, result.error.toUiText()),
                        )
                    }
                }
            }
        }
    }

    fun showToast(message: UiText) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    fun restartGateway() {
        _uiState.update { it.copy(isRestartingGateway = true) }
        val generation = scopeGeneration
        viewModelScope.launch {
            val result =
                withContext(ioDispatcher) {
                    safeApiCall { ApiClient.hermesApi.restartGateway() }
                }
            if (generation != scopeGeneration) return@launch
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isRestartingGateway = false,
                            keysChanged = false,
                            toastMessage = UiText.res(R.string.keys_msg_gateway_restart_initiated),
                        )
                    }
                }

                is NetworkResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isRestartingGateway = false,
                            toastMessage =
                                UiText.failure(
                                    R.string.keys_msg_gateway_restart_failed,
                                    result.error.toUiText(),
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
}
