package io.wutz.apollo.ui.chat.tool

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import io.wutz.apollo.ui.common.UiText
import io.wutz.apollo.ui.common.resolve

/**
 * Resolves the tool-display engine's own texts (titles, labels, fallbacks) in the current locale
 * (Apollo, issue #10).
 *
 * Renderers and [ToolViewBuilder] stay pure: they receive a [ToolText] and build their strings
 * from resource ids, so a unit test can pass a recording or English resolver. Tool output, paths,
 * commands, queries and other server data are only ever passed as format arguments.
 *
 * [localeKey] identifies the language the texts resolve to; [ToolViewCache] keys on it so a cached
 * [ToolView] never outlives a language switch.
 */
class ToolText(
    val localeKey: String,
    private val resolver: (UiText) -> String,
) {
    fun resolve(text: UiText): String = resolver(text)

    fun get(
        @StringRes id: Int,
        vararg args: Any,
    ): String = resolver(UiText.Resource(id, args.toList()))

    /** Plural for [count]; without [args] the count is the only format argument. */
    fun plural(
        @PluralsRes id: Int,
        count: Int,
        vararg args: Any,
    ): String = resolver(UiText.plural(id, count, *args))

    companion object {
        fun of(resources: Resources): ToolText =
            ToolText(resources.configuration.locales.toLanguageTags()) { it.resolve(resources) }
    }
}

/** The [ToolText] for the current composition; recreated when the configuration (locale) changes. */
@Composable
fun rememberToolText(): ToolText {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { ToolText.of(resources) }
}
