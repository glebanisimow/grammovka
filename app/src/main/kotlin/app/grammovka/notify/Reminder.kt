package app.grammovka.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.grammovka.GrammovkaApp
import app.grammovka.data.settings.ReminderSettings
import app.grammovka.di.AppContainer
import app.grammovka.domain.log.DayLedger
import java.time.DayOfWeek
import app.grammovka.domain.time.Daily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Morning question about yesterday (spec, phase 2). A one-time work request re-schedules itself for the
 * next day, which keeps the wall-clock time stable (periodic work drifts).
 */
object Reminder {
    private const val NAME = "morning-question"

    fun schedule(context: Context, r: ReminderSettings) {
        val wm = WorkManager.getInstance(context)
        if (!r.enabled) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val delay = Daily.delayUntilNext(LocalDateTime.now(), r.minuteOfDay)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build()
        wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GrammovkaApp).container
        val settings = c.settingsStore.current().reminder
        if (!settings.enabled) return Result.success()
        val yesterday = LocalDate.now().minusDays(1)
        val day = c.logRepository.intake(yesterday, yesterday)[yesterday]
        // Ask only about an open day with entries: a closed day is settled, an empty one is excluded anyway.
        if (day != null && day.hasEntries && !c.logRepository.isClosed(yesterday)) {
            Notifications.yesterdayQuestion(applicationContext, yesterday, day.kcal)
        }
        Reminder.schedule(applicationContext, settings)
        return Result.success()
    }
}

/**
 * Weekly review (spec, phase 3): the notification comes when the week is over, i.e. when its Sunday is closed
 * (user request 2026-11-15: the Sunday 20:00 timer offered a review of an unfinished week). If Sunday was never
 * started, it comes when a day of the next week is started. Each week is offered once.
 */
object WeeklyReview {
    /** Name of the old Sunday-evening timer; only cancelled now. */
    private const val NAME = "weekly-review"

    fun cancelTimer(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }

    suspend fun onDayClosed(c: AppContainer, date: LocalDate) {
        if (date.dayOfWeek == DayOfWeek.SUNDAY) offer(c, DayLedger.weekStart(date))
    }

    suspend fun onDayStarted(c: AppContainer, date: LocalDate) {
        val monday = DayLedger.weekStart(date).minusWeeks(1)
        val sunday = monday.plusDays(6)
        val mark = c.database.dayStatusDao().get(sunday)
        if (mark?.startedAt == null && mark?.closedAt == null) offer(c, monday)
    }

    private suspend fun offer(c: AppContainer, monday: LocalDate) {
        val w = c.settingsStore.current().weekly
        if (!w.enabled) return
        if (w.notifiedWeek != null && !w.notifiedWeek.isBefore(monday)) return
        val days = c.logRepository.intake(monday, monday.plusDays(6)).values.count { it.hasEntries && !it.incomplete }
        if (days == 0) return
        c.settingsStore.setWeekly(w.copy(notifiedWeek = monday))
        Notifications.weeklyReady(c.appContext, monday, days)
    }
}

/** The old Sunday-evening timer; work queued by an earlier version may still run once and does nothing. */
class WeeklyReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

/** «Закрыть» / «Не считать» from the notification: closes the day without opening the app. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val date = intent.getStringExtra(EXTRA_DATE)?.let(LocalDate::parse) ?: return
        val incomplete = intent.getStringExtra(EXTRA_ANSWER) == NO
        val pending = goAsync()
        val app = context.applicationContext as GrammovkaApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.logRepository.closeDay(date, if (incomplete) true else null)
                Notifications.cancel(context, Notifications.ID_REMINDER)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "app.grammovka.REMINDER_ANSWER"
        const val EXTRA_DATE = "date"
        const val EXTRA_ANSWER = "answer"
        const val YES = "yes"
        const val NO = "no"
    }
}
