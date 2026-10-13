package io.wutz.apollo.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.wutz.apollo.R
import io.wutz.apollo.data.remote.ServerEndpoint

/** Display text for [ServerEndpoint.securityWarning]; the data layer keeps the English constant. */
@Composable
internal fun transportWarningText(warning: String): String =
    if (warning == ServerEndpoint.CLEARTEXT_WARNING) stringResource(R.string.server_cleartext_warning) else warning
