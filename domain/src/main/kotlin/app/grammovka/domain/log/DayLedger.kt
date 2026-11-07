package app.grammovka.domain.log

import java.time.DayOfWeek
import java.time.LocalDate

/** Where a day is in its life: never started, being filled in, or closed (counted, no more edits). */
enum class DayState { NONE, OPEN, CLOSED }

/** The stored marks of one day. */
data class DayMark(val started: Boolean, val closed: Boolean, val incomplete: Boolean = false)

/**
 * Day lifecycle (user decision 2026-11-07): the user starts a day, logs into it, and closes it.
 * A closed day takes no more writes until it is explicitly reopened. Navigation never goes before
 * the first started day or after today.
 */
class DayLedger(private val marks: Map<LocalDate, DayMark>, private val today: LocalDate) {
    fun state(date: LocalDate): DayState {
        val m = marks[date] ?: return DayState.NONE
        return when {
            m.closed -> DayState.CLOSED
            m.started -> DayState.OPEN
            else -> DayState.NONE
        }
    }

    fun incomplete(date: LocalDate): Boolean = marks[date]?.incomplete == true

    /** The first day the user ever started; today when there is none yet. */
    val firstDay: LocalDate = marks.filterValues { it.started || it.closed }.keys.filter { !it.isAfter(today) }.minOrNull() ?: today

    /** Open days before today: each one shows a «this is not today» banner and can be closed. */
    val staleOpen: List<LocalDate> = marks.keys.filter { it.isBefore(today) && state(it) == DayState.OPEN }.sorted()

    /** The day the app shows on launch: the latest open day, otherwise today. */
    fun defaultDay(): LocalDate =
        marks.keys.filter { !it.isAfter(today) && state(it) == DayState.OPEN }.maxOrNull() ?: today

    /** Days the user can look at: from the first day through today. */
    fun selectable(date: LocalDate): Boolean = !date.isBefore(firstDay) && !date.isAfter(today)

    /** «Начать новый день»: offered while today has not been started. */
    val canStartToday: Boolean get() = state(today) == DayState.NONE

    companion object {
        fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
    }
}
