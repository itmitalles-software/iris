package io.wutz.apollo.ui.skills.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.data.model.SkillHubSource
import io.wutz.apollo.theme.LocalHermesStatusColors

@Composable
fun SourceChip(source: SkillHubSource) {
    val statusColors = LocalHermesStatusColors.current
    val label =
        buildString {
            append(source.label ?: source.id)
            when {
                source.rateLimited == true -> {
                    append(" · ")
                    append(stringResource(R.string.skills_hub_source_rate_limited))
                }

                source.available == false -> {
                    append(" · ")
                    append(stringResource(R.string.skills_hub_source_offline))
                }

                source.searchable == false -> {
                    append(" · ")
                    append(stringResource(R.string.skills_hub_source_via_index))
                }
            }
        }
    val color =
        when {
            source.rateLimited == true -> statusColors.warning
            source.available == false -> statusColors.error
            else -> MaterialTheme.colorScheme.primary
        }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
