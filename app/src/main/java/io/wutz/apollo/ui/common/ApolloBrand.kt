package io.wutz.apollo.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.wutz.apollo.R
import io.wutz.apollo.theme.ApolloWordmarkStyle

/**
 * Apollo's brand lockup: emblem, APOLLO wordmark and "for hermes agent"
 * (docs/BRANDING.md). Used on the landing screen and in About; the launcher
 * only shows "Apollo".
 */
@Composable
fun ApolloBrandLockup(
    modifier: Modifier = Modifier,
    emblemSize: Dp = 120.dp,
    wordmarkSize: Float = 40f,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_apollo_emblem),
            contentDescription = null,
            modifier = Modifier.size(emblemSize),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.brand_wordmark),
            style = ApolloWordmarkStyle.copy(fontSize = wordmarkSize.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.brand_lockup_subtitle),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.1.em),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
