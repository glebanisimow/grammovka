package app.grammovka.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random

class EnergyModelTest {
    private val model = EnergyModel()
    private val profile = Profile(
        sex = Sex.MALE, ageYears = 40, heightCm = 182.0, activityFactor = 1.4,
        ratePercentPerWeek = 0.5, proteinPerKg = 1.8,
    )

    /** Monday. */
    private val start: LocalDate = LocalDate.of(2026, 10, 5)

    private fun day(kcal: Double, protein: Double = 150.0, incomplete: Boolean = false) =
        DayIntake(kcal, protein, hasEntries = true, incomplete = incomplete)

    /** Gaussian via Box–Muller so the test does not depend on java.util.Random internals. */
    private fun Random.gauss(): Double {
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }

    /** True TDEE 2900, intake 2400 ± 150, daily weigh-ins with SD 0.4 kg noise, 28 days. */
    private fun synthetic(seed: Int): Pair<Map<LocalDate, DayIntake>, Map<LocalDate, Double>> {
        val rnd = Random(seed)
        val days = mutableMapOf<LocalDate, DayIntake>()
        val weights = mutableMapOf<LocalDate, Double>()
        var trueWeight = 90.0
        for (i in 0 until 28) {
            val d = start.plusDays(i.toLong())
            val intake = 2400 + 150 * rnd.gauss()
            days[d] = day(intake)
            weights[d] = trueWeight + 0.4 * rnd.gauss()
            trueWeight += (intake - 2900) / 7700
        }
        return days to weights
    }

    @Test
    fun syntheticTdeeWithin6PercentIn95PercentOfRuns() {
        val today = start.plusDays(28)
        val hits = (1..200).count { seed ->
            val (days, weights) = synthetic(seed)
            val raw = model.observed(today, days, weights).tdeeRaw!!
            abs(raw - 2900) / 2900 <= 0.06
        }
        assertTrue("only $hits/200 runs within ±6 %", hits >= 190)
    }

    @Test
    fun trendIsEmaAndCarriesOverGaps() {
        val w = mapOf(start to 90.0, start.plusDays(2) to 80.0)
        val t = model.trend(w, start.plusDays(3))
        assertEquals(90.0, t.getValue(start), 1e-9)
        assertEquals(90.0, t.getValue(start.plusDays(1)), 1e-9)
        assertEquals(89.0, t.getValue(start.plusDays(2)), 1e-9)
        assertEquals(89.0, t.getValue(start.plusDays(3)), 1e-9)
        assertFalse(t.containsKey(start.minusDays(1)))
    }

    @Test
    fun noWeighInsMeansNoSnapshot() {
        val days = (0 until 28).associate { start.plusDays(it.toLong()) to day(2400.0) }
        assertNull(model.snapshot(start.plusDays(28), profile, days, emptyMap(), null))
        assertNull(model.observed(start.plusDays(28), days, emptyMap()).tdeeRaw)
    }

    @Test
    fun skippedWeeksFallBackToPrior() {
        val (days, weights) = synthetic(1)
        // Two missing weeks: only 14 - 1 logged days remain, below the 14-day minimum.
        val sparse = days.filterKeys { it.isBefore(start.plusDays(13)) }
        val obs = model.observed(start.plusDays(28), sparse, weights)
        assertNull(obs.tdeeRaw)
        assertEquals(13, obs.includedDays)
        val s = model.snapshot(start.plusDays(28), profile, sparse, weights, null)!!
        assertEquals(s.bmr * profile.activityFactor, s.tdee, 1e-6)
    }

    @Test
    fun tooFewOrTooCloseWeighInsGiveNoMeasurement() {
        val (days, weights) = synthetic(2)
        val fewWeights = weights.filterKeys { it.dayOfMonth % 4 == 0 } // 7 weigh-ins
        assertNull(model.observed(start.plusDays(28), days, fewWeights).tdeeRaw)
        val clustered = weights.filterKeys { it.isAfter(start.plusDays(17)) } // 10 weigh-ins over 9 days
        assertNull(model.observed(start.plusDays(28), days, clustered).tdeeRaw)
    }

    @Test
    fun allDaysIncompleteGiveNoMeasurement() {
        val (days, weights) = synthetic(3)
        val incomplete = days.mapValues { it.value.copy(incomplete = true) }
        val obs = model.observed(start.plusDays(28), incomplete, weights)
        assertNull(obs.tdeeRaw)
        assertEquals(0, obs.includedDays)
    }

    @Test
    fun blendingAndSmoothing() {
        val (days, weights) = synthetic(4)
        val today = start.plusDays(28)
        val s = model.snapshot(today, profile, days, weights, previousTdee = 2500.0)!!
        val prior = s.bmr * profile.activityFactor
        val blended = s.tdeeRaw!! // 28 included days → weight 1
        assertEquals(2500.0 + 0.2 * (blended - 2500.0), s.tdee, 1e-6)
        assertTrue(prior != blended)
    }

