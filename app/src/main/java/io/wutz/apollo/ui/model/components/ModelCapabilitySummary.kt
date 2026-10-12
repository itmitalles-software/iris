package io.wutz.apollo.ui.model.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.data.model.ModelCapabilities
import io.wutz.apollo.data.model.reasoningSupport

@Composable
fun ModelCapabilitySummary(capabilities: ModelCapabilities?) {
    if (capabilities == null) return
    val labels =
        buildList {
            capabilities.supports_tools?.let {
                add(stringResource(if (it) R.string.model_cap_tools else R.string.model_cap_no_tools))
            }
            capabilities.supports_vision?.let {
                add(stringResource(if (it) R.string.model_cap_vision else R.string.model_cap_no_vision))
            }
            capabilities.reasoningSupport?.let {
                add(stringResource(if (it) R.string.model_cap_reasoning else R.string.model_cap_no_reasoning))
            }
            if (capabilities.reasoningSupport == true && capabilities.can_disable_reasoning == false) {
                add(stringResource(R.string.chat_reasoning_always_on))
            }
            capabilities.context_window?.let { add(stringResource(R.string.model_cap_context_k, it / 1000)) }
            capabilities.max_output_tokens?.let { add(stringResource(R.string.model_cap_output_k, it / 1000)) }
            capabilities.model_family?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    if (labels.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = labels.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
