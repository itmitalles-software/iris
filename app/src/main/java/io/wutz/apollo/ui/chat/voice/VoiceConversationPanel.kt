package io.wutz.apollo.ui.chat.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.ui.common.UiText

/** Localized text for a voice-conversation notice. Server details stay raw arguments. */
fun VoiceNotice.toUiText(): UiText =
    when (this) {
        VoiceNotice.SttDisabled -> {
            UiText.res(R.string.voice_conversation_err_stt_disabled)
        }

        VoiceNotice.MicrophoneUnavailable -> {
            UiText.res(R.string.voice_conversation_err_microphone)
        }

        VoiceNotice.NothingHeard -> {
            UiText.res(R.string.voice_conversation_err_nothing_heard)
        }

        VoiceNotice.NotConnected -> {
            UiText.res(R.string.voice_conversation_err_not_connected)
        }

        VoiceNotice.ConnectionLost -> {
            UiText.res(R.string.voice_conversation_err_connection_lost)
        }

        VoiceNotice.SpeechOutputFailed -> {
            UiText.res(R.string.voice_conversation_err_speech)
        }

        VoiceNotice.TranscriptUnsent -> {
            UiText.res(R.string.voice_conversation_err_unsent)
        }

        is VoiceNotice.TranscriptionFailed -> {
            detail?.takeIf { it.isNotBlank() }?.let { UiText.res(R.string.voice_conversation_err_transcription, it) }
                ?: UiText.res(R.string.voice_conversation_err_transcription_unknown)
        }
    }

/**
 * Replaces the composer while a voice conversation runs: what the loop is doing, the microphone
 * level, the live transcript, and explicit actions to send now, interrupt the reply, or end.
 */
@Composable
fun VoiceConversationPanel(
    state: VoiceConversationUiState,
    onTap: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("voice_conversation_panel"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = phaseIcon(state.phase),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(statusText(state)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier =
                        Modifier
                            .weight(1f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.phase == VoicePhase.LISTENING || state.phase == VoicePhase.SPEAKING) {
                    val levelDescription = stringResource(R.string.voice_conversation_level_desc)
                    LinearProgressIndicator(
                        progress = { state.level },
                        modifier =
                            Modifier
                                .width(56.dp)
                                .semantics { contentDescription = levelDescription },
                    )
                }
            }
            if (state.partialTranscript.isNotBlank()) {
                Text(
                    text = state.partialTranscript,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                primaryAction(state)?.let { label ->
                    FilledTonalButton(onClick = onTap, modifier = Modifier.testTag("voice_conversation_action")) {
                        Text(stringResource(label))
                    }
                }
                OutlinedButton(onClick = onEnd, modifier = Modifier.testTag("voice_conversation_end")) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.voice_conversation_end))
                }
            }
        }
    }
}

private fun phaseIcon(phase: VoicePhase): ImageVector =
    when (phase) {
        VoicePhase.OFF, VoicePhase.STARTING, VoicePhase.LISTENING -> Icons.Filled.Mic
        VoicePhase.TRANSCRIBING -> Icons.Filled.GraphicEq
        VoicePhase.THINKING -> Icons.Filled.HourglassTop
        VoicePhase.SPEAKING -> Icons.AutoMirrored.Filled.VolumeUp
    }

private fun statusText(state: VoiceConversationUiState): Int =
    when (state.phase) {
        VoicePhase.OFF, VoicePhase.STARTING -> {
            R.string.voice_conversation_status_starting
        }

        VoicePhase.LISTENING -> {
            if (state.heardSpeech) {
                R.string.voice_conversation_status_hearing
            } else {
                R.string.voice_conversation_status_listening
            }
        }

        VoicePhase.TRANSCRIBING -> {
            R.string.voice_conversation_status_transcribing
        }

        VoicePhase.THINKING -> {
            R.string.voice_conversation_status_thinking
        }

        VoicePhase.SPEAKING -> {
            R.string.voice_conversation_status_speaking
        }
    }

/** The panel's main action for this phase, or null when there is nothing to do but wait. */
internal fun primaryAction(state: VoiceConversationUiState): Int? =
    when (state.phase) {
        VoicePhase.LISTENING -> if (state.heardSpeech) R.string.voice_conversation_action_send else null
        VoicePhase.THINKING, VoicePhase.SPEAKING -> R.string.voice_conversation_action_interrupt
        else -> null
    }
