package app.grammovka.data.repo

import androidx.room.withTransaction
import app.grammovka.data.db.AppDatabase
import app.grammovka.data.db.DayStatusEntity
import app.grammovka.data.db.LogEntryEntity
import app.grammovka.domain.engine.DayIntake
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.model.Nutrition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime

/** One item to log: either a catalog food or a one-off entry without a catalog record. */
data class NewEntry(
    val foodId: Long?,
    val foodName: String,
    val grams: Double,
    val per100: Nutrients,
    val method: InputMethod,
    val note: String? = null,
)

class LogRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logDao = db.logDao()
    private val foodDao = db.foodDao()
    private val statusDao = db.dayStatusDao()

    /** Writes entries with a nutrient snapshot (invariant 4) and bumps food usage. Returns entry ids for Undo. */
    suspend fun log(date: LocalDate, time: LocalTime, items: List<NewEntry>): List<Long> = db.withTransaction {
        val now = clock()
        val ids = logDao.insertAll(
            items.map {
                val a = Nutrition.forGrams(it.per100, it.grams)
                LogEntryEntity(
                    date = date, time = time, foodId = it.foodId, foodName = it.foodName, grams = it.grams,
                    kcal = a.kcal, protein = a.protein, fat = a.fat, carbs = a.carbs, inputMethod = it.method, note = it.note,
                )
            },
        )
        items.mapNotNull { it.foodId }.forEach { foodDao.bumpUsage(it, now) }
        ids
    }

    /** Undo / delete: removes entries and rolls back usage counts. */
    suspend fun delete(ids: List<Long>) = db.withTransaction {
        val entries = logDao.byIds(ids)
        logDao.delete(ids)
        entries.mapNotNull { it.foodId }.forEach { foodDao.unbumpUsage(it) }
    }

    /** Changes grams of an entry, rescaling its own snapshot (not the catalog food). */
    suspend fun updateGrams(entry: LogEntryEntity, grams: Double) {
        if (entry.grams <= 0) return
        val k = grams / entry.grams
        logDao.update(entry.copy(grams = grams, kcal = entry.kcal * k, protein = entry.protein * k, fat = entry.fat * k, carbs = entry.carbs * k))
    }

    suspend fun lastGrams(foodId: Long): Double? = logDao.lastGrams(foodId)

    fun observeDay(date: LocalDate): Flow<List<LogEntryEntity>> = logDao.observeDay(date)

    fun observeStatus(date: LocalDate): Flow<Boolean> = statusDao.observe(date).map { it?.incomplete == true }

    suspend fun setIncomplete(date: LocalDate, incomplete: Boolean) = statusDao.upsert(DayStatusEntity(date, incomplete))

    fun observeChanges(): Flow<String> = logDao.observeChecksum()

    fun observeStatusChanges(): Flow<List<DayStatusEntity>> = statusDao.observeAll()

    /** Per-day totals for the engine. Days with no entries are absent. */
    suspend fun intake(from: LocalDate, to: LocalDate): Map<LocalDate, DayIntake> {
        val statuses = statusDao.between(from, to).associateBy { it.date }
        val totals = logDao.totals(from, to).associateBy { it.date }
        return (totals.keys + statuses.keys).associateWith { d ->
            val t = totals[d]
            DayIntake(
                kcal = t?.kcal ?: 0.0,
                protein = t?.protein ?: 0.0,
                hasEntries = (t?.entries ?: 0) > 0,
                incomplete = statuses[d]?.incomplete == true,
            )
        }
    }
}
