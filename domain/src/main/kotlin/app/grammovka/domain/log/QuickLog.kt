package app.grammovka.domain.log

import app.grammovka.domain.model.CatalogFood
import app.grammovka.domain.model.Meal
import app.grammovka.domain.parse.AskReason
import app.grammovka.domain.parse.Fragment
import app.grammovka.domain.parse.GramResolver
import app.grammovka.domain.parse.Grams
import app.grammovka.domain.parse.InputParser
import app.grammovka.domain.parse.MealPrefix
import app.grammovka.domain.parse.PortionDef
import app.grammovka.domain.search.Candidate
import app.grammovka.domain.search.FoodRanker

/** Port implemented by the app's catalog repository (FTS search + portions). */
interface CatalogPort {
    /** FTS prefix search over names and aliases; archived foods excluded. */
    suspend fun search(query: String, limit: Int): List<CatalogFood>
    suspend fun portions(foodId: Long): List<PortionDef>
}

sealed interface PlanItem {
    val fragment: Fragment

    /** Food and weight known: can be logged without asking. */
    data class Ready(override val fragment: Fragment, val food: CatalogFood, val grams: Double, val pieces: Double?) : PlanItem

    /** Food known, weight not: pieces or spoons without a portion, or no quantity. */
    data class NeedsGrams(override val fragment: Fragment, val food: CatalogFood, val reason: AskReason) : PlanItem

    /** Several plausible foods; [candidates] are the top FTS hits, best first. */
    data class Ambiguous(override val fragment: Fragment, val candidates: List<CatalogFood>) : PlanItem

    /** Nothing in the catalog. [candidates] may hold weak hits for PARSE. */
    data class Unknown(override val fragment: Fragment, val candidates: List<CatalogFood>) : PlanItem
}

data class LogPlan(val items: List<PlanItem>, val meal: Meal? = null) {
    /** Spec: every fragment matched unambiguously with a known weight → log at once, with Undo. */
    val canLogDirectly: Boolean get() = items.isNotEmpty() && items.all { it is PlanItem.Ready }
}

/** Offline fast path: parse → FTS → rank → grams. */
class QuickLogPlanner(private val catalog: CatalogPort) {

    suspend fun plan(input: String): LogPlan {
        val (meal, rest) = MealPrefix.extract(input)
        return LogPlan(InputParser.parse(rest).map { plan(it) }, meal)
    }

    suspend fun plan(fragment: Fragment): PlanItem {
        val hits = catalog.search(fragment.name, CANDIDATES)
        val ranked = FoodRanker.rank(fragment.name, hits.map { Candidate(it.id, listOf(it.name) + it.aliases, it.usageCount, it.lastUsedAt) })
        val byId = hits.associateBy { it.id }
        val ordered = ranked.map { byId.getValue(it.candidate.foodId) }
        val match = FoodRanker.unambiguous(ranked)?.let { byId.getValue(it.foodId) }
            ?: return if (ordered.isEmpty()) PlanItem.Unknown(fragment, emptyList()) else PlanItem.Ambiguous(fragment, ordered)
        return when (val g = GramResolver.resolve(fragment, catalog.portions(match.id))) {
            is Grams.Known -> PlanItem.Ready(fragment, match, g.grams, g.pieces)
            is Grams.Ask -> PlanItem.NeedsGrams(fragment, match, g.reason)
        }
    }

    companion object {
        /** Spec: top-8 candidates per fragment go to PARSE. */
        const val CANDIDATES = 8
    }
}
