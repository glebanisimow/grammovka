package app.grammovka.ui.review

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.grammovka.data.repo.FoodRepository
import app.grammovka.data.repo.LogRepository
import app.grammovka.data.repo.NewEntry
import app.grammovka.domain.log.LogPlan
import app.grammovka.domain.log.PlanItem
import app.grammovka.domain.model.CatalogFood
import app.grammovka.domain.model.FoodDraft
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.model.Nutrition
import app.grammovka.domain.parse.Fragment
import app.grammovka.domain.parse.GramResolver
import app.grammovka.domain.parse.Grams
import app.grammovka.ui.catalog.FoodFormState
import app.grammovka.ui.common.input
import app.grammovka.ui.common.toDecimal
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.grammovka.data.ai.AiService
import app.grammovka.data.ai.ClaudeException
import app.grammovka.data.ai.ParsedFragment
import app.grammovka.data.ai.userMessage
import app.grammovka.domain.parse.PortionDef
import java.time.LocalDate
import java.time.LocalTime

/** One line on the confirm screen. Each kind keeps Compose state so every field stays editable (invariant 2). */
sealed class ReviewItem(val fragment: Fragment) {
    var grams by mutableStateOf("")

    /** A catalog food (possibly picked from several candidates). Only grams are edited. */
    class Matched(fragment: Fragment, val candidates: List<CatalogFood>, selected: CatalogFood?) : ReviewItem(fragment) {
        var selected by mutableStateOf(selected)
    }

    /** A food that is not in the catalog yet: all fields editable, saved to the catalog if [save]. */
    class NewFood(fragment: Fragment, val form: FoodFormState, val note: String? = null) : ReviewItem(fragment) {
        var save by mutableStateOf(true)
    }

    /** Nothing matched; the user picks a candidate, enters it manually, asks Claude or drops it. */
    class Unresolved(fragment: Fragment, val candidates: List<CatalogFood>) : ReviewItem(fragment)
}

/** A plan or drafts handed from Today / scanner to the confirm screen. */
data class ReviewRequest(
    val date: LocalDate,
    val method: InputMethod,
    val plan: LogPlan? = null,
    val drafts: List<Pair<FoodDraft, String?>> = emptyList(),
    val grams: Double? = null,
)

class ReviewInbox {
    var pending: ReviewRequest? = null
}

data class LoggedEvent(val ids: List<Long>, val count: Int, val kcal: Double)

/** Global "logged, tap to undo" events shown as a snackbar on Today. */
class LogEvents {
    private val flow = MutableSharedFlow<LoggedEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LoggedEvent> = flow.asSharedFlow()
    fun emit(e: LoggedEvent) {
        flow.tryEmit(e)
    }
}

