package io.wutz.apollo.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.wutz.apollo.R
import io.wutz.apollo.data.local.AuthManager
import io.wutz.apollo.data.local.DataScope
import io.wutz.apollo.data.model.DiscoveredRepo
import io.wutz.apollo.data.model.ProjectInfo
import io.wutz.apollo.data.ws.HermesWsClient
import io.wutz.apollo.data.ws.ProjectsRepository
import io.wutz.apollo.data.ws.ProjectsResult
import io.wutz.apollo.data.ws.WsEvent
import io.wutz.apollo.ui.common.ToastHost
import io.wutz.apollo.ui.common.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which projects the list shows; archived projects stay out of pickers but remain manageable here. */
enum class ProjectsFilter { ACTIVE, ARCHIVED }

/** Values the create/edit sheet submits. [id] is null when creating. */
data class ProjectDraft(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val color: String? = null,
    // Create only: the project's first folder. Edit manages folders on the project itself.
    val folder: String = "",
)

/** The inverse of an archive or restore: set [projectId]'s archived flag to [archived]. */
data class ProjectArchiveUndo(
    val projectId: String,
    val archived: Boolean,
)

data class ProjectsUiState(
    val isLoading: Boolean = true,
    val errorMessage: UiText? = null,
    val projects: List<ProjectInfo> = emptyList(),
    // Git roots from past sessions that are not a project yet: one-tap "Create project" suggestions.
    val suggestions: List<DiscoveredRepo> = emptyList(),
    val filter: ProjectsFilter = ProjectsFilter.ACTIVE,
    val query: String = "",
    // Id of the project an RPC is running for; its actions are disabled until it settles.
    val busyId: String? = null,
    val isSaving: Boolean = false,
    val editor: ProjectDraft? = null,
    val editorError: UiText? = null,
    val deleteTarget: ProjectInfo? = null,
    val toastMessage: UiText? = null,
    /** Offered as "Undo" next to [toastMessage]: sets the project's archived flag back. */
    val toastUndo: ProjectArchiveUndo? = null,
) {
    val visibleProjects: List<ProjectInfo>
        get() {
            val needle = query.trim()
            return projects
                .filter { it.isArchived == (filter == ProjectsFilter.ARCHIVED) }
                .filter { project ->
                    needle.isEmpty() ||
                        project.name.contains(needle, ignoreCase = true) ||
                        project.folders.any { it.path.contains(needle, ignoreCase = true) }
                }
        }

    val archivedCount: Int get() = projects.count { it.isArchived }
}

