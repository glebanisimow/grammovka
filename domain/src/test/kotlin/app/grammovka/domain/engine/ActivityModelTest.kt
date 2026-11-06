package app.grammovka.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

/** Workouts logged on top of the norm must not be counted twice by the measured expenditure. */
class ActivityModelTest {
    private val model = EnergyModel()
    private val start: LocalDate = LocalDate.of(2026, 10, 5) // Monday

    private fun Random.gauss(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    /**
     * Base expenditure 2600 without workouts. Two long rides a week (net 2500 kcal each), eaten back in full,
     * plus a 500 kcal deficit every day. The measured norm must come out near 2600, not 2600 + 5000/7.
     */
    private fun synthetic(seed: Int, logActivity: Boolean): Pair<Map<LocalDate, DayIntake>, Map<LocalDate, Double>> {
        val rnd = Random(seed)
        val days = mutableMapOf<LocalDate, DayIntake>()
        val weights = mutableMapOf<LocalDate, Double>()
        var w = 85.0
        for (i in 0 until 28) {
            val d = start.plusDays(i.toLong())
            val ride = if (i % 7 == 2 || i % 7 == 5) 2500.0 else 0.0
            val intake = 2100 + ride + 150 * rnd.gauss()
            days[d] = DayIntake(intake, 150.0, hasEntries = true, incomplete = false, activity = if (logActivity) ride else 0.0)
            weights[d] = w + 0.4 * rnd.gauss()
            w += (intake - 2600 - ride) / 7700
        }
        return days to weights
    }

    @Test
    fun loggedWorkoutsAreTakenOutOfTheMeasuredNorm() {
        val today = start.plusDays(28)
        val hits = (1..200).count { seed ->
            val (days, weights) = synthetic(seed, logActivity = true)
            abs(model.observed(today, days, weights).tdeeRaw!! - 2600) / 2600 <= 0.06
        }
        assertTrue("only $hits/200 within ±6 %", hits >= 190)
    }

    @Test
    fun withoutLoggingTheNormAbsorbsTheAverageWorkout() {
        // Sanity check of the test itself: unlogged rides show up as a higher norm (~2600 + 714).
        val (days, weights) = synthetic(7, logActivity = false)
        val raw = model.observed(start.plusDays(28), days, weights).tdeeRaw!!
        assertTrue("raw $raw", raw > 3000)
    }

    @Test
    fun weeklyBudgetIgnoresEatenBackWorkout() {
        val monday = start
        val days = mapOf(
            monday to DayIntake(2100.0 + 3000, 150.0, true, false, activity = 3000.0),
        )
        val tuesday = model.allowance(monday.plusDays(1), 2100.0, BudgetMode.WEEKLY, days)
        assertEquals(2100.0, tuesday, 0.01)
    }

    @Test
    fun balanceOfALongRideDay() {
        // The example from the request: norm 2200, target 2100, ride 3000, eaten 1800.
        val b = model.balance(allowance = 2100.0, tdee = 2200.0, activity = 3000.0, eaten = 1800.0)
        assertEquals(3300.0, b.left, 0.01)
        assertEquals(5200.0, b.expenditure, 0.01)
        assertEquals(-3400.0, b.balance, 0.01)
    }
}
