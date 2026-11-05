package app.grammovka.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class WeeklyTest {
    private val wed = LocalDate.of(2026, 11, 4) // Wednesday

    @Test
    fun averagesOnlyCompleteDaysAndStopsAtToday() {
        val mon = LocalDate.of(2026, 11, 2)
        val days = mapOf(
            mon to DayIntake(2000.0, 100.0, true, false),
            mon.plusDays(1) to DayIntake(500.0, 20.0, true, true), // incomplete
            wed to DayIntake(1800.0, 90.0, true, false),
            wed.plusDays(1) to DayIntake(9999.0, 0.0, true, false), // future
        )
        val trend = mapOf(mon.minusDays(1) to 80.0, wed to 79.6)
        val targets = mapOf(mon to 1900.0, wed to 1700.0)
        val r = Weekly.rows(wed, 2, days, targets, trend)
        assertEquals(mon, r[0].monday)
        assertEquals(2, r[0].completeDays)
        assertEquals(1900.0, r[0].avgKcal!!, 1e-9)
        assertEquals(1800.0, r[0].avgTarget!!, 1e-9)
        assertEquals(-0.4, r[0].trendChangeKg!!, 1e-9)
        assertEquals(0, r[1].completeDays)
        assertNull(r[1].avgKcal)
        assertNull(r[1].trendChangeKg)
    }
}
