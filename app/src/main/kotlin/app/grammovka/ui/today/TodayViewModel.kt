package app.grammovka.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.grammovka.data.db.LogEntryEntity
import app.grammovka.data.db.UsedFoodRow
import app.grammovka.data.repo.DayModel
import app.grammovka.data.repo.NewEntry
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

data class TodayUi(
    val date: LocalDate = LocalDate.now(),
    val entries: List<LogEntryEntity> = emptyList(),
    val eaten: Amount = Amount.ZERO,
    val model: DayModel? = null,
    val profileComplete: Boolean = true,
    val weightKg: Double? = null,
    val incomplete: Boolean = false,
    val chips: List<Chip> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {
    val date = MutableStateFlow(LocalDate.now())
    val busy = MutableStateFlow(false)

    private val dayData = date.flatMapLatest { d ->
        combine(c.logRepository.observeDay(d), c.weightRepository.observe(d), c.logRepository.observeStatus(d)) { e, w, inc ->
            Triple(e, w?.kg, inc)
        }
    }

    val ui: StateFlow<TodayUi> = combine(
        date, dayData, c.modelHub.state, c.settingsStore.settings, c.foodRepository.observeUsed(),
    ) { d, (entries, w, inc), model, settings, used ->
        TodayUi(
            date = d,
            entries = entries,
            eaten = entries.fold(Amount.ZERO) { a, e -> a + Amount(e.kcal, e.protein, e.fat, e.carbs) },
            model = model?.day(d),
            profileComplete = settings.profile.complete,
            weightKg = w,
            incomplete = inc,
            chips = chips(used),
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
                    val entries = items.map { NewEntry(it.food.id, it.food.displayName, it.grams, it.food.per100, InputMethod.TEXT) }
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
