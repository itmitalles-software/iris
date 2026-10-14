package io.wutz.apollo.ui.sessions.transfer

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.wutz.apollo.R
import io.wutz.apollo.ui.common.ToastEffect

/** Entry points the history screen wires into its menus. */
class SessionTransferActions internal constructor(
    val isBusy: Boolean,
    val share: (sessionId: String) -> Unit,
    val export: (sessionId: String, title: String?) -> Unit,
    val importFile: () -> Unit,
)

/**
 * Hosts conversation share/export/import for the history screen: the document pickers, the share
 * sheet and the result toasts. [onImported] runs after an import added sessions.
 */
@Composable
fun rememberSessionTransfer(
    onImported: () -> Unit,
    viewModel: SessionTransferViewModel = viewModel { SessionTransferViewModel() },
): SessionTransferActions {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val currentOnImported by rememberUpdatedState(onImported)
    // The session whose export is waiting for the save picker; survives the picker's recreation.
    var pendingExportId by rememberSaveable { mutableStateOf<String?>(null) }

    val labels =
        TranscriptLabels(
            untitled = stringResource(R.string.history_untitled),
            user = stringResource(R.string.session_transfer_role_user),
            assistant = stringResource(R.string.session_transfer_role_assistant),
            image = stringResource(R.string.session_transfer_image),
            truncated = stringResource(R.string.session_transfer_truncated),
        )
    val chooserTitle = stringResource(R.string.session_transfer_share_chooser)

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val sessionId = pendingExportId
            pendingExportId = null
            if (uri != null && sessionId != null) {
                viewModel.exportTo(context.applicationContext.contentResolver, sessionId, uri)
            }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importFrom(context.applicationContext.contentResolver, uri)
        }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    LaunchedEffect(state.pendingShare) {
        val transcript = state.pendingShare ?: return@LaunchedEffect
        viewModel.consumeShare()
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, transcript.title)
                putExtra(Intent.EXTRA_TEXT, transcript.text)
            }
        try {
            context.startActivity(Intent.createChooser(send, chooserTitle))
        } catch (_: ActivityNotFoundException) {
            // No share target installed: nothing to do, the chooser itself reports it.
        }
    }

    var lastImportGeneration by remember { mutableIntStateOf(state.importGeneration) }
    LaunchedEffect(state.importGeneration) {
        if (state.importGeneration != lastImportGeneration) {
            lastImportGeneration = state.importGeneration
            currentOnImported()
        }
    }

    return SessionTransferActions(
        isBusy = state.isBusy,
        share = { sessionId -> viewModel.share(sessionId, labels) },
        export = { sessionId, title ->
            pendingExportId = sessionId
            exportLauncher.launch(SessionTransferFormat.exportFileName(title, sessionId))
        },
        importFile = { importLauncher.launch(arrayOf("*/*")) },
    )
}
