package app.grammovka.domain.engine

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** One row of the weekly table on «Прогресс». Averages use only complete days (entries, not flagged incomplete). */
data class WeekRow(
    val monday: LocalDate,
    val completeDays: Int,
    val avgKcal: Double?,
    val avgTarget: Double?,
    /** Trend at the last known day of this week minus trend at the end of the previous week. */
    val trendChangeKg: Double?,
)

object Weekly {
    fun rows(
        today: LocalDate,
        weeks: Int,
        days: Map<LocalDate, DayIntake>,
        targets: Map<LocalDate, Double>,
        trend: Map<LocalDate, Double>,
    ): List<WeekRow> {
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0 until weeks).map { i ->
            val monday = thisMonday.minusWeeks(i.toLong())
            val dates = (0L..6L).map { monday.plusDays(it) }.filter { !it.isAfter(today) }
            val complete = dates.filter { days[it]?.included == true }
            val tgt = dates.mapNotNull { targets[it] }
            val end = dates.lastOrNull { trend[it] != null }?.let { trend[it] }
            val prevEnd = trend[monday.minusDays(1)]
            WeekRow(
                monday = monday,
                completeDays = complete.size,
                avgKcal = complete.takeIf { it.isNotEmpty() }?.map { days.getValue(it).kcal }?.average(),
                avgTarget = tgt.takeIf { it.isNotEmpty() }?.average(),
                trendChangeKg = if (end != null && prevEnd != null) end - prevEnd else null,
            )
        }
    }
}
