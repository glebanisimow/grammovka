package app.grammovka.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.grammovka.data.db.LogEntryEntity
import app.grammovka.di.AppContainer
import app.grammovka.domain.engine.Warning
import app.grammovka.domain.model.Fmt
import app.grammovka.ui.common.NumberField
import app.grammovka.ui.common.input
import app.grammovka.ui.common.toDecimal
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.review.ReviewRequest
import app.grammovka.ui.theme.GTheme
import app.grammovka.ui.theme.NumberHero
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))

@Composable
fun TodayScreen(
    container: AppContainer,
    onReview: (ReviewRequest) -> Unit,
    onScan: (LocalDate) -> Unit,
    onLabel: (LocalDate) -> Unit,
    onOpenProfile: () -> Unit,
) {
    val vm: TodayViewModel = viewModel(factory = viewModelFactory { initializer { TodayViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var weightDialog by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<LogEntryEntity?>(null) }

    LaunchedEffect(Unit) {
        container.logEvents.events.collect { e ->
            val r = snackbar.showSnackbar(
                "Записано: ${e.count} · ${Fmt.kcal(e.kcal)} ккал", actionLabel = "Отменить", duration = SnackbarDuration.Short,
            )
            if (r == SnackbarResult.ActionPerformed) vm.undo(e.ids)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { DayHeader(ui.date, vm::shiftDay) }
                item { Budget(ui, onOpenProfile, onWeight = { weightDialog = true }) }
                if (ui.chips.isNotEmpty()) item { Chips(ui.chips, vm::repeat) }
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setIncomplete(!ui.incomplete) }.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("День записан не полностью", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = ui.incomplete, onCheckedChange = vm::setIncomplete)
                    }
                }
                if (ui.entries.isEmpty()) {
                    item {
                        Text(
                            "Записей нет. Введите, что съели, в строке внизу: «творог 5% 150, кофе с молоком».",
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                        )
                    }
                }
                items(ui.entries, key = { it.id }) { e -> EntryRow(e) { editEntry = e } }
            }
            InputBar(busy, onSubmit = { text, clear -> vm.submit(text, { r -> clear(); onReview(r) }) { clear() } }, onScan = { onScan(ui.date) }, onLabel = { onLabel(ui.date) })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    if (weightDialog) WeightDialog(ui.weightKg, onDismiss = { weightDialog = false }) { vm.setWeight(it); weightDialog = false }
    editEntry?.let { e -> EntryDialog(e, onDismiss = { editEntry = null }, onSave = { vm.updateGrams(e, it); editEntry = null }, onDelete = { vm.deleteEntry(e); editEntry = null }) }
}

@Composable
private fun DayHeader(date: LocalDate, shift: (Long) -> Unit) {
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { shift(-1) }) { Icon(GIcons.Back, "Предыдущий день") }
        Text(
            when (date) {
                today -> "Сегодня"
                today.minusDays(1) -> "Вчера"
                else -> dayFmt.format(date).replaceFirstChar { it.uppercase() }
            },
            Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
        )
        if (date.isBefore(today)) IconButton(onClick = { shift(1) }) { Icon(GIcons.Next, "Следующий день") }
    }
}

@Composable
private fun Budget(ui: TodayUi, onOpenProfile: () -> Unit, onWeight: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val m = ui.model
        when {
            !ui.profileComplete -> Notice("Заполните профиль: пол, год рождения и рост нужны, чтобы считать бюджет.", "Открыть профиль", onOpenProfile)
            m == null -> Notice("Введите вес: с первого взвешивания появится цель на день.", "Ввести вес", onWeight)
            else -> {
                val left = m.allowance - ui.eaten.kcal
                Text(if (left >= 0) "ОСТАЛОСЬ" else "СВЕРХ БЮДЖЕТА", style = MaterialTheme.typography.labelMedium, color = GTheme.extra.textMuted)
                Text(Fmt.kcal(abs(left)), style = NumberHero, color = if (left >= 0) GTheme.extra.accentText else MaterialTheme.colorScheme.error)
                Text(
                    "из ${Fmt.kcal(m.allowance)} ккал · съедено ${Fmt.kcal(ui.eaten.kcal)}",
                    style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                )
                val p = m.snapshot.proteinTarget
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Белок", style = MaterialTheme.typography.titleSmall)
                    Text("${Fmt.kcal(ui.eaten.protein)} / ${Fmt.kcal(p)} г", style = MaterialTheme.typography.titleSmall)
                }
                LinearProgressIndicator(
                    progress = { (ui.eaten.protein / p).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
                Text(
                    "Жиры ${Fmt.kcal(ui.eaten.fat)} г · Углеводы ${Fmt.kcal(ui.eaten.carbs)} г",
                    style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                )
                Flags(m)
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onWeight).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(GIcons.Scale, null, tint = GTheme.extra.textMuted)
            Spacer(Modifier.width(12.dp))
            Text(
                ui.weightKg?.let { "Вес ${Fmt.kg(it)} кг" } ?: "Вес не введён",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            )
            m?.let { Text("тренд ${Fmt.kg(it.snapshot.trendWeight)}", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted) }
        }
    }
}

