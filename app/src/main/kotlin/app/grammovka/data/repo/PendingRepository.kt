package app.grammovka.data.repo

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.grammovka.data.db.PendingDao
import app.grammovka.data.db.PendingItemEntity
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.model.Meal
import app.grammovka.work.PendingWorker
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** A batch of deferred fragments for one date, offered for review as a single request. */
data class PendingBatch(val ids: List<Long>, val date: LocalDate, val time: LocalTime, val text: String, val method: InputMethod, val meal: Meal?)

/**
 * Offline queue (spec, phase 2): fragments that need the network are kept here. A WorkManager job with a
 * network constraint posts a notification once the network is back; nothing is logged without review.
 */
class PendingRepository(private val context: Context, private val dao: PendingDao, private val clock: () -> Long = System::currentTimeMillis) {

    fun observeCount(): Flow<Int> = dao.observeCount()
    suspend fun count(): Int = dao.count()

    suspend fun add(date: LocalDate, time: LocalTime, text: String, method: InputMethod, meal: Meal?) {
        dao.insert(PendingItemEntity(createdAt = clock(), date = date, time = time, text = text.trim(), inputMethod = method, meal = meal))
        scheduleRetry()
    }

    /** The oldest date's items joined into one input, or null when the queue is empty. */
    suspend fun nextBatch(): PendingBatch? {
        val all = dao.all()
        val date = all.firstOrNull()?.date ?: return null
        val rows = all.filter { it.date == date }
        return PendingBatch(rows.map { it.id }, date, rows.first().time, rows.joinToString("\n") { it.text }, rows.first().inputMethod, rows.firstNotNullOfOrNull { it.meal })
    }

    suspend fun delete(ids: List<Long>) = dao.delete(ids)

    fun scheduleRetry() {
        val request = OneTimeWorkRequestBuilder<PendingWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(2, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(PendingWorker.NAME, ExistingWorkPolicy.KEEP, request)
    }
}
