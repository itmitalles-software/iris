package io.wutz.apollo.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.wutz.apollo.ChatScreen
import io.wutz.apollo.NavigationController
import io.wutz.apollo.R
import io.wutz.apollo.data.model.ProjectInfo
import io.wutz.apollo.data.model.SessionInfo
import io.wutz.apollo.data.model.SessionTreeItem
import io.wutz.apollo.data.model.UNTITLED_SESSION_TITLE
import io.wutz.apollo.data.model.flattenSessionsWithBranches
import io.wutz.apollo.data.model.startable
import io.wutz.apollo.theme.LocalHermesStatusColors
import io.wutz.apollo.theme.LocalSpacing
import io.wutz.apollo.ui.common.EmptyState
import io.wutz.apollo.ui.common.ErrorState
import io.wutz.apollo.ui.common.HermesScaffold
import io.wutz.apollo.ui.common.NavIcon
import io.wutz.apollo.ui.common.SearchBar
import io.wutz.apollo.ui.common.SkeletonListState
import io.wutz.apollo.ui.common.StatCard
import io.wutz.apollo.ui.common.StatusBadge
import io.wutz.apollo.ui.common.StatusBadgeType
import io.wutz.apollo.ui.common.ToastEffect
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.asString
import io.wutz.apollo.ui.common.listItemSpacing
import io.wutz.apollo.ui.sessions.components.BranchRow
import io.wutz.apollo.ui.sessions.components.PinnedSectionHeader
import io.wutz.apollo.ui.sessions.components.ProjectGroupHeader
import io.wutz.apollo.ui.sessions.components.SearchResultCard
import io.wutz.apollo.ui.sessions.components.SessionCard
import io.wutz.apollo.ui.sessions.components.SessionsBulkActionBar
import io.wutz.apollo.ui.sessions.components.SessionsDialogs
import io.wutz.apollo.ui.sessions.components.SessionsStatsRow
import io.wutz.apollo.ui.sessions.components.automationGroups
import io.wutz.apollo.ui.sessions.components.localizedSourceLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Auto-load the next history page when the user scrolls to within this many
 * items of the end — a pre-load buffer so paging feels continuous.
 */
private const val AUTO_LOAD_THRESHOLD = 6
private const val AGE_TICK_MS = 60_000L

internal fun displayedSessions(state: SessionsUiState): List<SessionTreeItem> =
    if (state.isSearchMode) {
        state.searchResults
            .map { result -> result.toSessionInfo() }
            .filter { session ->
                state.activeSourceFilter?.let { session.source?.lowercase() == it } ?: true
            }.map { session ->
                SessionTreeItem(
                    session = session,
                    depth = 0,
                    branchStem = null,
                    displayTitle =
                        session.title?.takeIf(String::isNotBlank)
                            ?: session.display_name?.takeIf(String::isNotBlank)
                            ?: session.preview?.takeIf(String::isNotBlank)?.take(80)
                            ?: UNTITLED_SESSION_TITLE,
                )
            }
    } else {
        flattenSessionsWithBranches(state.displaySessions)
    }

/**
 * Every user action of the History screen. [SessionsScreen] wires them to [SessionsViewModel] and
 * [NavigationController]; the no-op defaults let UI tests pass only the callbacks they check.
 */
