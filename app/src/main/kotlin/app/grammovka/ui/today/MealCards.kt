package app.grammovka.ui.today

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import app.grammovka.data.db.LogEntryEntity
import app.grammovka.domain.log.MealBlock
import app.grammovka.domain.model.Fmt
import app.grammovka.domain.model.Meal
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import java.time.LocalTime

val Meal.title: String get() = ru.replaceFirstChar { it.uppercase() }

private fun LocalTime.hm(): String = toString().take(5)

/** Card border: the card the input line writes to is outlined in the accent colour. */
@Composable
private fun cardModifier(target: Boolean): Modifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 16.dp, vertical = 6.dp)
    .border(
        if (target) 1.5.dp else 1.dp,
        if (target) GTheme.extra.accentText else MaterialTheme.colorScheme.outlineVariant,
        MaterialTheme.shapes.medium,
    )

/** A meal with its items and totals; the header collapses it, «⋯» opens type, time, template and delete. */
@Composable
fun MealCard(
    block: MealBlock<LogEntryEntity>,
    target: Boolean,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
    onMenu: () -> Unit,
    onEntry: (LogEntryEntity) -> Unit,
    /** A closed day: no menu, no «+ позиция», rows do not open the editor. */
    readOnly: Boolean = false,
) {
    val kcal = block.items.sumOf { it.kcal }
    Column(cardModifier(target)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 56.dp).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(GIcons.Down, null, Modifier.size(16.dp).rotate(if (collapsed) -90f else 0f), tint = GTheme.extra.textMuted)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${block.meal.title} · ${block.start.hm()}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Б ${Fmt.grams(block.items.sumOf { it.protein })} · Ж ${Fmt.grams(block.items.sumOf { it.fat })} · У ${Fmt.grams(block.items.sumOf { it.carbs })}",
                    style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                )
            }
            Text("${Fmt.kcal(kcal)} ккал", style = MaterialTheme.typography.titleMedium)
            if (readOnly) Spacer(Modifier.width(12.dp)) else IconButton(onClick = onMenu) { Icon(GIcons.More, "Действия с приёмом") }
        }
        if (!collapsed) {
            block.items.forEach { e ->
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ItemRow(e, enabled = !readOnly) { onEntry(e) }
            }
            if (!readOnly) {
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                AddRow(onAdd)
            }
        }
    }
}

/** A card opened with «+ Приём пищи» before anything is written to it. */
@Composable
fun EmptyMealCard(card: OpenCard, target: Boolean, onAdd: () -> Unit, onClose: () -> Unit) {
    Column(cardModifier(target)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${card.meal.title} · ${card.at.hm()}", style = MaterialTheme.typography.titleSmall)
                Text("Пусто. Введите в строке внизу, что съели.", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
            }
            IconButton(onClick = onClose) { Icon(GIcons.Close, "Убрать пустой приём") }
        }
        if (!target) {
            HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
            AddRow(onAdd)
        }
    }
}

@Composable
private fun AddRow(onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onAdd).heightIn(min = 44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(GIcons.Plus, null, Modifier.size(16.dp), tint = GTheme.extra.accentText)
        Spacer(Modifier.width(8.dp))
        Text("позиция", style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.accentText)
    }
}

@Composable
private fun ItemRow(e: LogEntryEntity, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(e.foodName, style = MaterialTheme.typography.bodyLarge)
            Text("${Fmt.kcal(e.grams)} г · Б ${Fmt.grams(e.protein)}", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
        }
        Text(Fmt.kcal(e.kcal), style = MaterialTheme.typography.bodyLarge)
    }
}

/** «+ Приём пищи»: any meal or a snack; an existing main meal just becomes the input target. */
@Composable
fun AddMealButton(onPick: (Meal) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(onClick = { open = true }, shape = MaterialTheme.shapes.small) {
            Icon(GIcons.Plus, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Приём пищи")
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Meal.entries.forEach { m -> DropdownMenuItem(text = { Text(m.title) }, onClick = { open = false; onPick(m) }) }
        }
    }
}

/** Above the input line: which card it writes to; tap to change. */
@Composable
fun TargetBar(target: Meal, picked: Boolean, onPick: (Meal) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(
                "Записываю в: ${target.title}" + if (picked) "" else " (авто)",
                style = MaterialTheme.typography.labelLarge, color = GTheme.extra.accentText,
            )
            Spacer(Modifier.width(4.dp))
            Icon(GIcons.Down, null, Modifier.size(14.dp), tint = GTheme.extra.accentText)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            Meal.entries.forEach { m -> DropdownMenuItem(text = { Text(m.title) }, onClick = { open = false; onPick(m) }) }
        }
    }
}

/** Card actions: change type or time of all its entries, save as a template, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealCardDialog(
    block: MealBlock<LogEntryEntity>,
    onDismiss: () -> Unit,
    onRetype: (Meal) -> Unit,
    onShift: (LocalTime) -> Unit,
    onTemplate: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var meal by remember { mutableStateOf(block.meal) }
    val time = rememberTimePickerState(block.start.hour, block.start.minute, is24Hour = true)
    var name by remember { mutableStateOf(block.meal.title) }
    var confirmDelete by remember { mutableStateOf(false) }
    val canTemplate = block.items.any { it.foodId != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${block.meal.title} · ${Fmt.kcal(block.items.sumOf { it.kcal })} ккал") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Приём", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Meal.entries.forEach { m -> FilterChip(selected = meal == m, onClick = { meal = m }, label = { Text(m.ru) }) }
                }
                Text("Время", style = MaterialTheme.typography.titleSmall)
                TimeInput(time)
                if (canTemplate) {
                    Text("Шаблон", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, label = { Text("Название") })
                        TextButton(onClick = { onTemplate(name) }, enabled = name.isNotBlank()) { Text("Сохранить") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (meal != block.meal) onRetype(meal)
                val t = LocalTime.of(time.hour, time.minute)
                if (t != block.start) onShift(t)
                onDismiss()
            }) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = { confirmDelete = true }) { Text("Удалить приём", color = MaterialTheme.colorScheme.error) }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${block.meal.ru}» целиком?") },
            text = { Text("Позиций: ${block.items.size}. Сразу после удаления можно отменить.") },
            confirmButton = { TextButton(onClick = { onDelete(); confirmDelete = false; onDismiss() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
