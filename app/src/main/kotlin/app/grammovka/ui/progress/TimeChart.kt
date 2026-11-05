package app.grammovka.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.grammovka.ui.theme.GTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

enum class MarkStyle { LINE, DASHED, DOTS, BARS }

/** One series on a day axis. [faded] days are drawn at low emphasis (e.g. days flagged incomplete). */
data class ChartSeries(
    val label: String,
    val color: Color,
    val style: MarkStyle,
    val points: Map<LocalDate, Double>,
    val faded: Set<LocalDate> = emptySet(),
)

private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))

/**
 * Small day-axis chart drawn on Canvas with Protoptera tokens (no chart library: four simple charts,
 * exact token colors and tabular figures without fighting a theme system).
 * One y-axis only. Tap or drag to inspect a day; the readout line above the plot shows its values,
 * otherwise the latest day's values.
 */
@Composable
fun TimeChart(
    from: LocalDate,
    to: LocalDate,
    series: List<ChartSeries>,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    zeroBased: Boolean = false,
) {
    val n = (ChronoUnit.DAYS.between(from, to) + 1).toInt().coerceAtLeast(1)
    var selected by remember(from, to) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axisStyle = MaterialTheme.typography.labelSmall.copy(color = GTheme.extra.textMuted)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.background
    val cursor = MaterialTheme.colorScheme.onSurfaceVariant

    val values = series.flatMap { s -> s.points.filterKeys { !it.isBefore(from) && !it.isAfter(to) }.values }
    val lastWithData = (n - 1 downTo 0).firstOrNull { i -> series.any { it.points.containsKey(from.plusDays(i.toLong())) } }
    val readoutIndex = selected ?: lastWithData

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (series.size >= 2) Legend(series)
        val readout = readoutIndex?.let { i ->
            val d = from.plusDays(i.toLong())
            val parts = series.mapNotNull { s -> s.points[d]?.let { "${s.label} ${format(it)}" } }
            if (parts.isEmpty()) null else d.format(dayFmt) + ": " + parts.joinToString(" · ")
        }
        Text(readout ?: " ", style = MaterialTheme.typography.bodySmall)

        if (values.isEmpty()) {
            Text("Нет данных за период.", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
            return@Column
        }
        var lo = values.min()
        var hi = values.max()
        if (zeroBased) lo = 0.0
        if (hi - lo < 1e-9) { hi += 1.0; lo -= if (zeroBased) 0.0 else 1.0 }
        val ticks = niceTicks(lo, hi, 3)
        lo = minOf(lo, ticks.first()); hi = maxOf(hi, ticks.last())

        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(n) {
                    detectTapGestures { o -> selected = indexAt(o.x, size.width.toFloat(), n, 40.dp.toPx()) }
                }
                .pointerInput(n) {
                    detectHorizontalDragGestures(onDragEnd = {}) { change, _ ->
                        selected = indexAt(change.position.x, size.width.toFloat(), n, 40.dp.toPx())
                    }
                },
        ) {
            val left = 40.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val top = 4.dp.toPx()
            val w = size.width - left
            val step = w / n
            fun x(i: Int) = left + (i + 0.5f) * step
            fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

            ticks.forEach { t ->
                val yy = y(t)
                drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx())
                val label = measurer.measure(format(t), axisStyle)
                drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), yy - label.size.height / 2f))
            }
            listOf(0, n / 2, n - 1).distinct().forEach { i ->
                val label = measurer.measure(from.plusDays(i.toLong()).format(dayFmt), axisStyle)
                val lx = (x(i) - label.size.width / 2f).coerceIn(left, size.width - label.size.width)
                drawText(label, topLeft = Offset(lx, bottom + 4.dp.toPx()))
            }

            series.forEach { s ->
                val pts = (0 until n).mapNotNull { i -> s.points[from.plusDays(i.toLong())]?.let { i to it } }
                when (s.style) {
                    MarkStyle.BARS -> {
                        val bw = (step - 2.dp.toPx()).coerceAtLeast(1f)
                        val base = y(maxOf(lo, 0.0))
                        pts.forEach { (i, v) ->
                            val d = from.plusDays(i.toLong())
                            val yy = y(v)
                            drawRoundRect(
                                s.color.copy(alpha = if (d in s.faded) 0.3f else 0.85f),
                                topLeft = Offset(x(i) - bw / 2, minOf(yy, base)),
                                size = Size(bw, kotlin.math.abs(base - yy)),
                                cornerRadius = CornerRadius(minOf(2.dp.toPx(), bw / 2)),
                            )
                        }
                    }
                    MarkStyle.DOTS -> pts.forEach { (i, v) ->
                        drawCircle(surface, radius = 4.5.dp.toPx(), center = Offset(x(i), y(v)))
                        drawCircle(s.color, radius = 3.dp.toPx(), center = Offset(x(i), y(v)))
                    }
                    MarkStyle.LINE, MarkStyle.DASHED -> line(pts.map { (i, v) -> Offset(x(i), y(v)) }, s.color, s.style == MarkStyle.DASHED)
                }
            }

            selected?.let { i ->
                drawLine(cursor, Offset(x(i), top), Offset(x(i), bottom), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

private fun DrawScope.line(points: List<Offset>, color: Color, dashed: Boolean) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, radius = 3.dp.toPx(), center = points[0])
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path, color,
        style = Stroke(
            width = 2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
        ),
    )
}

private fun indexAt(px: Float, width: Float, n: Int, left: Float): Int =
    (((px - left) / (width - left)) * n).toInt().coerceIn(0, n - 1)

/** «Nice» tick values (1, 2, 5 × 10^k) covering [lo, hi]. */
internal fun niceTicks(lo: Double, hi: Double, count: Int): List<Double> {
    val raw = (hi - lo) / count
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    return generateSequence(start) { it + step }.takeWhile { it <= end + step / 2 }.toList()
}

@Composable
private fun Legend(series: List<ChartSeries>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
                    val cy = size.height / 2
                    when (s.style) {
                        MarkStyle.DOTS -> drawCircle(s.color, radius = 3.dp.toPx(), center = Offset(size.width / 2, cy))
                        MarkStyle.BARS -> drawRoundRect(s.color, cornerRadius = CornerRadius(2.dp.toPx()))
                        else -> drawLine(
                            s.color, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                            pathEffect = if (s.style == MarkStyle.DASHED) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
                        )
                    }
                }
                Text(s.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
