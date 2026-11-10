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
import app.grammovka.data.settings.WeeklyReviewSettings
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
 * Weekly review (spec, phase 3): Sunday evening → notification → the review screen. Same self-rescheduling
 * one-time work as the morning question.
 */
object WeeklyReview {
    private const val NAME = "weekly-review"

    fun schedule(context: Context, w: WeeklyReviewSettings) {
        val wm = WorkManager.getInstance(context)
        if (!w.enabled) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val delay = Daily.delayUntilNextWeekday(LocalDateTime.now(), DayOfWeek.SUNDAY, w.minuteOfDay)
        val request = OneTimeWorkRequestBuilder<WeeklyReviewWorker>().setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS).build()
        wm.enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

class WeeklyReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as GrammovkaApp).container
        val settings = c.settingsStore.current().weekly
        if (!settings.enabled) return Result.success()
        val today = LocalDate.now()
        val monday = DayLedger.weekStart(today)
        // Nothing to review in a week without entries.
        val days = c.logRepository.intake(monday, today).values.count { it.hasEntries }
        if (days > 0) Notifications.weeklyReady(applicationContext, monday, days)
        WeeklyReview.schedule(applicationContext, settings)
        return Result.success()
    }
}

/** «Закрыть» / «Не полностью» from the notification: closes the day without opening the app. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val date = intent.getStringExtra(EXTRA_DATE)?.let(LocalDate::parse) ?: return
        val incomplete = intent.getStringExtra(EXTRA_ANSWER) == NO
        val pending = goAsync()
        val app = context.applicationContext as GrammovkaApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.logRepository.closeDay(date, incomplete)
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
