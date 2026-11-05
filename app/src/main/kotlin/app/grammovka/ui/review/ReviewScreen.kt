package app.grammovka.ui.review

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(GIcons.Back, contentDescription = "Назад") }
            Text("Проверьте и запишите", style = MaterialTheme.typography.headlineSmall)
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            vm.items.forEach { item ->
                key(item) {
                    ItemCard(vm, item, extraActions)
                }
            }
            Spacer(Modifier.size(8.dp))
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            val total = vm.items.mapNotNull { vm.kcalOf(it) }.sum()
            Button(
                onClick = { vm.error = null; vm.commit(onBack) },
                enabled = !vm.busy && vm.items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text("Записать · ${vm.items.size} · ${Fmt.kcal(total)} ккал") }
        }
    }
}

@Composable
private fun key(k: Any, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }

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
    FoodFormFields(item.form)
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
