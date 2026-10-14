package io.wutz.apollo.ui.kanban

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.wutz.apollo.KanbanTaskDetailKey
import io.wutz.apollo.NavigationController
import io.wutz.apollo.R
import io.wutz.apollo.data.model.KanbanActiveWorker
import io.wutz.apollo.data.model.KanbanBoard
import io.wutz.apollo.data.model.KanbanColumn
import io.wutz.apollo.data.model.KanbanProfile
import io.wutz.apollo.data.model.KanbanProject
import io.wutz.apollo.data.model.KanbanTask
import io.wutz.apollo.theme.LocalHermesStatusColors
import io.wutz.apollo.ui.common.ErrorState
import io.wutz.apollo.ui.common.HermesScaffold
import io.wutz.apollo.ui.common.NavIcon
import io.wutz.apollo.ui.common.SearchBar
import io.wutz.apollo.ui.common.SkeletonListState
import io.wutz.apollo.ui.common.ToastEffect
import io.wutz.apollo.ui.common.asString
import io.wutz.apollo.ui.kanban.components.KanbanBoardSettingsDialog
import io.wutz.apollo.ui.kanban.components.KanbanCreateTaskDialog
import io.wutz.apollo.ui.kanban.components.KanbanFilterSheet
import io.wutz.apollo.ui.kanban.components.KanbanOrchestrationDialog
import io.wutz.apollo.ui.kanban.components.KanbanTaskCard
import kotlinx.coroutines.launch

private const val DEFAULT_COLUMN = "todo"

