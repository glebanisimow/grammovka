package app.grammovka.data.repo

import androidx.room.withTransaction
import app.grammovka.data.db.AppDatabase
import app.grammovka.data.db.DayStatusEntity
import app.grammovka.data.db.LogEntryEntity
import app.grammovka.domain.engine.DayIntake
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.model.Meal
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
    val meal: Meal? = null,
)

/** A write into a day the user closed; the day has to be reopened first. */
class DayClosedException(val date: LocalDate) : IllegalStateException("Day $date is closed")

/**
 * [onClosed] and [onStarted] run after a day is closed or started (outside the transaction); the weekly review
 * hangs on them.
 */
class LogRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onClosed: suspend (LocalDate) -> Unit = {},
    private val onStarted: suspend (LocalDate) -> Unit = {},
) {
    private val logDao = db.logDao()
    private val foodDao = db.foodDao()
    private val statusDao = db.dayStatusDao()

    /**
     * Writes entries with a nutrient snapshot (invariant 4) and bumps food usage. Returns entry ids for Undo.
     * Writing into a day starts it; a closed day refuses with [DayClosedException].
     */
    suspend fun log(date: LocalDate, time: LocalTime, items: List<NewEntry>): List<Long> = afterStart(date) {
        val now = clock()
        ensureWritable(date)
        val ids = logDao.insertAll(
            items.map {
                val a = Nutrition.forGrams(it.per100, it.grams)
                LogEntryEntity(
                    date = date, time = time, foodId = it.foodId, foodName = it.foodName, grams = it.grams,
                    kcal = a.kcal, protein = a.protein, fat = a.fat, carbs = a.carbs, inputMethod = it.method, note = it.note, meal = it.meal,
                )
            },
        )
        items.mapNotNull { it.foodId }.forEach { foodDao.bumpUsage(it, now) }
        ids
    }

    /**
     * Copies entries (e.g. yesterday's breakfast) to [date] as they were: same grams and nutrient snapshot,
     * same meal. Returns new ids for Undo.
     */
    suspend fun copy(entries: List<LogEntryEntity>, date: LocalDate, time: LocalTime, meal: Meal? = null): List<Long> = afterStart(date) {
        val now = clock()
        ensureWritable(date)
        val ids = logDao.insertAll(entries.map { it.copy(id = 0, date = date, time = time, inputMethod = InputMethod.QUICK, meal = meal ?: it.meal) })
        entries.mapNotNull { it.foodId }.forEach { foodDao.bumpUsage(it, now) }
        ids
    }

    suspend fun setMeal(entry: LogEntryEntity, meal: Meal?) = logDao.update(entry.copy(meal = meal))

    /** Undo of a deletion: puts the same entries back (new ids, same snapshot, time and meal). */
    suspend fun restore(entries: List<LogEntryEntity>) = db.withTransaction {
        val now = clock()
        logDao.insertAll(entries.map { it.copy(id = 0) })
        entries.mapNotNull { it.foodId }.forEach { foodDao.bumpUsage(it, now) }
    }

    /** Moves a whole card in time, keeping the order of its entries. */
    suspend fun shiftTime(entries: List<LogEntryEntity>, minutes: Long) = db.withTransaction {
        entries.forEach { logDao.update(it.copy(time = it.time.plusMinutes(minutes))) }
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

    fun observeStatus(date: LocalDate): Flow<DayStatusEntity?> = statusDao.observe(date)

    /** The day's free-text note («в поездке»); blank removes it. Any day, in any state. */
    suspend fun setNote(date: LocalDate, note: String?) = edit(date) { it.copy(note = note?.trim()?.takeIf(String::isNotEmpty)) }

    /**
     * «Не считаем» (user request 2026-11-15; the stored flag is the old «incomplete»): the day stays out of the
     * norm estimate, the weekly budget counts it at the target, the review leaves it out. It can be set at any
     * time, so marking a day that was never started also starts it.
     */
    suspend fun setSkipped(date: LocalDate, skipped: Boolean, note: String? = null) {
        val started = statusDao.get(date)?.startedAt == null && skipped
        edit(date) {
            it.copy(
                incomplete = skipped,
                startedAt = it.startedAt ?: if (skipped) clock() else null,
                note = if (note != null) note.trim().takeIf(String::isNotEmpty) else it.note,
            )
        }
        if (started) onStarted(date)
    }

    /** «Начать день». Idempotent. */
    suspend fun startDay(date: LocalDate) {
        val was = statusDao.get(date)?.startedAt != null
        edit(date) { if (it.startedAt == null) it.copy(startedAt = clock()) else it }
        if (!was) onStarted(date)
    }

    /** «Закрыть день»: the day is counted and takes no more writes. [skip] null keeps the «не считаем» mark as it is. */
    suspend fun closeDay(date: LocalDate, skip: Boolean? = null) {
        edit(date) { it.copy(startedAt = it.startedAt ?: clock(), closedAt = clock(), incomplete = skip ?: it.incomplete) }
        onClosed(date)
    }

    /** «Открыть для правки». The «не считаем» mark is the user's own choice and stays. */
    suspend fun reopenDay(date: LocalDate) = edit(date) { it.copy(closedAt = null) }

    suspend fun isClosed(date: LocalDate): Boolean = statusDao.get(date)?.closedAt != null

    /** The day the app opens on: the latest open day, otherwise today. */
    suspend fun defaultDay(today: LocalDate = LocalDate.now()): LocalDate =
        app.grammovka.domain.log.DayLedger(
            statusDao.all().associate { it.date to app.grammovka.domain.log.DayMark(it.startedAt != null, it.closedAt != null, it.incomplete) },
            today,
        ).defaultDay()

    private suspend fun edit(date: LocalDate, f: (DayStatusEntity) -> DayStatusEntity) = db.withTransaction {
        statusDao.upsert(f(statusDao.get(date) ?: DayStatusEntity(date, incomplete = false)))
    }

    /** Runs a write that may start [date] and reports the start once the transaction is done. */
    private suspend fun <T> afterStart(date: LocalDate, block: suspend () -> T): T {
        val was = statusDao.get(date)?.startedAt != null
        val r = db.withTransaction { block() }
        if (!was) onStarted(date)
        return r
    }

    private suspend fun ensureWritable(date: LocalDate) {
        val s = statusDao.get(date)
        if (s?.closedAt != null) throw DayClosedException(date)
        if (s?.startedAt == null) statusDao.upsert((s ?: DayStatusEntity(date, incomplete = false)).copy(startedAt = clock()))
    }

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
