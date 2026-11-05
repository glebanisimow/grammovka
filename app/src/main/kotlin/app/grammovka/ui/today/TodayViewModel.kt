package app.grammovka.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.grammovka.data.db.LogEntryEntity
import app.grammovka.data.db.UsedFoodRow
import app.grammovka.data.repo.DayModel
import app.grammovka.data.repo.NewEntry
import app.grammovka.data.repo.TemplateView
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

data class TodayUi(
    val date: LocalDate = LocalDate.now(),
    val entries: List<LogEntryEntity> = emptyList(),
    val eaten: Amount = Amount.ZERO,
    val model: DayModel? = null,
    val profileComplete: Boolean = true,
    val weightKg: Double? = null,
    val incomplete: Boolean = false,
    val chips: List<Chip> = emptyList(),
    val repeats: List<RepeatMeal> = emptyList(),
    val templates: List<TemplateView> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {
    val date = MutableStateFlow(LocalDate.now())
    val busy = MutableStateFlow(false)

    private data class DayData(val entries: List<LogEntryEntity>, val kg: Double?, val incomplete: Boolean, val repeats: List<RepeatMeal>)

    private val dayData = date.flatMapLatest { d ->
        combine(
            c.logRepository.observeDay(d), c.weightRepository.observe(d), c.logRepository.observeStatus(d), c.logRepository.observeDay(d.minusDays(1)),
        ) { e, w, inc, prev ->
            val today = e.mapNotNull { it.meal }.toSet()
            val repeats = prev.filter { it.meal != null }.groupBy { it.meal!! }
                .filterKeys { it !in today }
                .toSortedMap().map { (m, list) -> RepeatMeal(m, list) }
            DayData(e, w?.kg, inc, repeats)
        }
    }

    private val shortcuts = combine(c.foodRepository.observeUsed(), c.templateRepository.observe()) { used, t -> used to t }

    val ui: StateFlow<TodayUi> = combine(
        date, dayData, c.modelHub.state, c.settingsStore.settings, shortcuts,
    ) { d, (entries, w, inc, repeats), model, settings, (used, templates) ->
        TodayUi(
            date = d,
            entries = entries,
            eaten = entries.fold(Amount.ZERO) { a, e -> a + Amount(e.kcal, e.protein, e.fat, e.carbs) },
            model = model?.day(d),
            profileComplete = settings.profile.complete,
            weightKg = w,
            incomplete = inc,
            chips = chips(used),
            repeats = repeats,
            templates = templates,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUi())

    /** Recent first, then frequent; each with the grams of its last entry (spec: repeat in one tap). */
    private fun chips(used: List<UsedFoodRow>): List<Chip> {
        val withGrams = used.filter { it.lastGrams != null }
        val recent = withGrams.take(6)
        val frequent = withGrams.sortedByDescending { it.usageCount }.take(6)
        return (recent + frequent).distinctBy { it.foodId }.take(10).map { Chip(it.foodId, it.name, it.lastGrams!!) }
    }

    fun shiftDay(days: Long) {
        val next = date.value.plusDays(days)
        if (!next.isAfter(LocalDate.now())) date.value = next
    }

    private fun timeFor(d: LocalDate): LocalTime =
        if (d == LocalDate.now()) LocalTime.now().withSecond(0).withNano(0) else LocalTime.NOON

    /** Offline fast path. Returns a review request when something needs confirmation. */
    fun submit(text: String, onReview: (ReviewRequest) -> Unit, onLogged: () -> Unit) {
        if (text.isBlank() || busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val plan = c.quickLogPlanner.plan(text)
                if (plan.canLogDirectly) {
                    val items = plan.items.map { it as app.grammovka.domain.log.PlanItem.Ready }
                    val entries = items.map { NewEntry(it.food.id, it.food.displayName, it.grams, it.food.per100, InputMethod.TEXT, meal = plan.meal) }
                    val ids = c.logRepository.log(date.value, timeFor(date.value), entries)
                    c.logEvents.emit(LoggedEvent(ids, ids.size, entries.sumOf { app.grammovka.domain.model.Nutrition.forGrams(it.per100, it.grams).kcal }))
                    onLogged()
                } else {
                    onReview(ReviewRequest(date.value, InputMethod.TEXT, plan))
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
            val ids = c.logRepository.log(date.value, timeFor(date.value), listOf(NewEntry(f.id, name, chip.grams, per100, InputMethod.QUICK)))
            c.logEvents.emit(LoggedEvent(ids, 1, app.grammovka.domain.model.Nutrition.forGrams(per100, chip.grams).kcal))
        }
    }

    fun repeatMeal(r: RepeatMeal) {
        viewModelScope.launch {
            val ids = c.logRepository.copy(r.entries, date.value, timeFor(date.value))
            c.logEvents.emit(LoggedEvent(ids, ids.size, r.kcal))
        }
    }

    fun logTemplate(t: TemplateView) {
        viewModelScope.launch {
            val entries = c.templateRepository.entries(t.id)
            if (entries.isEmpty()) return@launch
            val ids = c.logRepository.log(date.value, timeFor(date.value), entries)
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

    fun undo(ids: List<Long>) {
        viewModelScope.launch { c.logRepository.delete(ids) }
    }

    fun deleteEntry(e: LogEntryEntity) = undo(listOf(e.id))

    fun updateGrams(e: LogEntryEntity, grams: Double) {
        viewModelScope.launch { c.logRepository.updateGrams(e, grams) }
    }

    fun setIncomplete(v: Boolean) {
        viewModelScope.launch { c.logRepository.setIncomplete(date.value, v) }
    }

    fun setWeight(kg: Double?) {
        viewModelScope.launch {
            if (kg == null) c.weightRepository.delete(date.value) else c.weightRepository.setManual(date.value, kg)
        }
    }
}
