package app.grammovka.domain.time

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

object Daily {
    /** Time until the next [minuteOfDay] strictly after [now] (local wall clock). */
    fun delayUntilNext(now: LocalDateTime, minuteOfDay: Int): Duration {
        val at = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        var next = now.toLocalDate().atTime(at)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** Time until the next [day] at [minuteOfDay] strictly after [now] (the weekly review: Sunday evening). */
    fun delayUntilNextWeekday(now: LocalDateTime, day: DayOfWeek, minuteOfDay: Int): Duration {
        val at = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        var next = now.toLocalDate().plusDays(((day.value - now.dayOfWeek.value + 7) % 7).toLong()).atTime(at)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        return Duration.between(now, next)
    }
}
