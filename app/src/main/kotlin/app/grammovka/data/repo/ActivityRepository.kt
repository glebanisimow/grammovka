package app.grammovka.data.repo

import app.grammovka.data.db.ActivityEntryEntity
import app.grammovka.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Logged workouts. Their kcal go on top of the day's norm and out of the measured expenditure. */
class ActivityRepository(db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.activityDao()

    suspend fun add(items: List<ActivityEntryEntity>): List<Long> = dao.insertAll(items.map { it.copy(id = 0, createdAt = clock()) })
    suspend fun update(e: ActivityEntryEntity) = dao.update(e)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun restore(e: ActivityEntryEntity) = dao.insert(e.copy(id = 0))

    fun observeDay(date: LocalDate): Flow<List<ActivityEntryEntity>> = dao.observeDay(date)
    fun observeChanges(): Flow<String> = dao.observeChecksum()

    /** Net workout kcal per day; days without workouts are absent. */
    suspend fun totals(from: LocalDate, to: LocalDate): Map<LocalDate, Double> = dao.totals(from, to).associate { it.date to it.kcal }
}