/** Keeps only the selected ids that are still visible, so bulk actions cannot reach filtered-out tasks. */
internal fun pruneKanbanSelection(
    selectedIds: Set<String>,
    visibleTasks: List<KanbanTask>,
): Set<String> {
    if (selectedIds.isEmpty()) return selectedIds
    val visibleIds = visibleTasks.mapTo(HashSet()) { it.id }
    return selectedIds.filterTo(LinkedHashSet()) { it in visibleIds }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KanbanScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: KanbanViewModel = viewModel { KanbanViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val nowSeconds = rememberKanbanNowSeconds(state.tasks.any { it.status.equals("running", ignoreCase = true) })
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var selectedAssignee by remember { mutableStateOf<String?>(null) }
    var selectedTenant by remember { mutableStateOf<String?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showWorkersDialog by remember { mutableStateOf(false) }

    val filteredTasks =
        remember(
            query,
            state.tasks,
            selectedAssignee,
            selectedTenant,
            state.selectedWorkflowTemplateId,
            state.selectedCurrentStepKey,
        ) {
            filterKanbanTasks(
                state.tasks,
                state.selectedWorkflowTemplateId,
                state.selectedCurrentStepKey,
            ).filter { task ->
                val matchesQuery =
                    query.isBlank() ||
                        task.title.contains(query, ignoreCase = true) ||
                        task.description?.contains(query, ignoreCase = true) == true ||
                        task.status.contains(query, ignoreCase = true) ||
                        task.assignedTo?.contains(query, ignoreCase = true) == true ||
                        task.id.contains(query, ignoreCase = true)
                val matchesAssignee =
                    selectedAssignee == null || task.assignee.equals(selectedAssignee, ignoreCase = true)
                val matchesTenant = selectedTenant == null || task.tenant.equals(selectedTenant, ignoreCase = true)
                matchesQuery && matchesAssignee && matchesTenant
            }
        }

    val tasksByColumn =
        remember(filteredTasks) {
            filteredTasks.groupBy { it.status.lowercase() }
        }
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var createTaskDefaultColumn by remember { mutableStateOf(DEFAULT_COLUMN) }
    var taskForActions by remember { mutableStateOf<KanbanTask?>(null) }
    var confirmTarget by remember { mutableStateOf<Pair<KanbanTask, KanbanTaskAction>?>(null) }
    var summaryTarget by remember { mutableStateOf<Pair<KanbanTask, KanbanTaskAction>?>(null) }

    // Multi-select & Bulk operations state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedTaskIds by remember { mutableStateOf(setOf<String>()) }
    var showBulkMoveDialog by remember { mutableStateOf(false) }
    var showBulkCompleteDialog by remember { mutableStateOf(false) }
    var showBulkAssignDialog by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var showBulkArchiveDialog by remember { mutableStateOf(false) }
    var showBulkUnassignDialog by remember { mutableStateOf(false) }

    // Board management dialogs
    var showCreateBoardDialog by remember { mutableStateOf(false) }
    var showBoardSettingsDialog by remember { mutableStateOf(false) }
    var showDeleteBoardDialog by remember { mutableStateOf(false) }
    var showOrchestrationDialog by remember { mutableStateOf(false) }

    // Board Transfer (Export / Import)
    var pendingExportPath by rememberPendingKanbanExportPath()

    // The launchers only forward the picked Uri; the ViewModel owns the I/O so it survives leaving the screen.
    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/gzip"),
        ) { uri ->
            if (uri != null) {
                viewModel.saveBoardExport(context.applicationContext.contentResolver, pendingExportPath, uri)
                pendingExportPath = null
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                viewModel.importBoardFrom(context.applicationContext.contentResolver, uri)
            }
        }

    // Prune against the filtered list: bulk actions must never hit tasks hidden by search or filters.
    LaunchedEffect(filteredTasks) {
        selectedTaskIds = pruneKanbanSelection(selectedTaskIds, filteredTasks)
    }

    // The ViewModel is Activity-scoped: close the board events socket once this screen is gone.
    DisposableEffect(viewModel) {
        viewModel.attachScreen()
        onDispose { viewModel.detachScreen() }
    }

    LaunchedEffect(Unit) {
        viewModel.loadBoards()
    }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    val openAddTaskDialog: (String) -> Unit = { column ->
        createTaskDefaultColumn = column
        showAddTaskDialog = true
    }
    val toggleTaskSelection: (KanbanTask) -> Unit = { task ->
        selectedTaskIds =
            if (task.id in selectedTaskIds) {
                selectedTaskIds - task.id
            } else {
                selectedTaskIds + task.id
            }
    }
    // Bulk operations keep the failed tasks selected and leave multi-select once everything succeeded.
    val onBulkComplete: (Set<String>) -> Unit = { failed ->
        selectedTaskIds = failed
        if (failed.isEmpty()) isMultiSelectMode = false
    }

    HermesScaffold(
        title = { Text(stringResource(R.string.kanban_board_title)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        // The board shows its columns side by side and uses the full window width.
        maxContentWidth = Dp.Unspecified,
        actions = {
            KanbanTopBarActions(
                hasSelectedBoard = state.selectedBoard != null,
                canArchiveBoard =
                    state.boards.size > 1 && !state.selectedBoard?.id.equals("default", ignoreCase = true),
                isMultiSelectMode = isMultiSelectMode,
                onShowFilters = { showFilterSheet = true },
                onNewBoard = { showCreateBoardDialog = true },
                onShowWorkers = {
                    showWorkersDialog = true
                    viewModel.loadActiveWorkers()
                },
                onBoardSettings = { showBoardSettingsDialog = true },
                onArchiveBoard = { showDeleteBoardDialog = true },
                onExportBoard = {
                    state.selectedBoard?.let { b ->
                        coroutineScope.launch {
                            val cachedExport = viewModel.prepareBoardExport(b, context.cacheDir)
                            if (cachedExport != null) {
                                pendingExportPath = cachedExport.absolutePath
                                exportLauncher.launch("${b.slug}.tar.gz")
                            }
                        }
                    }
                },
                onImportBoard = {
                    importLauncher.launch(
                        arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*"),
                    )
                },
                onOrchestrationSettings = { showOrchestrationDialog = true },
                onToggleMultiSelect = {
                    isMultiSelectMode = !isMultiSelectMode
                    if (!isMultiSelectMode) selectedTaskIds = emptySet()
                },
            )
        },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadBoards() },
    ) { paddingValues ->
        when {
            state.isLoading && state.boards.isEmpty() -> {
                SkeletonListState(modifier = Modifier.padding(paddingValues))
            }

            state.errorMessage != null -> {
                ErrorState(
                    message = state.errorMessage?.asString() ?: "",
                    onRetry = { viewModel.loadBoards() },
                    modifier = Modifier.padding(paddingValues),
                )
            }

            else -> {
                Box(Modifier.fillMaxSize()) {
                    if (state.isLoading) {
                        CircularProgressIndicator()
                    } else if (state.errorMessage != null) {
                        Text(
                            text = state.errorMessage?.asString() ?: "",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(paddingValues),
                        )
                    } else {
                        KanbanBoardContent(
                            state = state,
                            query = query,
                            onQueryChange = { query = it },
                            tasksByColumn = tasksByColumn,
                            nowSeconds = nowSeconds,
                            isMultiSelectMode = isMultiSelectMode,
                            selectedTaskIds = selectedTaskIds,
                            onSelectBoard = viewModel::selectBoard,
                            onShowFilters = { showFilterSheet = true },
                            onAddTask = openAddTaskDialog,
                            onToggleColumnCollapse = viewModel::toggleColumnCollapse,
                            onTaskClick = { clickedTask ->
                                if (isMultiSelectMode) {
                                    toggleTaskSelection(clickedTask)
                                } else {
                                    state.selectedBoard?.let { board ->
                                        NavigationController.navigateTo(
                                            KanbanTaskDetailKey(
                                                boardSlug = board.id,
                                                taskId = clickedTask.id,
                                            ),
                                        )
                                    }
                                }
                            },
                            onTaskActionClick = { clickedTask ->
                                if (isMultiSelectMode) {
                                    toggleTaskSelection(clickedTask)
                                } else {
                                    taskForActions = clickedTask
                                }
                            },
                        )
                    }

                    if (showAddTaskDialog) {
                        KanbanCreateTaskDialog(
                            columns = state.columns,
                            defaultColumn = createTaskDefaultColumn,
                            profiles = state.profiles,
                            existingTasks = state.tasks,
                            isCreating = state.isCreatingTask,
                            defaultAssignee = state.orchestration?.resolvedDefaultAssignee,
                            boardDefaultWorkspaceKind = state.selectedBoard?.defaultWorkspaceKind,
                            boardDefaultWorkdir = state.selectedBoard?.defaultWorkdir,
                            modelProviders = state.modelProviders,
                            pinnedModels = state.pinnedModels,
                            onEstimate = viewModel::estimateNewTask,
                            onDismiss = { showAddTaskDialog = false },
                            onConfirm = { body, targetStatus ->
                                viewModel.createTask(body, targetStatus)
                                showAddTaskDialog = false
                            },
                        )
                    }

                    taskForActions?.let { task ->
                        val actions = kanbanActionsForStatus(task.status)
                        if (actions.isNotEmpty()) {
                            TaskActionSheet(
                                task = task,
                                actions = actions,
                                onAction = { action ->
                                    taskForActions = null
                                    when {
                                        action.needsSummary -> summaryTarget = task to action
                                        action.needsConfirm -> confirmTarget = task to action
                                        else -> viewModel.moveTask(task, action)
                                    }
                                },
                                onDismiss = { taskForActions = null },
                            )
                        }
                    }

                    confirmTarget?.let { (task, action) ->
                        ConfirmActionDialog(
                            message = stringResource(action.confirmRes()),
                            onConfirm = {
                                confirmTarget = null
                                if (action.needsSummary) {
                                    summaryTarget = task to action
                                } else {
                                    viewModel.moveTask(task, action)
                                }
                            },
                            onDismiss = { confirmTarget = null },
                        )
                    }

                    summaryTarget?.let { (task, action) ->
                        CompleteTaskDialog(
                            onConfirm = { summary ->
                                summaryTarget = null
                                viewModel.moveTask(task, action, summary)
                            },
                            onDismiss = { summaryTarget = null },
                        )
                    }

                    if (showFilterSheet) {
                        KanbanFilterSheetHost(
                            state = state,
                            selectedAssignee = selectedAssignee,
                            selectedTenant = selectedTenant,
                            onSelectAssignee = { selectedAssignee = it },
                            onSelectTenant = { selectedTenant = it },
                            onSelectWorkflowFilters = viewModel::setWorkflowFilters,
                            onToggleIncludeArchived = viewModel::setIncludeArchived,
                            onToggleGroupRunning = viewModel::setGroupRunning,
                            onClearFilters = {
                                selectedAssignee = null
                                selectedTenant = null
                                viewModel.setIncludeArchived(false)
                                viewModel.setGroupRunning(false)
                                viewModel.setWorkflowFilters(null, null)
                            },
                            onDismiss = { showFilterSheet = false },
                        )
                    }

                    if (isMultiSelectMode) {
                        val allSelected = selectedTaskIds.size == filteredTasks.size
                        KanbanMultiSelectBar(
                            selectedCount = selectedTaskIds.size,
                            allSelected = allSelected,
                            onToggleSelectAll = {
                                selectedTaskIds =
                                    if (allSelected) {
                                        emptySet()
                                    } else {
                                        filteredTasks.map { it.id }.toSet()
                                    }
                            },
                            onMove = { showBulkMoveDialog = true },
                            onAssign = { showBulkAssignDialog = true },
                            onUnassign = { showBulkUnassignDialog = true },
                            onArchive = { showBulkArchiveDialog = true },
                            onDelete = { showBulkDeleteDialog = true },
                            onClose = {
                                isMultiSelectMode = false
                                selectedTaskIds = emptySet()
                            },
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth(),
                        )
                    }

                    if (showBulkMoveDialog) {
                        KanbanBulkMoveDialog(
                            columns = state.columns,
                            taskCount = selectedTaskIds.size,
                            onSelectColumn = { columnName ->
                                if (columnName.equals("done", ignoreCase = true)) {
                                    showBulkCompleteDialog = true
                                } else {
                                    viewModel.bulkMove(
                                        selectedTaskIds.toList(),
                                        columnName,
                                        onComplete = onBulkComplete,
                                    )
                                }
                                showBulkMoveDialog = false
                            },
                            onDismiss = { showBulkMoveDialog = false },
                        )
                    }

                    if (showBulkCompleteDialog) {
                        BulkCompleteTasksDialog(
                            taskCount = selectedTaskIds.size,
                            onConfirm = { summary ->
                                viewModel.bulkMove(
                                    taskIds = selectedTaskIds.toList(),
                                    targetStatus = "done",
                                    summary = summary,
                                    onComplete = onBulkComplete,
                                )
                                showBulkCompleteDialog = false
                            },
                            onDismiss = { showBulkCompleteDialog = false },
                        )
                    }

                    if (showBulkAssignDialog) {
                        KanbanBulkAssignDialog(
                            profiles = state.profiles,
                            onSelectProfile = { profileName ->
                                viewModel.bulkAssign(selectedTaskIds.toList(), profileName, onBulkComplete)
                                showBulkAssignDialog = false
                            },
                            onDismiss = { showBulkAssignDialog = false },
                        )
                    }

                    if (showBulkUnassignDialog) {
                        ConfirmActionDialog(
                            message = stringResource(R.string.kanban_bulk_unassign_confirm, selectedTaskIds.size),
                            onConfirm = {
                                showBulkUnassignDialog = false
                                viewModel.bulkAssign(selectedTaskIds.toList(), null, onBulkComplete)
                            },
                            onDismiss = { showBulkUnassignDialog = false },
                        )
                    }

                    if (showBulkArchiveDialog) {
                        ConfirmActionDialog(
                            message = stringResource(R.string.kanban_bulk_archive_confirm, selectedTaskIds.size),
                            onConfirm = {
                                showBulkArchiveDialog = false
                                viewModel.bulkArchive(selectedTaskIds.toList(), onBulkComplete)
                            },
                            onDismiss = { showBulkArchiveDialog = false },
                        )
                    }

                    if (showBulkDeleteDialog) {
                        KanbanBulkDeleteDialog(
                            taskCount = selectedTaskIds.size,
                            onConfirm = {
                                viewModel.bulkDelete(selectedTaskIds.toList(), onBulkComplete)
                                showBulkDeleteDialog = false
                            },
                            onDismiss = { showBulkDeleteDialog = false },
                        )
                    }

                    if (showCreateBoardDialog) {
                        KanbanCreateBoardDialog(
                            projects = state.projects,
                            onCreate = { slug, name, description, projectId ->
                                viewModel.createBoard(
                                    slug = slug,
                                    name = name,
                                    description = description,
                                    projectId = projectId,
                                )
                                showCreateBoardDialog = false
                            },
                            onDismiss = { showCreateBoardDialog = false },
                        )
                    }

                    if (showBoardSettingsDialog && state.selectedBoard != null) {
                        KanbanBoardSettingsDialog(
                            board = state.selectedBoard!!,
                            projects = state.projects,
                            onSave = { body ->
                                state.selectedBoard?.let { b ->
                                    viewModel.updateBoard(b.id, body)
                                }
                            },
                            onDismiss = { showBoardSettingsDialog = false },
                        )
                    }

                    if (showOrchestrationDialog) {
                        KanbanOrchestrationDialog(
                            settings = state.orchestration,
                            profiles = state.profiles,
                            onSaveSettings = viewModel::updateOrchestration,
                            onSaveProfileDescription = viewModel::updateProfileDescription,
                            onAutoDescribe = viewModel::autoDescribeProfile,
                            onDismiss = { showOrchestrationDialog = false },
                        )
                    }

                    if (showWorkersDialog) {
                        KanbanActiveWorkersDialog(
                            isLoading = state.isLoadingWorkers,
                            workers = state.activeWorkers,
                            onOpenTask = { taskId ->
                                state.selectedBoard?.let { board ->
                                    showWorkersDialog = false
                                    NavigationController.navigateTo(
                                        KanbanTaskDetailKey(
                                            boardSlug = board.id,
                                            taskId = taskId,
                                        ),
                                    )
                                }
                            },
                            onDismiss = { showWorkersDialog = false },
                        )
                    }

                    if (showDeleteBoardDialog && state.selectedBoard != null) {
                        KanbanArchiveBoardDialog(
                            boardName = state.selectedBoard?.displayName.orEmpty(),
                            onConfirm = {
                                state.selectedBoard?.let { board ->
                                    viewModel.deleteBoard(board.id)
                                }
                                showDeleteBoardDialog = false
                            },
                            onDismiss = { showDeleteBoardDialog = false },
                        )
                    }
                }
            }
        }
    }
}

/** Filter button and the board options menu in the top bar. */
@Composable
private fun KanbanTopBarActions(
    hasSelectedBoard: Boolean,
    canArchiveBoard: Boolean,
    isMultiSelectMode: Boolean,
    onShowFilters: () -> Unit,
    onNewBoard: () -> Unit,
    onShowWorkers: () -> Unit,
    onBoardSettings: () -> Unit,
    onArchiveBoard: () -> Unit,
    onExportBoard: () -> Unit,
    onImportBoard: () -> Unit,
    onOrchestrationSettings: () -> Unit,
    onToggleMultiSelect: () -> Unit,
) {
    var showBoardMenu by remember { mutableStateOf(false) }
    IconButton(onClick = onShowFilters) {
        Icon(
            imageVector = Icons.Default.FilterList,
            contentDescription = stringResource(R.string.kanban_filter),
        )
    }
    Box {
        IconButton(onClick = { showBoardMenu = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.kanban_board_options),
            )
        }
        DropdownMenu(
            expanded = showBoardMenu,
            onDismissRequest = { showBoardMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kanban_new_board)) },
                onClick = {
                    showBoardMenu = false
                    onNewBoard()
                },
            )
            if (hasSelectedBoard) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.kanban_active_workers)) },
                    onClick = {
                        showBoardMenu = false
                        onShowWorkers()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.kanban_board_settings)) },
                    onClick = {
                        showBoardMenu = false
                        onBoardSettings()
                    },
                )
                if (canArchiveBoard) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.kanban_archive_board)) },
                        onClick = {
                            showBoardMenu = false
                            onArchiveBoard()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.kanban_export_board)) },
                    onClick = {
                        showBoardMenu = false
                        onExportBoard()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kanban_import_board)) },
                onClick = {
                    showBoardMenu = false
                    onImportBoard()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.kanban_orchestration_settings)) },
                onClick = {
                    showBoardMenu = false
                    onOrchestrationSettings()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (isMultiSelectMode) {
                                R.string.kanban_exit_multi_select
                            } else {
                                R.string.kanban_select_multiple
                            },
                        ),
                    )
                },
                onClick = {
                    showBoardMenu = false
                    onToggleMultiSelect()
                },
            )
        }
    }
}

