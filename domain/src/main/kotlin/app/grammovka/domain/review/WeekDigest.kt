package app.grammovka.domain.review

import app.grammovka.domain.log.DayState
import app.grammovka.domain.model.Amount
import app.grammovka.domain.model.Meal
import app.grammovka.domain.model.Nutrition
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** One logged item as the weekly review sees it. */
data class DigestEntry(val date: LocalDate, val time: LocalTime, val name: String, val grams: Double, val amount: Amount, val meal: Meal?)

/** What the model and the stored marks say about one day. */
data class DigestDayInput(
    val date: LocalDate,
    val state: DayState,
    val incomplete: Boolean,
    val tdee: Double?,
    val target: Double?,
    val proteinTarget: Double?,
    /** Net kcal of logged workouts. */
    val activity: Double,
    val weightKg: Double?,
    val trendKg: Double?,
)

/**
 * COUNTED days go into the averages. IN_PROGRESS is today while it is still open (the review comes on Sunday
 * evening, before the day ends); INCOMPLETE was closed as «записан не полностью»; EMPTY has no entries.
 */
enum class DigestStatus { COUNTED, IN_PROGRESS, INCOMPLETE, EMPTY }

data class DigestDay(
    val date: LocalDate,
    val status: DigestStatus,
    val eaten: Amount,
    val target: Double?,
    val proteinTarget: Double?,
    val activity: Double,
    /** Norm plus the day's workouts. */
    val expenditure: Double?,
    /** Eaten minus expenditure: negative is a deficit. */
    val balance: Double?,
    val weightKg: Double?,
    val mealKcal: Map<Meal, Double>,
    /** Eaten at or after [WeekDigest.LATE_FROM]. */
    val lateKcal: Double,
    val firstMeal: LocalTime?,
    val lastMeal: LocalTime?,
)

/** A week in numbers; averages are over COUNTED days only, null when there are none. */
data class WeekTotals(
    val monday: LocalDate,
    val countedDays: Int,
    val avgKcal: Double?,
    val avgProtein: Double?,
    val avgFat: Double?,
    val avgCarbs: Double?,
    val avgTarget: Double?,
    val avgBalance: Double?,
    val sumBalance: Double?,
    /** Counted days where eaten minus workouts was above the day's target. */
    val daysOverTarget: Int,
    val daysProteinMet: Int,
    val avgProteinTarget: Double?,
    val workoutKcal: Double,
    val weekdayAvgKcal: Double?,
    val weekendAvgKcal: Double?,
    val weighIns: Int,
    /** Trend weight at the end of the previous week (or the first known in this one) and at the last known day. */
    val trendStart: Double?,
    val trendEnd: Double?,
) {
    val trendChange: Double? get() = if (trendStart != null && trendEnd != null) trendEnd - trendStart else null
}

data class TopFood(val name: String, val times: Int, val grams: Double, val kcal: Double, val protein: Double)

/**
 * Aggregates for the weekly review (spec, phase 3): the code prepares every number, the model only reads them
 * (invariant 3).
 */
object WeekDigest {
    val LATE_FROM: LocalTime = LocalTime.of(21, 0)

    fun day(input: DigestDayInput, entries: List<DigestEntry>, today: LocalDate): DigestDay {
        val eaten = Nutrition.sum(entries.map { it.amount })
        val status = when {
            entries.isEmpty() -> DigestStatus.EMPTY
            input.incomplete -> DigestStatus.INCOMPLETE
            input.date == today && input.state != DayState.CLOSED -> DigestStatus.IN_PROGRESS
            else -> DigestStatus.COUNTED
        }
        val expenditure = input.tdee?.let { it + input.activity }
        return DigestDay(
            date = input.date,
            status = status,
            eaten = eaten,
            target = input.target,
            proteinTarget = input.proteinTarget,
            activity = input.activity,
            expenditure = expenditure,
            balance = if (entries.isEmpty()) null else expenditure?.let { eaten.kcal - it },
            weightKg = input.weightKg,
            mealKcal = entries.groupBy { it.meal ?: Meal.SNACK }.mapValues { (_, l) -> l.sumOf { it.amount.kcal } },
            lateKcal = entries.filter { !it.time.isBefore(LATE_FROM) }.sumOf { it.amount.kcal },
            firstMeal = entries.minOfOrNull { it.time },
            lastMeal = entries.maxOfOrNull { it.time },
        )
    }

    /** [days] are the week's days in order; [trend] maps dates to the trend weight. */
    fun totals(monday: LocalDate, days: List<DigestDay>, trend: Map<LocalDate, Double>): WeekTotals {
        val counted = days.filter { it.status == DigestStatus.COUNTED }
        fun avg(f: (DigestDay) -> Double?): Double? = counted.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        val balances = counted.mapNotNull { it.balance }
        val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val week = (0L..6L).map { monday.plusDays(it) }
        return WeekTotals(
            monday = monday,
            countedDays = counted.size,
            avgKcal = avg { it.eaten.kcal },
            avgProtein = avg { it.eaten.protein },
            avgFat = avg { it.eaten.fat },
            avgCarbs = avg { it.eaten.carbs },
            avgTarget = avg { it.target },
            avgBalance = balances.takeIf { it.isNotEmpty() }?.average(),
            sumBalance = balances.takeIf { it.isNotEmpty() }?.sum(),
            daysOverTarget = counted.count { d -> d.target != null && d.eaten.kcal - d.activity > d.target },
            daysProteinMet = counted.count { d -> d.proteinTarget != null && d.eaten.protein >= d.proteinTarget },
            avgProteinTarget = avg { it.proteinTarget },
            workoutKcal = days.sumOf { it.activity },
            weekdayAvgKcal = counted.filter { it.date.dayOfWeek !in weekend }.takeIf { it.isNotEmpty() }?.map { it.eaten.kcal }?.average(),
            weekendAvgKcal = counted.filter { it.date.dayOfWeek in weekend }.takeIf { it.isNotEmpty() }?.map { it.eaten.kcal }?.average(),
            weighIns = days.count { it.weightKg != null },
            trendStart = trend[monday.minusDays(1)] ?: week.firstNotNullOfOrNull { trend[it] },
            trendEnd = week.lastOrNull { trend[it] != null }?.let { trend[it] },
        )
    }

    /** Foods of the period by total kcal, same names merged. */
    fun topFoods(entries: List<DigestEntry>, limit: Int = 12): List<TopFood> =
        entries.groupBy { it.name.trim().lowercase() }.values
            .map { l -> TopFood(l.first().name.trim(), l.size, l.sumOf { it.grams }, l.sumOf { it.amount.kcal }, l.sumOf { it.amount.protein }) }
            .sortedByDescending { it.kcal }
            .take(limit)
}
