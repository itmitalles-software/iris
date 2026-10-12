package io.wutz.apollo.ui.skills.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.wutz.apollo.R
import io.wutz.apollo.ui.common.StatusBadge
import io.wutz.apollo.ui.common.StatusBadgeType

@Composable
internal fun SourceBadge(
    source: String,
    modifier: Modifier = Modifier,
) {
    val (status, label) =
        when (source) {
            "hub" -> StatusBadgeType.INFO to stringResource(R.string.skills_source_badge_hub)
            "built-in" -> StatusBadgeType.NEUTRAL to stringResource(R.string.skills_source_badge_builtin)
            "optional" -> StatusBadgeType.NEUTRAL to stringResource(R.string.skills_source_badge_optional)
            else -> StatusBadgeType.NEUTRAL to source
        }
    StatusBadge(
        text = label,
        status = status,
        modifier = modifier,
    )
}