@Immutable
internal class SessionsScreenActions(
    val onRefresh: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onNewChatInProject: (ProjectInfo) -> Unit = {},
    val onStartChat: () -> Unit = {},
    val onOpenSession: (sessionId: String) -> Unit = {},
    val onToggleShowArchived: () -> Unit = {},
    val onToggleShowHidden: () -> Unit = {},
    val onSelectSection: (HistorySection) -> Unit = {},
    val onSelectSourceFilter: (source: String) -> Unit = {},
    val onToggleGroupByProject: () -> Unit = {},
    val onSelectProjectFilter: (key: String) -> Unit = {},
    val onSearchQueryChange: (String) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onLoadMoreSearch: () -> Unit = {},
    val onTogglePinnedExpanded: () -> Unit = {},
    val onToggleProjectGroup: (key: String) -> Unit = {},
    val onToggleSessionSelection: (sessionId: String) -> Unit = {},
    val onStartSelection: (sessionId: String) -> Unit = {},
    val onSelectAll: (sessionIds: Set<String>) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onTogglePin: (sessionId: String) -> Unit = {},
    val onToggleArchive: (sessionId: String) -> Unit = {},
    val onOpenRename: (sessionId: String, currentTitle: String) -> Unit = { _, _ -> },
    val onRenameDraftChange: (String) -> Unit = {},
    val onRename: (sessionId: String, title: String) -> Unit = { _, _ -> },
    val onDismissRename: () -> Unit = {},
    val onRequestDelete: (sessionId: String) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDismissDelete: () -> Unit = {},
    val onRequestBulkDelete: () -> Unit = {},
    val onConfirmBulkDelete: () -> Unit = {},
    val onDismissBulkDelete: () -> Unit = {},
    val onRequestEmptyCleanup: () -> Unit = {},
    val onConfirmEmptyCleanup: () -> Unit = {},
    val onDismissEmptyCleanup: () -> Unit = {},
    val onShowPruneDialog: () -> Unit = {},
    val onPrune: (days: Int) -> Unit = {},
    val onDismissPrune: () -> Unit = {},
)

private fun SessionsViewModel.screenActions(): SessionsScreenActions =
    SessionsScreenActions(
        onRefresh = { loadSessions(forceRefresh = true) },
        onRetryLoad = { loadSessions() },
        onNewChat = { NavigationController.openNewChat() },
        onNewChatInProject = { project -> NavigationController.openNewChat(project) },
        onStartChat = { NavigationController.navigateTo(ChatScreen) },
        onOpenSession = { sessionId -> NavigationController.openChatSession(sessionId) },
        onToggleShowArchived = ::toggleShowArchived,
        onToggleShowHidden = ::toggleShowHidden,
        onSelectSection = ::selectSection,
        onSelectSourceFilter = { source -> selectSourceFilter(source) },
        onToggleGroupByProject = ::toggleGroupByProject,
        onSelectProjectFilter = { key -> selectProjectFilter(key) },
        onSearchQueryChange = ::setSearchQuery,
        onLoadMore = ::loadMore,
        onLoadMoreSearch = ::loadMoreSearch,
        onTogglePinnedExpanded = ::togglePinnedExpanded,
        onToggleProjectGroup = ::toggleProjectGroup,
        onToggleSessionSelection = ::toggleSessionSelection,
        onStartSelection = { sessionId ->
            toggleSelecting()
            toggleSessionSelection(sessionId)
        },
        onSelectAll = { sessionIds -> selectAll(sessionIds) },
        onClearSelection = ::clearSelection,
        onTogglePin = ::togglePin,
        onToggleArchive = ::toggleArchive,
        onOpenRename = ::openRenameDialog,
        onRenameDraftChange = ::updateRenameDraft,
        onRename = ::renameSession,
        onDismissRename = ::closeRenameDialog,
        onRequestDelete = ::requestDeleteSession,
        onConfirmDelete = ::confirmDeleteSession,
        onDismissDelete = ::cancelDeleteSession,
        onRequestBulkDelete = ::requestBulkDelete,
        onConfirmBulkDelete = ::confirmBulkDelete,
        onDismissBulkDelete = ::cancelBulkDelete,
        onRequestEmptyCleanup = ::requestEmptyCleanup,
        onConfirmEmptyCleanup = ::confirmEmptyCleanup,
        onDismissEmptyCleanup = ::hideEmptyCleanupDialog,
        onShowPruneDialog = ::showPruneDialog,
        onPrune = ::pruneSessions,
        onDismissPrune = ::hidePruneDialog,
    )

