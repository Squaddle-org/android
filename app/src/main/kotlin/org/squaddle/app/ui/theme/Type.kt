package org.squaddle.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.squaddle.app.R

// Fraunces (OFL, bundled res/font/fraunces_*.ttf, licenses/fraunces_OFL.txt) - a warm display
// serif for titles/headlines, the one "characterful" voice in the type system ("group chat meets
// sticky-note board" without tipping into a cartoon/script font). Inter (OFL, res/font/inter_*.ttf,
// licenses/inter_OFL.txt) is the clean sans for everything read in bulk - body copy, labels,
// numbers - chosen for its very high x-height/legibility at small sizes and full German
// (umlaut/eszett) glyph coverage.
internal val FrauncesDisplay = FontFamily(
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
    Font(R.font.fraunces_bold, FontWeight.Bold),
)

internal val InterBody = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** M3's default type scale, re-voiced: display/headline/title roles move to [FrauncesDisplay]
 * (room names, idea titles, screen headers - the "handwritten note" moments), body/label roles
 * stay on [InterBody] (comments, member names, buttons, anything dense or functional). Sizes/
 * line-heights/letter-spacing otherwise follow M3's own baseline values - this is a voice change,
 * not a new scale. */
internal val SquaddleTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = FrauncesDisplay, fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontFamily = FrauncesDisplay, fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontFamily = FrauncesDisplay, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = FrauncesDisplay, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = FrauncesDisplay, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = FrauncesDisplay, fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontFamily = FrauncesDisplay, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = InterBody, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = InterBody, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = InterBody),
        bodyMedium = base.bodyMedium.copy(fontFamily = InterBody),
        bodySmall = base.bodySmall.copy(fontFamily = InterBody),
        labelLarge = base.labelLarge.copy(fontFamily = InterBody, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = InterBody, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = InterBody, fontWeight = FontWeight.Medium),
    )
}