/** Search, board tabs, toolbar and the column row of a loaded board. */
@Composable
private fun KanbanBoardContent(
    state: KanbanUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    tasksByColumn: Map<String, List<KanbanTask>>,
    nowSeconds: Long,
    isMultiSelectMode: Boolean,
    selectedTaskIds: Set<String>,
    onSelectBoard: (KanbanBoard) -> Unit,
    onShowFilters: () -> Unit,
    onAddTask: (columnName: String) -> Unit,
    onToggleColumnCollapse: (columnName: String, boardHasWork: Boolean, taskCount: Int) -> Unit,
    onTaskClick: (KanbanTask) -> Unit,
    onTaskActionClick: (KanbanTask) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.kanban_filter_placeholder),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // Board selector tab row
        if (state.boards.isNotEmpty()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = state.boards.indexOf(state.selectedBoard).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.boards.forEach { board ->
                    Tab(
                        selected = board == state.selectedBoard,
                        onClick = { onSelectBoard(board) },
                        text = { Text(board.displayName) },
                    )
                }
            }
        }

        if (state.selectedBoard == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.kanban_no_boards))
            }
        } else if (state.columns.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.kanban_no_columns))
            }
        } else {
            KanbanBoardToolbar(
                isLive = state.isLive,
                onShowFilters = onShowFilters,
                onAddTask = { onAddTask(DEFAULT_COLUMN) },
            )

            val boardHasWork = state.tasks.isNotEmpty()
            val statusColors = LocalHermesStatusColors.current

            LazyRow(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.columns.size, key = { index ->
                    state.columns[index].name
                }) { columnIndex ->
                    val column = state.columns[columnIndex]
                    val colName = column.name
                    val colTasks = tasksByColumn[colName.lowercase()] ?: emptyList()
                    val isCollapsed =
                        KanbanColumnCollapseHelper.isColumnCollapsed(
                            columnName = colName,
                            columnTaskCount = colTasks.size,
                            boardHasWork = boardHasWork,
                            overrides = state.columnCollapseOverrides,
                        )
                    val statusTone =
                        when (colName.lowercase()) {
                            "ready" -> statusColors.info
                            "running" -> statusColors.success
                            "blocked" -> statusColors.error
                            "review" -> statusColors.warning
                            "done" -> statusColors.success
                            else -> MaterialTheme.colorScheme.outline
                        }
                    val onToggleCollapse = { onToggleColumnCollapse(colName, boardHasWork, colTasks.size) }

                    if (isCollapsed) {
                        KanbanCollapsedColumn(
                            columnName = colName,
                            taskCount = colTasks.size,
                            statusTone = statusTone,
                            onToggleCollapse = onToggleCollapse,
                            onAddTask = { onAddTask(colName) },
                        )
                    } else {
                        KanbanExpandedColumn(
                            columnName = colName,
                            tasks = colTasks,
                            groupRunning = state.groupRunning,
                            nowSeconds = nowSeconds,
                            defaultAssignee = state.orchestration?.resolvedDefaultAssignee,
                            isMultiSelectMode = isMultiSelectMode,
                            selectedTaskIds = selectedTaskIds,
                            onToggleCollapse = onToggleCollapse,
                            onAddTask = { onAddTask(colName) },
                            onTaskClick = onTaskClick,
                            onTaskActionClick = onTaskActionClick,
                        )
                    }
                }
            }
        }
    }
}

