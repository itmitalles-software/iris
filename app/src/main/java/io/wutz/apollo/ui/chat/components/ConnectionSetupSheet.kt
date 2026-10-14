package io.wutz.apollo.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.data.model.ConnectionOperationTarget
import io.wutz.apollo.data.model.ConnectionTargetState
import io.wutz.apollo.ui.chat.ChatConnectionOperationDelegate
import io.wutz.apollo.ui.chat.ConnectionOperationUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSetupSheet(
    state: ConnectionOperationUiState,
    onRespond: (String, Map<String, String>, Boolean) -> Unit,
    onContinue: () -> Unit,
    onOpenBrowser: (operationId: String, url: String) -> Unit,
    onDismiss: () -> Unit,
    onOpenConnectors: (() -> Unit)? = null,
) {
    if (state.operation == null) return
    ModalBottomSheet(
        onDismissRequest = {
            if (state.pendingAction == null) onDismiss()
        },
        modifier = Modifier.testTag("connection_setup_sheet"),
    ) {
        ConnectionSetupContent(
            state = state,
            onRespond = onRespond,
            onContinue = onContinue,
            onOpenBrowser = onOpenBrowser,
            onOpenConnectors = onOpenConnectors,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ConnectionSetupContent(
    state: ConnectionOperationUiState,
    onRespond: (String, Map<String, String>, Boolean) -> Unit,
    onContinue: () -> Unit,
    onOpenBrowser: (operationId: String, url: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenConnectors: (() -> Unit)? = null,
) {
    val operation = state.operation ?: return
    val display = operation.display
    if (display != null && operation.targets.isEmpty()) {
        ConnectionDisplayContent(
            suggested = display.suggested,
            state = state,
            onContinue = onContinue,
            onOpenConnectors = onOpenConnectors,
            modifier = modifier,
        )
        return
    }
    val target =
        operation.targets.firstOrNull { !isTerminalTarget(it) }
            ?: operation.targets.firstOrNull { !it.discoveryError.isNullOrBlank() }
            ?: operation.targets.lastOrNull()
            ?: return
    val targetIndex = operation.targets.indexOf(target)
    val values =
        remember(operation.opId, target.name) {
            mutableStateMapOf<String, String>().apply {
                target.requiredEnv.forEach { field ->
                    this[field.name] = if (field.secret) "" else field.defaultValue.orEmpty()
                }
            }
        }
    LaunchedEffect(target.requiredEnv) {
        val currentNames = target.requiredEnv.mapTo(HashSet()) { it.name }
        values.keys
            .toList()
            .filterNot(currentNames::contains)
            .forEach(values::remove)
        target.requiredEnv.forEach { field ->
            values.putIfAbsent(field.name, if (field.secret) "" else field.defaultValue.orEmpty())
        }
    }
    val missingRequired = target.requiredEnv.firstOrNull { it.required && values[it.name].orEmpty().isBlank() }
    val pending = state.pendingAction != null
    val safeUrl = target.safeConnectUrl

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val catalog = target.catalog
        Text(
            text =
                if (catalog != null) {
                    stringResource(R.string.connection_catalog_title, catalog.display)
                } else {
                    stringResource(R.string.connection_setup_target_title, target.name)
                },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("connection_setup_title"),
        )
        Text(
            text =
                stringResource(
                    R.string.connection_setup_progress,
                    targetIndex + 1,
                    operation.targets.size,
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        target.instructions?.takeIf { it.isNotBlank() }?.let { instructions ->
            Text(instructions, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        catalog?.let { ConnectionCatalogDetails(kind = target.kind, catalog = it) }

        when {
            target.state == ConnectionTargetState.CONNECTED && !target.discoveryError.isNullOrBlank() -> {
                Text(
                    text = stringResource(R.string.connection_setup_authorized_no_tools),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(target.discoveryError, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            isTerminalTarget(target) -> {
                Text(
                    text = stringResource(R.string.connection_setup_target_resolved),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (target.tools.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.connection_setup_tools_available, target.tools.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                catalog?.skill?.let { skill ->
                    Text(
                        text = stringResource(R.string.connection_catalog_skill_ready, skill),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                target.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            target.state == ConnectionTargetState.INITIATED && safeUrl != null -> {
                target.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = safeUrl,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("connection_setup_browser_url"),
                )
                OutlinedButton(
                    onClick = { onOpenBrowser(operation.opId, safeUrl) },
                    enabled = !pending,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("connection_setup_open_browser"),
                ) {
                    Text(stringResource(R.string.connection_setup_open_browser))
                }
            }

            catalog != null && target.state == ConnectionTargetState.INITIATED -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("connection_catalog_installing"),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text =
                            target.detail?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.connection_catalog_installing),
                    )
                }
            }

            target.state == ConnectionTargetState.UNKNOWN ||
                (target.state == ConnectionTargetState.INITIATED && target.connectUrl != null) -> {
                Text(
                    text = stringResource(R.string.connection_setup_unsupported),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                target.requiredEnv.forEach { field ->
                    OutlinedTextField(
                        value = values[field.name].orEmpty(),
                        onValueChange = { values[field.name] = it },
                        enabled = !pending,
                        label = { Text(field.prompt ?: field.name) },
                        visualTransformation =
                            if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                        singleLine = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("connection_env_${field.name}"),
                    )
                }
                target.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        text = detail,
                        color =
                            if (target.state == ConnectionTargetState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
                missingRequired?.let { field ->
                    Text(
                        text = stringResource(R.string.connection_setup_required, field.prompt ?: field.name),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("connection_setup_validation"),
                    )
                }
                Button(
                    onClick = {
                        val env = target.requiredEnv.associate { field -> field.name to values[field.name].orEmpty() }
                        onRespond(target.name, env, true)
                    },
                    enabled = !pending && missingRequired == null && target.state != ConnectionTargetState.UNKNOWN,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("connection_setup_connect"),
                ) {
                    Text(
                        stringResource(
                            when {
                                catalog == null -> R.string.connection_setup_connect
                                target.state == ConnectionTargetState.FAILED -> R.string.connection_catalog_retry
                                else -> R.string.connection_catalog_install
                            },
                        ),
                    )
                }
            }
        }

        ConnectionOperationStatus(state)
        ConnectionSetupActions(
            target = target,
            pending = pending,
            onSkip = { onRespond(target.name, emptyMap(), false) },
            onContinue = onContinue,
        )
    }
}

/** Request error and pending spinner shared by the target and display cards. */
@Composable
private fun ConnectionOperationStatus(state: ConnectionOperationUiState) {
    val pending = state.pendingAction != null
    state.error?.let { error ->
        Text(
            text =
                stringResource(
                    when (error.message) {
                        ChatConnectionOperationDelegate.NOT_OWNER -> {
                            R.string.session_integrations_err_not_owner
                        }

                        ChatConnectionOperationDelegate.UNSUPPORTED_RUNTIME -> {
                            R.string.session_integrations_err_unsupported_runtime
                        }

                        else -> {
                            R.string.connection_setup_request_failed
                        }
                    },
                ),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("connection_setup_error"),
        )
    }
    if (pending) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.testTag("connection_setup_pending"),
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.connection_setup_pending))
        }
    }
}

/**
 * Hermes v0.22.0 display card (`manage_connections` action `display`): no targets, only suggested
 * connector slugs. The agent waits until Continue, so the card offers the session integrations
 * sheet for connecting and Continue to let the turn go on.
 */
@Composable
private fun ConnectionDisplayContent(
    suggested: List<String>,
    state: ConnectionOperationUiState,
    onContinue: () -> Unit,
    onOpenConnectors: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val pending = state.pendingAction != null
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("connection_display_card"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.connection_display_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("connection_setup_title"),
        )
        Text(
            text =
                stringResource(
                    if (suggested.isEmpty()) {
                        R.string.connection_display_body_no_suggestions
                    } else {
                        R.string.connection_display_body
                    },
                ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (suggested.isNotEmpty()) {
            Text(
                text = suggested.joinToString(separator = ", "),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("connection_display_suggested"),
            )
        }
        ConnectionOperationStatus(state)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onOpenConnectors != null) {
                OutlinedButton(
                    onClick = onOpenConnectors,
                    enabled = !pending,
                    modifier =
                        Modifier
                            .weight(1f)
                            .testTag("connection_display_open_integrations"),
                ) {
                    Text(stringResource(R.string.connection_display_open_integrations))
                }
            }
            Button(
                onClick = onContinue,
                enabled = !pending,
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag("connection_setup_continue"),
            ) {
                Text(stringResource(R.string.connection_setup_continue))
            }
        }
    }
}

@Composable
private fun ConnectionSetupActions(
    target: ConnectionOperationTarget,
    pending: Boolean,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!isTerminalTarget(target)) {
            OutlinedButton(
                onClick = onSkip,
                enabled = !pending,
                modifier =
                    Modifier
                        .weight(1f)
                        .testTag("connection_setup_skip"),
            ) {
                Text(stringResource(R.string.connection_setup_skip))
            }
        }
        OutlinedButton(
            onClick = onContinue,
            enabled = !pending,
            modifier =
                Modifier
                    .weight(1f)
                    .testTag("connection_setup_continue"),
        ) {
            Text(stringResource(R.string.connection_setup_continue))
        }
    }
}

private fun isTerminalTarget(target: ConnectionOperationTarget): Boolean =
    target.state == ConnectionTargetState.CONNECTED ||
        target.state == ConnectionTargetState.SKIPPED ||
        target.state == ConnectionTargetState.EXPIRED ||
        target.state == ConnectionTargetState.UNAVAILABLE
