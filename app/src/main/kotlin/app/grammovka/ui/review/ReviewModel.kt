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
import app.grammovka.domain.model.Meal
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.model.Nutrition
import app.grammovka.domain.parse.Fragment
import app.grammovka.domain.parse.GramResolver
import app.grammovka.domain.parse.Grams
import app.grammovka.ui.catalog.FoodFormState
import app.grammovka.ui.common.input
import app.grammovka.ui.common.toDecimal
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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

/** Adds the user's own wording as a synonym so the same input matches exactly next time. */
private fun FoodDraft.withAlias(words: String): FoodDraft {
    val w = words.trim()
    if (w.isEmpty() || app.grammovka.domain.text.Normalize.text(w) == app.grammovka.domain.text.Normalize.text(name)) return this
    if (aliases.any { app.grammovka.domain.text.Normalize.text(it) == app.grammovka.domain.text.Normalize.text(w) }) return this
    return copy(aliases = aliases + w)
}

/** A plan or drafts handed from Today / scanner to the confirm screen. */
data class ReviewRequest(
    val date: LocalDate,
    val method: InputMethod,
    val plan: LogPlan? = null,
    val drafts: List<Pair<FoodDraft, String?>> = emptyList(),
    val grams: Double? = null,
    /** Per-draft grams (photo estimate); falls back to [grams]. */
    val draftGrams: List<Double?> = emptyList(),
    /** Deferred items this request came from; removed from the queue once logged. */
    val pendingIds: List<Long> = emptyList(),
    val pendingTime: LocalTime? = null,
    /** Card the user is adding to on «Сегодня»; a meal said in the text wins. */
    val meal: Meal? = null,
)

class ReviewInbox {
    var pending: ReviewRequest? = null
}

data class LoggedEvent(val ids: List<Long>, val count: Int, val kcal: Double)

/**
 * Global "logged, tap to undo" events shown as a snackbar on Today. A buffered channel keeps the event
 * while the confirm screen closes and Today starts collecting.
 */
class LogEvents {
    private val channel = Channel<LoggedEvent>(Channel.BUFFERED)
    val events: Flow<LoggedEvent> = channel.receiveAsFlow()
    fun emit(e: LoggedEvent) {
        channel.trySend(e)
    }
}

class ReviewViewModel(
    request: ReviewRequest?,
    private val foods: FoodRepository,
    private val logs: LogRepository,
    private val events: LogEvents,
    private val ai: AiService? = null,
    private val pending: app.grammovka.data.repo.PendingRepository? = null,
) : ViewModel() {
    private val pendingIds = request?.pendingIds.orEmpty()
    private val pendingTime = request?.pendingTime
    private val forToday = request?.date == null || request.date == LocalDate.now()
    private val time: LocalTime get() = pendingTime ?: if (forToday) {
        LocalTime.now().withSecond(0).withNano(0)
    } else {
        app.grammovka.domain.log.MealBlocks.defaultTime(meal)
    }

    /** Lines deferred until the network is back during this review. */
    var deferred by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    val date: LocalDate = request?.date ?: LocalDate.now()
    private val method = request?.method ?: InputMethod.TEXT

    /** Meal said in the input («завтрак: …»); the user can set or clear it here. */
    var meal by mutableStateOf(request?.plan?.meal ?: request?.meal)
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
        request?.drafts?.forEachIndexed { i, (d, note) ->
            val g = request.draftGrams.getOrNull(i) ?: request.grams
            items += ReviewItem.NewFood(Fragment(d.name, d.name), FoodFormState(d), note).also { it.grams = g?.input().orEmpty() }
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
        is PlanItem.Ambiguous -> {
            val only = p.candidates.singleOrNull()
            ReviewItem.Matched(p.fragment, p.candidates, only).also { m -> if (only != null) viewModelScope.launch { fillGrams(m, only) } }
        }
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
        n.grams = (GramResolver.resolve(item.fragment, emptyList()) as? Grams.Known)?.grams?.input() ?: item.grams
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

    /** Keeps the line's text in the offline queue and removes it from this review. */
    fun defer(item: ReviewItem.Unresolved) {
        val p = pending ?: return
        items.remove(item)
        deferred++
        viewModelScope.launch { p.add(date, time, item.fragment.raw, method, meal) }
    }

    fun deferAllUnresolved() = items.filterIsInstance<ReviewItem.Unresolved>().forEach(::defer)

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
        items.isEmpty() && deferred == 0 && pendingIds.isEmpty() -> "Нечего записывать."
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
                    is ReviewItem.Matched -> {
                        // The user picked this food for their own words: remember them as a synonym.
                        if (item.candidates.size > 1 || method == InputMethod.TEXT || method == InputMethod.VOICE) foods.learnAlias(item.selected!!.id, item.fragment.name)
                        NewEntry(item.selected!!.id, item.selected!!.displayName, grams, item.selected!!.per100, method, meal = meal)
                    }
                    is ReviewItem.NewFood -> {
                        val d = item.form.toDraft()!!.withAlias(item.fragment.name)
                        val id = if (item.save) foods.create(d) else null
                        NewEntry(id, listOfNotNull(d.name, d.brand).joinToString(", "), grams, d.per100, method, meal = meal)
                    }
                    is ReviewItem.Unresolved -> error("unreachable")
                }
            }
            val ids = try {
                if (entries.isEmpty()) emptyList() else logs.log(date, time, entries)
            } catch (e: app.grammovka.data.repo.DayClosedException) {
                // E.g. a deferred line from a day that has been closed since.
                error = "${app.grammovka.ui.today.dayShort(date).replaceFirstChar { it.uppercase() }} закрыт. Откройте этот день для правки на «Сегодня» (меню ⋯) и запишите снова."
                busy = false
                return@launch
            }
            if (pendingIds.isNotEmpty()) pending?.delete(pendingIds)
            if (ids.isNotEmpty()) events.emit(LoggedEvent(ids, ids.size, entries.sumOf { Nutrition.forGrams(it.per100, it.grams).kcal }))
            busy = false
            onDone()
        }
    }
}
