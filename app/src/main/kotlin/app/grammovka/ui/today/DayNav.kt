package app.grammovka.ui.today

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.grammovka.domain.log.DayLedger
import app.grammovka.domain.log.DayState
import app.grammovka.domain.model.Fmt
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private val ru = Locale.forLanguageTag("ru")
private val longFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", ru)
private val shortFmt = DateTimeFormatter.ofPattern("d MMMM", ru)
private val weekdays = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

fun dayTitle(d: LocalDate): String = longFmt.format(d).replaceFirstChar { it.uppercase() }
fun dayShort(d: LocalDate): String = shortFmt.format(d)

/** Signed kcal: «−620», «+150». */
fun signedKcal(v: Double): String = (if (v > 0.5) "+" else if (v < -0.5) "−" else "") + Fmt.kcal(abs(v))

/** The balance in words, so the sign needs no legend: «дефицит 620», «профицит 150», «ровно в расход». */
fun balanceWords(v: Double): String = when {
    v < -0.5 -> "дефицит ${Fmt.kcal(-v)}"
    v > 0.5 -> "профицит ${Fmt.kcal(v)}"
    else -> "ровно в расход"
}

@Composable
private fun balanceColor(v: Double): Color = if (v > 0.5) MaterialTheme.colorScheme.error else GTheme.extra.success

/** Day title (tap → month calendar) with the lifecycle mark and, for a closed day, the hidden «reopen». */
@Composable
fun DayHeader(ui: TodayUi, onCalendar: () -> Unit, onReopen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onCalendar).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(dayTitle(ui.date), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(6.dp))
            Icon(GIcons.Down, "Календарь", Modifier.size(16.dp), tint = GTheme.extra.textMuted)
        }
        when (ui.state) {
            DayState.CLOSED -> {
                Icon(GIcons.Lock, null, Modifier.size(16.dp), tint = GTheme.extra.textMuted)
                Spacer(Modifier.width(4.dp))
                Text("закрыт", style = MaterialTheme.typography.labelMedium, color = GTheme.extra.textMuted)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(GIcons.More, "Действия с днём") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Открыть для правки") }, onClick = { menu = false; onReopen() })
                    }
                }
            }
            DayState.OPEN -> Text("открыт", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelMedium, color = GTheme.extra.accentText)
            DayState.NONE -> Spacer(Modifier.width(12.dp))
        }
    }
}

/** Mon–Sun of the shown day's week; swipe for other weeks between the first day and this week. */
@Composable
fun WeekStrip(ui: TodayUi, onSelect: (LocalDate) -> Unit) {
    val first = DayLedger.weekStart(ui.ledger.firstDay)
    val last = DayLedger.weekStart(ui.today)
    val count = (ChronoUnit.WEEKS.between(first, last) + 1).toInt().coerceAtLeast(1)
    fun pageOf(d: LocalDate) = ChronoUnit.WEEKS.between(first, DayLedger.weekStart(d)).toInt().coerceIn(0, count - 1)
    val pager = rememberPagerState(initialPage = pageOf(ui.date)) { count }
    LaunchedEffect(ui.date, count) { pager.animateScrollToPage(pageOf(ui.date)) }
    HorizontalPager(pager, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) { page ->
        val start = first.plusWeeks(page.toLong())
        Column(Modifier.padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { i ->
                    val d = start.plusDays(i.toLong())
                    DayCellView(ui.cells[d], d, selected = d == ui.date, isToday = d == ui.today, Modifier.weight(1f)) { onSelect(d) }
                }
            }
            WeekSummary(ui, start)
        }
    }
}

@Composable
private fun WeekSummary(ui: TodayUi, start: LocalDate) {
    val closed = (0 until 7).mapNotNull { ui.cells[start.plusDays(it.toLong())] }
        .filter { it.state == DayState.CLOSED && !it.incomplete && it.balance != null }
    if (closed.isEmpty()) return
    val sum = closed.sumOf { it.balance!! }
    val n = closed.size
    Text(
        "Неделя: ${balanceWords(sum)} ккал за $n ${plural(n, "закрытый день", "закрытых дня", "закрытых дней")}",
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted, textAlign = TextAlign.Center,
    )
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

@Composable
private fun DayCellView(
    cell: DayCell?, d: LocalDate, selected: Boolean, isToday: Boolean, modifier: Modifier,
    showWeekday: Boolean = true, onClick: () -> Unit,
) {
    val enabled = cell?.selectable == true
    val outline = when {
        selected -> GTheme.extra.accentText
        isToday -> MaterialTheme.colorScheme.outlineVariant
        else -> Color.Transparent
    }
    Column(
        modifier.padding(2.dp).border(1.dp, outline, MaterialTheme.shapes.small)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.35f).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showWeekday) Text(weekdays[d.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall, color = GTheme.extra.textMuted)
        Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleSmall)
        Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
            when (cell?.state) {
                DayState.CLOSED -> Icon(GIcons.Check, "закрыт", Modifier.size(14.dp), tint = if (cell.incomplete) GTheme.extra.textMuted else GTheme.extra.accentText)
                DayState.OPEN -> Box(Modifier.size(8.dp).border(1.5.dp, GTheme.extra.accentText, MaterialTheme.shapes.extraLarge))
                else -> {}
            }
        }
        val b = cell?.balance
        Text(
            if (b != null) signedKcal(b) else " ",
            style = MaterialTheme.typography.labelSmall,
            color = if (b != null && cell.incomplete != true) balanceColor(b) else GTheme.extra.textMuted,
            maxLines = 1,
        )
    }
}

