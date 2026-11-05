package app.grammovka.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface FoodDao {
    @Insert suspend fun insert(food: FoodEntity): Long
    @Update suspend fun update(food: FoodEntity)

    @Query("SELECT * FROM food WHERE id = :id") suspend fun byId(id: Long): FoodEntity?
    @Query("SELECT * FROM food WHERE id = :id") fun observe(id: Long): Flow<FoodEntity?>
    @Query("SELECT * FROM food WHERE barcode = :code ORDER BY archived, updatedAt DESC LIMIT 1")
    suspend fun byBarcode(code: String): FoodEntity?

    @Query("SELECT * FROM food WHERE archived = :archived ORDER BY usageCount DESC, lastUsedAt DESC, name")
    fun observeAll(archived: Boolean): Flow<List<FoodEntity>>

    @Query("SELECT * FROM food ORDER BY id") suspend fun all(): List<FoodEntity>

    /** [match] is an FTS4 MATCH expression built by Normalize.ftsMatch. */
    @Query(
        """SELECT food.* FROM food JOIN food_fts ON food.id = food_fts.rowid
           WHERE food_fts MATCH :match AND (:includeArchived OR food.archived = 0)
           ORDER BY food.usageCount DESC LIMIT :limit""",
    )
    suspend fun search(match: String, limit: Int, includeArchived: Boolean = false): List<FoodEntity>

    @Query("UPDATE food SET usageCount = usageCount + 1, lastUsedAt = :ts WHERE id = :id")
    suspend fun bumpUsage(id: Long, ts: Long)

    @Query("UPDATE food SET usageCount = MAX(usageCount - 1, 0) WHERE id = :id")
    suspend fun unbumpUsage(id: Long)

    @Query("UPDATE food SET archived = :archived, updatedAt = :ts WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, ts: Long)

    /** Frequent and recent foods with the grams of their last entry (chips on Today). */
    @Query(
        """SELECT f.id AS foodId, f.name AS name, f.usageCount AS usageCount, f.lastUsedAt AS lastUsedAt,
                  (SELECT l.grams FROM log_entry l WHERE l.foodId = f.id ORDER BY l.date DESC, l.time DESC LIMIT 1) AS lastGrams
           FROM food f WHERE f.archived = 0 AND f.usageCount > 0
           ORDER BY f.lastUsedAt DESC LIMIT 40""",
    )
    fun observeUsed(): Flow<List<UsedFoodRow>>

    @Query("SELECT * FROM portion WHERE foodId = :foodId ORDER BY grams") suspend fun portions(foodId: Long): List<PortionEntity>
    @Query("SELECT * FROM portion WHERE foodId = :foodId ORDER BY grams") fun observePortions(foodId: Long): Flow<List<PortionEntity>>
    @Query("SELECT * FROM portion ORDER BY id") suspend fun allPortions(): List<PortionEntity>
    @Query("DELETE FROM portion WHERE foodId = :foodId") suspend fun deletePortions(foodId: Long)
    @Insert suspend fun insertPortions(p: List<PortionEntity>)

    @Transaction
    suspend fun replacePortions(foodId: Long, p: List<PortionEntity>) {
        deletePortions(foodId)
        insertPortions(p.map { it.copy(id = 0, foodId = foodId) })
    }
}

data class UsedFoodRow(val foodId: Long, val name: String, val usageCount: Int, val lastUsedAt: Long?, val lastGrams: Double?)

data class DayTotalRow(val date: LocalDate, val kcal: Double, val protein: Double, val fat: Double, val carbs: Double, val entries: Int)

@Dao
interface LogDao {
    @Insert suspend fun insert(e: LogEntryEntity): Long
    @Insert suspend fun insertAll(e: List<LogEntryEntity>): List<Long>
    @Update suspend fun update(e: LogEntryEntity)
    @Query("SELECT * FROM log_entry WHERE id IN (:ids)") suspend fun byIds(ids: List<Long>): List<LogEntryEntity>
    @Query("DELETE FROM log_entry WHERE id IN (:ids)") suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM log_entry WHERE date = :date ORDER BY time, id") fun observeDay(date: LocalDate): Flow<List<LogEntryEntity>>
    @Query("SELECT * FROM log_entry WHERE date BETWEEN :from AND :to ORDER BY date, time, id")
    suspend fun between(from: LocalDate, to: LocalDate): List<LogEntryEntity>

    @Query(
        """SELECT date, SUM(kcal) AS kcal, SUM(protein) AS protein, SUM(fat) AS fat, SUM(carbs) AS carbs, COUNT(*) AS entries
           FROM log_entry WHERE date BETWEEN :from AND :to GROUP BY date ORDER BY date""",
    )
    suspend fun totals(from: LocalDate, to: LocalDate): List<DayTotalRow>

    /** Emits on any change to the log; used to trigger model recomputation. */
    @Query("SELECT COUNT(*) || '/' || IFNULL(MAX(id), 0) || '/' || IFNULL(SUM(kcal), 0) FROM log_entry")
    fun observeChecksum(): Flow<String>

    @Query("SELECT MIN(date) FROM log_entry") suspend fun firstDate(): LocalDate?

    @Query("SELECT grams FROM log_entry WHERE foodId = :foodId ORDER BY date DESC, time DESC, id DESC LIMIT 1")
    suspend fun lastGrams(foodId: Long): Double?
}

@Dao
interface DayStatusDao {
    @Upsert suspend fun upsert(s: DayStatusEntity)
    @Query("SELECT * FROM day_status WHERE date = :date") fun observe(date: LocalDate): Flow<DayStatusEntity?>
    @Query("SELECT * FROM day_status WHERE date BETWEEN :from AND :to") suspend fun between(from: LocalDate, to: LocalDate): List<DayStatusEntity>
    @Query("SELECT * FROM day_status ORDER BY date") suspend fun all(): List<DayStatusEntity>
    @Query("SELECT * FROM day_status ORDER BY date") fun observeAll(): Flow<List<DayStatusEntity>>
}

@Dao
interface WeightDao {
    @Upsert suspend fun upsert(w: WeightEntryEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfAbsent(w: WeightEntryEntity)
    @Query("DELETE FROM weight_entry WHERE date = :date") suspend fun delete(date: LocalDate)
    @Query("SELECT * FROM weight_entry WHERE date = :date") fun observe(date: LocalDate): Flow<WeightEntryEntity?>
    @Query("SELECT * FROM weight_entry ORDER BY date") fun observeAll(): Flow<List<WeightEntryEntity>>
    @Query("SELECT * FROM weight_entry ORDER BY date") suspend fun all(): List<WeightEntryEntity>
}

@Dao
interface SnapshotDao {
    @Upsert suspend fun upsertAll(s: List<ModelSnapshotEntity>)
    @Query("DELETE FROM model_snapshot WHERE date >= :from") suspend fun deleteFrom(from: LocalDate)
    @Query("SELECT * FROM model_snapshot WHERE date < :date ORDER BY date DESC LIMIT 1") suspend fun before(date: LocalDate): ModelSnapshotEntity?
    @Query("SELECT * FROM model_snapshot WHERE date = :date") fun observe(date: LocalDate): Flow<ModelSnapshotEntity?>
    @Query("SELECT * FROM model_snapshot ORDER BY date") fun observeAll(): Flow<List<ModelSnapshotEntity>>
    @Query("SELECT * FROM model_snapshot ORDER BY date") suspend fun all(): List<ModelSnapshotEntity>
}

@Dao
interface AiCallLogDao {
    @Insert suspend fun insert(e: AiCallLogEntity)
    @Query("SELECT IFNULL(SUM(costUsd), 0) FROM ai_call_log WHERE ts >= :since") fun observeCostSince(since: Long): Flow<Double>
    @Query("SELECT IFNULL(SUM(costUsd), 0) FROM ai_call_log WHERE ts >= :since") suspend fun costSince(since: Long): Double
    @Query("SELECT * FROM ai_call_log ORDER BY ts DESC LIMIT :limit") fun observeRecent(limit: Int): Flow<List<AiCallLogEntity>>
}

@Dao
interface RemoteCacheDao {
    @Query("SELECT * FROM remote_cache WHERE key = :key AND fetchedAt >= :freshSince") suspend fun get(key: String, freshSince: Long): RemoteCacheEntity?
    @Upsert suspend fun put(e: RemoteCacheEntity)
    @Query("DELETE FROM remote_cache WHERE fetchedAt < :before") suspend fun purge(before: Long)
}

/** A recipe or template line joined with its catalog food (current values, for editing and previews). */
data class FoodLineRow(
    val id: Long,
    val ownerId: Long,
    val foodId: Long,
    val grams: Double,
    val name: String,
    val brand: String?,
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val fiber: Double?,
)

@Dao
interface RecipeDao {
    @Insert suspend fun insert(r: RecipeEntity): Long
    @Update suspend fun update(r: RecipeEntity)
    @Query("SELECT * FROM recipe WHERE id = :id") suspend fun byId(id: Long): RecipeEntity?
    @Query("SELECT * FROM recipe WHERE foodId = :foodId") suspend fun byFoodId(foodId: Long): RecipeEntity?
    @Query("SELECT * FROM recipe ORDER BY name") suspend fun all(): List<RecipeEntity>
    @Query("DELETE FROM recipe WHERE id = :id") suspend fun delete(id: Long)

    @Query(
        "SELECT i.id, i.recipeId AS ownerId, i.foodId, i.grams, f.name, f.brand, f.kcal, f.protein, f.fat, f.carbs, f.fiber " +
            "FROM recipe_item i JOIN food f ON f.id = i.foodId WHERE i.recipeId = :recipeId ORDER BY i.id",
    )
    suspend fun items(recipeId: Long): List<FoodLineRow>

    @Query("SELECT * FROM recipe_item ORDER BY id") suspend fun allItems(): List<RecipeItemEntity>
    @Query("DELETE FROM recipe_item WHERE recipeId = :recipeId") suspend fun deleteItems(recipeId: Long)
    @Insert suspend fun insertItems(items: List<RecipeItemEntity>)

    @Transaction
    suspend fun replaceItems(recipeId: Long, items: List<RecipeItemEntity>) {
        deleteItems(recipeId)
        if (items.isNotEmpty()) insertItems(items)
    }
}

@Dao
interface TemplateDao {
    @Insert suspend fun insert(t: MealTemplateEntity): Long
    @Insert suspend fun insertItems(items: List<MealTemplateItemEntity>)
    @Query("DELETE FROM meal_template WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM meal_template ORDER BY createdAt DESC") fun observeAll(): Flow<List<MealTemplateEntity>>
    @Query("SELECT * FROM meal_template ORDER BY id") suspend fun all(): List<MealTemplateEntity>
    @Query("SELECT * FROM meal_template_item ORDER BY id") suspend fun allItems(): List<MealTemplateItemEntity>

    @Query(
        "SELECT i.id, i.templateId AS ownerId, i.foodId, i.grams, f.name, f.brand, f.kcal, f.protein, f.fat, f.carbs, f.fiber " +
            "FROM meal_template_item i JOIN food f ON f.id = i.foodId ORDER BY i.id",
    )
    fun observeItems(): Flow<List<FoodLineRow>>

    @Query(
        "SELECT i.id, i.templateId AS ownerId, i.foodId, i.grams, f.name, f.brand, f.kcal, f.protein, f.fat, f.carbs, f.fiber " +
            "FROM meal_template_item i JOIN food f ON f.id = i.foodId WHERE i.templateId = :templateId ORDER BY i.id",
    )
    suspend fun items(templateId: Long): List<FoodLineRow>
}
