package app.grammovka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Colors outside the M3 scheme: muted text, accent-as-text (amber is too light on bone), status colors. */
@Immutable
data class ExtraColors(
    val textMuted: Color,
    val accentText: Color,
    val success: Color,
    val warning: Color,
    val border: Color,
    val formField: Color,
    /** Neutral reference series in charts (validated against the accent for CVD and normal-vision separation). */
    val chartNeutral: Color,
)

private val DarkExtra = ExtraColors(
    DarkTokens.textMuted, DarkTokens.accentText, DarkTokens.success, Brand.Amber, DarkTokens.border, DarkTokens.formField,
    Color(0xFFA39E91),
)
private val LightExtra = ExtraColors(
    LightTokens.textMuted, LightTokens.accentText, LightTokens.success, Brand.Amber, LightTokens.border, LightTokens.formField,
    Color(0xFF2B2924),
)

val LocalExtraColors = staticCompositionLocalOf { DarkExtra }

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Brand.Amber,
    onPrimary = Brand.Ink,
    primaryContainer = Color(0xFF3A2B0E),
    onPrimaryContainer = Brand.Amber,
    secondary = Brand.Bone,
    onSecondary = Brand.Ink,
    secondaryContainer = Color(0xFF3A2B0E),
    onSecondaryContainer = Brand.Amber,
    tertiary = Brand.Amber,
    onTertiary = Brand.Ink,
    background = DarkTokens.bg,
    onBackground = DarkTokens.text,
    surface = DarkTokens.bg,
    onSurface = DarkTokens.text,
    surfaceVariant = DarkTokens.surface,
    onSurfaceVariant = DarkTokens.textMuted,
    surfaceContainerLowest = DarkTokens.bg,
    surfaceContainerLow = DarkTokens.canvas,
    surfaceContainer = DarkTokens.canvas,
    surfaceContainerHigh = DarkTokens.surface,
    surfaceContainerHighest = DarkTokens.surfaceRaised,
    inverseSurface = DarkTokens.surfaceRaised,
    inverseOnSurface = Brand.Bone,
    inversePrimary = Brand.Amber,
    outline = Color(0xFF4A463C),
    outlineVariant = DarkTokens.border,
    error = DarkTokens.danger,
    onError = Brand.Ink,
    scrim = Color(0x8C000000),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Brand.Amber,
    onPrimary = Brand.Ink,
    primaryContainer = Color(0xFFFFE2B0),
    onPrimaryContainer = Brand.Ink,
    secondary = Brand.Ink,
    onSecondary = Brand.Bone,
    secondaryContainer = Color(0xFFFFE2B0),
    onSecondaryContainer = Brand.Ink,
    tertiary = LightTokens.accentText,
    onTertiary = Brand.Bone,
    background = LightTokens.bg,
    onBackground = LightTokens.text,
    surface = LightTokens.bg,
    onSurface = LightTokens.text,
    surfaceVariant = LightTokens.surface,
    onSurfaceVariant = LightTokens.textMuted,
    surfaceContainerLowest = LightTokens.surfaceRaised,
    surfaceContainerLow = LightTokens.surface,
    surfaceContainer = LightTokens.surface,
    surfaceContainerHigh = LightTokens.surfaceRaised,
    surfaceContainerHighest = LightTokens.canvas,
    inverseSurface = Brand.Ink,
    inverseOnSurface = Brand.Bone,
    inversePrimary = Brand.Amber,
    outline = Color(0xFFA9A396),
    outlineVariant = LightTokens.border,
    error = LightTokens.danger,
    onError = Brand.Bone,
    scrim = Color(0x590A0A09),
)

/** Token radii are 2/4/6 px: sharp, tool-like corners. */
private val GShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun GrammovkaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = GTypography,
            shapes = GShapes,
            content = content,
        )
    }
}

object GTheme {
    val extra: ExtraColors
        @Composable get() = LocalExtraColors.current
}
