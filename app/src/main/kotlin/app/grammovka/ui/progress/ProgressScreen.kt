package app.grammovka.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.grammovka.di.AppContainer
import app.grammovka.domain.engine.EngineConfig
import app.grammovka.domain.model.Fmt
import app.grammovka.ui.common.ScreenTitle
import app.grammovka.ui.common.SectionLabel
import app.grammovka.ui.theme.GTheme
import java.time.LocalDate

/** Phase 1: the model's current state as numbers. Charts arrive in phase 2. */
@Composable
fun ProgressScreen(container: AppContainer) {
    val state by container.modelHub.state.collectAsStateWithLifecycle()
    val weights by container.weightRepository.observeAll().collectAsStateWithLifecycle(emptyList())
    val today = state?.day(LocalDate.now())
    val cfg = EngineConfig()

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("Прогресс") }
        if (today == null) {
            item {
                Text(
                    "Модель появится после заполнения профиля и первого взвешивания.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                )
            }
        } else {
            val s = today.snapshot
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
            item { Line("Бюджет сегодня", "${Fmt.kcal(today.allowance)} ккал") }
            item { Line("Белок", "${Fmt.kcal(s.proteinTarget)} г") }
            item { Line("Полных дней в окне", "${s.includedDays} из ${cfg.windowDays}") }
            item { Line("Взвешиваний в окне", "${s.weighIns}") }
        }
        item { SectionLabel("Взвешивания") }
        if (weights.isEmpty()) {
            item { Text("Пока нет. Вес вводится на экране «Сегодня».", Modifier.padding(horizontal = 16.dp), color = GTheme.extra.textMuted) }
        }
        items(weights.reversed(), key = { it.date.toString() }) { w ->
            val trend = state?.snapshots?.get(w.date)?.trendWeight
            Line(w.date.toString(), "${Fmt.kg(w.kg)} кг", trend?.let { "тренд ${Fmt.kg(it)}" })
        }
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
