package app.grammovka.domain.log

import app.grammovka.domain.model.Meal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class MealBlocksTest {
    private data class E(val name: String, val t: String, val m: Meal?)

    private fun group(vararg e: E) = MealBlocks.group(e.toList(), { LocalTime.parse(it.t) }, { it.m })
    private fun shape(b: List<MealBlock<E>>) = b.map { blk -> blk.meal to blk.items.map { it.name } }

    @Test fun mainMealsAreOneCardEach() {
        val b = group(E("овсянка", "08:00", Meal.BREAKFAST), E("кофе", "08:20", Meal.BREAKFAST), E("суп", "14:00", Meal.LUNCH))
        assertEquals(listOf(Meal.BREAKFAST to listOf("овсянка", "кофе"), Meal.LUNCH to listOf("суп")), shape(b))
    }

    @Test fun snacksSplitByMainMeals() {
        val b = group(
            E("овсянка", "08:00", Meal.BREAKFAST), E("яблоко", "11:00", null), E("орехи", "11:30", Meal.SNACK),
            E("суп", "14:00", Meal.LUNCH), E("батончик", "17:00", null), E("ужин", "20:00", Meal.DINNER),
        )
        assertEquals(
            listOf(
                Meal.BREAKFAST to listOf("овсянка"), Meal.SNACK to listOf("яблоко", "орехи"),
                Meal.LUNCH to listOf("суп"), Meal.SNACK to listOf("батончик"), Meal.DINNER to listOf("ужин"),
            ),
            shape(b),
        )
    }

    @Test fun lateAdditionToBreakfastStaysInBreakfast() {
        val b = group(E("овсянка", "08:00", Meal.BREAKFAST), E("яблоко", "10:00", null), E("сыр", "10:30", Meal.BREAKFAST))
        assertEquals(listOf(Meal.BREAKFAST to listOf("овсянка", "сыр"), Meal.SNACK to listOf("яблоко")), shape(b))
    }

    @Test fun onlySnacksIsOneCard() {
        assertEquals(listOf(Meal.SNACK to listOf("a", "b")), shape(group(E("a", "09:00", null), E("b", "16:00", null))))
    }

    @Test fun emptyDay() = assertEquals(emptyList<Any>(), group())

    @Test fun defaultTargetIsRecentCardElseSnack() {
        val b = group(E("суп", "14:00", Meal.LUNCH), E("хлеб", "14:10", Meal.LUNCH))
        val t: (E) -> LocalTime = { LocalTime.parse(it.t) }
        assertEquals(Meal.LUNCH, MealBlocks.defaultTarget(b, t, LocalTime.parse("15:00")))
        assertEquals(Meal.SNACK, MealBlocks.defaultTarget(b, t, LocalTime.parse("16:00")))
        assertEquals(Meal.SNACK, MealBlocks.defaultTarget(emptyList(), t, LocalTime.parse("16:00")))
    }
}
