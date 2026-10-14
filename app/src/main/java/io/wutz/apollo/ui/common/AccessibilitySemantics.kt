package io.wutz.apollo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.wutz.apollo.R

/** Localized TalkBack state ("Expanded"/"Collapsed") and action label ("Collapse"/"Expand"). */
@Immutable
class ExpandToggleLabels(
    val state: String,
    val action: String,
)

@Composable
fun expandToggleLabels(expanded: Boolean): ExpandToggleLabels =
    ExpandToggleLabels(
        state = stringResource(if (expanded) R.string.a11y_state_expanded else R.string.a11y_state_collapsed),
        action = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
    )

/**
 * Exposes expand/collapse state and action label on a tap-to-expand control. Chain it next to the
 * control's own click handler: `action = null` keeps that handler and only adds the label, because
 * Compose merges a labelled action into an existing one on the same node.
 */
fun Modifier.expandToggleSemantics(labels: ExpandToggleLabels): Modifier =
    semantics {
        stateDescription = labels.state
        onClick(label = labels.action, action = null)
    }
