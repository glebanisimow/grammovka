package app.grammovka.ui.review

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.grammovka.ui.common.DiscardDialog
import app.grammovka.ui.common.ValidatorLine
import app.grammovka.ui.today.plural
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.grammovka.domain.model.Fmt
import app.grammovka.ui.catalog.FoodFormFields
import app.grammovka.ui.common.NumberField
import app.grammovka.ui.common.SourceBadge
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme

/**
 * Confirm sheet (spec: ui/review). Matched lines: grams editable. New foods: every field editable,
 * source badge, energy validator, "save to catalog" checkbox. Nothing is written until «Записать».
 */
@Composable
fun ReviewScreen(
    vm: ReviewViewModel,
    onBack: () -> Unit,
    extraActions: @Composable (ReviewItem.Unresolved) -> Unit = {},
    status: @Composable () -> Unit = {},
) {
    // Found by Claude, scanned or read from a label: leaving throws away work that cost time or money, so ask first.
    var confirmLeave by remember { mutableStateOf(false) }
    val leave = { if (!vm.busy && vm.items.any { it is ReviewItem.NewFood }) confirmLeave = true else onBack() }
    BackHandler(onBack = leave)
    if (confirmLeave) {
        DiscardDialog(
            "Выйти без записи?", "Найденные продукты и введённые цифры не сохранятся.", "Выйти",
            onDiscard = { confirmLeave = false; onBack() }, onStay = { confirmLeave = false },
        )
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = leave) { Icon(GIcons.Back, contentDescription = "Назад") }
            Text("Проверьте и запишите", style = MaterialTheme.typography.headlineSmall)
        }
        status()
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.items.forEach { item ->
                androidx.compose.runtime.key(item) {
                    ItemCard(vm, item, extraActions)
                }
            }
            Spacer(Modifier.size(8.dp))
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            app.grammovka.ui.common.MealChips(vm.meal, { vm.meal = it })
            val total = vm.items.mapNotNull { vm.kcalOf(it) }.sum()
            Button(
                onClick = { vm.error = null; vm.commit(onBack) },
                enabled = !vm.busy && vm.items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) {
                val n = vm.items.size
                Text(if (n == 0) "Записать" else "Записать $n ${plural(n, "позицию", "позиции", "позиций")} · ${Fmt.kcal(total)} ккал")
            }
        }
    }
}

@Composable
private fun ItemCard(vm: ReviewViewModel, item: ReviewItem, extraActions: @Composable (ReviewItem.Unresolved) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("«${item.fragment.raw.trim()}»", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
            IconButton(onClick = { vm.remove(item) }) { Icon(GIcons.Close, "Убрать", Modifier.size(18.dp)) }
        }
        when (item) {
            is ReviewItem.Matched -> MatchedBody(vm, item)
            is ReviewItem.NewFood -> NewFoodBody(vm, item)
            is ReviewItem.Unresolved -> UnresolvedBody(vm, item, extraActions)
        }
    }
}

@Composable
private fun GramsRow(vm: ReviewViewModel, item: ReviewItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField(item.grams, { item.grams = it }, "Вес", Modifier.weight(1f), suffix = "г", isError = item.grams.isBlank())
        Text(vm.kcalOf(item)?.let { "${Fmt.kcal(it)} ккал" } ?: "—", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MatchedBody(vm: ReviewViewModel, item: ReviewItem.Matched) {
    if (item.candidates.size > 1) {
        Text("Какой продукт?", style = MaterialTheme.typography.titleSmall)
        item.candidates.forEach { c ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.select(item, c) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = item.selected?.id == c.id, onClick = { vm.select(item, c) })
                Column(Modifier.weight(1f)) {
                    Text(c.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text("${Fmt.kcal(c.per100.kcal)} ккал / 100 г", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
                }
                SourceBadge(c.source, c.confidence)
            }
        }
    } else {
        item.selected?.let { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("${Fmt.kcal(f.per100.kcal)} ккал / 100 г · из каталога", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
                }
                SourceBadge(f.source, f.confidence)
            }
        }
    }
    GramsRow(vm, item)
}

@Composable
private fun NewFoodBody(vm: ReviewViewModel, item: ReviewItem.NewFood) {
    Text("Новый продукт", style = MaterialTheme.typography.titleSmall, color = GTheme.extra.accentText)
    item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted) }
    if (item.expanded) {
        FoodFormFields(item.form)
    } else {
        // Collapsed: what will be saved, its source and the validator; every field is one tap away (invariant 2).
        val f = item.form
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(listOf(f.name, f.brand).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${f.kcal} ккал / 100 г · Б ${f.protein} · Ж ${f.fat} · У ${f.carbs}",
                    style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                )
            }
            SourceBadge(f.source, f.confidence)
        }
        ValidatorLine(f.nutrients)
        TextButton(onClick = { item.expanded = true }, contentPadding = PaddingValues(0.dp)) {
            Text("Изменить название, цифры, порции", color = GTheme.extra.accentText)
        }
    }
    Row(Modifier.clickable { item.save = !item.save }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = item.save, onCheckedChange = { item.save = it })
        Text("Сохранить в каталог", style = MaterialTheme.typography.bodyMedium)
    }
    GramsRow(vm, item)
}

@Composable
private fun UnresolvedBody(vm: ReviewViewModel, item: ReviewItem.Unresolved, extraActions: @Composable (ReviewItem.Unresolved) -> Unit) {
    Text("«${item.fragment.name}» нет в каталоге.", style = MaterialTheme.typography.titleSmall)
    if (item.candidates.isNotEmpty()) {
        Text("Похожие:", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
        item.candidates.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable { vm.pick(item, c) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.displayName, Modifier.weight(1f))
                SourceBadge(c.source, c.confidence)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        extraActions(item)
        AssistChip(onClick = { vm.manual(item) }, label = { Text("Ввести вручную") })
    }
}
