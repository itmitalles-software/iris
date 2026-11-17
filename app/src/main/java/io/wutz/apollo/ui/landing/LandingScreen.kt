package io.wutz.apollo.ui.landing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.ui.common.IrisArt
import io.wutz.apollo.ui.common.IrisBrandLockup
import io.wutz.apollo.ui.common.IrisIllustration
import io.wutz.apollo.ui.common.irisArtEnabled

@Composable
fun LandingScreen(
    onAuthLogin: () -> Unit,
    modifier: Modifier = Modifier,
    onConnections: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (irisArtEnabled()) {
                IrisIllustration(
                    art = IrisArt.LANDING,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                )
                IrisBrandLockup(emblemSize = 72.dp, wordmarkSize = 34f)
            } else {
                IrisBrandLockup()
            }

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedButton(onClick = onConnections, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.screen_connections))
            }
            Button(
                onClick = onAuthLogin,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(
                    text = stringResource(R.string.landing_action_auth_login),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            // First-time users need a running Hermes server; the README explains the setup.
            TextButton(onClick = { runCatching { uriHandler.openUri(SERVER_SETUP_URL) } }) {
                Text(stringResource(R.string.landing_setup_help))
            }
        }
    }
}

/** README section that walks through installing Hermes and reaching it over Tailscale. */
internal const val SERVER_SETUP_URL =
    "https://github.com/itmitalles-software/iris#connect-over-tailscale-recommended"
