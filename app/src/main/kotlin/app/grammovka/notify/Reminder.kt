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
        // Ask only about a day with entries that is not already flagged: an empty day is excluded anyway.
        if (day != null && day.hasEntries && !day.incomplete) Notifications.yesterdayQuestion(applicationContext, yesterday, day.kcal)
        Reminder.schedule(applicationContext, settings)
        return Result.success()
    }
}

/** «Да» / «Нет — неполный» from the notification: sets the day flag without opening the app. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val date = intent.getStringExtra(EXTRA_DATE)?.let(LocalDate::parse) ?: return
        val incomplete = intent.getStringExtra(EXTRA_ANSWER) == NO
        val pending = goAsync()
        val app = context.applicationContext as GrammovkaApp
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.container.logRepository.setIncomplete(date, incomplete)
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