class ReviewViewModel(
    request: ReviewRequest?,
    private val foods: FoodRepository,
    private val logs: LogRepository,
    private val events: LogEvents,
    private val ai: AiService? = null,
) : ViewModel() {
    val date: LocalDate = request?.date ?: LocalDate.now()
    private val method = request?.method ?: InputMethod.TEXT
    val items = mutableStateListOf<ReviewItem>()
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** AI status line: null when idle. */
    var aiStatus by mutableStateOf<String?>(null)
    var aiRunning by mutableStateOf(false)
    var hasKey by mutableStateOf(false)
    private var aiJob: Job? = null

    init {
        request?.plan?.items?.forEach { items += fromPlan(it) }
        request?.drafts?.forEach { (d, note) ->
            items += ReviewItem.NewFood(Fragment(d.name, d.name), FoodFormState(d), note).also { it.grams = request.grams?.input().orEmpty() }
        }
        viewModelScope.launch {
            hasKey = ai?.hasKey() == true
            if (hasKey && items.any { it is ReviewItem.Unresolved }) resolveWithAi()
        }
    }

    /**
     * PARSE for unrecognized fragments (fast model, with up to 8 catalog candidates each), then RESOLVE
     * for whatever is still unknown. Results replace the lines as editable proposals.
     */
    fun resolveWithAi(only: ReviewItem.Unresolved? = null) {
        val svc = ai ?: return
        if (aiRunning) return
        val targets = only?.let(::listOf) ?: items.filterIsInstance<ReviewItem.Unresolved>()
        if (targets.isEmpty()) return
        aiRunning = true
        aiStatus = "Разбираю ввод…"
        aiJob = viewModelScope.launch {
            try {
                val parsed = svc.parse(targets.map { it.fragment to it.candidates })
                val toResolve = mutableListOf<Pair<ReviewItem.Unresolved, ParsedFragment>>()
                targets.forEachIndexed { i, item ->
                    val p = parsed.getOrNull(i) ?: return@forEachIndexed
                    val match = p.foodId?.let { id -> item.candidates.firstOrNull { it.id == id } }
                    if (match != null) {
                        val m = ReviewItem.Matched(item.fragment, item.candidates, match)
                        p.grams?.let { m.grams = it.input() }
                        replace(item, m)
                        if (m.grams.isBlank()) fillGrams(m, match, p.pieces)
                    } else {
                        toResolve += item to p
                    }
                }
                if (toResolve.isNotEmpty()) {
                    aiStatus = "Ищу в USDA, Open Food Facts и вебе…"
                    val proposals = svc.resolve(toResolve.map { it.second.nameRu.ifBlank { it.first.fragment.name } })
                    toResolve.forEachIndexed { i, (item, p) ->
                        val prop = proposals.getOrNull(i) ?: return@forEachIndexed
                        val grams = p.grams
                            ?: p.pieces?.let { n -> prop.portionG?.let { n * it } }
                            ?: (GramResolver.resolve(item.fragment, prop.draft.portions.map { PortionDef(it.first, it.second) }) as? Grams.Known)?.grams
                        replaceWithDrafts(item, listOf(prop.draft to prop.note), grams)
                    }
                }
                aiStatus = null
            } catch (e: ClaudeException) {
                aiStatus = e.userMessage()
            } catch (e: CancellationException) {
                aiStatus = "Поиск остановлен. Можно ввести продукт вручную."
                throw e
            } finally {
                aiRunning = false
            }
        }
    }

    fun stopAi() {
        aiJob?.cancel()
    }

    private fun fromPlan(p: PlanItem): ReviewItem = when (p) {
        is PlanItem.Ready -> ReviewItem.Matched(p.fragment, listOf(p.food), p.food).also { it.grams = p.grams.input() }
        is PlanItem.NeedsGrams -> ReviewItem.Matched(p.fragment, listOf(p.food), p.food)
        is PlanItem.Ambiguous -> ReviewItem.Matched(p.fragment, p.candidates, null)
        is PlanItem.Unknown -> ReviewItem.Unresolved(p.fragment, p.candidates)
    }

    fun select(item: ReviewItem.Matched, food: CatalogFood) {
        item.selected = food
        viewModelScope.launch { fillGrams(item, food) }
    }

    /** Turns an unresolved line into a catalog match. */
    fun pick(item: ReviewItem.Unresolved, food: CatalogFood) {
        val m = ReviewItem.Matched(item.fragment, item.candidates, food)
        replace(item, m)
        viewModelScope.launch { fillGrams(m, food) }
    }

    fun manual(item: ReviewItem.Unresolved) {
        val draft = FoodDraft(name = item.fragment.name, per100 = Nutrients(0.0, 0.0, 0.0, 0.0), source = app.grammovka.domain.model.FoodSource.MANUAL)
        val n = ReviewItem.NewFood(item.fragment, FoodFormState(draft).apply { kcal = ""; protein = ""; fat = ""; carbs = "" })
        n.grams = item.grams
        replace(item, n)
    }

    /** Replaces a line with AI or database proposals (each one still fully editable). */
    fun replaceWithDrafts(item: ReviewItem, drafts: List<Pair<FoodDraft, String?>>, grams: Double?) {
        val i = items.indexOf(item).takeIf { it >= 0 } ?: return
        items.removeAt(i)
        drafts.reversed().forEach { (d, note) ->
            items.add(i, ReviewItem.NewFood(item.fragment, FoodFormState(d), note).also { it.grams = grams?.input().orEmpty() })
        }
    }

    fun remove(item: ReviewItem) {
        items.remove(item)
    }

    private fun replace(old: ReviewItem, new: ReviewItem) {
        val i = items.indexOf(old)
        if (i >= 0) items[i] = new
    }

    private suspend fun fillGrams(item: ReviewItem.Matched, food: CatalogFood, pieces: Double? = null) {
        if (item.grams.isNotBlank()) return
        val portions = foods.portions(food.id)
        val fragment = if (pieces != null && item.fragment.quantity == null) {
            item.fragment.copy(quantity = pieces, unit = app.grammovka.domain.parse.QtyUnit.PIECE)
        } else {
            item.fragment
        }
        val g = GramResolver.resolve(fragment, portions)
        if (g is Grams.Known) item.grams = g.grams.input()
    }

    /** kcal of a line for the summary, or null while incomplete. All math via domain Nutrition. */
    fun kcalOf(item: ReviewItem): Double? {
        val g = item.grams.toDecimal()?.takeIf { it > 0 } ?: return null
        val per100 = when (item) {
            is ReviewItem.Matched -> item.selected?.per100
            is ReviewItem.NewFood -> item.form.nutrients
            is ReviewItem.Unresolved -> null
        } ?: return null
        return Nutrition.forGrams(per100, g).kcal
    }

    fun problem(): String? = when {
        items.isEmpty() -> "Нечего записывать."
        items.any { it is ReviewItem.Unresolved } -> "Есть нераспознанные позиции: выберите продукт, введите вручную или уберите."
        items.any { it is ReviewItem.Matched && it.selected == null } -> "Выберите продукт в каждой строке."
        items.any { it.grams.toDecimal()?.takeIf { g -> g > 0 } == null } -> "Укажите вес в каждой строке."
        else -> items.filterIsInstance<ReviewItem.NewFood>().firstNotNullOfOrNull { it.form.problem() }
    }

    fun commit(onDone: () -> Unit) {
        problem()?.let {
            error = it
            return
        }
        busy = true
        viewModelScope.launch {
            val entries = items.map { item ->
                val grams = item.grams.toDecimal()!!
                when (item) {
                    is ReviewItem.Matched -> NewEntry(item.selected!!.id, item.selected!!.displayName, grams, item.selected!!.per100, method)
                    is ReviewItem.NewFood -> {
                        val d = item.form.toDraft()!!
                        val id = if (item.save) foods.create(d) else null
                        NewEntry(id, listOfNotNull(d.name, d.brand).joinToString(", "), grams, d.per100, method)
                    }
                    is ReviewItem.Unresolved -> error("unreachable")
                }
            }
            val time = if (date == LocalDate.now()) LocalTime.now().withSecond(0).withNano(0) else LocalTime.NOON
            val ids = logs.log(date, time, entries)
            events.emit(LoggedEvent(ids, ids.size, entries.sumOf { Nutrition.forGrams(it.per100, it.grams).kcal }))
            busy = false
            onDone()
        }
    }
}
