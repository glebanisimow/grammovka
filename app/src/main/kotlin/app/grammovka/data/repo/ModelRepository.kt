package app.grammovka.data.repo

import app.grammovka.data.db.AppDatabase
import app.grammovka.data.db.ModelSnapshotEntity
import app.grammovka.data.db.WeightEntryEntity
import app.grammovka.data.settings.ProfileSettings
import app.grammovka.domain.engine.DayBalance
import app.grammovka.domain.engine.DayIntake
import app.grammovka.domain.engine.EnergyModel
import app.grammovka.domain.engine.Profile
import app.grammovka.domain.engine.Snapshot
import app.grammovka.domain.engine.Warning
import app.grammovka.domain.model.WeightSource
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

class WeightRepository(db: AppDatabase) {
    private val dao = db.weightDao()

    /** Manual weigh-ins replace imported ones for the same date. */
    suspend fun setManual(date: LocalDate, kg: Double) = dao.upsert(WeightEntryEntity(date, kg, WeightSource.MANUAL))
    suspend fun delete(date: LocalDate) = dao.delete(date)
    fun observe(date: LocalDate): Flow<WeightEntryEntity?> = dao.observe(date)
    fun observeAll(): Flow<List<WeightEntryEntity>> = dao.observeAll()
}

/** One day's numbers for the UI; all arithmetic comes from the domain engine. */
data class DayModel(
    val snapshot: Snapshot,
    val allowance: Double,
    val warnings: Set<Warning>,
    /** Net kcal of the day's logged workouts, on top of the norm. */
    val activity: Double = 0.0,
    private val engine: EnergyModel = EnergyModel(),
) {
    fun balance(eaten: Double): DayBalance = engine.balance(allowance, snapshot.tdee, activity, eaten)
}

/** Result of a model replay: the per-day snapshots plus what is needed to derive any day's budget. */
class ModelState(
    private val engine: EnergyModel,
    private val profile: Profile,
    val days: Map<LocalDate, DayIntake>,
    val weights: Map<LocalDate, Double>,
    val snapshots: Map<LocalDate, Snapshot>,
) {
    fun day(date: LocalDate): DayModel? {
        val s = snapshots[date] ?: return null
        return DayModel(
            snapshot = s,
            allowance = engine.allowance(date, s.target, profile.budgetMode, days),
            warnings = engine.warnings(date, profile, s, days, weights),
            activity = days[date]?.activity ?: 0.0,
            engine = engine,
        )
    }
}

/**
 * Recomputes ModelSnapshot rows. A full replay from the first weigh-in is cheap (a few hundred days)
 * and keeps the smoothed TDEE consistent after edits to past days.
 */
class ModelRepository(
    private val db: AppDatabase,
    private val logs: LogRepository,
    private val activities: ActivityRepository,
    private val engine: EnergyModel = EnergyModel(),
) {
    private val weights = db.weightDao()
    private val snapshots = db.snapshotDao()

    fun observeAll(): Flow<List<ModelSnapshotEntity>> = snapshots.observeAll()

    /** Null when the profile is incomplete or there is no weigh-in yet. */
    suspend fun recompute(profileSettings: ProfileSettings, today: LocalDate): ModelState? {
        val profile = profileSettings.toProfile(today)
        val allWeights = weights.all().associate { it.date to it.kg }
        val first = allWeights.keys.minOrNull()
        if (profile == null || first == null || first.isAfter(today)) {
            snapshots.deleteFrom(LocalDate.of(1900, 1, 1))
            return null
        }
        val food = logs.intake(first.minusDays(28), today)
        val workouts = activities.totals(first.minusDays(28), today)
        val days: Map<LocalDate, DayIntake> = (food.keys + workouts.keys).associateWith { d ->
            (food[d] ?: DayIntake(0.0, 0.0, hasEntries = false, incomplete = false)).copy(activity = workouts[d] ?: 0.0)
        }
        val run = engine.run(first, today, profile, days, allWeights, previousTdee = null)
        snapshots.deleteFrom(LocalDate.of(1900, 1, 1))
        snapshots.upsertAll(run.map { it.toEntity() })
        return ModelState(engine, profile, days, allWeights, run.associateBy { it.date })
    }

    private fun Snapshot.toEntity() = ModelSnapshotEntity(
        date, trendWeight, tdeeRaw, tdee, target, deficit, capped, bmrFloor, proteinTarget, includedDays, weighIns,
    )
}
