package app.grammovka.domain.model

import app.grammovka.domain.engine.FiberTarget
import app.grammovka.domain.engine.Sex
import app.grammovka.domain.log.DayState
import app.grammovka.domain.review.DigestDayInput
import app.grammovka.domain.review.DigestEntry
import app.grammovka.domain.review.WeekDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class FiberTest {
    private val bread = Nutrients(kcal = 220.0, protein = 7.0, fat = 1.5, carbs = 40.0, fiber = 8.0)
    private val chicken = Nutrients(kcal = 110.0, protein = 23.0, fat = 1.5, carbs = 0.0)
    private val soup = Nutrients(kcal = 50.0, protein = 2.0, fat = 2.0, carbs = 6.0)

    @Test fun forGramsScalesKnownFiber() {
        val a = Nutrition.forGrams(bread, 50.0)
        assertEquals(4.0, a.fiber, 1e-9)
        assertEquals(0, a.fiberUnknown)
    }

    @Test fun missingFiberWithoutCarbsCountsAsZero() {
        val a = Nutrition.forGrams(chicken, 150.0)
        assertEquals(0.0, a.fiber, 0.0)
        assertEquals(0, a.fiberUnknown)
    }

    @Test fun missingFiberWithCarbsIsUnknown() {
        assertEquals(1, Nutrition.forGrams(soup, 300.0).fiberUnknown)
    }

    @Test fun sumKeepsKnownPartAndCountsUnknown() {
        val total = Nutrition.sum(listOf(Nutrition.forGrams(bread, 100.0), Nutrition.forGrams(chicken, 150.0), Nutrition.forGrams(soup, 300.0)))
        assertEquals(8.0, total.fiber, 1e-9)
        assertEquals(1, total.fiberUnknown)
        assertEquals("≥ 8", Fmt.fiber(total))
        assertEquals("8", Fmt.fiber(Nutrition.forGrams(bread, 100.0)))
        assertEquals("?", Fmt.fiber(Nutrition.forGrams(soup, 300.0)))
        assertEquals("0", Fmt.fiber(Nutrition.forGrams(chicken, 100.0)))
    }

    @Test fun snapshotUsesCarbsPer100OfTheEntry() {
        // 300 g of soup with 18 g carbs → 6 g per 100 g: unknown. 2 g of carbs in 300 g of tea → 0.67 per 100 g: zero.
        assertEquals(1, Nutrition.snapshot(150.0, 6.0, 6.0, 18.0, null, 300.0).fiberUnknown)
        assertEquals(0, Nutrition.snapshot(8.0, 0.0, 0.0, 2.0, null, 300.0).fiberUnknown)
        assertEquals(3.0, Nutrition.snapshot(150.0, 6.0, 6.0, 18.0, 3.0, 300.0).fiber, 0.0)
    }

    @Test fun catalogLineMarksMissingFiber() {
        assertEquals(" · Кл 8", Fmt.fiberPart(8.0, 40.0))
        assertEquals(" · Кл ?", Fmt.fiberPart(null, 6.0))
        assertEquals("", Fmt.fiberPart(null, 0.0))
    }

    @Test fun targetByAgeAndSex() {
        assertEquals(38.0, FiberTarget.grams(Sex.MALE, 35), 0.0)
        assertEquals(38.0, FiberTarget.grams(Sex.MALE, 50), 0.0)
        assertEquals(30.0, FiberTarget.grams(Sex.MALE, 51), 0.0)
        assertEquals(26.0, FiberTarget.grams(Sex.FEMALE, 17), 0.0)
        assertEquals(25.0, FiberTarget.grams(Sex.FEMALE, 30), 0.0)
        assertEquals(21.0, FiberTarget.grams(Sex.FEMALE, 60), 0.0)
    }

    @Test fun weekTotalsAverageFiberAndCountDaysAtTheFloor() {
        val monday = LocalDate.of(2026, 11, 9)
        fun day(i: Int, vararg items: Pair<Nutrients, Double>) = WeekDigest.day(
            DigestDayInput(monday.plusDays(i.toLong()), DayState.CLOSED, false, 2600.0, 2100.0, 150.0, 0.0, null, null),
            items.map { (n, g) -> DigestEntry(monday.plusDays(i.toLong()), LocalTime.NOON, "x", g, Nutrition.forGrams(n, g), null) },
            today = monday.plusDays(7),
        )
        val days = listOf(day(0, bread to 500.0), day(1, bread to 250.0, soup to 300.0))
        val t = WeekDigest.totals(monday, days, emptyMap(), fiberTarget = 38.0)
        assertEquals(30.0, t.avgFiber!!, 1e-9)
        assertEquals(1, t.daysFiberMet)
        assertEquals(1, t.fiberUnknownEntries)
        assertEquals(38.0, t.fiberTarget!!, 0.0)
        assertNull(WeekDigest.totals(monday, emptyList(), emptyMap()).avgFiber)
    }
}