/** Live status, filter and add-task row above the columns. */
@Composable
private fun KanbanBoardToolbar(
    isLive: Boolean,
    onShowFilters: () -> Unit,
    onAddTask: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveStatusPill(isLive = isLive)
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onShowFilters) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = stringResource(R.string.kanban_filters),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onAddTask) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.kanban_add_task),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A collapsed column: a narrow strip with status dot, count and abbreviated name. */
@Composable
private fun KanbanCollapsedColumn(
    columnName: String,
    taskCount: Int,
    statusTone: Color,
    onToggleCollapse: () -> Unit,
    onAddTask: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .width(56.dp)
                .fillMaxHeight()
                .clickable(onClick = onToggleCollapse),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(statusTone, shape = CircleShape),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = taskCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = columnName.take(3).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (KanbanStatusConstraints.isUserWritableTarget(columnName)) {
                IconButton(
                    onClick = onAddTask,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription =
                            stringResource(
                                R.string.kanban_add_to_column,
                                columnName,
                            ),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            IconButton(
                onClick = onToggleCollapse,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription =
                        stringResource(
                            R.string.kanban_expand_column,
                            columnName,
                        ),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** An expanded column: header with count and actions, then the task cards. */
@Composable
private fun KanbanExpandedColumn(
    columnName: String,
    tasks: List<KanbanTask>,
    groupRunning: Boolean,
    nowSeconds: Long,
    defaultAssignee: String?,
    isMultiSelectMode: Boolean,
    selectedTaskIds: Set<String>,
    onToggleCollapse: () -> Unit,
    onAddTask: () -> Unit,
    onTaskClick: (KanbanTask) -> Unit,
    onTaskActionClick: (KanbanTask) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(280.dp)
                .fillMaxSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
        ) {
            Text(
                text = "${columnName.replaceFirstChar {
                    it.uppercase()
                }} (${tasks.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (KanbanStatusConstraints.isUserWritableTarget(columnName)) {
                    IconButton(
                        onClick = onAddTask,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription =
                                stringResource(
                                    R.string.kanban_add_to_column,
                                    columnName,
                                ),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onToggleCollapse,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector =
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription =
                            stringResource(
                                R.string.kanban_collapse_column,
                                columnName,
                            ),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        val taskCard: @Composable (KanbanTask) -> Unit = { task ->
            KanbanTaskCard(
                task = task,
                nowSeconds = nowSeconds,
                defaultAssignee = defaultAssignee,
                isSelected = isMultiSelectMode && task.id in selectedTaskIds,
                onTaskClick = onTaskClick,
                onActionClick = onTaskActionClick,
            )
        }

        if (tasks.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.kanban_no_tasks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (columnName.equals("running", ignoreCase = true) && groupRunning) {
            val runningGroups =
                remember(tasks) {
                    KanbanLaneGrouping.groupRunningTasks(tasks)
                }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                runningGroups.forEach { group ->
                    item(key = group.lazyKey) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth().padding(
                                    vertical = 4.dp,
                                ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text =
                                    "${group.assignee ?: stringResource(
                                        R.string.kanban_unassigned_group,
                                    )} (${group.tasks.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    items(group.tasks, key = { it.id }) { task -> taskCard(task) }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tasks, key = { it.id }) { task -> taskCard(task) }
            }
        }
    }
}

/** Filter sheet with the assignee, tenant and workflow options derived from the loaded tasks. */
@Composable
private fun KanbanFilterSheetHost(
    state: KanbanUiState,
    selectedAssignee: String?,
    selectedTenant: String?,
    onSelectAssignee: (String?) -> Unit,
    onSelectTenant: (String?) -> Unit,
    onSelectWorkflowFilters: (String?, String?) -> Unit,
    onToggleIncludeArchived: (Boolean) -> Unit,
    onToggleGroupRunning: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onDismiss: () -> Unit,
) {
    val assignees =
        remember(state.tasks) {
            state.tasks
                .mapNotNull { it.assignee }
                .distinct()
                .sorted()
        }
    val tenants =
        remember(state.tasks) {
            state.tasks
                .mapNotNull { it.tenant }
                .distinct()
                .sorted()
        }
    KanbanFilterSheet(
        assignees = assignees,
        tenants = tenants,
        selectedAssignee = selectedAssignee,
        selectedTenant = selectedTenant,
        workflowTemplateIds =
            state.tasks
                .mapNotNull { it.workflowTemplateId }
                .distinct()
                .sorted(),
        currentStepKeys =
            state.tasks
                .mapNotNull { it.currentStepKey }
                .distinct()
                .sorted(),
        selectedWorkflowTemplateId = state.selectedWorkflowTemplateId,
        selectedCurrentStepKey = state.selectedCurrentStepKey,
        includeArchived = state.includeArchived,
        groupRunning = state.groupRunning,
        onSelectAssignee = onSelectAssignee,
        onSelectTenant = onSelectTenant,
        onSelectWorkflowFilters = onSelectWorkflowFilters,
        onToggleIncludeArchived = onToggleIncludeArchived,
        onToggleGroupRunning = onToggleGroupRunning,
        onClearFilters = onClearFilters,
        onDismiss = onDismiss,
    )
}

/** Bottom bar of the multi-select mode with the selection count and the bulk actions. */
@Composable
private fun KanbanMultiSelectBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onMove: () -> Unit,
    onAssign: () -> Unit,
    onUnassign: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSelection = selectedCount > 0
    Card(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.kanban_selected_count, selectedCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            // Six actions do not fit a phone's width: scroll them instead of
            // squeezing Archive, Unassign and Delete off the screen.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .horizontalScroll(rememberScrollState()),
            ) {
                TextButton(onClick = onToggleSelectAll) {
                    Text(
                        if (allSelected) {
                            stringResource(R.string.kanban_clear_selection)
                        } else {
                            stringResource(R.string.kanban_select_all)
                        },
                    )
                }
                Button(
                    onClick = onMove,
                    enabled = hasSelection,
                ) {
                    Text(stringResource(R.string.kanban_move))
                }
                Button(
                    onClick = onAssign,
                    enabled = hasSelection,
                ) {
                    Text(stringResource(R.string.kanban_bulk_assign))
                }
                Button(
                    onClick = onUnassign,
                    enabled = hasSelection,
                ) {
                    Text(stringResource(R.string.kanban_bulk_unassign))
                }
                Button(
                    onClick = onArchive,
                    enabled = hasSelection,
                ) {
                    Text(stringResource(R.string.kanban_action_archive))
                }
                Button(
                    onClick = onDelete,
                    enabled = hasSelection,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(R.string.kanban_bulk_delete))
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.kanban_close_multi_select),
                    )
                }
            }
        }
    }
}

/** Picks the target column for moving the selected tasks; only user-writable columns are offered. */
@Composable
private fun KanbanBulkMoveDialog(
    columns: List<KanbanColumn>,
    taskCount: Int,
    onSelectColumn: (columnName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val writableMoveColumns =
        remember(columns) {
            columns.filter { KanbanStatusConstraints.isUserWritableTarget(it.name) }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_move_tasks, taskCount)) },
        text = {
            Column {
                writableMoveColumns.forEach { col ->
                    TextButton(
                        onClick = { onSelectColumn(col.name) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(col.name.uppercase(), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun KanbanBulkAssignDialog(
    profiles: List<KanbanProfile>,
    onSelectProfile: (profileName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_bulk_assign)) },
        text = {
            Column {
                profiles.forEach { profile ->
                    TextButton(
                        onClick = { onSelectProfile(profile.name) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(profile.name, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun KanbanBulkDeleteDialog(
    taskCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_bulk_delete)) },
        text = {
            Text(stringResource(R.string.kanban_bulk_delete_confirm, taskCount))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text(stringResource(R.string.kanban_bulk_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Creates a board; [onCreate] receives the trimmed slug and the optional name, description and project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KanbanCreateBoardDialog(
    projects: List<KanbanProject>,
    onCreate: (slug: String, name: String?, description: String?, projectId: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var boardSlug by remember { mutableStateOf("") }
    var boardName by remember { mutableStateOf("") }
    var boardDesc by remember { mutableStateOf("") }
    var selectedProjId by remember { mutableStateOf<String?>(null) }
    var projectDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_create_new_board)) },
        text = {
            Column {
                OutlinedTextField(
                    value = boardSlug,
                    onValueChange = { boardSlug = it },
                    label = { Text(stringResource(R.string.kanban_slug)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = boardName,
                    onValueChange = { boardName = it },
                    label = { Text(stringResource(R.string.kanban_display_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = boardDesc,
                    onValueChange = { boardDesc = it },
                    label = { Text(stringResource(R.string.kanban_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                ExposedDropdownMenuBox(
                    expanded = projectDropdownExpanded,
                    onExpandedChange = { projectDropdownExpanded = it },
                ) {
                    val displayProj =
                        projects.find { it.id == selectedProjId }?.name
                            ?: stringResource(R.string.kanban_no_project)
                    OutlinedTextField(
                        value = displayProj,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.kanban_project)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = projectDropdownExpanded,
                            )
                        },
                        modifier =
                            Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = projectDropdownExpanded,
                        onDismissRequest = { projectDropdownExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.kanban_no_project)) },
                            onClick = {
                                selectedProjId = null
                                projectDropdownExpanded = false
                            },
                        )
                        projects.forEach { proj ->
                            DropdownMenuItem(
                                text = { Text(proj.name) },
                                onClick = {
                                    selectedProjId = proj.id
                                    projectDropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (boardSlug.isNotBlank()) {
                        onCreate(
                            boardSlug.trim(),
                            boardName.trim().ifBlank { null },
                            boardDesc.trim().ifBlank { null },
                            selectedProjId,
                        )
                    }
                },
                enabled = boardSlug.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Lists the running workers; tapping one opens its task. */
@Composable
private fun KanbanActiveWorkersDialog(
    isLoading: Boolean,
    workers: List<KanbanActiveWorker>,
    onOpenTask: (taskId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_active_workers)) },
        text = {
            if (isLoading) {
                CircularProgressIndicator()
            } else if (workers.isEmpty()) {
                Text(stringResource(R.string.kanban_no_active_workers))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(workers, key = { it.runId }) { worker ->
                        Text(
                            "${worker.taskTitle} · ${worker.profile ?: worker.taskAssignee.orEmpty()}",
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenTask(worker.taskId) }
                                    .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun KanbanArchiveBoardDialog(
    boardName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_archive_board)) },
        text = {
            Text(
                stringResource(
                    R.string.kanban_archive_board_confirm,
                    boardName,
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text(stringResource(R.string.kanban_action_archive))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun LiveStatusPill(isLive: Boolean) {
    val color =
        if (isLive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isLive) stringResource(R.string.kanban_live) else stringResource(R.string.kanban_offline),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskActionSheet(
    task: KanbanTask,
    actions: List<KanbanTaskAction>,
    onAction: (KanbanTaskAction) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
        ) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            actions.forEach { action ->
                TextButton(
                    onClick = { onAction(action) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(action.labelRes()),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmActionDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_confirm_action)) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun CompleteTaskDialog(
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var summary by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_complete_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text(stringResource(R.string.kanban_complete_summary_label)) },
                    placeholder = { Text(stringResource(R.string.kanban_complete_summary_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(summary.trim().ifBlank { null }) }) {
                Text(stringResource(R.string.kanban_action_complete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun BulkCompleteTasksDialog(
    taskCount: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var summary by remember { mutableStateOf("") }
    val trimmedSummary = summary.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kanban_bulk_complete_title, taskCount)) },
        text = {
            OutlinedTextField(
                value = summary,
                onValueChange = { summary = it },
                label = { Text(stringResource(R.string.kanban_complete_summary_label)) },
                placeholder = { Text(stringResource(R.string.kanban_complete_summary_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmedSummary) },
                enabled = trimmedSummary.isNotEmpty(),
            ) {
                Text(stringResource(R.string.kanban_action_complete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun KanbanTaskAction.labelRes(): Int =
    when (this) {
        KanbanTaskAction.TRIAGE -> R.string.kanban_action_triage
        KanbanTaskAction.READY -> R.string.kanban_action_ready
        KanbanTaskAction.BLOCK -> R.string.kanban_action_block
        KanbanTaskAction.UNBLOCK -> R.string.kanban_action_unblock
        KanbanTaskAction.COMPLETE -> R.string.kanban_action_complete
        KanbanTaskAction.ARCHIVE -> R.string.kanban_action_archive
    }

private fun KanbanTaskAction.confirmRes(): Int =
    when (this) {
        KanbanTaskAction.BLOCK -> R.string.kanban_confirm_blocked
        KanbanTaskAction.COMPLETE -> R.string.kanban_confirm_done
        KanbanTaskAction.ARCHIVE -> R.string.kanban_confirm_archive
        else -> R.string.kanban_confirm_blocked
    }
