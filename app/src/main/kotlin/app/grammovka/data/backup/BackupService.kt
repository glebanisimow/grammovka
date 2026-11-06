package app.grammovka.data.backup

import androidx.room.withTransaction
import app.grammovka.data.db.AppDatabase
import app.grammovka.data.db.DayStatusEntity
import app.grammovka.data.db.FoodEntity
import app.grammovka.data.db.LogEntryEntity
import app.grammovka.data.db.MealTemplateEntity
import app.grammovka.data.db.MealTemplateItemEntity
import app.grammovka.data.db.RecipeEntity
import app.grammovka.data.db.RecipeItemEntity
import app.grammovka.data.db.PortionEntity
import app.grammovka.data.db.WeightEntryEntity
import app.grammovka.data.settings.AppSettings
import app.grammovka.data.settings.ProfileSettings
import app.grammovka.data.settings.SettingsStore
import app.grammovka.domain.engine.BudgetMode
import app.grammovka.domain.engine.Sex
import app.grammovka.domain.model.AiPurpose
import app.grammovka.domain.model.Confidence
import app.grammovka.domain.model.FoodBasis
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.model.Meal
import app.grammovka.domain.model.WeightSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

@Serializable
data class FoodDto(
    val id: Long, val name: String, val brand: String? = null, val aliases: List<String> = emptyList(),
    val kcal: Double, val protein: Double, val fat: Double, val carbs: Double, val fiber: Double? = null,
    val basis: String, val source: String, val sourceRef: String? = null, val confidence: String? = null,
    val barcode: String? = null, val usageCount: Int = 0, val lastUsedAt: Long? = null, val archived: Boolean = false,
    val createdAt: Long, val updatedAt: Long,
    val portions: List<PortionDto> = emptyList(),
)

@Serializable data class PortionDto(val name: String, val grams: Double)

@Serializable
data class ActivityDto(
    val date: String, val time: String, val type: String, val minutes: Double, val distanceKm: Double? = null,
    val avgHr: Int? = null, val intensity: String? = null, val description: String = "", val kcal: Double,
    val source: String, val formulaKcal: Double? = null, val claudeKcal: Double? = null, val note: String? = null, val createdAt: Long = 0,
)

@Serializable
data class EntryDto(
    val date: String, val time: String, val foodId: Long? = null, val foodName: String, val grams: Double,
    val kcal: Double, val protein: Double, val fat: Double, val carbs: Double, val inputMethod: String, val note: String? = null,
    val meal: String? = null,
)

@Serializable data class DayStatusDto(val date: String, val incomplete: Boolean, val note: String? = null)
@Serializable data class WeightDto(val date: String, val kg: Double, val source: String)
@Serializable data class LineDto(val foodId: Long, val grams: Double)
@Serializable data class RecipeDto(val id: Long, val name: String, val cookedWeightG: Double, val foodId: Long? = null, val items: List<LineDto>)
@Serializable data class TemplateDto(val id: Long, val name: String, val createdAt: Long, val items: List<LineDto>)

@Serializable
data class SettingsDto(
    val sex: String? = null, val birthYear: Int? = null, val heightCm: Double? = null,
    val activityFactor: Double, val ratePercentPerWeek: Double, val proteinPerKg: Double, val budgetMode: String,
    val aiMonthlyLimitUsd: Double, val models: Map<String, String> = emptyMap(),
)

/** Full export. API keys are never included. */
@Serializable
data class BackupDto(
    val format: String = FORMAT,
    val version: Int = 3,
    val exportedAt: Long,
    val foods: List<FoodDto>,
    val entries: List<EntryDto>,
    val dayStatus: List<DayStatusDto>,
    val weights: List<WeightDto>,
    val settings: SettingsDto,
    val recipes: List<RecipeDto> = emptyList(),
    val templates: List<TemplateDto> = emptyList(),
    val activities: List<ActivityDto> = emptyList(),
) {
    companion object {
        const val FORMAT = "grammovka-backup"
    }
}

/** ISO strings compare lexicographically in SQL, so bounds must be plain 4-digit years. */
private val FIRST_DAY: LocalDate = LocalDate.of(1900, 1, 1)
private val LAST_DAY: LocalDate = LocalDate.of(2999, 12, 31)

data class ImportSummary(val foods: Int, val entries: Int, val weights: Int)

/**
 * Export/import through Storage Access Framework streams (spec: «Безопасность»):
 * full JSON (catalog, log, weights, settings without secrets) and two CSVs (entries; daily totals with weight, trend, target).
 */
