package app.grammovka.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Tabular figures so columns of numbers line up (UX principle: numbers are monospaced). */
const val TABULAR = "tnum"

private val base = Typography()

val GTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFeatureSettings = TABULAR, fontWeight = FontWeight.Medium),
    displayMedium = base.displayMedium.copy(fontFeatureSettings = TABULAR, fontWeight = FontWeight.Medium),
    displaySmall = base.displaySmall.copy(fontFeatureSettings = TABULAR, fontWeight = FontWeight.Medium),
    headlineLarge = base.headlineLarge.copy(fontFeatureSettings = TABULAR),
    headlineMedium = base.headlineMedium.copy(fontFeatureSettings = TABULAR),
    headlineSmall = base.headlineSmall.copy(fontFeatureSettings = TABULAR),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontFeatureSettings = TABULAR),
    titleSmall = base.titleSmall.copy(fontFeatureSettings = TABULAR),
    bodyLarge = base.bodyLarge.copy(fontFeatureSettings = TABULAR),
    bodyMedium = base.bodyMedium.copy(fontFeatureSettings = TABULAR),
    bodySmall = base.bodySmall.copy(fontFeatureSettings = TABULAR),
    labelLarge = base.labelLarge.copy(fontFeatureSettings = TABULAR),
    labelMedium = base.labelMedium.copy(fontFeatureSettings = TABULAR, letterSpacing = 0.4.sp),
    labelSmall = base.labelSmall.copy(fontFeatureSettings = TABULAR, letterSpacing = 0.6.sp),
)

/** Big number on the Today screen. */
val NumberHero = TextStyle(fontSize = 64.sp, lineHeight = 68.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR)
