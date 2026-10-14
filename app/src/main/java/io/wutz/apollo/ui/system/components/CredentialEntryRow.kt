package io.wutz.apollo.ui.system.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.data.model.CredentialPoolEntry
import io.wutz.apollo.ui.common.StatusBadge
import io.wutz.apollo.ui.common.StatusBadgeType

@Composable
internal fun CredentialEntryRow(
    entry: CredentialPoolEntry,
    providerName: String,
    onRemove: (() -> Unit)?,
    spacing: io.wutz.apollo.theme.Spacing,
    removeEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label ?: "(${stringResource(R.string.system_credentials_api_key)} ${entry.index ?: 0})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                entry.token_preview?.let { preview ->
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                entry.last_status?.let { status ->
                    StatusBadge(
                        text = status,
                        status =
                            when (status.lowercase()) {
                                "ok", "valid", "active" -> StatusBadgeType.SUCCESS
                                "error", "invalid", "expired" -> StatusBadgeType.ERROR
                                else -> StatusBadgeType.NEUTRAL
                            },
                    )
                }
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, enabled = removeEnabled) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint =
                        if (removeEnabled) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
