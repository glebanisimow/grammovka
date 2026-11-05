package app.grammovka.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons in the Protoptera style (docs/design/icons): 24 grid, stroke 1.75, square caps, miter joins.
 * Path data is copied from the design SVGs where an icon exists; the rest are drawn to the same rules.
 */
object GIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.75f,
                    strokeLineCap = StrokeCap.Square,
                    strokeLineJoin = StrokeJoin.Miter,
                )
            }
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    // From the design set.
    val Back = icon("back", "M20 12H5M11 6l-6 6 6 6")
    val Close = icon("close", "M6 6l12 12M18 6L6 18")
    val Info = icon("info", circle(12f, 12f, 9f), "M12 11v6M12 7v.25")
    val Warning = icon("warning", "M12 3l10 18H2zM12 9.5v4.5M12 17.5v.25")
    val Lock = icon("lock", "M5 11h14v10H5zM8 11V7a4 4 0 0 1 8 0v4")
    val Search = icon("search", circle(10f, 10f, 6f), "M15 15l6 6")
    val Undo = icon("undo", "M9 14L4 9l5-5M4 9h11a5 5 0 0 1 0 10h-4")
    val Theme = icon("theme", "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z")
    val Next = icon("next", "M9 5l7 7-7 7")

    // Drawn for this app in the same style.
    /** Today: a plate seen from above. */
    val Today = icon("today", circle(12f, 12f, 8.5f), circle(12f, 12f, 4f))
    /** Catalog: stacked index cards. */
    val Catalog = icon("catalog", "M4 5h16v4H4zM4 11h16v4H4zM4 18h10")
    /** Progress: a falling trend line over a baseline. */
    val Progress = icon("progress", "M3 21h18", "M4 6l5 5 4-3 7 8")
    /** Settings: three sliders. */
    val Settings = icon("settings", "M4 6h16M4 12h16M4 18h16", "M8 4v4M15 10v4M10 16v4")
    val Key = icon("key", circle(7.5f, 12f, 4f), "M11.5 12H21M17 12v3.5M20 12v2.5")
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7")
    val Scale = icon("scale", "M4 6h16l-2 14H6z", "M9 11a3 3 0 0 1 6 0M12 11l1.5-2")
    val Barcode = icon("barcode", "M4 5v14M7 5v14M11 5v14M14 5v14M18 5v14M20 5v14")
    val Camera = icon("camera", "M3 7h4l2-3h6l2 3h4v13H3z", circle(12f, 13f, 4f))
    val Plus = icon("plus", "M12 5v14M5 12h14")
}
