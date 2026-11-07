package app.grammovka.domain.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DayLedgerTest {
    private val today = LocalDate.of(2026, 11, 7)
    private fun d(day: Int) = LocalDate.of(2026, 11, day)

    @Test fun nothingStartedShowsTodayAndOffersToStart() {
        val l = DayLedger(emptyMap(), today)
        assertEquals(today, l.defaultDay())
        assertEquals(today, l.firstDay)
        assertTrue(l.canStartToday)
        assertFalse(l.selectable(d(6)))
        assertTrue(l.selectable(today))
    }

    @Test fun yesterdayLeftOpenIsShownUntilClosed() {
        val l = DayLedger(mapOf(d(6) to DayMark(started = true, closed = false)), today)
        assertEquals(d(6), l.defaultDay())
        assertEquals(listOf(d(6)), l.staleOpen)
        assertEquals(DayState.OPEN, l.state(d(6)))
        assertTrue(l.canStartToday)
    }

    @Test fun afterClosingYesterdayTodayIsShownAndNotStarted() {
        val l = DayLedger(mapOf(d(6) to DayMark(started = true, closed = true)), today)
        assertEquals(today, l.defaultDay())
        assertEquals(DayState.CLOSED, l.state(d(6)))
        assertEquals(DayState.NONE, l.state(today))
        assertTrue(l.staleOpen.isEmpty())
    }

    @Test fun latestOpenDayWinsAndFirstDayLimitsNavigation() {
        val l = DayLedger(
            mapOf(
                d(4) to DayMark(started = true, closed = true),
                d(5) to DayMark(started = true, closed = false),
                today to DayMark(started = true, closed = false),
                // An «incomplete» flag alone (old data) does not start a day.
                d(1) to DayMark(started = false, closed = false, incomplete = true),
            ),
            today,
        )
        assertEquals(today, l.defaultDay())
        assertEquals(d(4), l.firstDay)
        assertFalse(l.selectable(d(3)))
        assertFalse(l.selectable(d(8)))
        assertEquals(listOf(d(5)), l.staleOpen)
        assertFalse(l.canStartToday)
        assertEquals(DayState.NONE, l.state(d(1)))
    }

    @Test fun weekStartsOnMonday() {
        assertEquals(d(2), DayLedger.weekStart(today))
        assertEquals(d(2), DayLedger.weekStart(d(2)))
        assertEquals(d(2), DayLedger.weekStart(d(8)))
    }
}
