package app.grammovka.ui.theme

import androidx.compose.ui.graphics.Color

/** Protoptera brand tokens (docs/design/tokens.json). */
object Brand {
    val Ink = Color(0xFF0A0A09)
    val Amber = Color(0xFFFFAA1D)
    val AmberHover = Color(0xFFFFBC4A)
    val Bone = Color(0xFFF4F1E8)
}

internal object DarkTokens {
    val bg = Brand.Ink
    val canvas = Color(0xFF14130F)
    val surface = Color(0xFF1F1D18)
    val surfaceRaised = Color(0xFF26231D)
    val border = Color(0xFF2A2720)
    val text = Brand.Bone
    val textMuted = Color(0xFF8C877B)
    val accentText = Brand.Amber
    val danger = Color(0xFFFF6B5A)
    val success = Color(0xFF7FB069)
    val formField = Color(0x2EFFAA1D) // amber 18 %
}

internal object LightTokens {
    val bg = Brand.Bone
    val canvas = Color(0xFFE4E0D4)
    val surface = Color(0xFFFAF8F2)
    val surfaceRaised = Color(0xFFFFFFFF)
    val border = Color(0xFFD6D1C3)
    val text = Brand.Ink
    val textMuted = Color(0xFF5E5A50)
    val accentText = Color(0xFF9A5B00)
    val danger = Color(0xFFB42318)
    val success = Color(0xFF2F7D32)
    val formField = Color(0x2EFFAA1D)
}
