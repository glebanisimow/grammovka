package app.grammovka.domain.time

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime

class DailyTest {
    @Test fun laterToday() = assertEquals(Duration.ofMinutes(90), Daily.delayUntilNext(LocalDateTime.of(2026, 11, 5, 7, 30), 9 * 60))
    @Test fun alreadyPassed() = assertEquals(Duration.ofHours(23), Daily.delayUntilNext(LocalDateTime.of(2026, 11, 5, 10, 0), 9 * 60))
    @Test fun exactlyNowMeansTomorrow() = assertEquals(Duration.ofDays(1), Daily.delayUntilNext(LocalDateTime.of(2026, 11, 5, 9, 0), 9 * 60))

    // 2026-11-05 is a Thursday.
    @Test fun nextSunday() = assertEquals(Duration.ofDays(3).plusHours(10), Daily.delayUntilNextWeekday(LocalDateTime.of(2026, 11, 5, 10, 0), DayOfWeek.SUNDAY, 20 * 60))
    @Test fun sundayBeforeTheHour() = assertEquals(Duration.ofHours(2), Daily.delayUntilNextWeekday(LocalDateTime.of(2026, 11, 8, 18, 0), DayOfWeek.SUNDAY, 20 * 60))
    @Test fun sundayAfterTheHourMeansNextWeek() = assertEquals(Duration.ofDays(7).minusHours(1), Daily.delayUntilNextWeekday(LocalDateTime.of(2026, 11, 8, 21, 0), DayOfWeek.SUNDAY, 20 * 60))
}