@Composable
private fun Flags(m: app.grammovka.data.repo.DayModel) {
    val lines = buildList {
        if (m.snapshot.capped) add("Дефицит ограничен 25 % расхода.")
        if (m.snapshot.bmrFloor) add("Цель поднята до уровня BMR (${Fmt.kcal(m.snapshot.bmr)} ккал).")
        m.warnings.forEach {
            add(
                when (it) {
                    Warning.RATE_TOO_HIGH -> "Темп в профиле выше 1 % массы в неделю."
                    Warning.LOSING_TOO_FAST -> "За 4 недели вес снижается в 1,5 раза быстрее цели. Можно есть больше."
                    Warning.PROTEIN_LOW -> "Белок ниже цели 5 из последних 7 дней."
                },
            )
        }
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = GTheme.extra.accentText) }
}

@Composable
private fun Notice(text: String, action: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) { Text(action, color = GTheme.extra.accentText) }
    }
}

@Composable
private fun Chips(chips: List<Chip>, onTap: (Chip) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips, key = { it.foodId }) { c ->
            SuggestionChip(onClick = { onTap(c) }, label = { Text("${c.name} · ${Fmt.kcal(c.grams)} г") }, shape = MaterialTheme.shapes.small)
        }
    }
}

@Composable
private fun EntryRow(e: LogEntryEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.time.toString().take(5), style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f)) {
            Text(e.foodName, style = MaterialTheme.typography.bodyLarge)
            Text("${Fmt.kcal(e.grams)} г · Б ${Fmt.grams(e.protein)}", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
        }
        Text(Fmt.kcal(e.kcal), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun InputBar(busy: Boolean, onSubmit: (String, () -> Unit) -> Unit, onScan: () -> Unit, onLabel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = { if (text.isNotBlank()) onSubmit(text) { text = "" } }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("творог 150, кофе с молоком…") },
            maxLines = 3,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = if (text.isNotBlank()) ({ IconButton(onClick = send) { Icon(GIcons.Check, "Записать", tint = GTheme.extra.accentText) } }) else null,
        )
        IconButton(onClick = onScan, Modifier.size(48.dp)) { Icon(GIcons.Barcode, "Сканер штрихкода") }
        IconButton(onClick = onLabel, Modifier.size(48.dp)) { Icon(GIcons.Camera, "Фото этикетки") }
    }
}

@Composable
private fun WeightDialog(current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var v by remember { mutableStateOf(current?.input().orEmpty()) }
    val kg = v.toDecimal()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Вес за день") },
        text = { NumberField(v, { v = it }, "Вес", suffix = "кг", imeAction = ImeAction.Done, isError = v.isNotEmpty() && (kg == null || kg !in 30.0..300.0)) },
        confirmButton = { TextButton(onClick = { onSave(kg) }, enabled = kg != null && kg in 30.0..300.0) { Text("Сохранить") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@Composable
private fun EntryDialog(e: LogEntryEntity, onDismiss: () -> Unit, onSave: (Double) -> Unit, onDelete: () -> Unit) {
    var v by remember { mutableStateOf(e.grams.input()) }
    val g = v.toDecimal()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.foodName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(v, { v = it }, "Вес", suffix = "г", imeAction = ImeAction.Done)
                Text("Калории пересчитаются из сохранённых в записи цифр.", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(g!!) }, enabled = g != null && g > 0) { Text("Сохранить") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
