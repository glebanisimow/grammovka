package app.grammovka.data.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.grammovka.data.db.AppDatabase
import app.grammovka.data.db.WeightEntryEntity
import app.grammovka.domain.health.ImportedWeight
import app.grammovka.domain.health.WeightImport
import app.grammovka.domain.model.WeightSource
import java.time.Duration
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

enum class HealthStatus { AVAILABLE, NEEDS_INSTALL_OR_UPDATE, UNSUPPORTED }

data class Workout(val date: LocalDate, val title: String, val minutes: Long)

/**
 * Health Connect (spec, phase 2): weight is imported into WeightEntry only for dates without a manual value;
 * steps and workouts are read for analytics only and never feed the expenditure estimate.
 * https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started
 * Reads happen while the app is open (no background-read permission); history beyond 30 days before the
 * first grant is not requested.
 */
class HealthRepository(private val context: Context, private val db: AppDatabase) {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    fun status(): HealthStatus = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthStatus.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthStatus.NEEDS_INSTALL_OR_UPDATE
        else -> HealthStatus.UNSUPPORTED
    }

    /** Play Store page of the provider (Android 9–13, where Health Connect is a separate app). */
    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW)
        .setData(Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding"))
        .setPackage("com.android.vending")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun client(): HealthConnectClient? =
        if (status() == HealthStatus.AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    suspend fun granted(): Set<String> = client()?.permissionController?.getGrantedPermissions().orEmpty()

    /** Imports weights of the last [days] days. Returns the number of dates written. */
    suspend fun syncWeights(days: Long = 30): Int {
        val c = client() ?: return 0
        if (HealthPermission.getReadPermission(WeightRecord::class) !in c.permissionController.getGrantedPermissions()) return 0
        val zone = ZoneId.systemDefault()
        val end = java.time.Instant.now()
        val start = LocalDate.now().minusDays(days).atStartOfDay(zone).toInstant()
        val records = buildList {
            var token: String? = null
            do {
                val r = c.readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(start, end), pageToken = token))
                addAll(r.records)
                token = r.pageToken
            } while (token != null)
        }
        val imported = records.map {
            val local = it.time.atZone(it.zoneOffset ?: zone.rules.getOffset(it.time)).toLocalDate()
            ImportedWeight(local, it.weight.inKilograms, it.time.toEpochMilli())
        }
        val dao = db.weightDao()
        val existing = dao.all().associate { it.date to it.source }
        val write = WeightImport.toWrite(existing, imported)
        write.forEach { (date, kg) -> dao.upsert(WeightEntryEntity(date, kg, WeightSource.HEALTH_CONNECT)) }
        return write.size
    }

    /** Steps per local day. Aggregation avoids double counting between sources. */
    suspend fun steps(from: LocalDate, to: LocalDate): Map<LocalDate, Long> {
        val c = client() ?: return emptyMap()
        if (HealthPermission.getReadPermission(StepsRecord::class) !in c.permissionController.getGrantedPermissions()) return emptyMap()
        val rows = c.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        return rows.mapNotNull { r -> r.result[StepsRecord.COUNT_TOTAL]?.let { r.startTime.toLocalDate() to it } }.toMap()
    }

    suspend fun workouts(from: LocalDate, to: LocalDate): List<Workout> {
        val c = client() ?: return emptyList()
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in c.permissionController.getGrantedPermissions()) return emptyList()
        val zone = ZoneId.systemDefault()
        val r = c.readRecords(
            ReadRecordsRequest(
                ExerciseSessionRecord::class,
                TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant()),
            ),
        )
        return r.records.map {
            Workout(
                it.startTime.atZone(it.startZoneOffset ?: zone.rules.getOffset(it.startTime)).toLocalDate(),
                it.title?.takeIf(String::isNotBlank) ?: typeName(it.exerciseType),
                Duration.between(it.startTime, it.endTime).toMinutes(),
            )
        }.sortedByDescending { it.date }
    }

    private fun typeName(t: Int): String = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Бег"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Ходьба"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Поход"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Велосипед"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Силовая"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Плавание"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Йога"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "Интервальная"
        else -> "Тренировка"
    }

    companion object {
        const val PROVIDER = "com.google.android.apps.healthdata"
    }
}
