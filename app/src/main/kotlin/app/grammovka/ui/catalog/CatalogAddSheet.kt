package app.grammovka.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.grammovka.domain.model.Fmt
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.Meal
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.model.Nutrition
import app.grammovka.ui.common.NumberField
import app.grammovka.ui.common.SourceBadge
import app.grammovka.ui.common.input
import app.grammovka.ui.common.toDecimal
import app.grammovka.ui.theme.GTheme
import app.grammovka.ui.today.dayShort

/**
 * A catalog food → an entry in a meal of the day being filled in: grams (the last amount is pre-filled,
 * portions are one tap), the meal, «Добавить». The editor is one step further, behind «Изменить».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogAddSheet(c: AddContext, onDismiss: () -> Unit, onEdit: () -> Unit, onAdd: (grams: Double, meal: Meal) -> Unit) {
    val f = c.food
    val per100 = Nutrients(f.kcal, f.protein, f.fat, f.carbs, f.fiber)
    var grams by remember { mutableStateOf(c.lastGrams?.input().orEmpty()) }
    var meal by remember { mutableStateOf(c.meal) }
    val g = grams.toDecimal()?.takeIf { it > 0 }
    val focus = remember { FocusRequester() }
    // Nothing to pre-fill: the weight is the only thing to type, so the keyboard comes up at once.
    LaunchedEffect(Unit) { if (c.lastGrams == null && !c.closed) runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (f.brand.isNullOrBlank()) f.name else "${f.name}, ${f.brand}", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${Fmt.kcal(f.kcal)} ккал на 100 г · Б ${Fmt.grams(f.protein)} · Ж ${Fmt.grams(f.fat)} · У ${Fmt.grams(f.carbs)}",
                        style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                    )
                }
                SourceBadge(f.source, f.confidence)
            }
            if (c.closed) {
                Text(
                    "${dayShort(c.day).replaceFirstChar { it.uppercase() }} закрыт, записи в нём не меняются. Откройте день на «Сегодня» (меню ⋯) или начните новый.",
                    style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.warning,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(grams, { grams = it }, "Вес", Modifier.weight(1f).focusRequester(focus), suffix = "г", imeAction = ImeAction.Done)
                    Text(
                        g?.let { "${Fmt.kcal(Nutrition.forGrams(per100, it).kcal)} ккал" } ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                // The last amount is already in the field; its chip is only a way back after trying a portion.
                val quick = buildList {
                    c.lastGrams?.let { add("как в прошлый раз" to it) }
                    c.portions.forEach { add(it.name to it.grams) }
                }.distinctBy { it.second }
                if (c.portions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quick.forEach { (name, v) ->
                            SuggestionChip(onClick = { grams = v.input() }, label = { Text("$name · ${Fmt.kcal(v)} г") }, shape = MaterialTheme.shapes.small)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Meal.entries.forEach { m -> FilterChip(selected = meal == m, onClick = { meal = m }, label = { Text(m.ru) }) }
                }
                if (c.day != c.today) {
                    Text(
                        "Запись пойдёт в ${dayShort(c.day)}: этот день ещё открыт, а сегодня ${dayShort(c.today)}.",
                        style = MaterialTheme.typography.bodySmall, color = GTheme.extra.warning,
                    )
                }
                Button(onClick = { onAdd(g!!, meal) }, enabled = g != null, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                    Text("Добавить в ${meal.ru}")
                }
            }
            TextButton(onClick = onEdit, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (f.source == FoodSource.RECIPE) "Изменить рецепт" else "Изменить продукт", color = GTheme.extra.accentText)
            }
        }
    }
}