@Composable
fun SessionsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: SessionsViewModel = viewModel { SessionsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel) { viewModel.screenActions() }

    LaunchedEffect(Unit) {
        viewModel.loadSessions()
    }

    DisposableEffect(Unit) {
        viewModel.startLiveStatusTracking()
        onDispose {
            viewModel.stopLiveStatusTracking()
        }
    }

    // Toast effect
    ToastEffect(
        toastMessage = state.toastMessage,
        onClearToast = { viewModel.clearToast() },
    )

    SessionsScreenContent(
        state = state,
        actions = actions,
        modifier = modifier,
        onOpenDrawer = onOpenDrawer,
    )
}

/** Stateless History screen: renders [state] and reports every interaction through [actions]. */
@Composable
internal fun SessionsScreenContent(
    state: SessionsUiState,
    actions: SessionsScreenActions,
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
) {
    val statusColors = LocalHermesStatusColors.current

    var pruneDays by remember { mutableStateOf("7") }
    var expandedAutomationGroups by remember { mutableStateOf(emptySet<String>()) }

    val sessionsToDisplay =
        remember(
            state.isSearchMode,
            state.searchQuery,
            state.sessions,
            state.showHidden,
            state.searchResults,
            state.sourceFilter,
            state.projectFilter,
            state.projects,
        ) {
            displayedSessions(state)
        }

    val hasSelection = state.selectedIds.isNotEmpty()
    val visibleSessionIds =
        remember(sessionsToDisplay) {
            sessionsToDisplay.mapTo(linkedSetOf()) { it.session.id }
        }
    val allVisibleSessionsSelected =
        visibleSessionIds.isNotEmpty() && visibleSessionIds.all { it in state.selectedIds }
    val listPadding =
        PaddingValues(
            start = 12.dp,
            top = 8.dp,
            end = 12.dp,
            bottom = if (hasSelection) 72.dp else 8.dp,
        )

    SessionsDialogs(
        state = state,
        pruneDays = pruneDays,
        onPruneDaysChange = { pruneDays = it },
        statusColors = statusColors,
        onConfirmEmptyCleanup = actions.onConfirmEmptyCleanup,
        onDismissEmptyCleanup = actions.onDismissEmptyCleanup,
        onConfirmPrune = actions.onPrune,
        onDismissPrune = actions.onDismissPrune,
        onConfirmDeleteSession = actions.onConfirmDelete,
        onDismissDeleteSession = actions.onDismissDelete,
        onConfirmBulkDelete = actions.onConfirmBulkDelete,
        onDismissBulkDelete = actions.onDismissBulkDelete,
        onRenameSession = actions.onRename,
        onRenameDraftChange = actions.onRenameDraftChange,
        onDismissRename = actions.onDismissRename,
    )

    HermesScaffold(
        title = { Text(stringResource(R.string.screen_history)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = actions.onRefresh,
        actions = {
            SessionsTopBarActions(
                showArchived = state.showArchived,
                hasHiddenSessions = state.hasHiddenSessions,
                showHidden = state.showHidden,
                actions = actions,
            )
        },
        modifier = modifier,
    ) {
        // Single scrolling column: search bar pinned on top, list fills below.
        // (The scaffold already applies top-bar padding via its inner Box, so we
        //  must NOT re-apply paddingValues here.)
        Column(modifier = Modifier.fillMaxSize()) {
            // Prune can take a while on large histories — show it's working.
            if (state.isPruning) {
                SessionsPruningIndicator()
            }
            SessionsFilterChips(state = state, actions = actions)
            SessionsSearchRow(
                searchQuery = state.searchQuery,
                section = state.section,
                emptyCount = state.emptyCount,
                onSearchQueryChange = actions.onSearchQueryChange,
                onRequestEmptyCleanup = actions.onRequestEmptyCleanup,
            )

            // List/state area takes all remaining height below the search bar.
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                SessionsListArea(
                    state = state,
                    sessionsToDisplay = sessionsToDisplay,
                    listPadding = listPadding,
                    expandedAutomationGroups = expandedAutomationGroups,
                    onToggleAutomationGroup = { groupKey ->
                        expandedAutomationGroups =
                            if (groupKey in expandedAutomationGroups) {
                                expandedAutomationGroups - groupKey
                            } else {
                                expandedAutomationGroups + groupKey
                            }
                    },
                    actions = actions,
                )
            }
        }

        // ── Bulk action toolbar (animated) ──────────────────────────────────
        SessionsBulkActionBar(
            visible = state.isSelecting && hasSelection,
            allVisibleSessionsSelected = allVisibleSessionsSelected,
            selectedCount = state.selectedIds.size,
            isDeletingBulk = state.isDeletingBulk,
            spacing = LocalSpacing.current,
            statusColors = statusColors,
            onSelectAllToggle = {
                if (allVisibleSessionsSelected) {
                    actions.onClearSelection()
                } else {
                    actions.onSelectAll(visibleSessionIds)
                }
            },
            onDeleteSelected = actions.onRequestBulkDelete,
        )
    }
}

/** New chat, archived toggle and (when hidden sessions exist) the hidden toggle. */
@Composable
private fun SessionsTopBarActions(
    showArchived: Boolean,
    hasHiddenSessions: Boolean,
    showHidden: Boolean,
    actions: SessionsScreenActions,
) {
    IconButton(
        onClick = actions.onNewChat,
        modifier = Modifier.testTag("sessions_action_new_chat"),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.content_desc_new_chat),
        )
    }
    IconButton(
        onClick = actions.onToggleShowArchived,
        modifier = Modifier.testTag("sessions_action_toggle_archived"),
    ) {
        Icon(
            imageVector = if (showArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
            contentDescription =
                if (showArchived) {
                    stringResource(R.string.sessions_hide_archived)
                } else {
                    stringResource(R.string.sessions_show_archived)
                },
        )
    }
    if (hasHiddenSessions) {
        IconButton(
            onClick = actions.onToggleShowHidden,
            modifier = Modifier.testTag("sessions_action_toggle_hidden"),
        ) {
            Icon(
                imageVector =
                    if (showHidden) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                contentDescription =
                    if (showHidden) {
                        stringResource(R.string.sessions_hide_hidden)
                    } else {
                        stringResource(R.string.sessions_show_hidden)
                    },
            )
        }
    }
}