class BackupService(private val db: AppDatabase, private val settings: SettingsStore) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun exportJson(out: OutputStream) {
        val foods = db.foodDao().all()
        val portions = db.foodDao().allPortions().groupBy { it.foodId }
        val s = settings.current()
        val dto = BackupDto(
            exportedAt = System.currentTimeMillis(),
            foods = foods.map { f ->
                FoodDto(
                    f.id, f.name, f.brand, f.aliases.split('\n').filter { it.isNotBlank() }, f.kcal, f.protein, f.fat, f.carbs, f.fiber,
                    f.basis.name, f.source.name, f.sourceRef, f.confidence?.name, f.barcode, f.usageCount, f.lastUsedAt, f.archived,
                    f.createdAt, f.updatedAt, portions[f.id].orEmpty().map { PortionDto(it.name, it.grams) },
                )
            },
            entries = db.logDao().between(FIRST_DAY, LAST_DAY).map {
                EntryDto(it.date.toString(), it.time.toString(), it.foodId, it.foodName, it.grams, it.kcal, it.protein, it.fat, it.carbs, it.inputMethod.name, it.note, it.meal?.name)
            },
            dayStatus = db.dayStatusDao().all().map { DayStatusDto(it.date.toString(), it.incomplete, it.note) },
            weights = db.weightDao().all().map { WeightDto(it.date.toString(), it.kg, it.source.name) },
            settings = s.toDto(),
            activities = db.activityDao().all().map {
                ActivityDto(
                    it.date.toString(), it.time.toString(), it.type.name, it.minutes, it.distanceKm, it.avgHr, it.intensity?.name,
                    it.description, it.kcal, it.source.name, it.formulaKcal, it.claudeKcal, it.note, it.createdAt,
                )
            },
            recipes = db.recipeDao().allItems().groupBy { it.recipeId }.let { items ->
                db.recipeDao().all().map { r -> RecipeDto(r.id, r.name, r.cookedWeightG, r.foodId, items[r.id].orEmpty().map { LineDto(it.foodId, it.grams) }) }
            },
            templates = db.templateDao().allItems().groupBy { it.templateId }.let { items ->
                db.templateDao().all().map { t -> TemplateDto(t.id, t.name, t.createdAt, items[t.id].orEmpty().map { LineDto(it.foodId, it.grams) }) }
            },
        )
        out.write(json.encodeToString(BackupDto.serializer(), dto).toByteArray(Charsets.UTF_8))
    }

    /** Replaces catalog, log, statuses and weights with the file's content. Keys and theme stay as they are. */
    suspend fun importJson(input: InputStream): ImportSummary {
        val dto = json.decodeFromString(BackupDto.serializer(), input.readBytes().toString(Charsets.UTF_8))
        require(dto.format == BackupDto.FORMAT) { "not a grammovka backup" }
        db.withTransaction {
            db.clearAllTables()
            dto.foods.forEach { f ->
                db.foodDao().insert(
                    FoodEntity(
                        id = f.id, name = f.name, brand = f.brand, aliases = f.aliases.joinToString("\n"),
                        searchText = app.grammovka.domain.text.Normalize.searchText(listOfNotNull(f.name, f.brand).joinToString(" "), f.aliases),
                        kcal = f.kcal, protein = f.protein, fat = f.fat, carbs = f.carbs, fiber = f.fiber,
                        basis = FoodBasis.valueOf(f.basis), source = FoodSource.valueOf(f.source), sourceRef = f.sourceRef,
                        confidence = f.confidence?.let(Confidence::valueOf), barcode = f.barcode, usageCount = f.usageCount,
                        lastUsedAt = f.lastUsedAt, archived = f.archived, createdAt = f.createdAt, updatedAt = f.updatedAt,
                    ),
                )
                if (f.portions.isNotEmpty()) db.foodDao().insertPortions(f.portions.map { PortionEntity(0, f.id, it.name, it.grams) })
            }
            db.logDao().insertAll(
                dto.entries.map {
                    LogEntryEntity(
                        date = LocalDate.parse(it.date), time = LocalTime.parse(it.time), foodId = it.foodId, foodName = it.foodName,
                        grams = it.grams, kcal = it.kcal, protein = it.protein, fat = it.fat, carbs = it.carbs,
                        inputMethod = InputMethod.valueOf(it.inputMethod), note = it.note,
                        meal = it.meal?.let { m -> runCatching { Meal.valueOf(m) }.getOrNull() },
                    )
                },
            )
            dto.dayStatus.forEach { db.dayStatusDao().upsert(DayStatusEntity(LocalDate.parse(it.date), it.incomplete, it.note)) }
            dto.recipes.forEach { r ->
                db.recipeDao().insert(RecipeEntity(r.id, r.name, r.cookedWeightG, r.foodId))
                if (r.items.isNotEmpty()) db.recipeDao().insertItems(r.items.map { RecipeItemEntity(recipeId = r.id, foodId = it.foodId, grams = it.grams) })
            }
            dto.templates.forEach { t ->
                db.templateDao().insert(MealTemplateEntity(t.id, t.name, t.createdAt))
                if (t.items.isNotEmpty()) db.templateDao().insertItems(t.items.map { MealTemplateItemEntity(templateId = t.id, foodId = it.foodId, grams = it.grams) })
            }
            db.activityDao().insertAll(
                dto.activities.map {
                    app.grammovka.data.db.ActivityEntryEntity(
                        date = LocalDate.parse(it.date), time = LocalTime.parse(it.time),
                        type = app.grammovka.domain.activity.ActivityType.valueOf(it.type), minutes = it.minutes,
                        distanceKm = it.distanceKm, avgHr = it.avgHr,
                        intensity = it.intensity?.let { i -> runCatching { app.grammovka.domain.activity.Intensity.valueOf(i) }.getOrNull() },
                        description = it.description, kcal = it.kcal, source = app.grammovka.domain.activity.ActivitySource.valueOf(it.source),
                        formulaKcal = it.formulaKcal, claudeKcal = it.claudeKcal, note = it.note, createdAt = it.createdAt,
                    )
                },
            )
            dto.weights.forEach { db.weightDao().upsert(WeightEntryEntity(LocalDate.parse(it.date), it.kg, WeightSource.valueOf(it.source))) }
        }
        val st = dto.settings
        settings.setProfile(
            ProfileSettings(
                sex = st.sex?.let(Sex::valueOf), birthYear = st.birthYear, heightCm = st.heightCm,
                activityFactor = st.activityFactor, ratePercentPerWeek = st.ratePercentPerWeek, proteinPerKg = st.proteinPerKg,
                budgetMode = BudgetMode.valueOf(st.budgetMode),
            ),
        )
        settings.setAiMonthlyLimit(st.aiMonthlyLimitUsd)
        st.models.forEach { (k, v) -> runCatching { AiPurpose.valueOf(k) }.getOrNull()?.let { settings.setModel(it, v) } }
        return ImportSummary(dto.foods.size, dto.entries.size, dto.weights.size)
    }

    /** One row per log entry. */
    suspend fun exportEntriesCsv(out: OutputStream) {
        val rows = db.logDao().between(FIRST_DAY, LAST_DAY)
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("date,time,food,grams,kcal,protein,fat,carbs,input_method\n")
            rows.forEach { e ->
                w.write(listOf(e.date, e.time, csv(e.foodName), n(e.grams), n(e.kcal), n(e.protein), n(e.fat), n(e.carbs), e.inputMethod).joinToString(","))
                w.write("\n")
            }
        }
    }

    /** One row per day: totals, incomplete flag, weight, trend and target from model_snapshot. */
    suspend fun exportDaysCsv(out: OutputStream) {
        val totals = db.logDao().totals(FIRST_DAY, LAST_DAY).associateBy { it.date }
        val statuses = db.dayStatusDao().all().associateBy { it.date }
        val weights = db.weightDao().all().associateBy { it.date }
        val snaps = db.snapshotDao().all().associateBy { it.date }
        val workouts = db.activityDao().totals(FIRST_DAY, LAST_DAY).associate { it.date to it.kcal }
        val days = (totals.keys + weights.keys + snaps.keys + statuses.keys + workouts.keys).sorted()
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("date,kcal,protein,fat,carbs,entries,incomplete,weight_kg,trend_kg,tdee,target,workout_kcal\n")
            days.forEach { d ->
                val t = totals[d]
                val s = snaps[d]
                w.write(
                    listOf(
                        d, n(t?.kcal), n(t?.protein), n(t?.fat), n(t?.carbs), t?.entries ?: 0, statuses[d]?.incomplete == true,
                        n(weights[d]?.kg), n(s?.trendWeight), n(s?.tdee), n(s?.target), n(workouts[d]),
                    ).joinToString(","),
                )
                w.write("\n")
            }
        }
    }

    private fun AppSettings.toDto() = SettingsDto(
        profile.sex?.name, profile.birthYear, profile.heightCm, profile.activityFactor, profile.ratePercentPerWeek,
        profile.proteinPerKg, profile.budgetMode.name, aiMonthlyLimitUsd, models.mapKeys { it.key.name },
    )

    private fun n(v: Double?): String = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: ""
    private fun csv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
}
