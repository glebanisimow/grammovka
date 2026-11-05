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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.parse.VoiceText
import kotlinx.coroutines.launch
import app.grammovka.data.repo.TemplateView
import app.grammovka.domain.model.Meal
import app.grammovka.ui.common.MealChips
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateMapOf
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
    onOpenPending: () -> Unit,
) {
    val vm: TodayViewModel = viewModel(factory = viewModelFactory { initializer { TodayViewModel(container) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var weightDialog by remember { mutableStateOf(false) }
    var editEntry by remember { mutableStateOf<LogEntryEntity?>(null) }
    var templatesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        container.debugInput.collect { text ->
            if (text != null) {
                container.debugInput.value = null
                // «voice:…» simulates a final speech result (debug builds only).
                if (text.startsWith("voice:")) {
                    vm.submit(VoiceText.toInput(text.removePrefix("voice:")), onReview, InputMethod.VOICE) {}
                } else {
                    vm.submit(text, onReview) {}
                }
            }
        }
    }
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
                if (ui.pending > 0) {
                    item {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Notice("Отложено до сети: ${ui.pending}. Claude разберёт, вы подтвердите.", "Разобрать", onOpenPending)
                        }
                    }
                }
                if (ui.offerReminder) item { ReminderOffer(vm) }
                if (ui.chips.isNotEmpty() || ui.repeats.isNotEmpty() || ui.templates.isNotEmpty() || ui.entries.isNotEmpty()) {
                    item { Chips(ui, vm, onTemplates = { templatesDialog = true }) }
                }
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
            InputBar(
                busy,
                focusSignal = container.widgetAction,
                onSubmit = { text, method, clear -> vm.submit(text, { r -> clear(); onReview(r) }, method) { clear() } },
                onScan = { onScan(ui.date) }, onLabel = { onLabel(ui.date) },
                onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg, duration = SnackbarDuration.Short) } },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    if (weightDialog) WeightDialog(ui.weightKg, onDismiss = { weightDialog = false }) { vm.setWeight(it); weightDialog = false }
    editEntry?.let { e ->
        EntryDialog(
            e, onDismiss = { editEntry = null },
            onSave = { g, meal ->
                if (g != e.grams) vm.updateGrams(e.copy(meal = meal), g) else if (meal != e.meal) vm.setMeal(e, meal)
                editEntry = null
            },
            onDelete = { vm.deleteEntry(e); editEntry = null },
        )
    }
    if (templatesDialog) {
        TemplatesDialog(
            ui.entries, ui.templates, onDismiss = { templatesDialog = false },
            onSave = { name, list -> vm.saveTemplate(name, list) }, onDelete = vm::deleteTemplate,
        )
    }
}

@Composable
private fun DayHeader(date: LocalDate, shift: (Long) -> Unit) {
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { shift(-1) }) { Icon(GIcons.Prev, "Предыдущий день") }
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

/** Contextual permission request: notifications are asked for only when the user turns the question on. */
@Composable
private fun ReminderOffer(vm: TodayViewModel) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.answerReminderOffer(granted) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Спрашивать утром «вчера всё записано?»", style = MaterialTheme.typography.titleSmall)
        Text(
            "В 9:00 придёт уведомление с кнопками «да» и «нет — неполный». Неполные дни модель не учитывает. Время меняется в настройках.",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
        Row {
            TextButton(onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.answerReminderOffer(true)
            }) { Text("Включить") }
            TextButton(onClick = { vm.answerReminderOffer(false) }) { Text("Не нужно") }
        }
    }
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

/** One-tap shortcuts: repeat yesterday's meals, templates, recent foods; the last chip manages templates. */
@Composable
private fun Chips(ui: TodayUi, vm: TodayViewModel, onTemplates: () -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ui.repeats, key = { "r" + it.meal.name }) { r ->
            SuggestionChip(
                onClick = { vm.repeatMeal(r) },
                icon = { Icon(GIcons.Repeat, null, Modifier.size(16.dp)) },
                label = { Text("${if (ui.date == LocalDate.now()) "вчерашний" else "прошлый"} ${r.meal.ru} · ${Fmt.kcal(r.kcal)} ккал") },
                shape = MaterialTheme.shapes.small,
            )
        }
        items(ui.templates, key = { "t" + it.id }) { t ->
            SuggestionChip(
                onClick = { vm.logTemplate(t) },
                icon = { Icon(GIcons.Bookmark, null, Modifier.size(16.dp)) },
                label = { Text("${t.name} · ${Fmt.kcal(t.kcal)} ккал") },
                shape = MaterialTheme.shapes.small,
            )
        }
        items(ui.chips, key = { "f" + it.foodId }) { c ->
            SuggestionChip(onClick = { vm.repeat(c) }, label = { Text("${c.name} · ${Fmt.kcal(c.grams)} г") }, shape = MaterialTheme.shapes.small)
        }
        item(key = "manage") {
            SuggestionChip(
                onClick = onTemplates,
                icon = { Icon(GIcons.Plus, null, Modifier.size(16.dp)) },
                label = { Text("шаблон") },
                shape = MaterialTheme.shapes.small,
            )
        }
    }
}

