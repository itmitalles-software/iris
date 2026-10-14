package io.wutz.apollo.ui.settings

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.wutz.apollo.R
import io.wutz.apollo.theme.LocalSpacing
import io.wutz.apollo.ui.common.HermesScaffold
import io.wutz.apollo.ui.common.NavIcon
import io.wutz.apollo.ui.common.listContentPadding
import io.wutz.apollo.ui.common.listItemSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One bundled license text (res/raw) with its title and a one-line summary. */
internal data class LicenseEntry(
    val key: String,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:RawRes val text: Int,
)

/** The texts Apollo ships with: its own license and notice, the bundled font and the libraries. */
internal val OPEN_SOURCE_LICENSES =
    listOf(
        LicenseEntry(
            "apollo",
            R.string.licenses_apollo_title,
            R.string.licenses_apollo_summary,
            R.raw.license_apache_2_0,
        ),
        LicenseEntry("notice", R.string.licenses_notice_title, R.string.licenses_notice_summary, R.raw.notice),
        LicenseEntry(
            "cinzel",
            R.string.licenses_cinzel_title,
            R.string.licenses_cinzel_summary,
            R.raw.license_cinzel_ofl,
        ),
        LicenseEntry(
            "libraries",
            R.string.licenses_libraries_title,
            R.string.licenses_libraries_summary,
            R.raw.third_party_licenses,
        ),
    )

/**
 * Settings → About → Open-source licenses: the license texts bundled as static resources, each
 * expandable in place. No library and no network access.
 */
@Composable
internal fun OpenSourceLicensesPage(onBack: () -> Unit) {
    HermesScaffold(
        title = { Text(stringResource(R.string.licenses_title)) },
        navigationIcon = NavIcon.Back(onBack),
        drawerGesturesEnabled = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("licenses_list"),
            contentPadding = listContentPadding,
            verticalArrangement = listItemSpacing,
        ) {
            items(OPEN_SOURCE_LICENSES, key = { it.key }) { entry ->
                LicenseCard(entry)
            }
        }
    }
}

@Composable
private fun LicenseCard(entry: LicenseEntry) {
    val spacing = LocalSpacing.current
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }
    val toggleLabel = stringResource(if (expanded) R.string.licenses_collapse else R.string.licenses_expand)
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = toggleLabel, role = Role.Button) { expanded = !expanded }
                    .padding(spacing.md)
                    .testTag("license_${entry.key}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(entry.title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(entry.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            val text = rawText(entry.text)
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(start = spacing.md, end = spacing.md, bottom = spacing.md),
                )
            }
        }
    }
}

@Composable
private fun rawText(
    @RawRes id: Int,
): String {
    val resources = LocalResources.current
    val text by produceState(initialValue = "", id) {
        value =
            withContext(Dispatchers.IO) {
                resources.openRawResource(id).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
    }
    return text
}
