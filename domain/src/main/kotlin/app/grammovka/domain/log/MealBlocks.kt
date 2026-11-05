package app.grammovka.domain.log

import app.grammovka.domain.model.Meal
import java.time.LocalTime

/** A meal card on the day screen: [meal] is never null (unmarked entries are snacks). */
data class MealBlock<T>(val meal: Meal, val start: LocalTime, val items: List<T>)

/**
 * Day structure (user decision 2026-11-05): breakfast, lunch and dinner are one card each per day; anything eaten
 * in between is a snack. Snack entries (marked SNACK or unmarked) form a separate card for every gap between main
 * meals, so a morning snack and an afternoon snack stay apart. Cards are ordered by their first entry.
 */
object MealBlocks {

    fun <T> group(items: List<T>, time: (T) -> LocalTime, meal: (T) -> Meal?): List<MealBlock<T>> {
        val sorted = items.sortedBy(time)
        val main = sorted.filter { meal(it).isMain() }.groupBy { meal(it)!! }
            .map { (m, list) -> MealBlock(m, time(list.first()), list) }
        val starts = main.map { it.start }.sorted()
        val snacks = sorted.filter { !meal(it).isMain() }
            .groupBy { t -> starts.count { !it.isAfter(time(t)) } }
            .map { (_, list) -> MealBlock(Meal.SNACK, time(list.first()), list) }
        return (main + snacks).sortedWith(compareBy({ it.start }, { it.meal.ordinal }))
    }

    /**
     * Where the input line writes when the user has not picked a card: the card whose last entry was at most
     * [windowMinutes] ago, otherwise a snack (spec decision: unspecified intake between meals is a snack).
     */
    fun <T> defaultTarget(blocks: List<MealBlock<T>>, time: (T) -> LocalTime, now: LocalTime, windowMinutes: Long = 90): Meal {
        val recent = blocks.mapNotNull { b -> b.items.maxOfOrNull(time)?.let { b to it } }
            .filter { (_, last) -> !last.isAfter(now) && java.time.Duration.between(last, now).toMinutes() <= windowMinutes }
            .maxByOrNull { it.second }
        return recent?.first?.meal ?: Meal.SNACK
    }

    /** Time for entries added to a past day, so the cards keep their natural order. */
    fun defaultTime(meal: Meal?): LocalTime = when (meal) {
        Meal.BREAKFAST -> LocalTime.of(8, 0)
        Meal.LUNCH -> LocalTime.of(13, 0)
        Meal.DINNER -> LocalTime.of(19, 0)
        Meal.SNACK, null -> LocalTime.of(16, 0)
    }

    private fun Meal?.isMain() = this != null && this != Meal.SNACK
}
