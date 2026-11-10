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
import app.grammovka.domain.log.MealBlock
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
    onScan: (LocalDate, Meal) -> Unit,
    onLabel: (LocalDate, Meal) -> Unit,
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
    var cardMenu by remember { mutableStateOf<MealBlock<LogEntryEntity>?>(null) }
    /** Workout sheet: null = closed; [ActivitySheetState.editing] null = new workout. */
    var activitySheet by remember { mutableStateOf<ActivitySheetState?>(null) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var monthSheet by remember { mutableStateOf(false) }
    /** Close dialog: null = hidden; true = close and then start / move to today (the «not today» banner). */
    var closeDialog by remember { mutableStateOf<Boolean?>(null) }
    var reopenDialog by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        vm.refreshToday()
        onPauseOrDispose {}
    }
    val focusInput = { container.widgetAction.value = app.grammovka.widget.GrammovkaWidget.ACTION_ADD }

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

    LaunchedEffect(Unit) {
        container.debugActivity.collect { text ->
            if (text != null) {
                container.debugActivity.value = null
                activitySheet = ActivitySheetState(null, text)
            }
        }
    }
    LaunchedEffect(Unit) {
        vm.activityDeletions.collect { e ->
            val r = snackbar.showSnackbar("Удалено: ${e.type.ru.lowercase()}", actionLabel = "Отменить", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.restoreActivity(e)
        }
    }
    LaunchedEffect(Unit) {
        vm.deletions.collect { b ->
            val r = snackbar.showSnackbar("Удалено: ${b.meal.ru}", actionLabel = "Отменить", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) vm.restore(b)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { DayHeader(ui, onCalendar = { monthSheet = true }, onReopen = { reopenDialog = true }) }
                item { WeekStrip(ui, vm::select) }
                val open = ui.state == app.grammovka.domain.log.DayState.OPEN
                val closed = ui.state == app.grammovka.domain.log.DayState.CLOSED
                if (open && ui.date != ui.today) item { NotTodayBanner(ui) { closeDialog = true } }
                if (ui.state == app.grammovka.domain.log.DayState.NONE) {
                    item { NotStarted(ui) { vm.startDay(ui.date) } }
                } else {
                    item { Budget(ui, onOpenProfile, onWeight = { if (open) weightDialog = true }) }
                    if (open && ui.pending > 0) {
                        item {
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Notice("Отложено до сети: ${ui.pending}. Claude разберёт, вы подтвердите.", "Разобрать", onOpenPending)
                            }
                        }
                    }
                    if (open && ui.offerReminder) item { ReminderOffer(vm) }
                    if (open && (ui.chips.isNotEmpty() || ui.repeats.isNotEmpty() || ui.templates.isNotEmpty() || ui.entries.isNotEmpty())) {
                        item { Chips(ui, vm, onTemplates = { templatesDialog = true }) }
                    }
                    if (ui.entries.isEmpty() && ui.openCards.isEmpty()) {
                        item {
                            Text(
                                if (open) "Записей нет. Добавьте приём пищи или просто введите внизу, что съели: «творог 5% 150, кофе с молоком»."
                                else "Записей нет.",
                                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                            )
                        }
                    }
                    // The snack card that a new snack would join: the last one, if no main meal started after it.
                    val lastMainStart = ui.blocks.filter { it.meal != Meal.SNACK }.maxOfOrNull { it.start }
                    val liveSnack = ui.blocks.lastOrNull { it.meal == Meal.SNACK }?.takeIf { s -> lastMainStart == null || s.start.isAfter(lastMainStart) }
                    val cards: List<Pair<java.time.LocalTime, Any>> = (ui.blocks.map { it.start to it } + ui.openCards.map { it.at to it }).sortedBy { it.first }
                    items(cards, key = { (_, c) -> if (c is OpenCard) "o" + c.meal else (c as MealBlock<*>).let { "b" + it.meal + (it.items.first() as LogEntryEntity).id } }) { (_, c) ->
                        if (c is OpenCard) {
                            EmptyMealCard(c, target = ui.target == c.meal, onAdd = { vm.pickTarget(c.meal); focusInput() }, onClose = { vm.closeCard(c.meal) })
                        } else {
                            @Suppress("UNCHECKED_CAST") val b = c as MealBlock<LogEntryEntity>
                            val key = "b" + b.meal + b.items.first().id
                            val isTarget = ui.target == b.meal && (b.meal != Meal.SNACK || b === liveSnack) && ui.openCards.none { it.meal == b.meal }
                            MealCard(
                                b, isTarget, collapsed[key] == true,
                                onToggle = { collapsed[key] = collapsed[key] != true },
                                onAdd = {
                                    // Adding to an earlier snack card: entries go in at its time so they land in it.
                                    vm.pickTarget(b.meal); focusInput()
                                },
                                onMenu = { cardMenu = b },
                                onEntry = { editEntry = it },
                                readOnly = closed,
                            )
                        }
                    }
                    if (ui.activities.isNotEmpty()) {
                        item { ActivityCard(ui.activities) { if (open) activitySheet = ActivitySheetState(it, null) } }
                    }
                    if (open) {
                        item {
                            Row {
                                AddMealButton { m -> vm.openCard(m); focusInput() }
                                Box(Modifier.padding(vertical = 8.dp)) {
                                    androidx.compose.material3.OutlinedButton(onClick = { activitySheet = ActivitySheetState(null, null) }, shape = MaterialTheme.shapes.small) {
                                        Icon(GIcons.Pulse, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Тренировка")
                                    }
                                }
                            }
                        }
                        item { CloseDayButton { closeDialog = false } }
                    }
                    if (closed) item { ClosedFooter(ui, onStartToday = { vm.startDay(ui.today) }, onGoToday = { vm.select(ui.today) }) }
                }
            }
            if (ui.state == app.grammovka.domain.log.DayState.OPEN) {
                TargetBar(ui.target, ui.targetPicked, vm::pickTarget)
                InputBar(
                    busy,
                    focusSignal = container.widgetAction,
                    onSubmit = { text, method, clear -> vm.submit(text, { r -> clear(); onReview(r) }, method) { clear() } },
                    onScan = { onScan(ui.date, ui.target) }, onLabel = { onLabel(ui.date, ui.target) },
                    onMessage = { msg -> scope.launch { snackbar.showSnackbar(msg, duration = SnackbarDuration.Short) } },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    activitySheet?.let { st ->
        ActivitySheet(
            editing = st.editing, body = ui.body, initialText = st.text,
            estimate = vm::estimateActivity,
            onDismiss = { activitySheet = null },
            onSave = { drafts -> vm.saveActivities(drafts, st.editing); activitySheet = null },
            onDelete = { vm.deleteActivity(it); activitySheet = null },
        )
    }
    if (monthSheet) MonthSheet(ui, onSelect = vm::select, onDismiss = { monthSheet = false })
    closeDialog?.let { thenToday ->
        CloseDayDialog(
            ui, thenToday,
            onClose = { incomplete -> vm.closeDay(incomplete, thenToday); closeDialog = null },
            onDismiss = { closeDialog = null },
        )
    }
    if (reopenDialog) ReopenDialog(ui, onConfirm = { vm.reopenDay(); reopenDialog = false }, onDismiss = { reopenDialog = false })
    if (weightDialog) WeightDialog(ui.weightKg, onDismiss = { weightDialog = false }) { vm.setWeight(it); weightDialog = false }
    editEntry?.let { e ->
        EntryDialog(
            e, onDismiss = { editEntry = null },
            onSave = { g, meal ->
                if (g != e.grams) vm.updateGrams(e.copy(meal = meal), g) else if (meal != (e.meal ?: Meal.SNACK)) vm.setMeal(e, meal)
                editEntry = null
            },
            onDelete = { vm.deleteEntry(e); editEntry = null },
        )
    }
    cardMenu?.let { b ->
        MealCardDialog(
            b, onDismiss = { cardMenu = null },
            onRetype = { vm.retypeCard(b, it) },
            onShift = { vm.shiftCard(b, it) },
            onTemplate = { name -> vm.saveCardTemplate(name, b); scope.launch { snackbar.showSnackbar("Шаблон «$name» сохранён", duration = SnackbarDuration.Short) } },
            onDelete = { vm.deleteCard(b) },
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
private fun Budget(ui: TodayUi, onOpenProfile: () -> Unit, onWeight: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val m = ui.model
        when {
            !ui.profileComplete -> Notice("Заполните профиль: пол, год рождения и рост нужны, чтобы считать бюджет.", "Открыть профиль", onOpenProfile)
            m == null -> Notice("Введите вес: с первого взвешивания появится цель на день.", "Ввести вес", onWeight)
            else -> {
                val b = m.balance(ui.eaten.kcal)
                val left = b.left
                if (ui.state == app.grammovka.domain.log.DayState.CLOSED) {
                    // A closed day shows its result: the balance against the day's expenditure.
                    // In words, not as a signed «баланс»: a minus sign alone does not say which way is good.
                    Text(
                        when {
                            b.balance < -0.5 -> "ДЕФИЦИТ ДНЯ"
                            b.balance > 0.5 -> "ПРОФИЦИТ ДНЯ"
                            else -> "ИТОГ ДНЯ"
                        },
                        style = MaterialTheme.typography.labelMedium, color = GTheme.extra.textMuted,
                    )
                    Text(
                        Fmt.kcal(abs(b.balance)), style = NumberHero,
                        color = if (b.balance > 0.5) MaterialTheme.colorScheme.error else GTheme.extra.success,
                    )
                    Text(
                        "съедено ${Fmt.kcal(ui.eaten.kcal)} − расход ≈ ${Fmt.kcal(b.expenditure)}" +
                            if (m.activity > 0) " (норма ${Fmt.kcal(m.snapshot.tdee)} + тренировки ${Fmt.kcal(m.activity)})" else "",
                        style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                    )
                    Text(
                        "Бюджет был ${Fmt.kcal(m.allowance + m.activity)}: " +
                            if (left >= 0) "осталось ${Fmt.kcal(left)}" else "сверх на ${Fmt.kcal(-left)}",
                        style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                    )
                } else {
                    Text(if (left >= 0) "ОСТАЛОСЬ" else "СВЕРХ БЮДЖЕТА", style = MaterialTheme.typography.labelMedium, color = GTheme.extra.textMuted)
                    Text(Fmt.kcal(abs(left)), style = NumberHero, color = if (left >= 0) GTheme.extra.accentText else MaterialTheme.colorScheme.error)
                    Text(
                        if (m.activity > 0) "из ${Fmt.kcal(m.allowance)} + ${Fmt.kcal(m.activity)} тренировки · съедено ${Fmt.kcal(ui.eaten.kcal)}"
                        else "из ${Fmt.kcal(m.allowance)} ккал · съедено ${Fmt.kcal(ui.eaten.kcal)}",
                        style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                    )
                    Text(
                        "Расход дня ≈ ${Fmt.kcal(b.expenditure)}" +
                            (if (m.activity > 0) " (норма ${Fmt.kcal(m.snapshot.tdee)} + тренировки ${Fmt.kcal(m.activity)})" else "") +
                            " · сейчас ${balanceWords(b.balance)}",
                        style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                    )
                }
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
        Text("Спрашивать утром «закрыть вчерашний день?»", style = MaterialTheme.typography.titleSmall)
        Text(
            "Если вчерашний день остался открытым, в 9:00 придёт уведомление: «закрыть» или «не полностью». Неполные дни норма не учитывает. Время меняется в настройках.",
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
            placeholder = { Text(if (voice.listening) "Говорите, в конце нажмите микрофон" else "творог 150, кофе с молоком…") },
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
    // Unmarked entries are shown as snacks, so the dialog says so too.
    var meal by remember { mutableStateOf(e.meal ?: Meal.SNACK) }
    val g = v.toDecimal()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.foodName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(v, { v = it }, "Вес", suffix = "г", imeAction = ImeAction.Done)
                Text("Калории пересчитаются из сохранённых в записи цифр.", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
                MealChips(meal, { meal = it ?: Meal.SNACK })
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

/** Open workout sheet: [editing] null for a new one; [text] pre-fills and runs the Claude estimate (debug hook). */
private data class ActivitySheetState(val editing: app.grammovka.data.db.ActivityEntryEntity?, val text: String?)

/** The day's workouts: net kcal on top of the norm; tap a row to edit. */
@Composable
private fun ActivityCard(items: List<app.grammovka.data.db.ActivityEntryEntity>, onOpen: (app.grammovka.data.db.ActivityEntryEntity) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(GIcons.Pulse, null, Modifier.size(18.dp), tint = GTheme.extra.textMuted)
            Spacer(Modifier.width(8.dp))
            Text("Тренировки", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("+${Fmt.kcal(items.sumOf { it.kcal })} ккал", style = MaterialTheme.typography.titleSmall, color = GTheme.extra.accentText)
        }
        items.forEach { a ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(a) }.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.note ?: a.type.ru, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        a.summary() + when (a.source) {
                            app.grammovka.domain.activity.ActivitySource.FORMULA -> " · формула"
                            app.grammovka.domain.activity.ActivitySource.CLAUDE_ESTIMATE -> " · оценка Claude"
                            app.grammovka.domain.activity.ActivitySource.MANUAL -> " · вручную"
                        },
                        style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                    )
                }
                Text(Fmt.kcal(a.kcal), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