@Composable
private fun SessionsPruningIndicator() {
    val spacing = LocalSpacing.current
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm)
                .testTag("sessions_pruning_indicator"),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(R.string.sessions_pruning),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** Section tabs, then source chips and project chips when there is more than one choice. */
@Composable
private fun SessionsFilterChips(
    state: SessionsUiState,
    actions: SessionsScreenActions,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        FilterChip(
            selected = state.section == HistorySection.CONVERSATIONS,
            onClick = { actions.onSelectSection(HistorySection.CONVERSATIONS) },
            label = { Text(stringResource(R.string.sessions_tab_conversations)) },
            modifier = Modifier.testTag("history_tab_conversations"),
        )
        FilterChip(
            selected = state.section == HistorySection.AUTOMATIONS,
            onClick = { actions.onSelectSection(HistorySection.AUTOMATIONS) },
            label = { Text(stringResource(R.string.sessions_tab_automations)) },
            modifier = Modifier.testTag("history_tab_automations"),
        )
    }

    // Source chips: hidden until a loaded row actually carries that source.
    val sources = state.availableSources
    if (sources.size > 1) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("history_source_chips"),
            contentPadding = PaddingValues(horizontal = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(sources, key = { it }) { source ->
                FilterChip(
                    selected = state.activeSourceFilter == source,
                    onClick = { actions.onSelectSourceFilter(source) },
                    label = { Text(localizedSourceLabel(source)) },
                    modifier = Modifier.testTag("history_source_chip_$source"),
                )
            }
        }
    }

    // Project chips (issue #1526): only for projects present in the rows currently shown.
    val projectChoices = state.availableProjects
    if (state.section == HistorySection.CONVERSATIONS && projectChoices.size > 1) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().testTag("history_project_chips"),
            contentPadding = PaddingValues(horizontal = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "group_toggle") {
                FilterChip(
                    selected = state.groupByProject,
                    onClick = actions.onToggleGroupByProject,
                    label = { Text(stringResource(R.string.sessions_group_by_project)) },
                    modifier = Modifier.testTag("history_group_by_project"),
                )
            }
            items(projectChoices, key = { "project_${it.key}" }) { choice ->
                FilterChip(
                    selected = state.activeProjectFilter == choice.key,
                    onClick = { actions.onSelectProjectFilter(choice.key) },
                    label = {
                        Text(
                            if (choice.key == NO_PROJECT_KEY) {
                                stringResource(R.string.sessions_project_none)
                            } else {
                                choice.key
                            },
                        )
                    },
                    modifier = Modifier.testTag("history_project_chip_${choice.key}"),
                )
            }
        }
    }
}

