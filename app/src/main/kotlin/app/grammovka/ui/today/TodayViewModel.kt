package app.grammovka.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.grammovka.data.db.LogEntryEntity
import app.grammovka.data.db.UsedFoodRow
import app.grammovka.data.repo.DayModel
import app.grammovka.data.repo.NewEntry
import app.grammovka.data.repo.TemplateView
import app.grammovka.domain.log.DayLedger
import app.grammovka.domain.log.DayMark
import app.grammovka.domain.log.DayState
import app.grammovka.domain.log.MealBlock
import app.grammovka.domain.log.MealBlocks
import app.grammovka.domain.model.Meal
import app.grammovka.di.AppContainer
import app.grammovka.domain.model.Amount
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.model.Nutrients
import app.grammovka.ui.review.LoggedEvent
import app.grammovka.ui.review.ReviewRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class Chip(val foodId: Long, val name: String, val grams: Double)

/** «Повторить вчерашний завтрак»: a meal the previous day had (marked) and this day does not yet. */
data class RepeatMeal(val meal: Meal, val entries: List<LogEntryEntity>) {
    val kcal: Double get() = entries.sumOf { it.kcal }
}

/** One day in the week strip and the month calendar. [balance] is shown only for closed days. */
data class DayCell(val date: LocalDate, val state: DayState, val incomplete: Boolean, val selectable: Boolean, val balance: Double?, val eaten: Double?)

/** An empty card the user opened with «+ Приём пищи» and has not filled yet. */
data class OpenCard(val meal: Meal, val at: LocalTime)

data class TodayUi(
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    /** Lifecycle of [date]: only an OPEN day takes writes. */
    val state: DayState = DayState.NONE,
    val ledger: DayLedger = DayLedger(emptyMap(), LocalDate.now()),
    /** Cells from the first day's week through the end of this week. */
    val cells: Map<LocalDate, DayCell> = emptyMap(),
    val entries: List<LogEntryEntity> = emptyList(),
    val blocks: List<MealBlock<LogEntryEntity>> = emptyList(),
    val openCards: List<OpenCard> = emptyList(),
    /** Where the input line writes now; [targetPicked] is false when it follows the default rule. */
    val target: Meal = Meal.SNACK,
    val targetPicked: Boolean = false,
    val eaten: Amount = Amount.ZERO,
    val model: DayModel? = null,
    val profileComplete: Boolean = true,
    val weightKg: Double? = null,
    val incomplete: Boolean = false,
    val chips: List<Chip> = emptyList(),
    val pending: Int = 0,
    val offerReminder: Boolean = false,
    val repeats: List<RepeatMeal> = emptyList(),
    val templates: List<TemplateView> = emptyList(),
    val activities: List<app.grammovka.data.db.ActivityEntryEntity> = emptyList(),
    /** Body data for workout formulas; null without a profile or a weigh-in. */
    val body: app.grammovka.domain.activity.Body? = null,
    val sex: app.grammovka.domain.engine.Sex? = null,
    val ageYears: Int? = null,
)

private fun LogEntryEntity.mealOrSnack(): Meal = meal ?: Meal.SNACK

