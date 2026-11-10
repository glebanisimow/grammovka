package app.grammovka.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.grammovka.MainActivity
import app.grammovka.R
import app.grammovka.domain.model.Fmt
import java.time.LocalDate

/**
 * Notification channels and the two notifications of phase 2. Posting is skipped silently when the user
 * has not granted POST_NOTIFICATIONS (Android 13+).
 * https://developer.android.com/develop/ui/views/notifications/build-notification
 */
object Notifications {
    const val CHANNEL_PENDING = "pending"
    const val CHANNEL_REMINDER = "reminder"
    private const val ID_PENDING = 1001
    const val ID_REMINDER = 1002

    const val CHANNEL_WEEKLY = "weekly"
    private const val ID_WEEKLY = 1003

    const val EXTRA_OPEN_PENDING = "open_pending"

    /** Monday (ISO date) of the week whose review the notification opens. */
    const val EXTRA_OPEN_WEEKLY = "open_weekly"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PENDING, "Отложенные записи", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Сеть появилась: отложенное можно разобрать" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Утренний вопрос", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "«Вчера всё записано?»" },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WEEKLY, "Разбор недели", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "В воскресенье вечером: разбор питания за неделю" },
        )
    }

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun pendingReady(context: Context, count: Int) {
        if (!allowed(context)) return
        val open = PendingIntent.getActivity(
            context, ID_PENDING,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_PENDING, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_PENDING)
            .setSmallIcon(R.drawable.ic_stat_grammovka)
            .setContentTitle("Сеть появилась")
            .setContentText("Отложено записей: $count. Нажмите, чтобы разобрать и подтвердить.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(context, ID_PENDING, n)
    }

    /** «Вчера записано N ккал. Всё?» with «Да» and «Нет — неполный» answering from the shade. */
    fun yesterdayQuestion(context: Context, date: LocalDate, kcal: Double) {
        if (!allowed(context)) return
        fun action(answer: String, code: Int) = PendingIntent.getBroadcast(
            context, code,
            Intent(context, ReminderActionReceiver::class.java)
                .setAction(ReminderActionReceiver.ACTION)
                .putExtra(ReminderActionReceiver.EXTRA_DATE, date.toString())
                .putExtra(ReminderActionReceiver.EXTRA_ANSWER, answer),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, ID_REMINDER, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_grammovka)
            .setContentTitle("Закрыть вчерашний день?")
            .setContentText("Записано ${Fmt.kcal(kcal)} ккал. Если записано не всё, закройте как неполный: такой день норма не учитывает.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Закрыть", action(ReminderActionReceiver.YES, 2001))
            .addAction(0, "Не полностью", action(ReminderActionReceiver.NO, 2002))
            .build()
        notify(context, ID_REMINDER, n)
    }

    /** Sunday evening: the tap opens the review screen, where Claude writes the review (never in the background). */
    fun weeklyReady(context: Context, monday: LocalDate, days: Int) {
        if (!allowed(context)) return
        val open = PendingIntent.getActivity(
            context, ID_WEEKLY,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_WEEKLY, monday.toString()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_WEEKLY)
            .setSmallIcon(R.drawable.ic_stat_grammovka)
            .setContentTitle("Разбор недели")
            .setContentText("Дней с записями: $days. Нажмите, и Claude разберёт питание за неделю.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(context, ID_WEEKLY, n)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    @Suppress("MissingPermission") // checked in allowed()
    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
