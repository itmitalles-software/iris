package io.wutz.apollo.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.local.SwrCache
import io.wutz.apollo.data.model.LearningGraphNode
import io.wutz.apollo.data.model.LearningGraphResponse
import io.wutz.apollo.data.model.LearningNodeDetail
import io.wutz.apollo.data.model.LearningNodeEdit
import io.wutz.apollo.data.model.LearningNodeMutationResponse
import io.wutz.apollo.data.model.LearningNodeRef
import io.wutz.apollo.data.model.MemoryResetRequest
import io.wutz.apollo.data.model.MemoryResponse
import io.wutz.apollo.data.remote.ApiClient
import io.wutz.apollo.data.remote.NetworkResult
import io.wutz.apollo.data.remote.safeApiCall
import io.wutz.apollo.data.session.ProfileSwitchCoordinator
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.Response

data class MemoryUiState(
    val isLoading: Boolean = false,
    val memory: MemoryResponse? = null,
    val learningGraph: LearningGraphResponse? = null,
    val errorMessage: UiText? = null,
    val toastMessage: UiText? = null,
    val resetting: String? = null,
    val node: MemoryNodeUiState? = null,
)

/**
 * The learning-graph node opened from the self-improvement list: its detail for the edit prefill,
 * plus the in-flight and error state of an edit or forget.
 */
data class MemoryNodeUiState(
    val id: String,
    val kind: String,
    val label: String,
    val detail: LearningNodeDetail? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: UiText? = null,
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
    private var nodeJob: Job? = null
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
                node = null,
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
                                        errorMessage = UiText.raw(memoryResult.error.message),
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
                            toastMessage = UiText.res(R.string.memory_msg_reset_failed, result.error.message),
                        )
                    }
                }
            }
            load(forceRefresh = true)
        }
    }

    /** Opens [node] and loads its current content (full SKILL.md or the raw memory entry). */
    fun openNode(node: LearningGraphNode) {
        nodeJob?.cancel()
        _uiState.update { it.copy(node = MemoryNodeUiState(id = node.id, kind = node.kind, label = node.label)) }
        nodeJob =
            viewModelScope.launch {
                val result = safeApiCall { ApiClient.hermesApi.getLearningNode(node.id) }
                _uiState.update { state ->
                    val current = state.node?.takeIf { it.id == node.id } ?: return@update state
                    val next =
                        when (result) {
                            is NetworkResult.Success -> {
                                current.copy(detail = result.data, isLoading = false, error = null)
                            }

                            is NetworkResult.Failure -> {
                                current.copy(
                                    isLoading = false,
                                    error = UiText.res(R.string.memory_node_load_failed, result.error.message),
                                )
                            }
                        }
                    state.copy(node = next)
                }
            }
    }

    fun closeNode() {
        nodeJob?.cancel()
        _uiState.update { it.copy(node = null) }
    }

    /** Rewrites the open node's content. Memory ids name the old text, so the graph reloads afterwards. */
    fun saveNode(content: String) {
        val node = _uiState.value.node ?: return
        if (node.isSaving || node.detail == null) return
        if (content.isBlank()) {
            updateNode(node.id) { it.copy(error = UiText.res(R.string.memory_node_empty_content)) }
            return
        }
        mutateNode(
            node = node,
            call = { ApiClient.hermesApi.updateLearningNode(LearningNodeEdit(id = node.id, content = content)) },
            successToast = UiText.res(R.string.memory_node_saved, node.label),
            failure = { UiText.res(R.string.memory_node_save_failed, it) },
        )
    }

    /** Forgets the open node: a memory entry is removed, a skill is archived (restorable on the server). */
    fun forgetNode() {
        val node = _uiState.value.node ?: return
        if (node.isSaving) return
        val successToast =
            if (node.kind == "skill") {
                UiText.res(R.string.memory_node_forgot_skill, node.label)
            } else {
                UiText.res(R.string.memory_node_forgot_memory)
            }
        mutateNode(
            node = node,
            call = { ApiClient.hermesApi.deleteLearningNode(LearningNodeRef(id = node.id)) },
            successToast = successToast,
            failure = { UiText.res(R.string.memory_node_forget_failed, it) },
        )
    }

    private fun mutateNode(
        node: MemoryNodeUiState,
        call: suspend () -> Response<LearningNodeMutationResponse>,
        successToast: UiText,
        failure: (String) -> UiText,
    ) {
        updateNode(node.id) { it.copy(isSaving = true, error = null) }
        nodeJob?.cancel()
        nodeJob =
            viewModelScope.launch {
                when (val result = safeApiCall { call() }) {
                    is NetworkResult.Success -> {
                        if (result.data.ok) {
                            _uiState.update { it.copy(node = null, toastMessage = successToast) }
                            load(silent = true, forceRefresh = true)
                        } else {
                            updateNode(node.id) {
                                it.copy(isSaving = false, error = failure(result.data.message.orEmpty()))
                            }
                        }
                    }

                    is NetworkResult.Failure -> {
                        updateNode(node.id) { it.copy(isSaving = false, error = failure(result.error.message)) }
                    }
                }
            }
    }

    private fun updateNode(
        id: String,
        transform: (MemoryNodeUiState) -> MemoryNodeUiState,
    ) {
        _uiState.update { state ->
            val current = state.node?.takeIf { it.id == id } ?: return@update state
            state.copy(node = transform(current))
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }
}