/** Month grid with the same marks; only days from the first day through today can be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthSheet(ui: TodayUi, onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val firstMonth = YearMonth.from(ui.ledger.firstDay)
    val lastMonth = YearMonth.from(ui.today)
    var month by remember { mutableStateOf(YearMonth.from(ui.date)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }, enabled = month.isAfter(firstMonth)) { Icon(GIcons.Prev, "Предыдущий месяц") }
                Text(
                    month.month.getDisplayName(TextStyle.FULL_STANDALONE, ru).replaceFirstChar { it.uppercase() } + " " + month.year,
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                )
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month.isBefore(lastMonth)) { Icon(GIcons.Next, "Следующий месяц") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                weekdays.forEach {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = GTheme.extra.textMuted, textAlign = TextAlign.Center)
                }
            }
            val start = DayLedger.weekStart(month.atDay(1))
            val end = month.atEndOfMonth()
            var w = start
            while (!w.isAfter(end)) {
                Row(Modifier.fillMaxWidth()) {
                    (0 until 7).forEach { i ->
                        val d = w.plusDays(i.toLong())
                        if (YearMonth.from(d) == month) {
                            val cell = ui.cells[d] ?: DayCell(d, ui.ledger.state(d), ui.ledger.incomplete(d), ui.ledger.selectable(d), null, null)
                            DayCellView(cell, d, d == ui.date, d == ui.today, Modifier.weight(1f), showWeekday = false) { onSelect(d); onDismiss() }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                w = w.plusWeeks(1)
            }
            Text(
                "✓ закрыт · ○ открыт · число — итог закрытого дня: съедено минус расход. С минусом — дефицит, с плюсом — профицит.",
                Modifier.padding(top = 12.dp, start = 4.dp), style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
            )
        }
    }
}

/** Shown on an open day that is not today: the reason for the whole lifecycle (entries went to yesterday). */
@Composable
fun NotTodayBanner(ui: TodayUi, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, GTheme.extra.warning, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Вы вносите в ${dayShort(ui.date)}, а сегодня ${dayShort(ui.today)}", style = MaterialTheme.typography.titleSmall)
        Text(
            "Если этот день записан, закройте его. Записи в нём больше не изменятся.",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
        Button(onClick = onClose, shape = MaterialTheme.shapes.small) {
            Text(if (ui.ledger.canStartToday) "Закрыть ${dayShort(ui.date)} и начать ${dayShort(ui.today)}" else "Закрыть ${dayShort(ui.date)}")
        }
    }
}

/** A day that was never started: today gets «Начать день», a skipped past day a quieter «внести задним числом». */
@Composable
fun NotStarted(ui: TodayUi, onStart: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.date == ui.today) {
            Text("День ещё не начат.", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Начните день, и все записи пойдут в ${dayShort(ui.date)}. Вечером закройте его: итог зафиксируется.",
                style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
            )
            Button(onClick = onStart, shape = MaterialTheme.shapes.small) { Text("Начать день · ${dayShort(ui.date)}") }
        } else {
            Text("Этот день не вели.", style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onStart, contentPadding = PaddingValues(0.dp)) { Text("Внести задним числом", color = GTheme.extra.accentText) }
        }
    }
}

/** Bottom of an open day. */
@Composable
fun CloseDayButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), shape = MaterialTheme.shapes.small) {
        Icon(GIcons.Lock, null, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Закрыть день")
    }
}

/** Bottom of a closed day: the way forward. */
@Composable
fun ClosedFooter(ui: TodayUi, onStartToday: () -> Unit, onGoToday: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (ui.ledger.incomplete(ui.date)) "День закрыт как записанный не полностью: в расчёт нормы он не идёт."
            else "День закрыт: записи не меняются, итог учтён.",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
        when {
            ui.ledger.canStartToday -> Button(onClick = onStartToday, shape = MaterialTheme.shapes.small) { Text("Начать новый день · ${dayShort(ui.today)}") }
            ui.date != ui.today -> OutlinedButton(onClick = onGoToday, shape = MaterialTheme.shapes.small) { Text("К сегодняшнему дню") }
        }
    }
}

@Composable
fun CloseDayDialog(ui: TodayUi, thenToday: Boolean, onClose: (incomplete: Boolean) -> Unit, onDismiss: () -> Unit) {
    val m = ui.model
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Закрыть ${dayShort(ui.date)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Записи этого дня больше нельзя будет менять.", style = MaterialTheme.typography.bodyMedium)
                val summary = buildString {
                    append("Съедено ${Fmt.kcal(ui.eaten.kcal)} ккал")
                    if (m != null) append(" · ${balanceWords(m.balance(ui.eaten.kcal).balance)}")
                }
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted)
                if (thenToday) Text("Затем начнётся ${dayShort(ui.today)}.", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
                TextButton(onClick = { onClose(true) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Закрыть, но день записан не полностью", color = GTheme.extra.textMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onClose(false) }) { Text("Закрыть день") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun ReopenDialog(ui: TodayUi, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Открыть ${dayShort(ui.date)} для правки?") },
        text = { Text("День снова можно менять, его итог пересчитается. Когда закончите, закройте его ещё раз.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Открыть") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
