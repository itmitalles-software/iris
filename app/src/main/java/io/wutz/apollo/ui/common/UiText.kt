package io.wutz.apollo.ui.common

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources

/**
 * A user-visible message that is resolved to text only when it is shown (Apollo, issue #10).
 *
 * ViewModels and pure formatters return [UiText] instead of built English strings, so the UI
 * renders them in the current locale and unit tests can assert the resource id and arguments.
 * Text that comes from the server or the agent (error bodies, names, tool output) stays [Raw] and
 * is never translated; it may be wrapped as an argument of a [Resource], for example
 * `UiText.res(R.string.keys_msg_load_failed, serverMessage)`.
 *
 * Arguments may themselves be [UiText]; they are resolved recursively before formatting.
 * The classes are data classes so `assertEquals(UiText.res(R.string.x, "a"), state.toastMessage)`
 * works in tests.
 */
sealed interface UiText {
    /** Text that is shown as is: server or agent output, user input, names. */
    data class Raw(
        val text: String,
    ) : UiText

    /** A string resource, formatted with [args] when there are any. */
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** A plurals resource selected by [count] and formatted with [args] (by default just [count]). */
    data class Plural(
        @param:PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = listOf(count),
    ) : UiText

    companion object {
        fun raw(text: String): UiText = Raw(text)

        fun res(
            @StringRes id: Int,
            vararg args: Any,
        ): UiText = Resource(id, args.toList())

        /** Plural for [count]; without [args] the count is the only format argument. */
        fun plural(
            @PluralsRes id: Int,
            count: Int,
            vararg args: Any,
        ): UiText = Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
    }
}

/** Resolves this text with [resources]; nested [UiText] arguments are resolved first. */
fun UiText.resolve(resources: Resources): String =
    when (this) {
        is UiText.Raw -> {
            text
        }

        is UiText.Resource -> {
            if (args.isEmpty()) {
                resources.getString(id)
            } else {
                resources.getString(id, *resolveArgs(args, resources))
            }
        }

        is UiText.Plural -> {
            resources.getQuantityString(id, count, *resolveArgs(args, resources))
        }
    }

fun UiText.resolve(context: Context): String = resolve(context.resources)

/** Resolves this text in composition (recomposes when the configuration, e.g. the locale, changes). */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = resolve(LocalResources.current)

private fun resolveArgs(
    args: List<Any>,
    resources: Resources,
): Array<Any> = args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()
