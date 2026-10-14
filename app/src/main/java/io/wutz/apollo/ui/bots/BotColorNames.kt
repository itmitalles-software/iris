package io.wutz.apollo.ui.bots

import androidx.annotation.StringRes
import io.wutz.apollo.R

/** Spoken name of a bot palette color, so TalkBack does not read a bare hex value or nothing at all. */
@StringRes
internal fun botColorName(hex: String): Int? =
    when (hex.uppercase()) {
        "#4F46E5" -> R.string.bot_color_indigo
        "#2563EB" -> R.string.bot_color_blue
        "#0D9488" -> R.string.bot_color_teal
        "#16A34A" -> R.string.bot_color_green
        "#D97706" -> R.string.bot_color_amber
        "#EA580C" -> R.string.bot_color_orange
        "#DC2626" -> R.string.bot_color_red
        "#DB2777" -> R.string.bot_color_pink
        "#9333EA" -> R.string.bot_color_purple
        "#4B5563" -> R.string.bot_color_gray
        else -> null
    }
