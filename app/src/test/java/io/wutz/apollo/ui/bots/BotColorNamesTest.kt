package io.wutz.apollo.ui.bots

import io.wutz.apollo.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BotColorNamesTest {
    // Mirrors PALETTE_COLORS in CreateBotDialog and EditBotBottomSheet; every swatch needs a spoken name.
    private val palette =
        listOf(
            "#4F46E5",
            "#2563EB",
            "#0D9488",
            "#16A34A",
            "#D97706",
            "#EA580C",
            "#DC2626",
            "#DB2777",
            "#9333EA",
            "#4B5563",
        )

    @Test
    fun everyPaletteColorHasADistinctName() {
        val names = palette.map { hex -> botColorName(hex) }
        names.forEachIndexed { index, name -> assertNotNull(palette[index], name) }
        assertEquals(palette.size, names.toSet().size)
    }

    @Test
    fun matchingIgnoresHexCase() {
        assertEquals(R.string.bot_color_indigo, botColorName("#4f46e5"))
    }

    @Test
    fun customColorsHaveNoName() {
        assertNull(botColorName("#123456"))
    }
}