class ProjectsViewModel(
    private val repository: ProjectsRepository = ProjectsRepository(),
    scopes: StateFlow<DataScope?> = AuthManager.dataScopeFlow,
    events: Flow<WsEvent> = HermesWsClient.events,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ProjectsUiState())
    val uiState: StateFlow<ProjectsUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            // Projects are per profile: a profile/server switch starts from a clean list.
            scopes.collect {
                _uiState.value = ProjectsUiState()
                load()
            }
        }
        viewModelScope.launch {
            events.collect { if (it is WsEvent.GatewayReady) load() }
        }
    }

    fun load() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        loadJob =
            viewModelScope.launch {
                val repos = async { repository.discoveredRepos() }
                when (val result = repository.list()) {
                    is ProjectsResult.Ok -> {
                        applyProjects(result.value, repos.await())
                    }

                    is ProjectsResult.Failed -> {
                        _uiState.update { it.copy(isLoading = false, errorMessage = UiText.raw(result.message)) }
                    }
                }
            }
    }

    private fun applyProjects(
        projects: List<ProjectInfo>,
        repos: List<DiscoveredRepo>,
    ) {
        val owned = projects.flatMap { project -> project.folders.map { it.path } }.toSet()
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = null,
                projects = projects,
                suggestions = repos.filter { repo -> repo.root !in owned },
            )
        }
    }

    fun setFilter(filter: ProjectsFilter) = _uiState.update { it.copy(filter = filter) }

    fun setQuery(query: String) = _uiState.update { it.copy(query = query) }

    fun startCreate(repo: DiscoveredRepo? = null) =
        _uiState.update {
            it.copy(
                editor = ProjectDraft(name = repo?.label.orEmpty(), folder = repo?.root.orEmpty()),
                editorError = null,
            )
        }

    fun startEdit(project: ProjectInfo) =
        _uiState.update {
            it.copy(
                editor =
                    ProjectDraft(
                        id = project.id,
                        name = project.name,
                        description = project.description.orEmpty(),
                        color = project.color,
                    ),
                editorError = null,
            )
        }

    fun dismissEditor() = _uiState.update { it.copy(editor = null, editorError = null) }

    fun save(draft: ProjectDraft) {
        val name = draft.name.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(editorError = UiText.res(R.string.projects_error_name_required)) }
            return
        }
        _uiState.update { it.copy(isSaving = true, editorError = null) }
        viewModelScope.launch {
            val result =
                if (draft.id == null) {
                    repository.create(name, draft.folder.trim().ifEmpty { null }, draft.color)
                } else {
                    repository.update(draft.id, name, draft.description.trim(), draft.color)
                }
            when (result) {
                is ProjectsResult.Ok -> {
                    _uiState.update { it.copy(isSaving = false, editor = null) }
                    load()
                }

                // Keep the sheet open with the gateway's reason (duplicate folder, bad name, …).
                is ProjectsResult.Failed -> {
                    _uiState.update { it.copy(isSaving = false, editorError = UiText.raw(result.message)) }
                }
            }
        }
    }

    fun setArchived(
        project: ProjectInfo,
        archived: Boolean,
    ) = mutate(
        id = project.id,
        success = archivedMessage(archived),
        undo = ProjectArchiveUndo(project.id, archived = !archived),
    ) { repository.setArchived(project.id, archived) }

    /** Runs the inverse of the archive change reported by the last message ("Undo" in the snackbar). */
    fun undoArchive(undo: ProjectArchiveUndo) =
        mutate(id = undo.projectId, success = archivedMessage(undo.archived)) {
            repository.setArchived(undo.projectId, undo.archived)
        }

    private fun archivedMessage(archived: Boolean): UiText =
        UiText.res(if (archived) R.string.projects_msg_archived else R.string.projects_msg_restored)

    fun requestDelete(project: ProjectInfo) = _uiState.update { it.copy(deleteTarget = project) }

    fun dismissDelete() = _uiState.update { it.copy(deleteTarget = null) }

    fun confirmDelete() {
        val target = _uiState.value.deleteTarget ?: return
        _uiState.update { it.copy(deleteTarget = null) }
        mutate(target.id) { repository.delete(target.id) }
    }

    fun addFolder(
        project: ProjectInfo,
        path: String,
    ) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        mutate(project.id) { repository.addFolder(project.id, trimmed) }
    }

    fun removeFolder(
        project: ProjectInfo,
        path: String,
    ) = mutate(project.id) { repository.removeFolder(project.id, path) }

    fun setPrimaryFolder(
        project: ProjectInfo,
        path: String,
    ) = mutate(project.id) { repository.setPrimaryFolder(project.id, path) }

    /**
     * Runs one project RPC, then reloads so the list shows what the gateway actually stored.
     * On success it shows [success] (if any) with [undo] offered next to it.
     */
    private fun mutate(
        id: String,
        success: UiText? = null,
        undo: ProjectArchiveUndo? = null,
        call: suspend () -> ProjectsResult<Unit>,
    ) {
        if (_uiState.value.busyId != null) return
        _uiState.update { it.copy(busyId = id) }
        viewModelScope.launch {
            val result = call()
            _uiState.update {
                when (result) {
                    is ProjectsResult.Failed -> {
                        it.copy(
                            busyId = null,
                            toastMessage = UiText.Failure(UiText.raw(result.message)),
                            toastUndo = null,
                        )
                    }

                    is ProjectsResult.Ok -> {
                        it.copy(busyId = null, toastMessage = success, toastUndo = undo.takeIf { success != null })
                    }
                }
            }
            load()
        }
    }

    override fun clearToast() = _uiState.update { it.copy(toastMessage = null, toastUndo = null) }
}
