package app.grammovka.domain.log

import app.grammovka.domain.model.CatalogFood
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.model.Nutrition
import app.grammovka.domain.parse.AskReason
import app.grammovka.domain.parse.PortionDef
import app.grammovka.domain.text.Normalize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLogPlannerTest {
    private fun food(id: Long, name: String, vararg aliases: String) = CatalogFood(
        id, name, null, aliases.toList(), Nutrients(100.0, 10.0, 2.0, 10.0), FoodSource.MANUAL, null, 0, null,
    )

    /** In-memory stand-in for FTS: every query token must prefix some token of name/aliases. */
    private class FakeCatalog(val foods: List<CatalogFood>, val portions: Map<Long, List<PortionDef>>) : CatalogPort {
        override suspend fun search(query: String, limit: Int): List<CatalogFood> {
            val q = Normalize.tokens(query)
            return foods.filter { f ->
                val toks = Normalize.tokens(Normalize.searchText(f.name, f.aliases))
                q.all { qt -> toks.any { it.startsWith(qt) } }
            }.take(limit)
        }

        override suspend fun portions(foodId: Long) = portions[foodId].orEmpty()
    }

    private val catalog = FakeCatalog(
        listOf(food(1, "Творог 5%"), food(2, "Яйцо куриное", "яйцо", "яйца"), food(3, "Груша азиатская"), food(4, "Груша конференция")),
        mapOf(2L to listOf(PortionDef("шт", 55.0))),
    )
    private val planner = QuickLogPlanner(catalog)

    @Test
    fun allKnownLogsDirectly() = runBlocking {
        val plan = planner.plan("творог 5% 150, яйцо 2")
        assertTrue(plan.canLogDirectly)
        val ready = plan.items.map { it as PlanItem.Ready }
        assertEquals(listOf(150.0, 110.0), ready.map { it.grams })
        assertEquals(2.0, ready[1].pieces)
    }

    @Test
    fun unknownAndAmbiguousGoToReview() = runBlocking {
        val plan = planner.plan("творог 5% 150, кофе с молоком, груша 200")
        assertFalse(plan.canLogDirectly)
        assertTrue(plan.items[0] is PlanItem.Ready)
        assertTrue(plan.items[1] is PlanItem.Unknown)
        assertEquals(2, (plan.items[2] as PlanItem.Ambiguous).candidates.size)
    }

    @Test
    fun wordOrderDoesNotMatter() = runBlocking {
        val item = planner.plan("азиатская груша 230").items.single()
        assertEquals(3L, (item as PlanItem.Ready).food.id)
    }

    @Test
    fun missingWeightIsAsked() = runBlocking {
        val item = planner.plan("творог 5% 2 ст. л.").items.single()
        assertEquals(AskReason.NO_SPOON_PORTION, (item as PlanItem.NeedsGrams).reason)
    }

    @Test
    fun nutritionMath() {
        val a = Nutrition.forGrams(Nutrients(121.0, 17.0, 5.0, 1.8), 150.0)
        assertEquals(181.5, a.kcal, 1e-9)
        assertEquals(25.5, a.protein, 1e-9)
        val per100 = Nutrition.per100FromServing(Nutrients(90.0, 3.0, 1.0, 17.0), 30.0)
        assertEquals(300.0, per100.kcal, 1e-9)
        assertEquals(100.0, Nutrition.kcalFromKj(418.4), 1e-9)
    }
}
