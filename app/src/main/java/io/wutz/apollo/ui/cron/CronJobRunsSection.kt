package io.wutz.apollo.ui.cron

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.wutz.apollo.R
import io.wutz.apollo.data.model.CronJobRun
import io.wutz.apollo.theme.LocalHermesStatusColors
import io.wutz.apollo.theme.LocalSpacing
import io.wutz.apollo.ui.common.StatusBadge
import io.wutz.apollo.ui.common.StatusBadgeType
import io.wutz.apollo.ui.common.asString
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Runs" list of the cron job detail: agent runs open their session in the chat, script-only runs
 * expand their output preview in place.
 */
@Composable
internal fun CronJobRunsSection(
    jobId: String,
    onOpenSession: (String) -> Unit,
    viewModel: CronJobRunsViewModel = viewModel { CronJobRunsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = LocalSpacing.current

    LaunchedEffect(jobId) { viewModel.load(jobId) }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("cron_runs_section"),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = stringResource(R.string.cron_runs_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = spacing.sm),
        )
        val runs = if (state.jobId == jobId) state.runs else emptyList()
        when {
            state.isLoading && runs.isEmpty() -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.error != null && runs.isEmpty() -> {
                Text(
                    text = state.error?.asString().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalHermesStatusColors.current.error,
                )
                TextButton(onClick = { viewModel.load(jobId) }) {
                    Text(stringResource(R.string.action_retry))
                }
            }

            runs.isEmpty() -> {
                Text(
                    text = stringResource(R.string.cron_runs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                runs.forEach { run ->
                    CronJobRunRow(run = run, onOpenSession = onOpenSession)
                }
            }
        }
    }
}

@Composable
private fun CronJobRunRow(
    run: CronJobRun,
    onOpenSession: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    val clickLabel =
        stringResource(
            if (run.hasSession) R.string.cron_runs_open_session else R.string.cron_runs_toggle_output,
        )
    val canExpand = !run.hasSession && !run.preview.isNullOrBlank()
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    enabled = run.hasSession || canExpand,
                    onClickLabel = clickLabel,
                    role = Role.Button,
                ) {
                    if (run.hasSession) onOpenSession(run.id) else expanded = !expanded
                }.padding(vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = run.title?.takeIf { it.isNotBlank() } ?: stringResource(defaultTitle(run)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (run.isActive == true) {
                StatusBadge(text = stringResource(R.string.cron_runs_running), status = StatusBadgeType.INFO)
            }
            if (run.hasSession) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = runMetaLine(run),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        run.preview?.takeIf { it.isNotBlank() && it != run.title }?.let { preview ->
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun defaultTitle(run: CronJobRun): Int =
    if (run.hasSession) R.string.cron_runs_agent_run else R.string.cron_runs_script_run

@Composable
private fun runMetaLine(run: CronJobRun): String {
    val locale = LocalConfiguration.current.locales[0]
    val time =
        run.startedAt?.takeIf { it > 0.0 }?.let { seconds ->
            DateTimeFormatter
                .ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMdjmm"), locale)
                .format(Instant.ofEpochMilli((seconds * 1000).toLong()).atZone(ZoneId.systemDefault()))
        }
    val tokens = (run.inputTokens ?: 0L) + (run.outputTokens ?: 0L)
    val tokenText =
        if (run.hasSession && tokens > 0) {
            val quantity = tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            pluralStringResource(R.plurals.cron_runs_tokens, quantity, tokens)
        } else {
            null
        }
    val kind = stringResource(if (run.hasSession) R.string.cron_runs_kind_agent else R.string.cron_runs_kind_script)
    return listOfNotNull(time, kind, tokenText).joinToString(" · ")
}
