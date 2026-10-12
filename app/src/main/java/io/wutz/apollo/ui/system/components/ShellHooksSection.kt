package io.wutz.apollo.ui.system.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.theme.Spacing
import io.wutz.apollo.ui.common.SectionHeader
import io.wutz.apollo.ui.system.SystemUiState
import io.wutz.apollo.ui.system.SystemViewModel

fun LazyListScope.shellHooksSection(
    state: SystemUiState,
    spacing: Spacing,
    viewModel: SystemViewModel,
) {
    val hooks = state.hooks?.hooks ?: emptyList()

    item {
        SectionHeader(
            title = stringResource(R.string.system_sec_hooks),
            trailing = {
                FilledTonalButton(onClick = { viewModel.toggleHookModal() }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(spacing.xs))
                    Text(stringResource(R.string.system_hooks_new), maxLines = 1, softWrap = false)
                }
            },
        )
    }

    if (hooks.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.system_hooks_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            )
        }
    }

    items(hooks, key = { "${it.event}:${it.command}" }) { hook ->
        HookCard(hook = hook, spacing = spacing, onDelete = {
            viewModel.deleteHook(hook.event ?: "", hook.command ?: "")
        })
    }
}
