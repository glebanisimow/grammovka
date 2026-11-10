package app.grammovka.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import app.grammovka.ui.icons.GIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.grammovka.di.AppContainer
import app.grammovka.domain.engine.EngineConfig
import app.grammovka.domain.engine.Weekly
import app.grammovka.domain.model.Fmt
import app.grammovka.ui.common.ScreenTitle
import app.grammovka.ui.common.SectionLabel
import app.grammovka.ui.theme.GTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ranges = listOf(30 to "30 дней", 90 to "90 дней", 365 to "Год")
private val weekFmt = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))

/** Charts from ModelSnapshot and the log (spec, phase 2), the weekly table and the model's numbers. */
@Composable
fun ProgressScreen(container: AppContainer, onOpenWeekly: () -> Unit) {
    val state by container.modelHub.state.collectAsStateWithLifecycle()
    val weights by container.weightRepository.observeAll().collectAsStateWithLifecycle(emptyList())
    val today = LocalDate.now()
    val day = state?.day(today)
    val cfg = EngineConfig()
    var range by rememberSaveable { mutableIntStateOf(30) }
    val from = today.minusDays(range - 1L)
    val accent = GTheme.extra.accentText
    val neutral = GTheme.extra.chartNeutral
    val snaps = state?.snapshots.orEmpty()
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(app.grammovka.data.settings.AppSettings())
    val activity by produceState<Pair<Map<LocalDate, Long>, List<app.grammovka.data.health.Workout>>?>(null, settings.healthConnect, range) {
        value = if (!settings.healthConnect) null else runCatching {
            container.healthRepository.steps(from, today) to container.healthRepository.workouts(from, today)
        }.getOrNull()
    }
    val days = state?.days.orEmpty()

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("Прогресс") }
        item {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpenWeekly).heightIn(min = 64.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Разбор недели", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Цифры недели и разбор от Claude: что сработало и что поменять",
                        style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                    )
                }
                Icon(GIcons.Next, null, Modifier.size(20.dp), tint = GTheme.extra.textMuted)
            }
            HorizontalDivider(Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                ranges.forEachIndexed { i, (d, l) ->
                    SegmentedButton(
                        selected = range == d, onClick = { range = d },
                        shape = SegmentedButtonDefaults.itemShape(i, ranges.size, MaterialTheme.shapes.small), icon = {},
                    ) { Text(l) }
                }
            }
        }

        item { SectionLabel("Вес и тренд") }
        item {
            TimeChart(
                from, today,
                listOf(
                    ChartSeries("взвешивание", neutral, MarkStyle.DOTS, weights.associate { it.date to it.kg }),
                    ChartSeries("тренд", accent, MarkStyle.LINE, snaps.mapValues { it.value.trendWeight }),
                ),
                format = { Fmt.kg(it) },
            )
        }

        item { SectionLabel("Расход") }
        item {
            TimeChart(
                from, today,
                listOf(
                    ChartSeries("сглаженный", accent, MarkStyle.LINE, snaps.mapValues { it.value.tdee }),
                    ChartSeries("по данным", neutral, MarkStyle.DASHED, snaps.mapNotNull { (d, s) -> s.tdeeRaw?.let { d to it } }.toMap()),
                ),
                format = { Fmt.kcal(it) },
            )
        }
        item {
            Note("«По данным» — из веса и съеденного за ${cfg.windowDays} дней. Пока данных мало, расход берётся по формуле Миффлина — Сан Жеора.")
        }

        item { SectionLabel("Съедено и цель") }
        item {
            val incomplete = days.filterValues { it.hasEntries && it.incomplete }.keys
            TimeChart(
                from, today,
                listOf(
                    ChartSeries("съедено", neutral, MarkStyle.BARS, days.filterValues { it.hasEntries }.mapValues { it.value.kcal }, faded = incomplete),
                    ChartSeries("цель", accent, MarkStyle.LINE, snaps.mapValues { it.value.target }),
                ),
                format = { Fmt.kcal(it) },
                zeroBased = true,
            )
        }
        item { Note("Бледные столбцы — дни, отмеченные как записанные не полностью: модель их не учитывает.") }

        activity?.let { (steps, workouts) ->
            item { SectionLabel("Активность · Health Connect") }
            item {
                TimeChart(
                    from, today,
                    listOf(ChartSeries("шаги", neutral, MarkStyle.BARS, steps.mapValues { it.value.toDouble() })),
                    format = { Fmt.kcal(it) },
                    zeroBased = true,
                )
            }
            item { Note("Шаги и тренировки только для информации: расход считается по весу и съеденному.") }
            if (workouts.isEmpty()) {
                item { Note("Тренировок за период нет.") }
            }
            items(workouts.take(30), key = { "w" + it.date + it.title + it.minutes }) { w ->
                Line(w.date.format(weekFmt), "${w.minutes} мин", w.title)
            }
        }

        item { SectionLabel("По неделям") }
        item { WeekHeader() }
        val rows = Weekly.rows(today, 8, days, snaps.mapValues { it.value.target }, snaps.mapValues { it.value.trendWeight })
        items(rows, key = { it.monday.toString() }) { r ->
            WeekLine(
                r.monday.format(weekFmt), "${r.completeDays}/7",
                r.avgKcal?.let(Fmt::kcal) ?: "—", r.avgTarget?.let(Fmt::kcal) ?: "—",
                r.trendChangeKg?.let { (if (it > 0) "+" else "") + Fmt.kg(it) } ?: "—",
            )
        }

        if (day == null) {
            item {
                Text(
                    "Модель появится после заполнения профиля и первого взвешивания.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                )
            }
        } else {
            val s = day.snapshot
            item { SectionLabel("Модель на сегодня") }
            item { Line("Трендовый вес", "${Fmt.kg(s.trendWeight)} кг") }
            item {
                Line(
                    "Расход (сглаженный)", "${Fmt.kcal(s.tdee)} ккал",
                    if (s.tdeeRaw != null) "по данным: ${Fmt.kcal(s.tdeeRaw!!)} ккал" else "пока по формуле: нужно ≥ ${cfg.minIncludedDays} полных дней и ≥ ${cfg.minWeighIns} взвешиваний за ${cfg.windowDays} дней",
                )
            }
            item { Line("BMR", "${Fmt.kcal(s.bmr)} ккал") }
            item { Line("Дефицит", "${Fmt.kcal(s.deficit)} ккал/день", if (s.capped) "ограничен 25 % расхода" else null) }
            item { Line("Цель", "${Fmt.kcal(s.target)} ккал", if (s.bmrFloor) "поднята до BMR" else null) }
            item { Line("Бюджет сегодня", "${Fmt.kcal(day.allowance)} ккал") }
            item { Line("Белок", "${Fmt.kcal(s.proteinTarget)} г") }
            item { Line("Полных дней в окне", "${s.includedDays} из ${cfg.windowDays}") }
            item { Line("Взвешиваний в окне", "${s.weighIns}") }
        }
        item { SectionLabel("Взвешивания") }
        if (weights.isEmpty()) {
            item { Text("Пока нет. Вес вводится на экране «Сегодня».", Modifier.padding(horizontal = 16.dp), color = GTheme.extra.textMuted) }
        }
        items(weights.reversed().take(60), key = { it.date.toString() }) { w ->
            val trend = snaps[w.date]?.trendWeight
            Line(w.date.toString(), "${Fmt.kg(w.kg)} кг", trend?.let { "тренд ${Fmt.kg(it)}" })
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
}

@Composable
private fun WeekHeader() {
    WeekLine("Неделя", "Полных", "Ср. ккал", "Цель", "Тренд", header = true)
}

@Composable
private fun WeekLine(week: String, complete: String, kcal: String, target: String, trend: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val color = if (header) GTheme.extra.textMuted else MaterialTheme.colorScheme.onSurface
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(week, Modifier.weight(1.2f), style = style, color = color)
            Text(complete, Modifier.weight(0.9f), style = style, color = color, textAlign = TextAlign.End)
            Text(kcal, Modifier.weight(1f), style = style, color = color, textAlign = TextAlign.End)
            Text(target, Modifier.weight(1f), style = style, color = color, textAlign = TextAlign.End)
            Text(trend, Modifier.weight(1f), style = style, color = color, textAlign = TextAlign.End)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun Line(label: String, value: String, note: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