private const val PICK_MINUTES = 90L

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {
    val date = MutableStateFlow(LocalDate.now())
    val busy = MutableStateFlow(false)
    private val today = MutableStateFlow(LocalDate.now())
    private var placed = false

    private val ledger = combine(c.logRepository.observeStatusChanges(), today) { list, t ->
        DayLedger(list.associate { it.date to DayMark(it.startedAt != null, it.closedAt != null, it.incomplete) }, t)
    }

    init {
        // On launch: the latest open day, otherwise today (never an arbitrary past date).
        viewModelScope.launch {
            ledger.collect { l ->
                if (!placed) {
                    placed = true
                    date.value = l.defaultDay()
                }
            }
        }
    }

    /** On resume: a new calendar day moves «today»; the shown day stays (an open yesterday gets its banner). */
    fun refreshToday() {
        today.value = LocalDate.now()
    }

    /** Card picked by the user; it expires after [PICK_MINUTES] without writes, then the default rule applies. */
    private data class Pick(val date: LocalDate, val meal: Meal, val at: Long)
    private val pick = MutableStateFlow<Pick?>(null)
    private val opened = MutableStateFlow<Map<LocalDate, List<OpenCard>>>(emptyMap())

    private data class DayData(
        val entries: List<LogEntryEntity>, val kg: Double?, val incomplete: Boolean, val repeats: List<RepeatMeal>,
        val activities: List<app.grammovka.data.db.ActivityEntryEntity>,
    )

    private val dayData = date.flatMapLatest { d ->
        combine(
            c.logRepository.observeDay(d), c.weightRepository.observe(d), c.logRepository.observeStatus(d), c.logRepository.observeDay(d.minusDays(1)),
            c.activityRepository.observeDay(d),
        ) { e, w, inc, prev, acts ->
            val today = e.mapNotNull { it.meal }.toSet()
            // Snacks are not repeated: there can be several a day and they are rarely the same.
            val repeats = prev.filter { it.meal != null && it.meal != Meal.SNACK }.groupBy { it.meal!! }
                .filterKeys { it !in today }
                .toSortedMap().map { (m, list) -> RepeatMeal(m, list) }
            DayData(e, w?.kg, inc, repeats, acts)
        }
    }

    private data class Shortcuts(val used: List<UsedFoodRow>, val templates: List<TemplateView>, val pending: Int)

    private val shortcuts = combine(c.foodRepository.observeUsed(), c.templateRepository.observe(), c.pendingRepository.observeCount(), ::Shortcuts)

    private val cards = combine(pick, opened, ::Pair)

    private fun cells(l: DayLedger, model: app.grammovka.data.repo.ModelState?, t: LocalDate): Map<LocalDate, DayCell> {
        var d = DayLedger.weekStart(l.firstDay)
        val end = DayLedger.weekStart(t).plusDays(6)
        val out = LinkedHashMap<LocalDate, DayCell>()
        while (!d.isAfter(end)) {
            val st = l.state(d)
            val eaten = model?.days?.get(d)?.kcal
            val balance = if (st == DayState.CLOSED && eaten != null) model.day(d)?.balance(eaten)?.balance else null
            out[d] = DayCell(d, st, l.incomplete(d), l.selectable(d), balance, eaten)
            d = d.plusDays(1)
        }
        return out
    }

    val ui: StateFlow<TodayUi> = combine(
        combine(date, ledger, today, ::Triple), dayData, c.modelHub.state, c.settingsStore.settings, combine(shortcuts, cards, ::Pair),
    ) { (d, l, t), day, model, settings, (sc, cardState) ->
        val (entries, w, inc, repeats) = day
        val (used, templates, pending) = sc
        val (p, open) = cardState
        val blocks = MealBlocks.group(entries, { it.time }, { it.meal })
        val picked = p?.takeIf { it.date == d && System.currentTimeMillis() - it.at <= PICK_MINUTES * 60_000 }?.meal
        val dayModel = model?.day(d)
        val profile = settings.profile.toProfile(d)
        val body = if (profile != null && dayModel != null) {
            app.grammovka.domain.activity.Body(profile.sex, profile.ageYears, dayModel.snapshot.trendWeight, dayModel.snapshot.bmr)
        } else null
        val auto = if (d == LocalDate.now()) MealBlocks.defaultTarget(blocks, { it.time }, LocalTime.now()) else Meal.SNACK
        TodayUi(
            date = d,
            today = t,
            state = l.state(d),
            ledger = l,
            cells = cells(l, model, t),
            entries = entries,
            blocks = blocks,
            // An opened card disappears once something is written to it (a snack: after it was opened).
            openCards = open[d].orEmpty().filter { o -> entries.none { it.mealOrSnack() == o.meal && (o.meal != Meal.SNACK || !it.time.isBefore(o.at)) } },
            target = picked ?: auto,
            targetPicked = picked != null,
            eaten = entries.fold(Amount.ZERO) { a, e -> a + Amount(e.kcal, e.protein, e.fat, e.carbs) },
            model = dayModel,
            profileComplete = settings.profile.complete,
            weightKg = w,
            incomplete = inc,
            chips = chips(used),
            repeats = repeats,
            templates = templates,
            pending = pending,
            activities = day.activities,
            body = body,
            sex = profile?.sex,
            ageYears = profile?.ageYears,
            // Offered once, after the first day with entries: the question only makes sense then.
            offerReminder = !settings.reminder.offered && !settings.reminder.enabled && used.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUi())

    /** Recent first, then frequent; each with the grams of its last entry (spec: repeat in one tap). */
    private fun chips(used: List<UsedFoodRow>): List<Chip> {
        val withGrams = used.filter { it.lastGrams != null }
        val recent = withGrams.take(6)
        val frequent = withGrams.sortedByDescending { it.usageCount }.take(6)
        return (recent + frequent).distinctBy { it.foodId }.take(10).map { Chip(it.foodId, it.name, it.lastGrams!!) }
    }

    fun select(d: LocalDate) {
        if (ui.value.ledger.selectable(d)) date.value = d
    }

    /** «Начать день» for [d] (today, or a skipped past day the user fills in afterwards). */
    fun startDay(d: LocalDate) {
        viewModelScope.launch {
            c.logRepository.startDay(d)
            date.value = d
        }
    }

    /** Closes the shown day; [thenToday] also starts today (if not yet) and moves there. */
    fun closeDay(incomplete: Boolean, thenToday: Boolean) {
        val d = date.value
        viewModelScope.launch {
            c.logRepository.closeDay(d, incomplete)
            opened.value = opened.value - d
            if (thenToday) {
                val t = LocalDate.now()
                if (!c.logRepository.isClosed(t)) c.logRepository.startDay(t)
                date.value = t
            }
        }
    }

    fun reopenDay() {
        viewModelScope.launch { c.logRepository.reopenDay(date.value) }
    }

    private fun timeFor(d: LocalDate, meal: Meal? = ui.value.target): LocalTime =
        if (d == LocalDate.now()) LocalTime.now().withSecond(0).withNano(0) else MealBlocks.defaultTime(meal)

    private fun touchPick() {
        pick.value?.let { if (it.date == date.value) pick.value = it.copy(at = System.currentTimeMillis()) }
    }

    /** Input line and shortcuts write to this card until the user picks another or stops for a while. */
    fun pickTarget(meal: Meal) {
        pick.value = Pick(date.value, meal, System.currentTimeMillis())
    }

    /** «+ Приём пищи»: shows an empty card and directs input to it. */
    fun openCard(meal: Meal) {
        val d = date.value
        val at = timeFor(d, meal)
        val list = opened.value[d].orEmpty().filter { it.meal != meal } + OpenCard(meal, at)
        opened.value = opened.value + (d to list)
        pickTarget(meal)
    }

    fun closeCard(meal: Meal) {
        val d = date.value
        opened.value = opened.value + (d to opened.value[d].orEmpty().filter { it.meal != meal })
        if (pick.value?.meal == meal) pick.value = null
    }

    /** Offline fast path. Returns a review request when something needs confirmation. */
    fun submit(text: String, onReview: (ReviewRequest) -> Unit, method: InputMethod = InputMethod.TEXT, onLogged: () -> Unit) {
        if (text.isBlank() || busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val plan = c.quickLogPlanner.plan(text)
                val meal = plan.meal ?: ui.value.target
                touchPick()
                if (plan.canLogDirectly) {
                    val items = plan.items.map { it as app.grammovka.domain.log.PlanItem.Ready }
                    val entries = items.map { NewEntry(it.food.id, it.food.displayName, it.grams, it.food.per100, method, meal = meal) }
                    val ids = c.logRepository.log(date.value, timeFor(date.value, meal), entries)
                    c.logEvents.emit(LoggedEvent(ids, ids.size, entries.sumOf { app.grammovka.domain.model.Nutrition.forGrams(it.per100, it.grams).kcal }))
                    onLogged()
                } else {
                    onReview(ReviewRequest(date.value, method, plan, meal = meal))
                }
            } finally {
                busy.value = false
            }
        }
    }

    fun repeat(chip: Chip) {
        viewModelScope.launch {
            val f = c.foodRepository.byId(chip.foodId) ?: return@launch
            val per100 = Nutrients(f.kcal, f.protein, f.fat, f.carbs, f.fiber)
            val name = if (f.brand.isNullOrBlank()) f.name else "${f.name}, ${f.brand}"
            val meal = ui.value.target
            touchPick()
            val ids = c.logRepository.log(date.value, timeFor(date.value, meal), listOf(NewEntry(f.id, name, chip.grams, per100, InputMethod.QUICK, meal = meal)))
            c.logEvents.emit(LoggedEvent(ids, 1, app.grammovka.domain.model.Nutrition.forGrams(per100, chip.grams).kcal))
        }
    }

    fun repeatMeal(r: RepeatMeal) {
        viewModelScope.launch {
            val ids = c.logRepository.copy(r.entries, date.value, timeFor(date.value, r.meal))
            c.logEvents.emit(LoggedEvent(ids, ids.size, r.kcal))
        }
    }

    fun logTemplate(t: TemplateView) {
        viewModelScope.launch {
            val entries = c.templateRepository.entries(t.id)
            if (entries.isEmpty()) return@launch
            val meal = ui.value.target
            touchPick()
            val ids = c.logRepository.log(date.value, timeFor(date.value, meal), entries.map { it.copy(meal = meal) })
            c.logEvents.emit(LoggedEvent(ids, ids.size, entries.sumOf { app.grammovka.domain.model.Nutrition.forGrams(it.per100, it.grams).kcal }))
        }
    }

    fun saveTemplate(name: String, entries: List<LogEntryEntity>) {
        val items = entries.filter { it.foodId != null }.map { it.foodId!! to it.grams }
        if (name.isBlank() || items.isEmpty()) return
        viewModelScope.launch { c.templateRepository.create(name, items) }
    }

    fun deleteTemplate(t: TemplateView) {
        viewModelScope.launch { c.templateRepository.delete(t.id) }
    }

    fun setMeal(e: LogEntryEntity, meal: Meal?) {
        viewModelScope.launch { c.logRepository.setMeal(e, meal) }
    }

    /** Changes the type of a whole card (e.g. a snack that was really lunch). */
    fun retypeCard(b: MealBlock<LogEntryEntity>, meal: Meal) {
        viewModelScope.launch { b.items.forEach { c.logRepository.setMeal(it, meal) } }
    }

    fun shiftCard(b: MealBlock<LogEntryEntity>, newStart: LocalTime) {
        val minutes = java.time.Duration.between(b.start, newStart).toMinutes()
        if (minutes != 0L) viewModelScope.launch { c.logRepository.shiftTime(b.items, minutes) }
    }

    /** Deleted cards, for the «Отменить» snackbar. */
    val deletions = kotlinx.coroutines.flow.MutableSharedFlow<MealBlock<LogEntryEntity>>(extraBufferCapacity = 1)

    fun deleteCard(b: MealBlock<LogEntryEntity>) {
        viewModelScope.launch {
            c.logRepository.delete(b.items.map { it.id })
            closeCard(b.meal)
            deletions.emit(b)
        }
    }

    fun restore(b: MealBlock<LogEntryEntity>) {
        viewModelScope.launch { c.logRepository.restore(b.items) }
    }

    fun saveCardTemplate(name: String, b: MealBlock<LogEntryEntity>) = saveTemplate(name, b.items)

    /** Answer to the one-time offer of the morning question. */
    fun answerReminderOffer(enable: Boolean) {
        viewModelScope.launch {
            val r = c.settingsStore.current().reminder.copy(enabled = enable, offered = true)
            c.settingsStore.setReminder(r)
            app.grammovka.notify.Reminder.schedule(c.appContext, r)
        }
    }

    fun undo(ids: List<Long>) {
        viewModelScope.launch { c.logRepository.delete(ids) }
    }

    fun deleteEntry(e: LogEntryEntity) = undo(listOf(e.id))

    fun updateGrams(e: LogEntryEntity, grams: Double) {
        viewModelScope.launch { c.logRepository.updateGrams(e, grams) }
    }

    /** Claude reads a free-text workout description; the result only pre-fills the confirm sheet. */
    suspend fun estimateActivity(text: String): List<app.grammovka.data.ai.ActivityProposal> {
        val u = ui.value
        return c.aiService.activity(text, u.sex?.name, u.ageYears, u.body?.weightKg)
    }

    fun saveActivities(drafts: List<ActivityDraft>, editing: app.grammovka.data.db.ActivityEntryEntity?) {
        val d = date.value
        val body = ui.value.body
        val time = if (d == LocalDate.now()) LocalTime.now().withSecond(0).withNano(0) else LocalTime.of(12, 0)
        val items = drafts.mapNotNull { it.toEntity(editing, body, d, time) }
        if (items.isEmpty()) return
        viewModelScope.launch {
            if (editing != null) c.activityRepository.update(items.first()) else c.activityRepository.add(items)
        }
    }

    /** Deleted workout, for the «Отменить» snackbar. */
    val activityDeletions = kotlinx.coroutines.flow.MutableSharedFlow<app.grammovka.data.db.ActivityEntryEntity>(extraBufferCapacity = 1)

    fun deleteActivity(e: app.grammovka.data.db.ActivityEntryEntity) {
        viewModelScope.launch {
            c.activityRepository.delete(e.id)
            activityDeletions.emit(e)
        }
    }

    fun restoreActivity(e: app.grammovka.data.db.ActivityEntryEntity) {
        viewModelScope.launch { c.activityRepository.restore(e) }
    }

    fun setWeight(kg: Double?) {
        viewModelScope.launch {
            if (kg == null) c.weightRepository.delete(date.value) else c.weightRepository.setManual(date.value, kg)
        }
    }
}
