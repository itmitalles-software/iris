package io.wutz.apollo.ui.chat

import android.content.ClipData
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.data.model.Attachment
import io.wutz.apollo.theme.DarkOnSurface
import io.wutz.apollo.theme.LightOnSurface
import io.wutz.apollo.theme.LocalHermesStatusColors
import io.wutz.apollo.theme.LocalThemePreset
import io.wutz.apollo.theme.ThemePreset
import io.wutz.apollo.ui.chat.components.rememberCopyFeedback
import io.wutz.apollo.ui.chat.tool.ToolJson
import io.wutz.apollo.ui.chat.tool.rememberToolText
import io.wutz.apollo.util.BidiUtils
import kotlinx.coroutines.launch

private val UserBubbleShape =
    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)

/**
 * The user-message bubble — the universal anchor in the full-bleed chat
 * renderer (issue #866). Agent prose renders full-bleed; user messages keep
 * this bubble so the conversation stays scannable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserBubble(
    message: ChatMessage,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    onOpenAttachment: (Attachment) -> Unit = {},
    onSaveAttachment: (Attachment) -> Unit = {},
    savingAttachmentPath: String? = null,
    openingAttachmentPath: String? = null,
    canSaveAttachment: Boolean = true,
    onImageClick: (ImageViewerModel) -> Unit = {},
    messageStatsEnabled: Boolean = false,
    showUserMessageTokens: Boolean = true,
    modifier: Modifier = Modifier,
    pendingSendState: PendingSendState? = null,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.80f

    AnimatedVisibility(
        visible = true,
        enter =
            fadeIn() +
                expandVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ),
    ) {
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        // Copy feedback: briefly show ✓ then revert
        var copied by rememberCopyFeedback()

        val statusColors = LocalHermesStatusColors.current
        val deliveryState =
            when {
                pendingSendState == PendingSendState.SENDING -> DeliveryState.SENT

                pendingSendState == PendingSendState.ACCEPTED ||
                    message.canonicalRestId != null || message.serverRowId != null -> DeliveryState.DELIVERED

                else -> null
            }

        // #1432: also protect cached/legacy rows and unexpectedly large plain-string payloads.
        // Keep the original content for Copy; only the layout input is bounded.
        val toolText = rememberToolText()
        val displayContent =
            remember(message.content, message.attachments, toolText) {
                // #1432: the path is only hidden when its image renders as an attachment below.
                val visible =
                    if (message.attachments.isNullOrEmpty()) message.content else hideImageRefLines(message.content)
                ToolJson.clampForDisplay(visible, toolText)
            }
        val highlightedText =
            remember(displayContent, searchQuery, isCurrentMatch, statusColors) {
                if (searchQuery.isNotBlank()) {
                    buildHighlightedString(
                        displayContent,
                        searchQuery,
                        isCurrentMatch,
                        statusColors,
                    )
                } else {
                    AnnotatedString(displayContent)
                }
            }
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // Apollo: a calm bronze bubble with a gold hairline instead of a solid gold slab.
            val apolloBubble = LocalThemePreset.current == ThemePreset.APOLLO
            val primary =
                if (apolloBubble) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary
            val userBubbleTextColor =
                if (apolloBubble) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else if (primary.luminance() > 0.5f) {
                    if (MaterialTheme.colorScheme.onPrimary.luminance() < 0.5f) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        LightOnSurface
                    }
                } else {
                    if (MaterialTheme.colorScheme.onPrimary.luminance() > 0.5f) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        DarkOnSurface
                    }
                }
            Box {
                Surface(
                    modifier =
                        Modifier
                            .widthIn(max = maxBubbleWidth)
                            .clip(UserBubbleShape)
                            .background(color = primary)
                            .then(
                                if (apolloBubble) {
                                    Modifier.border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                        shape = UserBubbleShape,
                                    )
                                } else {
                                    Modifier
                                },
                            ).testTag("chat_bubble_user"),
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                ) {
                    val isRtl = remember(displayContent) { BidiUtils.isRtlText(displayContent) }
                    val bubbleDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides bubbleDirection) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            SelectionContainer {
                                Text(
                                    text = highlightedText,
                                    color = userBubbleTextColor,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            textDirection =
                                                if (isRtl) {
                                                    TextDirection.Rtl
                                                } else {
                                                    TextDirection.Ltr
                                                },
                                        ),
                                )
                            }
                            // Render inline attachments
                            InlineAttachmentList(
                                attachments = message.attachments,
                                diagnosticId = ChatImageDiagnostics.rowKey(message.id),
                                frameKeyPrefix = message.id,
                                textColor = userBubbleTextColor,
                                onOpen = onOpenAttachment,
                                onSave = onSaveAttachment,
                                savingPath = savingAttachmentPath,
                                openingPath = openingAttachmentPath,
                                canSave = canSaveAttachment,
                                onImageClick = onImageClick,
                            )
                            if (!message.isStreaming) {
                                FlowRow(
                                    modifier =
                                        Modifier
                                            .align(Alignment.End)
                                            .padding(top = 4.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                clipboard.setClipEntry(
                                                    ClipEntry(ClipData.newPlainText(null, message.content)),
                                                )
                                            }
                                            copied = true
                                        },
                                        modifier = Modifier.size(20.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                            contentDescription =
                                                stringResource(
                                                    if (copied) {
                                                        R.string.content_desc_copied
                                                    } else {
                                                        R.string.content_desc_copy
                                                    },
                                                ),
                                            modifier = Modifier.size(12.dp),
                                            tint = userBubbleTextColor.copy(alpha = 0.7f),
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text =
                                            formatTimestamp(
                                                message.timestamp,
                                                DateFormat.is24HourFormat(LocalContext.current),
                                            ),
                                        color = userBubbleTextColor.copy(alpha = 0.6f),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    DeliveryChecks(
                                        state = deliveryState,
                                        tint = userBubbleTextColor,
                                        messageId = message.id,
                                    )
                                    if (messageStatsEnabled && showUserMessageTokens &&
                                        message.tokenCount != null && message.tokenCount > 0
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "•",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = userBubbleTextColor.copy(alpha = 0.4f),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text =
                                                    stringResource(
                                                        R.string.chat_msg_tokens,
                                                        TokenEstimator.formatTokenCount(message.tokenCount),
                                                    ),
                                                color = userBubbleTextColor.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.testTag("bubble_token_count"),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** WhatsApp-style delivery ticks: one dim check while sending, two once the server has the prompt. */
private enum class DeliveryState { SENT, DELIVERED }

@Composable
private fun DeliveryChecks(
    state: DeliveryState?,
    tint: Color,
    messageId: String,
) {
    if (state == null) return
    val delivered = state == DeliveryState.DELIVERED
    Spacer(modifier = Modifier.width(4.dp))
    Icon(
        imageVector = if (delivered) Icons.Filled.DoneAll else Icons.Filled.Done,
        contentDescription =
            stringResource(if (delivered) R.string.chat_send_accepted else R.string.chat_pending_sending),
        modifier = Modifier.size(14.dp).testTag("user_send_status_$messageId"),
        tint = tint.copy(alpha = if (delivered) 1f else 0.6f),
    )
}
