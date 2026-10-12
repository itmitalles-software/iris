package io.wutz.apollo.ui.system.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.theme.Spacing
import io.wutz.apollo.ui.common.InfoRow
import io.wutz.apollo.ui.common.SectionHeader
import io.wutz.apollo.ui.system.SystemUiState
import io.wutz.apollo.util.formatBytes

fun LazyListScope.checkpointsSection(
    state: SystemUiState,
    spacing: Spacing,
    onPruneConfirm: (Boolean) -> Unit,
) {
    state.checkpoints?.let { checkpoints ->
        item {
            SectionHeader(title = stringResource(R.string.system_sec_checkpoints))
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
            ) {
                Column(modifier = Modifier.padding(spacing.md)) {
                    val sessionsCount = checkpoints.sessions?.size ?: 0
                    val totalBytes = checkpoints.total_bytes ?: 0L
                    InfoRow(
                        label = "",
                        value =
                            stringResource(
                                R.string.system_checkpoints_summary,
                                sessionsCount,
                                formatBytes(totalBytes),
                            ),
                    )

                    Spacer(modifier = Modifier.height(spacing.sm))
                    Button(
                        onClick = { onPruneConfirm(true) },
                        enabled = sessionsCount > 0,
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(spacing.xs))
                        Text(stringResource(R.string.system_checkpoints_prune), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