/** Search field plus the empty-session cleanup button (Conversations only). */
@Composable
private fun SessionsSearchRow(
    searchQuery: String,
    section: HistorySection,
    emptyCount: Int,
    onSearchQueryChange: (String) -> Unit,
    onRequestEmptyCleanup: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // ── Search + bulk toggle (always visible) ─────────────
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBar(
            query = searchQuery,
            onQueryChange = onSearchQueryChange,
            placeholder = stringResource(R.string.sessions_search_placeholder),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        // This API operates on all history, so do not present it as scoped
        // to the Automations tab.
        if (section == HistorySection.CONVERSATIONS) {
            BadgedBox(
                badge = {
                    if (emptyCount > 0) {
                        Badge { Text("$emptyCount") }
                    }
                },
            ) {
                FilledTonalButton(
                    onClick = onRequestEmptyCleanup,
                    enabled = emptyCount > 0,
                    contentPadding = PaddingValues(horizontal = spacing.md, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text(stringResource(R.string.sessions_empty_cleanup_desc))
                }
            }
        }
    }
}

/** Search results, loading/error/empty states, or the session list. */
@Composable
private fun SessionsListArea(
    state: SessionsUiState,
    sessionsToDisplay: List<SessionTreeItem>,
    listPadding: PaddingValues,
    expandedAutomationGroups: Set<String>,
    onToggleAutomationGroup: (String) -> Unit,
    actions: SessionsScreenActions,
) {
    when {
        state.isSearchMode -> {
            SessionsSearchResults(
                state = state,
                sessionsToDisplay = sessionsToDisplay,
                listPadding = listPadding,
                actions = actions,
            )
        }

        state.isLoading && state.sessions.isEmpty() -> {
            SkeletonListState()
        }

        state.errorMessage != null -> {
            ErrorState(
                message = state.errorMessage.asString(),
                onRetry = actions.onRetryLoad,
            )
        }

        state.sessions.isEmpty() && state.corruptStorageProfiles.isNotEmpty() -> {
            ErrorState(
                message =
                    stringResource(
                        R.string.sessions_storage_corrupt,
                        state.corruptStorageProfiles.sorted().joinToString(", "),
                    ),
                onRetry = actions.onRefresh,
            )
        }

        state.sessions.isEmpty() && state.showArchived -> {
            EmptyState(
                title = stringResource(R.string.sessions_archived_empty_title),
                subtitle = stringResource(R.string.sessions_archived_empty_desc),
                icon = Icons.Filled.Archive,
            )
        }

        state.sessions.isEmpty() -> {
            EmptyState(
                title =
                    stringResource(
                        if (state.section == HistorySection.AUTOMATIONS) {
                            R.string.sessions_automations_empty_title
                        } else {
                            R.string.history_empty_title
                        },
                    ),
                subtitle =
                    stringResource(
                        if (state.section == HistorySection.AUTOMATIONS) {
                            R.string.sessions_automations_empty_desc
                        } else {
                            R.string.history_empty_desc
                        },
                    ),
                icon = Icons.Filled.History,
                actionLabel =
                    if (state.section == HistorySection.CONVERSATIONS) {
                        stringResource(R.string.empty_action_start_chat)
                    } else {
                        null
                    },
                onAction = actions.onStartChat,
            )
        }

        state.displaySessions.isEmpty() -> {
            EmptyState(
                title = stringResource(R.string.history_empty_title),
                subtitle = stringResource(R.string.sessions_all_hidden_desc),
                icon = Icons.Filled.VisibilityOff,
                actionLabel = stringResource(R.string.sessions_show_hidden),
                onAction = actions.onToggleShowHidden,
            )
        }

        else -> {
            SessionsList(
                state = state,
                sessionsToDisplay = sessionsToDisplay,
                listPadding = listPadding,
                expandedAutomationGroups = expandedAutomationGroups,
                onToggleAutomationGroup = onToggleAutomationGroup,
                actions = actions,
            )
        }
    }
}

@Composable
private fun SessionsSearchResults(
    state: SessionsUiState,
    sessionsToDisplay: List<SessionTreeItem>,
    listPadding: PaddingValues,
    actions: SessionsScreenActions,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxSize()) {
        when {
            state.isSearching && state.searchResults.isEmpty() -> {
                SkeletonListState()
            }

            state.searchError != null -> {
                ErrorState(
                    message = state.searchError.asString(),
                    onRetry = { actions.onSearchQueryChange(state.searchQuery) },
                )
            }

            state.searchResults.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.sessions_search_empty_title),
                    subtitle =
                        stringResource(
                            R.string.sessions_search_empty_desc,
                            state.searchQuery,
                        ),
                    icon = Icons.Filled.Search,
                )
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text =
                            pluralStringResource(
                                R.plurals.sessions_search_results_header,
                                state.searchResults.size,
                                state.searchResults.size,
                                state.searchQuery,
                            ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = spacing.md,
                                    vertical = spacing.sm,
                                ),
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = listPadding,
                        verticalArrangement = listItemSpacing,
                    ) {
                        items(sessionsToDisplay, key = { it.session.id }) { item ->
                            val session = item.session
                            SearchResultCard(
                                session = session,
                                query = state.searchQuery,
                                isSelecting = state.isSelecting,
                                isSelected = session.id in state.selectedIds,
                                isDeleting = session.id in state.deletingSessionIds,
                                liveStatus = state.liveStatuses[session.id],
                                highlightBackground = MaterialTheme.colorScheme.primaryContainer,
                                highlightForeground = MaterialTheme.colorScheme.onPrimaryContainer,
                                onCardClick = {
                                    if (state.isSelecting) {
                                        actions.onToggleSessionSelection(session.id)
                                    } else {
                                        actions.onOpenSession(session.id)
                                    }
                                },
                                onToggleSelection = { actions.onToggleSessionSelection(session.id) },
                                onSelect = { actions.onStartSelection(session.id) },
                                onRename = { actions.onOpenRename(session.id, session.title.orEmpty()) },
                                onDelete = { actions.onRequestDelete(session.id) },
                            )
                        }
                        if (state.searchHasMore) {
                            item(key = "search_paging_footer") {
                                SearchPagingFooter(
                                    loadMoreError = state.searchLoadMoreError,
                                    isLoadingMore = state.isLoadingMoreSearch,
                                    onLoadMore = actions.onLoadMoreSearch,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPagingFooter(
    loadMoreError: UiText?,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        loadMoreError?.let { error ->
            Text(
                error.asString(),
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(
            onClick = onLoadMore,
            enabled = !isLoadingMore,
            modifier =
                Modifier.fillMaxWidth().testTag(
                    "search_load_more",
                ),
        ) {
            if (isLoadingMore) {
                CircularProgressIndicator(Modifier.size(20.dp))
            } else {
                Text(
                    stringResource(
                        if (loadMoreError != null) {
                            R.string.action_retry
                        } else {
                            R.string.history_load_more
                        },
                    ),
                )
            }
        }
    }
}

/** Stats row and the paged session list (pinned, project groups or automation groups). */
@Composable
private fun SessionsList(
    state: SessionsUiState,
    sessionsToDisplay: List<SessionTreeItem>,
    listPadding: PaddingValues,
    expandedAutomationGroups: Set<String>,
    onToggleAutomationGroup: (String) -> Unit,
    actions: SessionsScreenActions,
) {
    val spacing = LocalSpacing.current
    val resources = LocalResources.current
    val statusColors = LocalHermesStatusColors.current
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer

    val pinnedItems =
        remember(
            state.isSearchMode,
            state.pinnedSessions,
            state.showHidden,
        ) {
            if (state.isSearchMode) {
                emptyList()
            } else {
                flattenSessionsWithBranches(state.pinnedSessions, preserveOrder = true)
            }
        }
    val projectGroups =
        remember(sessionsToDisplay, state.projects, state.groupByProject) {
            if (state.groupByProject) groupSessionsByProject(sessionsToDisplay, state.projects) else emptyList()
        }
    val noProjectLabel = stringResource(R.string.sessions_project_none)
    val loadedMessageCount = state.sessions.sumOf { it.message_count ?: 0 }
    val automationSessionGroups = remember(sessionsToDisplay) { automationGroups(sessionsToDisplay.map { it.session }) }
    val sessionItemsById = remember(sessionsToDisplay) { sessionsToDisplay.associateBy { it.session.id } }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.corruptStorageProfiles.isNotEmpty()) {
            Text(
                text =
                    stringResource(
                        R.string.sessions_storage_corrupt,
                        state.corruptStorageProfiles.sorted().joinToString(", "),
                    ),
                color = statusColors.warning,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(spacing.md),
            )
        }
        // ── Stats row ───────────────────────────────────────
        SessionsStatsRow(
            total = state.total,
            loadedMessageCount = loadedMessageCount,
            section = state.section,
            spacing = spacing,
            statusColors = statusColors,
            onPruneClick = actions.onShowPruneDialog,
        )

        // ── Session list ────────────────────────────────────
        val listState = rememberLazyListState()
        // Opening (or switching section) always starts at the top. Keyed items otherwise
        // keep the old anchor row when fresher sessions land above it after the refresh.
        var userScrolled by remember(state.section) { mutableStateOf(false) }
        LaunchedEffect(state.section) {
            snapshotFlow { listState.isScrollInProgress }.first { it }
            userScrolled = true
        }
        val firstSessionId = sessionsToDisplay.firstOrNull()?.session?.id
        LaunchedEffect(state.section, firstSessionId, userScrolled) {
            if (!userScrolled && firstSessionId != null) listState.scrollToItem(0)
        }
        // Fluid infinite scroll: once the user reaches within
        // AUTO_LOAD_THRESHOLD items of the end, pull the next
        // page automatically. Driven by real scroll position
        // (layoutInfo is snapshot state), so it recomputes on
        // every scroll — not a frozen first-composition read.
        val nearListEnd by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                if (lastVisible < 0 || info.totalItemsCount == 0) {
                    false
                } else if (info.totalItemsCount <= AUTO_LOAD_THRESHOLD) {
                    lastVisible >= info.totalItemsCount - 1
                } else {
                    lastVisible >= info.totalItemsCount - AUTO_LOAD_THRESHOLD
                }
            }
        }
        LaunchedEffect(
            nearListEnd,
            state.isLoadingMore,
            state.hasMore,
            state.isSearchMode,
        ) {
            if (
                state.section == HistorySection.CONVERSATIONS &&
                nearListEnd &&
                !state.isLoadingMore &&
                state.hasMore &&
                !state.isSearchMode
            ) {
                actions.onLoadMore()
            }
        }
        // One clock for every row's "15m" age, re-read on each minute boundary.
        val nowMillis by produceState(System.currentTimeMillis()) {
            while (true) {
                delay(AGE_TICK_MS - value % AGE_TICK_MS)
                value = System.currentTimeMillis()
            }
        }
        val sessionCard: @Composable (SessionTreeItem) -> Unit = { item ->
            val session = item.session
            val project =
                remember(session.cwd, session.git_repo_root, state.projects) {
                    resolveSessionProject(session, state.projects)
                }
            BranchRow(item = item) {
                SessionCard(
                    session = session,
                    displayTitle = item.displayTitle,
                    branchStem = null,
                    branchNumber = item.branchNumber,
                    isFork = item.isFork,
                    forkDepth = item.forkDepth,
                    query = state.searchQuery,
                    isSelecting = state.isSelecting,
                    isSelected = session.id in state.selectedIds,
                    isDeleting = session.id in state.deletingSessionIds,
                    isPinned = session.pinned == true,
                    isArchived = session.archived == true,
                    liveStatus = state.liveStatuses[session.id],
                    project = project,
                    nowMillis = nowMillis,
                    highlightBackground = primaryContainer,
                    highlightForeground = onPrimaryContainer,
                    onCardClick = {
                        if (state.isSelecting) {
                            actions.onToggleSessionSelection(session.id)
                        } else {
                            actions.onOpenSession(session.id)
                        }
                    },
                    onToggleSelection = { actions.onToggleSessionSelection(session.id) },
                    onSelect = { actions.onStartSelection(session.id) },
                    onRename = {
                        actions.onOpenRename(
                            session.id,
                            item.branchNumber?.let { n ->
                                resources.getString(R.string.sessions_branch_title, n)
                            } ?: item.displayTitle,
                        )
                    },
                    onTogglePin = { actions.onTogglePin(session.id) },
                    onToggleArchive = { actions.onToggleArchive(session.id) },
                    onDelete = { actions.onRequestDelete(session.id) },
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = listPadding,
            verticalArrangement = listItemSpacing,
        ) {
            if (state.section == HistorySection.AUTOMATIONS) {
                automationGroups(
                    groups = automationSessionGroups,
                    expandedGroups = expandedAutomationGroups,
                    sessionItemsById = sessionItemsById,
                    spacing = spacing,
                    onToggleGroup = onToggleAutomationGroup,
                    renderSessionCard = sessionCard,
                )
            } else {
                if (pinnedItems.isNotEmpty() && !state.isSearchMode) {
                    item(key = "pinned_header") {
                        PinnedSectionHeader(
                            count = pinnedItems.size,
                            expanded = state.pinnedExpanded,
                            onToggle = actions.onTogglePinnedExpanded,
                        )
                    }
                    if (state.pinnedExpanded) {
                        items(pinnedItems, key = { "pin_${it.session.id}" }) { item ->
                            sessionCard(item)
                        }
                    }
                    item(key = "history_header") {
                        Text(
                            text = stringResource(R.string.screen_history),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = spacing.xs),
                        )
                    }
                }
                if (state.groupByProject && !state.isSearchMode) {
                    projectGroups.forEach { group ->
                        val expanded = group.key !in state.collapsedProjectGroups
                        item(key = "project_header_${group.key}") {
                            ProjectGroupHeader(
                                label =
                                    if (group.key ==
                                        NO_PROJECT_KEY
                                    ) {
                                        noProjectLabel
                                    } else {
                                        group.key
                                    },
                                color = group.color,
                                count = group.items.size,
                                expanded = expanded,
                                onToggle = { actions.onToggleProjectGroup(group.key) },
                                onNewChat =
                                    state.projects
                                        .startable()
                                        .firstOrNull { it.id == group.projectId }
                                        ?.let { project ->
                                            { actions.onNewChatInProject(project) }
                                        },
                            )
                        }
                        if (expanded) {
                            items(
                                group.items,
                                key = { "project_${group.key}_${it.session.id}" },
                            ) { item ->
                                sessionCard(item)
                            }
                        }
                    }
                } else {
                    items(sessionsToDisplay, key = { it.session.id }) { item ->
                        sessionCard(item)
                    }
                }
            }

            // Load more
            if (state.hasMore || state.isLoadingMore) {
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = spacing.sm),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isLoadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.history_load_more),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier =
                                    Modifier
                                        .testTag("load_more_sessions")
                                        .clickable(role = Role.Button, onClick = actions.onLoadMore),
                            )
                        }
                    }
                }
            }
        }
    }
}
