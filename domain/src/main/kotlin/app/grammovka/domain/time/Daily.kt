package app.grammovka.domain.time

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
}
