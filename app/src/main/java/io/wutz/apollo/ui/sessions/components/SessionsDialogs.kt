package io.wutz.apollo.ui.sessions.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.theme.HermesStatusColors
import io.wutz.apollo.theme.LocalSpacing
import io.wutz.apollo.ui.sessions.SessionsUiState
import io.wutz.apollo.ui.sessions.cleanSearchSnippet

/** The History screen's dialogs: empty-session cleanup, prune, single and bulk delete, and rename. */
@Composable
fun SessionsDialogs(
    state: SessionsUiState,
    pruneDays: String,
    onPruneDaysChange: (String) -> Unit,
    statusColors: HermesStatusColors,
    onConfirmEmptyCleanup: () -> Unit,
    onDismissEmptyCleanup: () -> Unit,
    onConfirmPrune: (Int) -> Unit,
    onDismissPrune: () -> Unit,
    onConfirmDeleteSession: () -> Unit,
    onDismissDeleteSession: () -> Unit,
    onConfirmBulkDelete: () -> Unit,
    onDismissBulkDelete: () -> Unit,
    onRenameSession: (String, String) -> Unit,
    onRenameDraftChange: (String) -> Unit,
    onDismissRename: () -> Unit,
) {
    val spacing = LocalSpacing.current

    // Empty-session cleanup dialog (issue #787)
    if (state.showEmptyCleanupDialog) {
        AlertDialog(
            onDismissRequest = onDismissEmptyCleanup,
            title = { Text(stringResource(R.string.sessions_empty_cleanup_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.sessions_empty_cleanup_message,
                        state.emptyCount,
                        state.emptyCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmEmptyCleanup) {
                    if (state.isCleaningEmpty) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.sessions_empty_cleanup_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissEmptyCleanup) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Prune dialog
    if (state.showPruneDialog) {
        AlertDialog(
            onDismissRequest = onDismissPrune,
            title = { Text(stringResource(R.string.sessions_prune_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.sessions_prune_desc))
                    Spacer(modifier = Modifier.height(spacing.md))
                    OutlinedTextField(
                        value = pruneDays,
                        onValueChange = { onPruneDaysChange(it.filter { c -> c.isDigit() }) },
                        label = { Text(stringResource(R.string.sessions_prune_days_label)) },
                        placeholder = { Text("7") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions =
                            KeyboardActions(
                                onDone = {
                                    val days = pruneDays.toIntOrNull()
                                    if (days != null && days > 0) onConfirmPrune(days)
                                },
                            ),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val days = pruneDays.toIntOrNull()
                        if (days != null && days > 0) onConfirmPrune(days)
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = statusColors.error,
                        ),
                ) {
                    Text(stringResource(R.string.sessions_prune_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPrune) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Single-session delete confirmation dialog
    if (state.sessionToDeleteConfirm != null) {
        val sessionToDelete = state.sessionToDeleteConfirm
        val sessionTitle =
            state.sessions
                .find { it.id == sessionToDelete }
                ?.title
                ?.takeIf { it.isNotBlank() }
                ?: state.searchResults
                    .find { it.session_id == sessionToDelete }
                    ?.snippet
                    ?.let(::cleanSearchSnippet)
                    ?.take(80)
                ?: stringResource(R.string.history_untitled)
        AlertDialog(
            onDismissRequest = onDismissDeleteSession,
            title = { Text(stringResource(R.string.sessions_delete_title)) },
            text = {
                Text(stringResource(R.string.sessions_delete_message, sessionTitle))
            },
            confirmButton = {
                Button(
                    onClick = onConfirmDeleteSession,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = statusColors.error,
                        ),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteSession) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Bulk delete confirmation dialog
    if (state.showBulkDeleteConfirm) {
        AlertDialog(
            onDismissRequest = onDismissBulkDelete,
            title = { Text(stringResource(R.string.sessions_bulk_delete_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.sessions_bulk_delete_message,
                        state.selectedIds.size,
                        state.selectedIds.size,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = onConfirmBulkDelete,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = statusColors.error,
                        ),
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissBulkDelete) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Single-session rename dialog (issue #785) — opened from the card's
    // hold menu; PATCH /api/sessions/{id} with {title}.
    state.renamingSessionId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = onDismissRename,
            title = { Text(stringResource(R.string.sessions_rename_title)) },
            text = {
                OutlinedTextField(
                    value = state.renameDraft,
                    onValueChange = onRenameDraftChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.sessions_rename_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRenameSession(sessionId, state.renameDraft) },
                    enabled = state.renameDraft.isNotBlank(),
                ) {
                    Text(stringResource(R.string.sessions_action_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRename) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