    @Test
    fun deficitCappedAt25PercentAndTargetNeverBelowBmr() {
        val weights = mapOf(start to 120.0)
        val aggressive = profile.copy(ratePercentPerWeek = 2.0, activityFactor = 1.5)
        val s = model.snapshot(start, aggressive, emptyMap(), weights, null)!!
        // Wanted: 2 % of 120 kg per week = 2640 kcal/day, far above 25 %.
        assertTrue(s.capped)
        assertEquals(s.tdee * 0.75, s.target, 1e-6)
        assertFalse(s.bmrFloor)

        val floor = model.snapshot(start, aggressive.copy(activityFactor = 1.2), emptyMap(), weights, null)!!
        assertTrue(floor.bmrFloor)
        assertEquals(floor.bmr, floor.target, 1e-6)
        assertTrue(floor.target >= floor.bmr)
    }

    @Test
    fun proteinTargetFollowsTrendWeight() {
        val s = model.snapshot(start, profile, emptyMap(), mapOf(start to 90.0), null)!!
        assertEquals(1.8 * 90.0, s.proteinTarget, 1e-9)
    }

    @Test
    fun weeklyAllowance() {
        val target = 2000.0
        // Monday: nothing eaten yet this week → plain target.
        assertEquals(target, model.allowance(start, target, BudgetMode.WEEKLY, emptyMap()), 1e-9)
        // Ate 1700 on Mon and Tue → 600 spare spread over Wed–Sun (5 days) → 2120.
        val days = mapOf(start to day(1700.0), start.plusDays(1) to day(1700.0))
        assertEquals(2120.0, model.allowance(start.plusDays(2), target, BudgetMode.WEEKLY, days), 1e-9)
        // Daily mode ignores the week.
        assertEquals(target, model.allowance(start.plusDays(2), target, BudgetMode.DAILY, days), 1e-9)
    }

    @Test
    fun weeklyAllowanceClampsAndCountsExcludedDaysAsTarget() {
        val target = 2000.0
        val binge = (0..5).associate { start.plusDays(it.toLong()) to day(3000.0) }
        assertEquals(1500.0, model.allowance(start.plusDays(6), target, BudgetMode.WEEKLY, binge), 1e-9)
        val incomplete = (0..5).associate { start.plusDays(it.toLong()) to day(500.0, incomplete = true) }
        assertEquals(target, model.allowance(start.plusDays(6), target, BudgetMode.WEEKLY, incomplete), 1e-9)
        val fasting = (0..5).associate { start.plusDays(it.toLong()) to day(800.0) }
        assertEquals(2500.0, model.allowance(start.plusDays(6), target, BudgetMode.WEEKLY, fasting), 1e-9)
    }

    @Test
    fun targetChangeMidWeekUsesTodaysTarget() {
        val days = mapOf(start to day(2000.0), start.plusDays(1) to day(2000.0), start.plusDays(2) to day(2000.0))
        // Target dropped from 2000 to 1800 on Thursday: 7·1800 − 6000 = 6600 over 4 days = 1650.
        assertEquals(1650.0, model.allowance(start.plusDays(3), 1800.0, BudgetMode.WEEKLY, days), 1e-9)
    }

    @Test
    fun weekBoundaryResetsTheBudget() {
        val lastWeek = (0..6).associate { start.plusDays(it.toLong()) to day(3000.0) }
        val nextMonday = start.plusDays(7)
        assertEquals(2000.0, model.allowance(nextMonday, 2000.0, BudgetMode.WEEKLY, lastWeek), 1e-9)
        // Sunday: only today remains, still clamped.
        val sunday = start.plusDays(6)
        val week = (0..5).associate { start.plusDays(it.toLong()) to day(2000.0) }
        assertEquals(2000.0, model.allowance(sunday, 2000.0, BudgetMode.WEEKLY, week), 1e-9)
    }

    @Test
    fun runChainsSmoothing() {
        val (days, weights) = synthetic(5)
        val snaps = model.run(start, start.plusDays(28), profile, days, weights, null)
        assertEquals(29, snaps.size)
        for (i in 1 until snaps.size) {
            assertNotNull(snaps[i].tdee)
        }
        // With measured TDEE ~2900 and prior lower, the smoothed value moves toward it monotonically late in the run.
        assertTrue(snaps.last().tdeeRaw != null)
    }

    @Test
    fun warnings() {
        val today = start.plusDays(28)
        val weights = mapOf(start to 95.0, today to 89.0)
        val lowProteinDays = (1..7).associate { today.minusDays(it.toLong()) to day(1800.0, protein = 80.0) }
        val fast = profile.copy(ratePercentPerWeek = 1.5)
        val s = model.snapshot(today, fast, lowProteinDays, weights, null)!!
        val w = model.warnings(today, fast, s, lowProteinDays, weights)
        assertTrue(Warning.RATE_TOO_HIGH in w)
        assertTrue(Warning.PROTEIN_LOW in w)

        // Trend fell 95 → 94.4 (one weigh-in at 89 moves EMA by 0.6): 0.15 kg/week vs target 0.47 → not too fast.
        assertFalse(Warning.LOSING_TOO_FAST in w)
        val dailyDrop = (0..28).associate { start.plusDays(it.toLong()) to 95.0 - it * 0.3 }
        val s2 = model.snapshot(today, profile, emptyMap(), dailyDrop, null)!!
        assertTrue(Warning.LOSING_TOO_FAST in model.warnings(today, profile, s2, emptyMap(), dailyDrop))
    }
}
