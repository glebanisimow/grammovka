package app.grammovka.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import app.grammovka.domain.model.AiPurpose
import app.grammovka.domain.model.Confidence
import app.grammovka.domain.model.FoodBasis
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.model.WeightSource
import java.time.LocalDate
import java.time.LocalTime

/**
 * Catalog food. Nutrients are per 100 g (drinks: per 100 ml, treated as grams).
 * [aliases] is newline-separated user synonyms; [searchText] is the normalized name + aliases
 * (lowercase, ё→е, no punctuation) that [FoodFts] indexes.
 */
@Entity(
    tableName = "food",
    indices = [Index("barcode"), Index("archived")],
)
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val brand: String? = null,
    val aliases: String = "",
    val searchText: String,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val fiber: Double? = null,
    val basis: FoodBasis,
    val source: FoodSource,
    val sourceRef: String? = null,
    val confidence: Confidence? = null,
    val barcode: String? = null,
    val usageCount: Int = 0,
    val lastUsedAt: Long? = null,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

/** External-content FTS4 index over [FoodEntity.searchText]; rowid = food.id. */
@Fts4(contentEntity = FoodEntity::class)
@Entity(tableName = "food_fts")
data class FoodFts(val searchText: String)

@Entity(
    tableName = "portion",
    foreignKeys = [ForeignKey(FoodEntity::class, ["id"], ["foodId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("foodId")],
)
data class PortionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val foodId: Long,
    val name: String,
    val grams: Double,
)

@Entity(tableName = "recipe")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val cookedWeightG: Double,
)

@Entity(
    tableName = "recipe_item",
    foreignKeys = [
        ForeignKey(RecipeEntity::class, ["id"], ["recipeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(FoodEntity::class, ["id"], ["foodId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("recipeId"), Index("foodId")],
)
data class RecipeItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val foodId: Long,
    val grams: Double,
)

@Entity(tableName = "meal_template")
data class MealTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "meal_template_item",
    foreignKeys = [
        ForeignKey(MealTemplateEntity::class, ["id"], ["templateId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(FoodEntity::class, ["id"], ["foodId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("templateId"), Index("foodId")],
)
data class MealTemplateItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val foodId: Long,
    val grams: Double,
)

/** A logged item with a nutrient snapshot: editing the food later never rewrites history (invariant 4). */
@Entity(
    tableName = "log_entry",
    foreignKeys = [ForeignKey(FoodEntity::class, ["id"], ["foodId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("date"), Index("foodId")],
)
data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val time: LocalTime,
    val foodId: Long?,
    val foodName: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val inputMethod: InputMethod,
    val note: String? = null,
)

@Entity(tableName = "day_status")
data class DayStatusEntity(
    @PrimaryKey val date: LocalDate,
    val incomplete: Boolean,
    val note: String? = null,
)

/** One value per date; a manual weigh-in replaces an imported one. */
@Entity(tableName = "weight_entry")
data class WeightEntryEntity(
    @PrimaryKey val date: LocalDate,
    val kg: Double,
    val source: WeightSource,
)

@Entity(tableName = "model_snapshot")
data class ModelSnapshotEntity(
    @PrimaryKey val date: LocalDate,
    val trendWeight: Double,
    val tdeeRaw: Double?,
    val tdee: Double,
    val target: Double,
    val deficit: Double,
    val capped: Boolean,
    val bmrFloor: Boolean,
    val proteinTarget: Double,
    val includedDays: Int,
    val weighIns: Int,
)

@Entity(tableName = "ai_call_log", indices = [Index("ts")])
data class AiCallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val purpose: AiPurpose,
    val model: String,
    val promptVersion: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val webSearches: Int,
    val costUsd: Double,
    val ok: Boolean,
    val errorCode: String? = null,
)

/** Input that could not be resolved offline; retried when the network is back (phase 2). */
@Entity(tableName = "pending_item")
data class PendingItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val date: LocalDate,
    val time: LocalTime,
    val text: String,
    val inputMethod: InputMethod,
)

/**
 * Cached responses of Open Food Facts and USDA (schema v2). OFF rate limits are strict:
 * answers are reused for 30 days. [key] is e.g. "off:product:5900512320359" or "usda:search:pear asian".
 */
@Entity(tableName = "remote_cache")
data class RemoteCacheEntity(
    @PrimaryKey val key: String,
    val body: String,
    val fetchedAt: Long,
)
