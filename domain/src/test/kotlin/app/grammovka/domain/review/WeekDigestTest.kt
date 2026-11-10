package app.grammovka.domain.review

import app.grammovka.domain.log.DayState
import app.grammovka.domain.model.Amount
import app.grammovka.domain.model.Meal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WeekDigestTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private val sunday = monday.plusDays(6)

    private fun entry(day: Int, hour: Int, name: String, kcal: Double, protein: Double = 10.0, meal: Meal? = null, grams: Double = 100.0) =
        DigestEntry(monday.plusDays(day.toLong()), LocalTime.of(hour, 0), name, grams, Amount(kcal, protein, 5.0, 20.0), meal)

    private fun input(day: Int, state: DayState = DayState.CLOSED, incomplete: Boolean = false, activity: Double = 0.0, weight: Double? = null) =
        DigestDayInput(monday.plusDays(day.toLong()), state, incomplete, tdee = 2600.0, target = 2100.0, proteinTarget = 150.0, activity = activity, weightKg = weight, trendKg = null)

    @Test fun dayBalanceMealsAndLateEating() {
        val d = WeekDigest.day(
            input(0, activity = 400.0),
            listOf(
                entry(0, 8, "Овсянка", 400.0, meal = Meal.BREAKFAST),
                entry(0, 13, "Плов", 900.0, meal = Meal.LUNCH),
                entry(0, 16, "Яблоко", 100.0),
                entry(0, 22, "Сыр", 300.0),
            ),
            today = sunday,
        )
        assertEquals(DigestStatus.COUNTED, d.status)
        assertEquals(1700.0, d.eaten.kcal, 0.0)
        assertEquals(3000.0, d.expenditure!!, 0.0)
        assertEquals(-1300.0, d.balance!!, 0.0)
        assertEquals(mapOf(Meal.BREAKFAST to 400.0, Meal.LUNCH to 900.0, Meal.SNACK to 400.0), d.mealKcal)
        assertEquals(300.0, d.lateKcal, 0.0)
        assertEquals(LocalTime.of(8, 0), d.firstMeal)
        assertEquals(LocalTime.of(22, 0), d.lastMeal)
    }

    @Test fun statuses() {
        val e = listOf(entry(6, 9, "Каша", 300.0))
        assertEquals(DigestStatus.IN_PROGRESS, WeekDigest.day(input(6, DayState.OPEN), e, sunday).status)
        assertEquals(DigestStatus.COUNTED, WeekDigest.day(input(6, DayState.CLOSED), e, sunday).status)
        // An open past day (days migrated before the lifecycle existed) still counts.
        assertEquals(DigestStatus.COUNTED, WeekDigest.day(input(6, DayState.OPEN), e, sunday.plusDays(1)).status)
        assertEquals(DigestStatus.INCOMPLETE, WeekDigest.day(input(6, incomplete = true), e, sunday).status)
        val empty = WeekDigest.day(input(6), emptyList(), sunday)
        assertEquals(DigestStatus.EMPTY, empty.status)
        assertNull(empty.balance)
    }

    @Test fun totalsUseCountedDaysOnly() {
        val days = listOf(
            WeekDigest.day(input(0, weight = 85.0), listOf(entry(0, 9, "a", 2000.0, protein = 160.0)), sunday),
            WeekDigest.day(input(1, activity = 500.0), listOf(entry(1, 9, "a", 2500.0, protein = 100.0)), sunday), // 2500 − 500 ≤ 2100
            WeekDigest.day(input(2, incomplete = true), listOf(entry(2, 9, "a", 500.0)), sunday),
            WeekDigest.day(input(3), emptyList(), sunday),
            WeekDigest.day(input(5), listOf(entry(5, 9, "a", 3000.0, protein = 150.0)), sunday),
            WeekDigest.day(input(6, DayState.OPEN), listOf(entry(6, 9, "a", 700.0)), sunday),
        )
        val t = WeekDigest.totals(monday, days, mapOf(monday.minusDays(1) to 85.4, monday.plusDays(2) to 85.1, monday.plusDays(5) to 84.9))
        assertEquals(3, t.countedDays)
        assertEquals(2500.0, t.avgKcal!!, 1e-9)
        assertEquals(1, t.daysOverTarget)
        assertEquals(2, t.daysProteinMet)
        assertEquals(500.0, t.workoutKcal, 0.0)
        assertEquals(2250.0, t.weekdayAvgKcal!!, 1e-9)
        assertEquals(3000.0, t.weekendAvgKcal!!, 1e-9)
        // (2000 − 2600) + (2500 − 3100) + (3000 − 2600)
        assertEquals(-800.0, t.sumBalance!!, 1e-9)
        assertEquals(1, t.weighIns)
        assertEquals(-0.5, t.trendChange!!, 1e-9)
    }

    @Test fun emptyWeek() {
        val t = WeekDigest.totals(monday, emptyList(), emptyMap())
        assertEquals(0, t.countedDays)
        assertNull(t.avgKcal)
        assertNull(t.sumBalance)
        assertNull(t.trendChange)
    }

    @Test fun topFoodsMergeNamesAndSortByKcal() {
        val top = WeekDigest.topFoods(
            listOf(entry(0, 9, "Сыр", 300.0, grams = 80.0), entry(1, 9, "сыр ", 350.0, grams = 90.0), entry(0, 9, "Хлеб", 500.0), entry(2, 9, "Яблоко", 90.0)),
            limit = 2,
        )
        assertEquals(listOf("Сыр", "Хлеб"), top.map { it.name })
        assertEquals(2, top[0].times)
        assertEquals(170.0, top[0].grams, 0.0)
        assertEquals(650.0, top[0].kcal, 0.0)
    }
}
