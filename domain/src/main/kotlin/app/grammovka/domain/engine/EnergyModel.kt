package app.grammovka.domain.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Totals for one calendar day of logging. [activity] is the net kcal of logged workouts (above rest):
 * it is not part of the norm, it is added on top on its own day.
 */
data class DayIntake(
    val kcal: Double,
    val protein: Double,
    val hasEntries: Boolean,
    val incomplete: Boolean,
    val activity: Double = 0.0,
) {
    /** A day counts for the model when it has entries and is not flagged incomplete. */
    val included: Boolean get() = hasEntries && !incomplete
}

data class Observed(val tdeeRaw: Double?, val includedDays: Int, val weighIns: Int, val slopeKgPerDay: Double?)

/** Per-day model state; persisted as ModelSnapshot. */
data class Snapshot(
    val date: LocalDate,
    val trendWeight: Double,
    val tdeeRaw: Double?,
    val tdee: Double,
    val target: Double,
    val deficit: Double,
    val capped: Boolean,
    val bmrFloor: Boolean,
    val bmr: Double,
    val proteinTarget: Double,
    val includedDays: Int,
    val weighIns: Int,
)

/**
 * [left] — what can still be eaten today to keep the planned deficit; [expenditure] — the norm without
 * workouts plus the day's workouts; [balance] — eaten minus expenditure (negative = deficit).
 */
data class DayBalance(val left: Double, val expenditure: Double, val balance: Double)

enum class Warning { RATE_TOO_HIGH, LOSING_TOO_FAST, PROTEIN_LOW }

/**
 * The energy model. All arithmetic on calories lives here (invariant 3); the LLM never computes totals.
 * Pure functions over plain maps so it is easy to test with synthetic data.
 */
class EnergyModel(private val cfg: EngineConfig = EngineConfig()) {

    /** 1. EMA trend by calendar day, starting at the first weigh-in; days without a weigh-in carry the trend. */
    fun trend(weights: Map<LocalDate, Double>, until: LocalDate): Map<LocalDate, Double> {
        val first = weights.keys.filter { !it.isAfter(until) }.minOrNull() ?: return emptyMap()
        val out = LinkedHashMap<LocalDate, Double>()
        var t = weights.getValue(first)
        var d = first
        while (!d.isAfter(until)) {
            weights[d]?.let { w -> t += cfg.trendAlpha * (w - t) }
            out[d] = t
            d = d.plusDays(1)
        }
        return out
    }

    /** 2. Measured expenditure over the window ending the day before [today]. */
    fun observed(today: LocalDate, days: Map<LocalDate, DayIntake>, weights: Map<LocalDate, Double>): Observed {
        val start = today.minusDays(cfg.windowDays.toLong())
        val end = today.minusDays(1)
        val window = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        val included = window.mapNotNull { d -> days[d]?.takeIf { it.included } }
        val weighIns = window.mapNotNull { d -> weights[d]?.let { d to it } }
        val span = if (weighIns.size >= 2) ChronoUnit.DAYS.between(weighIns.first().first, weighIns.last().first) else 0
        if (included.size < cfg.minIncludedDays || weighIns.size < cfg.minWeighIns || span < cfg.minWeighInSpanDays) {
            return Observed(null, included.size, weighIns.size, null)
        }
        val slope = olsSlope(weighIns.map { (d, w) -> ChronoUnit.DAYS.between(start, d).toDouble() to w })
        val meanIntake = included.map { it.kcal }.average()
        // Logged workouts are taken out, so the result is the norm without them: food eaten to cover a long
        // ride must not raise the norm of every other day (that would count the ride twice).
        val meanActivity = window.sumOf { days[it]?.activity ?: 0.0 } / window.size
        return Observed(meanIntake - meanActivity - slope * cfg.kcalPerKg, included.size, weighIns.size, slope)
    }

    /** 3. Mifflin–St Jeor. */
    fun bmr(sex: Sex, weightKg: Double, heightCm: Double, ageYears: Int): Double =
        10 * weightKg + 6.25 * heightCm - 5 * ageYears + if (sex == Sex.MALE) 5 else -161