@Composable
private fun TemplatesDialog(
    entries: List<LogEntryEntity>,
    templates: List<TemplateView>,
    onDismiss: () -> Unit,
    onSave: (String, List<LogEntryEntity>) -> Unit,
    onDelete: (TemplateView) -> Unit,
) {
    val usable = entries.filter { it.foodId != null }
    var name by remember { mutableStateOf("") }
    val checked = remember { mutableStateMapOf<Long, Boolean>().apply { usable.forEach { put(it.id, true) } } }
    var confirm by remember { mutableStateOf<TemplateView?>(null) }
    val chosen = usable.filter { checked[it.id] == true }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Шаблоны") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (usable.isEmpty()) {
                    Text("Запишите еду за день, и её можно будет сохранить как шаблон.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Новый шаблон из записей дня", style = MaterialTheme.typography.titleSmall)
                    usable.forEach { e ->
                        Row(Modifier.fillMaxWidth().clickable { checked[e.id] = checked[e.id] != true }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked[e.id] == true, onCheckedChange = { checked[e.id] = it })
                            Text("${e.foodName} · ${Fmt.kcal(e.grams)} г", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Название, например «Завтрак будний»") })
                    TextButton(
                        onClick = { onSave(name, chosen); name = "" },
                        enabled = name.isNotBlank() && chosen.isNotEmpty(),
                    ) { Text("Сохранить шаблон · ${chosen.size}") }
                }
                if (templates.isNotEmpty()) {
                    Text("Сохранённые", style = MaterialTheme.typography.titleSmall)
                    templates.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    t.lines.joinToString(", ") { "${it.name} ${Fmt.kcal(it.grams)} г" },
                                    style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                                )
                            }
                            IconButton(onClick = { confirm = t }) { Icon(GIcons.Trash, "Удалить шаблон", Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Удалить «${t.name}»?") },
            text = { Text("Записи, сделанные по шаблону, останутся.") },
            confirmButton = { TextButton(onClick = { onDelete(t); confirm = null }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
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
            Text(listOfNotNull(e.meal?.ru, "${Fmt.kcal(e.grams)} г", "Б ${Fmt.grams(e.protein)}").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
        }
        Text(Fmt.kcal(e.kcal), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun InputBar(
    busy: Boolean,
    focusSignal: kotlinx.coroutines.flow.MutableStateFlow<String?>,
    onSubmit: (String, InputMethod, () -> Unit) -> Unit,
    onScan: () -> Unit,
    onLabel: () -> Unit,
    onMessage: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = { if (text.isNotBlank()) onSubmit(text, InputMethod.TEXT) { text = "" } }
    val voice = rememberVoiceController()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val signal by focusSignal.collectAsStateWithLifecycle()
    LaunchedEffect(signal) {
        if (signal == app.grammovka.widget.GrammovkaWidget.ACTION_ADD) {
            focusSignal.value = null
            focus.requestFocus()
            keyboard?.show()
        }
    }
    val context = LocalContext.current
    val listen = {
        voice.start(
            onFinal = { spoken ->
                text = VoiceText.toInput(spoken)
                onSubmit(text, InputMethod.VOICE) { text = "" }
            },
            onError = onMessage,
        )
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else onMessage("Без доступа к микрофону голосовой ввод не работает. Разрешение можно дать в настройках Android.")
    }
    val onMic = {
        when {
            voice.listening -> voice.stop()
            !voice.available -> onMessage("На телефоне нет службы распознавания речи.")
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = if (voice.listening) voice.partial else text, onValueChange = { text = it },
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(if (voice.listening) "Говорите: «творог 150 и банан»" else "творог 150, кофе с молоком…") },
            maxLines = 3,
            enabled = !busy && !voice.listening,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            trailingIcon = if (text.isNotBlank()) ({ IconButton(onClick = send) { Icon(GIcons.Check, "Записать", tint = GTheme.extra.accentText) } }) else null,
        )
        IconButton(onClick = onMic, Modifier.size(48.dp)) {
            Icon(
                GIcons.Mic, if (voice.listening) "Остановить запись" else "Голосовой ввод",
                tint = if (voice.listening) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
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
private fun EntryDialog(e: LogEntryEntity, onDismiss: () -> Unit, onSave: (Double, Meal?) -> Unit, onDelete: () -> Unit) {
    var v by remember { mutableStateOf(e.grams.input()) }
    var meal by remember { mutableStateOf(e.meal) }
    val g = v.toDecimal()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.foodName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(v, { v = it }, "Вес", suffix = "г", imeAction = ImeAction.Done)
                Text("Калории пересчитаются из сохранённых в записи цифр.", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
                MealChips(meal, { meal = it })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(g!!, meal) }, enabled = g != null && g > 0) { Text("Сохранить") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
