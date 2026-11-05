package app.grammovka.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.grammovka.GrammovkaApp
import app.grammovka.notify.Notifications

/**
 * Runs when the network is back (WorkManager network constraint) and reminds the user to review the
 * deferred items. Claude is called from the review screen, so every number is confirmed (invariant 2).
 * https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#work-constraints
 */
class PendingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val n = (applicationContext as GrammovkaApp).container.pendingRepository.count()
        if (n > 0) Notifications.pendingReady(applicationContext, n)
        return Result.success()
    }

    companion object {
        const val NAME = "pending-retry"
    }
}
