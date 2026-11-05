package app.grammovka.domain.time

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class DailyTest {
    @Test fun laterToday() = assertEquals(Duration.ofMinutes(90), Daily.delayUntilNext(LocalDateTime.of(2026, 11, 5, 7, 30), 9 * 60))
    @Test fun alreadyPassed() = assertEquals(Duration.ofHours(23), Daily.delayUntilNext(LocalDateTime.of(2026, 11, 5, 10, 0), 9 * 60))
    @Test fun exactlyNowMeansTomorrow() = assertEquals(Duration.ofDays(1), Daily.delayUntilNext(LocalDateTime.of(2026, 11, 5, 9, 0), 9 * 60))
}
