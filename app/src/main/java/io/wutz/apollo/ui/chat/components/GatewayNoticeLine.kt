package io.wutz.apollo.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.theme.LocalHermesStatusColors
import io.wutz.apollo.ui.chat.GatewayNoticeLevel
import io.wutz.apollo.ui.chat.GatewayNoticeUi

/**
 * One-line status strip for the gateway's out-of-band notices (`notification.show`), shown under
 * the connection banner. The level is carried by icon and color together; TalkBack reads a new
 * notice politely.
 */
@Composable
internal fun GatewayNoticeLine(
    notice: GatewayNoticeUi?,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the last notice while the strip animates out.
    var shown by remember { mutableStateOf(notice) }
    if (notice != null) shown = notice
    AnimatedVisibility(
        visible = notice != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        val current = shown ?: return@AnimatedVisibility
        val status = LocalHermesStatusColors.current
        val (container, content) =
            when (current.level) {
                GatewayNoticeLevel.INFO -> status.infoContainer to status.info
                GatewayNoticeLevel.WARNING -> status.warningContainer to status.warning
                GatewayNoticeLevel.ERROR -> status.errorContainer to status.error
                GatewayNoticeLevel.SUCCESS -> status.successContainer to status.success
            }
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("gateway_notice"),
            color = container,
            contentColor = content,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector =
                        when (current.level) {
                            GatewayNoticeLevel.INFO -> Icons.Filled.Info
                            GatewayNoticeLevel.WARNING -> Icons.Filled.Warning
                            GatewayNoticeLevel.ERROR -> Icons.Filled.Error
                            GatewayNoticeLevel.SUCCESS -> Icons.Filled.CheckCircle
                        },
                    contentDescription = stringResource(current.level.labelRes()),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = current.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
                IconButton(
                    onClick = { onDismiss(current.key) },
                    modifier = Modifier.size(48.dp).testTag("gateway_notice_dismiss"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_notice_dismiss),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private fun GatewayNoticeLevel.labelRes(): Int =
    when (this) {
        GatewayNoticeLevel.INFO -> R.string.chat_notice_level_info
        GatewayNoticeLevel.WARNING -> R.string.chat_notice_level_warning
        GatewayNoticeLevel.ERROR -> R.string.chat_notice_level_error
        GatewayNoticeLevel.SUCCESS -> R.string.chat_notice_level_success
    }
