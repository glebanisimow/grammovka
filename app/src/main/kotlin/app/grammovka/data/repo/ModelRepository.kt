package app.grammovka.data.repo

import app.grammovka.data.db.AppDatabase
import app.grammovka.data.db.ModelSnapshotEntity
import app.grammovka.data.db.WeightEntryEntity
import app.grammovka.data.settings.ProfileSettings
import app.grammovka.domain.engine.DayIntake
import app.grammovka.domain.engine.EnergyModel
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

/** Today's numbers for the UI; all arithmetic comes from the domain engine. */
data class DayModel(
    val snapshot: Snapshot,
    val allowance: Double,
    val warnings: Set<Warning>,
)

/**
 * Recomputes ModelSnapshot rows. A full replay from the first weigh-in is cheap (a few hundred days)
 * and keeps the smoothed TDEE consistent after edits to past days.
 */
class ModelRepository(
    private val db: AppDatabase,
    private val logs: LogRepository,
    private val engine: EnergyModel = EnergyModel(),
) {
    private val weights = db.weightDao()
    private val snapshots = db.snapshotDao()

    fun observeAll(): Flow<List<ModelSnapshotEntity>> = snapshots.observeAll()

    /** Returns today's model, or null when the profile is incomplete or there is no weigh-in yet. */
    suspend fun recompute(profileSettings: ProfileSettings, today: LocalDate): DayModel? {
        val profile = profileSettings.toProfile(today)
        val allWeights = weights.all().associate { it.date to it.kg }
        val first = allWeights.keys.minOrNull()
        if (profile == null || first == null || first.isAfter(today)) {
            snapshots.deleteFrom(LocalDate.MIN)
            return null
        }
        val days: Map<LocalDate, DayIntake> = logs.intake(first.minusDays(28), today)
        val run = engine.run(first, today, profile, days, allWeights, previousTdee = null)
        snapshots.deleteFrom(LocalDate.MIN)
        snapshots.upsertAll(run.map { it.toEntity() })
        val s = run.lastOrNull() ?: return null
        return DayModel(
            snapshot = s,
            allowance = engine.allowance(today, s.target, profile.budgetMode, days),
            warnings = engine.warnings(today, profile, s, days, allWeights),
        )
    }

    private fun Snapshot.toEntity() = ModelSnapshotEntity(
        date, trendWeight, tdeeRaw, tdee, target, deficit, capped, bmrFloor, proteinTarget, includedDays, weighIns,
    )
}
