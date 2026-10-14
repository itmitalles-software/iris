package io.wutz.apollo.ui.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.wutz.apollo.BuildConfig
import io.wutz.apollo.NavigationController
import io.wutz.apollo.OpenSourceLicensesScreen
import io.wutz.apollo.R
import io.wutz.apollo.data.update.AppUpdateState
import io.wutz.apollo.data.update.UpdateNoticeManager
import io.wutz.apollo.ui.common.ApolloArt
import io.wutz.apollo.ui.common.ApolloBrandLockup
import io.wutz.apollo.ui.common.ApolloIllustration
import io.wutz.apollo.ui.common.apolloArtEnabled
import io.wutz.apollo.ui.common.asString
import io.wutz.apollo.ui.settings.InfoRow
import io.wutz.apollo.ui.settings.SectionCard

@Composable
internal fun AboutSection(
    updateState: AppUpdateState = AppUpdateState.Idle,
    checkReleaseCandidateUpdates: Boolean = false,
    onCheckUpdate: () -> Unit = {},
    onStartUpdate: () -> Unit = {},
    onCheckReleaseCandidateUpdatesChange: (Boolean) -> Unit = {},
    onOpenInstallSettings: () -> Unit = {},
    selfUpdateAvailable: Boolean = UpdateNoticeManager.selfUpdateAvailable,
) {
    val uriHandler = LocalUriHandler.current
    val websiteUrl = stringResource(R.string.brand_website)
    val openLinkLabel = stringResource(R.string.a11y_open_link)
    SectionCard {
        if (apolloArtEnabled()) {
            ApolloIllustration(
                art = ApolloArt.ABOUT_BANNER,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium),
            )
        }
        ApolloBrandLockup(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            emblemSize = 88.dp,
            wordmarkSize = 30f,
        )

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(8.dp))

        InfoRow(
            label = stringResource(R.string.settings_about_developer),
            value = websiteUrl,
            modifier = Modifier.linkTarget(openLinkLabel) { uriHandler.openUri(websiteUrl) },
        )
        InfoRow(
            label = stringResource(R.string.settings_about_version),
            value = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        // The Play build has no self-updater (issue #7): Play delivers updates.
        if (selfUpdateAvailable) {
            UpdateRow(
                state = updateState,
                onCheckUpdate = onCheckUpdate,
                onStartUpdate = onStartUpdate,
                onOpenInstallSettings = onOpenInstallSettings,
            )
            ReleaseCandidateRow(
                checked = checkReleaseCandidateUpdates,
                // Mid-download / mid-install the channel must not flip under the
                // in-flight APK.
                enabled =
                    updateState !is AppUpdateState.Downloading &&
                        updateState !is AppUpdateState.Installing,
                onCheckedChange = onCheckReleaseCandidateUpdatesChange,
            )
        }
        InfoRow(
            label = stringResource(R.string.settings_about_build),
            value =
                if (BuildConfig.DEBUG) {
                    stringResource(R.string.settings_about_debug)
                } else {
                    stringResource(R.string.settings_about_release)
                },
        )
        if (BuildConfig.GIT_SHA.isNotBlank()) {
            InfoRow(
                label = stringResource(R.string.settings_about_commit),
                value = BuildConfig.GIT_SHA,
            )
        }

        InfoRow(
            label = stringResource(R.string.licenses_title),
            value = stringResource(R.string.licenses_about_value),
            modifier =
                Modifier
                    .linkTarget(stringResource(R.string.licenses_open)) {
                        NavigationController.navigateTo(OpenSourceLicensesScreen)
                    }.testTag("about_open_source_licenses"),
        )

        // The 48dp link targets supply the vertical spacing the fixed spacers used to provide.
        Text(
            text = REPOSITORY_URL,
            modifier = Modifier.linkTarget(openLinkLabel) { uriHandler.openUri(REPOSITORY_URL) },
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.primary,
                ),
        )
        Text(
            text = stringResource(R.string.settings_about_upstream_credit),
            modifier = Modifier.linkTarget(openLinkLabel) { uriHandler.openUri(UPSTREAM_URL) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_about_unofficial),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpdateRow(
    state: AppUpdateState,
    onCheckUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    // GitHub release tags carry a leading "v" (e.g. "v1.21.1"); the string
    // templates below already prepend one, so strip it here to avoid "vv".
    val tag =
        (state as? AppUpdateState.UpToDate)?.latestTag
            ?: (state as? AppUpdateState.UpdateAvailable)?.latestTag
            ?: (state as? AppUpdateState.Installing)?.latestTag
    val displayTag = tag?.trimStart('v').orEmpty()
    val tappable =
        state is AppUpdateState.Idle ||
            state is AppUpdateState.UpToDate ||
            state is AppUpdateState.Error
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(enabled = tappable) { onCheckUpdate() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_about_update),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
            Spacer(modifier = Modifier.weight(1f))
            when (state) {
                is AppUpdateState.Idle -> {
                    ValueText(stringResource(R.string.settings_about_update_check))
                }

                is AppUpdateState.Checking -> {
                    ValueText(stringResource(R.string.settings_about_update_checking))
                }

                is AppUpdateState.UpToDate -> {
                    ValueText(
                        stringResource(R.string.settings_about_update_uptodate, displayTag),
                    )
                }

                is AppUpdateState.UpdateAvailable -> {
                    ValueText(
                        stringResource(R.string.settings_about_update_available, displayTag),
                    )
                }

                is AppUpdateState.Downloading -> {
                    ValueText(
                        stringResource(
                            R.string.settings_about_update_downloading,
                            (state.progress * 100).toInt(),
                        ),
                    )
                }

                is AppUpdateState.Installing -> {
                    ValueText(
                        stringResource(R.string.settings_about_update_installing, displayTag),
                    )
                }

                is AppUpdateState.NeedsUnknownSourcesPermission -> {
                    ValueText(stringResource(R.string.settings_about_update_allow_sources))
                }

                is AppUpdateState.Error -> {
                    ValueText(state.message.asString())
                }
            }
        }

        // Action area: the button/progress gets its own full-width line so a
        // long status text or error message never fights it for space.
        when (state) {
            is AppUpdateState.UpdateAvailable -> {
                Button(
                    onClick = onStartUpdate,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_about_update_action))
                }
            }

            is AppUpdateState.Downloading -> {
                if (state.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            is AppUpdateState.NeedsUnknownSourcesPermission -> {
                OutlinedButton(
                    onClick = onOpenInstallSettings,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_about_update_open_settings))
                }
            }

            is AppUpdateState.Error -> {
                OutlinedButton(
                    onClick = onCheckUpdate,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.settings_about_update_retry))
                }
            }

            else -> {}
        }
    }
}

@Composable
private fun ValueText(text: String) {
    Text(
        text = text,
        style =
            MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
    )
}

/**
 * Opt-in switch for pre-release (release candidate) updates. Stable-only is
 * the default; enabling this makes the update check consider RC tags too.
 */
@Composable
private fun ReleaseCandidateRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_about_rc_updates_title),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.settings_about_rc_updates_desc),
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
    }
}

private const val REPOSITORY_URL = "https://github.com/wutz-io/apollo-hermes-android"
private const val UPSTREAM_URL = "https://github.com/Hy4ri/hermes-mobile"

/** A tappable link line with a 48dp minimum height and a TalkBack action label. */
private fun Modifier.linkTarget(
    label: String,
    onOpen: () -> Unit,
): Modifier =
    fillMaxWidth()
        .heightIn(min = 48.dp)
        .clickable(onClickLabel = label, role = Role.Button, onClick = onOpen)
        .wrapContentHeight(Alignment.CenterVertically)
