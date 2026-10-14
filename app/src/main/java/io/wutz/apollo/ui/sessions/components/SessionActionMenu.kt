package io.wutz.apollo.ui.sessions.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import io.wutz.apollo.R
import io.wutz.apollo.theme.LocalHermesStatusColors

/**
 * Per-session overflow/long-press menu.
 */
@Composable
fun SessionActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isDeleting: Boolean,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    isArchived: Boolean = false,
    onToggleArchive: (() -> Unit)? = null,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
) {
    val statusColors = LocalHermesStatusColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (onTogglePin != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (isPinned) {
                                R.string.sessions_action_unpin
                            } else {
                                R.string.sessions_action_pin
                            },
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                onClick = onTogglePin,
            )
        }
        if (onToggleArchive != null) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (isArchived) {
                                R.string.sessions_action_unarchive
                            } else {
                                R.string.sessions_action_archive
                            },
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector =
                            if (isArchived) {
                                Icons.Filled.Unarchive
                            } else {
                                Icons.Filled.Archive
                            },
                        contentDescription = null,
                    )
                },
                onClick = onToggleArchive,
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sessions_action_select)) },
            leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
            onClick = onSelect,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sessions_action_rename)) },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            onClick = onRename,
        )
        if (onShare != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_transfer_action_share)) },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = onShare,
            )
        }
        if (onExport != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_transfer_action_export)) },
                leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                onClick = onExport,
            )
        }
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = statusColors.error,
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = statusColors.error,
                )
            },
            enabled = !isDeleting,
            onClick = onDelete,
        )
    }
}

/**
 * TalkBack custom actions that mirror [SessionActionMenu], so pin, archive, select, rename, share, export and delete are
 * reachable without the long-press gesture. Returns an empty list while selecting (the menu is off then).
 */
@Composable
internal fun sessionCustomActions(
    enabled: Boolean,
    isDeleting: Boolean,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    isArchived: Boolean = false,
    onToggleArchive: (() -> Unit)? = null,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
): List<CustomAccessibilityAction> {
    val pinLabel = stringResource(if (isPinned) R.string.sessions_action_unpin else R.string.sessions_action_pin)
    val archiveLabel =
        stringResource(if (isArchived) R.string.sessions_action_unarchive else R.string.sessions_action_archive)
    val selectLabel = stringResource(R.string.sessions_action_select)
    val renameLabel = stringResource(R.string.sessions_action_rename)
    val deleteLabel = stringResource(R.string.action_delete)
    val shareLabel = stringResource(R.string.session_transfer_action_share)
    val exportLabel = stringResource(R.string.session_transfer_action_export)
    if (!enabled) return emptyList()
    return buildList {
        fun action(
            label: String,
            run: () -> Unit,
        ) = add(
            CustomAccessibilityAction(label) {
                run()
                true
            },
        )
        onTogglePin?.let { action(pinLabel, it) }
        onToggleArchive?.let { action(archiveLabel, it) }
        action(selectLabel, onSelect)
        action(renameLabel, onRename)
        onShare?.let { action(shareLabel, it) }
        onExport?.let { action(exportLabel, it) }
        if (!isDeleting) action(deleteLabel, onDelete)
    }
}
