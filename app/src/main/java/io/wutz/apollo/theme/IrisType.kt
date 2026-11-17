package io.wutz.apollo.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import io.wutz.apollo.R

/**
 * Iris's display face: Cinzel (SIL OFL 1.1, docs/licenses/Cinzel-OFL.txt),
 * classical Roman capitals. Reserved for brand surfaces (wordmark, hero
 * titles); body, chat and code keep the system faces for readability.
 */
val IrisDisplayFamily =
    FontFamily(
        Font(
            R.font.cinzel,
            weight = FontWeight.SemiBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(600)),
        ),
    )

/** Wordmark style: tracked capitals, as in docs/brand/apollo-wordmark.svg. */
val IrisWordmarkStyle =
    TextStyle(
        fontFamily = IrisDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.16.em,
    )

/** [Typography] with Cinzel display styles; everything else is unchanged. */
internal val IrisTypography: Typography =
    Typography.copy(
        displayLarge = Typography.displayLarge.merge(IrisWordmarkStyle),
        displayMedium = Typography.displayMedium.merge(IrisWordmarkStyle),
        displaySmall = Typography.displaySmall.merge(IrisWordmarkStyle),
    )
