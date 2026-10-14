@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.wutz.apollo.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.theme.LocalHermesStatusColors
import io.wutz.apollo.theme.LocalSpacing
import io.wutz.apollo.ui.common.ConfirmDialog
import io.wutz.apollo.ui.common.StatusBadge
import io.wutz.apollo.ui.common.StatusBadgeType
import io.wutz.apollo.ui.common.asString

/**
 * Detail of one learned skill or memory: its current content as an editable field, Save, and a
 * confirmed Forget. Memories are removed; skills are archived and can be restored on the server.
 */
@Composable
internal fun MemoryNodeSheet(
    node: MemoryNodeUiState,
    onSave: (String) -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val statusColors = LocalHermesStatusColors.current
    val isSkill = node.kind == "skill"
    val loadedContent = node.detail?.content
    // Keyed by the loaded text so the field fills once the detail arrives.
    var draft by rememberSaveable(node.id, loadedContent) { mutableStateOf(loadedContent.orEmpty()) }
    var confirmForget by rememberSaveable(node.id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg, vertical = spacing.sm)
                    .testTag("memory_node_sheet"),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = node.label,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    text =
                        stringResource(
                            if (isSkill) R.string.memory_node_kind_skill else R.string.memory_node_kind_memory,
                        ),
                    status = StatusBadgeType.INFO,
                )
            }
            Text(
                text =
                    stringResource(
                        if (isSkill) R.string.memory_node_skill_hint else R.string.memory_node_memory_hint,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (node.isLoading || node.isSaving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (node.detail != null) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = !node.isSaving,
                    label = { Text(stringResource(R.string.memory_node_content_label)) },
                    textStyle =
                        if (isSkill) {
                            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp)
                            .testTag("memory_node_content"),
                )
            }

            node.error?.let { error ->
                Text(
                    text = error.asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColors.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canForget = !node.isSaving && !node.isLoading
                TextButton(
                    onClick = { confirmForget = true },
                    enabled = canForget,
                    modifier = Modifier.testTag("memory_node_forget"),
                ) {
                    Text(
                        text = stringResource(R.string.memory_node_forget),
                        color = if (canForget) statusColors.error else LocalContentColor.current,
                    )
                }
                Button(
                    onClick = { onSave(draft) },
                    enabled =
                        node.detail != null &&
                            !node.isSaving &&
                            draft.isNotBlank() &&
                            draft != loadedContent,
                    modifier = Modifier.testTag("memory_node_save"),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = stringResource(R.string.memory_node_forget_title),
            message =
                if (isSkill) {
                    stringResource(R.string.memory_node_forget_skill_message, node.label)
                } else {
                    stringResource(R.string.memory_node_forget_memory_message)
                },
            confirmText = stringResource(R.string.memory_node_forget),
            isDestructive = true,
            onConfirm = {
                confirmForget = false
                onForget()
            },
            onDismiss = { confirmForget = false },
        )
    }
}