    /**
     * Steps 3–7 for one day. [previousTdee] is yesterday's smoothed value (null on the first day).
     * Returns null when there is no weigh-in yet: without a weight there is no target.
     */
    fun snapshot(
        today: LocalDate,
        profile: Profile,
        days: Map<LocalDate, DayIntake>,
        weights: Map<LocalDate, Double>,
        previousTdee: Double?,
    ): Snapshot? {
        val trendWeight = trend(weights, today)[today] ?: return null
        val bmr = bmr(profile.sex, trendWeight, profile.heightCm, profile.ageYears)
        val prior = bmr * profile.activityFactor
        val obs = observed(today, days, weights)
        // 4. Blend measured with prior by how many days we have.
        val blended = obs.tdeeRaw?.let { raw ->
            val w = min(1.0, obs.includedDays.toDouble() / cfg.windowDays)
            w * raw + (1 - w) * prior
        } ?: prior
        // 5. Smooth over time.
        val tdee = previousTdee?.let { it + cfg.tdeeSmoothing * (blended - it) } ?: blended
        // 6. Target with the 25 % cap and the BMR floor.
        val wanted = profile.ratePercentPerWeek / 100 * trendWeight * cfg.kcalPerKg / 7
        val cap = cfg.maxDeficitFraction * tdee
        val capped = wanted > cap
        var target = tdee - min(wanted, cap)
        val bmrFloor = target < bmr
        target = max(target, bmr)
        return Snapshot(
            date = today,
            trendWeight = trendWeight,
            tdeeRaw = obs.tdeeRaw,
            tdee = tdee,
            target = target,
            deficit = tdee - target,
            capped = capped,
            bmrFloor = bmrFloor,
            bmr = bmr,
            // 7. Protein.
            proteinTarget = profile.proteinPerKg * trendWeight,
            includedDays = obs.includedDays,
            weighIns = obs.weighIns,
        )
    }

    /** Runs [snapshot] for every day in [from]..[to], chaining the smoothed TDEE. */
    fun run(
        from: LocalDate,
        to: LocalDate,
        profile: Profile,
        days: Map<LocalDate, DayIntake>,
        weights: Map<LocalDate, Double>,
        previousTdee: Double?,
    ): List<Snapshot> {
        val out = mutableListOf<Snapshot>()
        var prev = previousTdee
        var d = from
        while (!d.isAfter(to)) {
            snapshot(d, profile, days, weights, prev)?.let {
                out += it
                prev = it.tdee
            }
            d = d.plusDays(1)
        }
        return out
    }

    /**
     * 8. Today's allowance. Weekly mode (week starts Monday): spread what is left of 7·target over the
     * remaining days including today, clamped to 0.75–1.25 × target. Past days that are not included count as target.
     * A past day counts as intake minus its workouts: eating back a ride does not cut the rest of the week.
     */
    fun allowance(today: LocalDate, target: Double, mode: BudgetMode, days: Map<LocalDate, DayIntake>): Double {
        if (mode == BudgetMode.DAILY) return target
        val monday = today.with(DayOfWeek.MONDAY)
        val past = generateSequence(monday) { it.plusDays(1) }.takeWhile { it.isBefore(today) }
            .sumOf { d -> days[d]?.takeIf { it.included }?.let { it.kcal - it.activity } ?: target }
        val remaining = 7 - ChronoUnit.DAYS.between(monday, today)
        val raw = (7 * target - past) / remaining
        return raw.coerceIn(cfg.weeklyMinFactor * target, cfg.weeklyMaxFactor * target)
    }

    /** Today's numbers with workouts: the norm stays, workouts of the day go on top. */
    fun balance(allowance: Double, tdee: Double, activity: Double, eaten: Double) = DayBalance(
        left = allowance + activity - eaten,
        expenditure = tdee + activity,
        balance = eaten - (tdee + activity),
    )

    /** 9. Warnings shown on Today / Progress. */
    fun warnings(
        today: LocalDate,
        profile: Profile,
        snapshot: Snapshot,
        days: Map<LocalDate, DayIntake>,
        weights: Map<LocalDate, Double>,
    ): Set<Warning> {
        val out = mutableSetOf<Warning>()
        if (profile.ratePercentPerWeek > cfg.maxSafeRatePercent) out += Warning.RATE_TOO_HIGH

        val trend = trend(weights, today)
        val past = trend[today.minusDays(cfg.fastLossWindowDays.toLong())]
        if (past != null) {
            val actualKgPerWeek = (past - snapshot.trendWeight) / cfg.fastLossWindowDays * 7
            val targetKgPerWeek = profile.ratePercentPerWeek / 100 * snapshot.trendWeight
            if (targetKgPerWeek > 0 && actualKgPerWeek > cfg.fastLossFactor * targetKgPerWeek) out += Warning.LOSING_TOO_FAST
        }

        val lastWeek = (1..7).mapNotNull { days[today.minusDays(it.toLong())]?.takeIf { d -> d.hasEntries } }
        if (lastWeek.count { it.protein < snapshot.proteinTarget } >= cfg.proteinLowDays) out += Warning.PROTEIN_LOW
        return out
    }

    companion object {
        /** Ordinary least squares slope of y over x. */
        fun olsSlope(points: List<Pair<Double, Double>>): Double {
            val mx = points.map { it.first }.average()
            val my = points.map { it.second }.average()
            val num = points.sumOf { (x, y) -> (x - mx) * (y - my) }
            val den = points.sumOf { (x, _) -> (x - mx) * (x - mx) }
            return if (den == 0.0) 0.0 else num / den
        }
    }
}
