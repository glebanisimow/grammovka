package app.grammovka.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate
import java.time.LocalTime

/** ISO strings keep dates sortable and readable in exports. */
class Converters {
    @TypeConverter fun dateToString(d: LocalDate?): String? = d?.toString()
    @TypeConverter fun stringToDate(s: String?): LocalDate? = s?.let(LocalDate::parse)
    @TypeConverter fun timeToString(t: LocalTime?): String? = t?.toString()
    @TypeConverter fun stringToTime(s: String?): LocalTime? = s?.let(LocalTime::parse)
}

@Database(
    version = 5,
    exportSchema = true,
    entities = [
        FoodEntity::class,
        FoodFts::class,
        PortionEntity::class,
        RecipeEntity::class,
        RecipeItemEntity::class,
        MealTemplateEntity::class,
        MealTemplateItemEntity::class,
        LogEntryEntity::class,
        DayStatusEntity::class,
        WeightEntryEntity::class,
        ModelSnapshotEntity::class,
        AiCallLogEntity::class,
        PendingItemEntity::class,
        RemoteCacheEntity::class,
        ActivityEntryEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao
    abstract fun logDao(): LogDao
    abstract fun dayStatusDao(): DayStatusDao
    abstract fun weightDao(): WeightDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun aiCallLogDao(): AiCallLogDao
    abstract fun remoteCacheDao(): RemoteCacheDao
    abstract fun recipeDao(): RecipeDao
    abstract fun templateDao(): TemplateDao
    abstract fun pendingDao(): PendingDao
    abstract fun activityDao(): ActivityDao

    companion object {
        const val NAME = "grammovka.db"

        /** Explicit migrations only: no fallbackToDestructiveMigration, ever. */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

object Migrations {
    /** v2: remote_cache for OFF/USDA responses. */
    val M1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `remote_cache` (`key` TEXT NOT NULL, `body` TEXT NOT NULL, " +
                    "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            )
        }
    }

    /** v3: recipe → materialized food link; optional meal on log entries and deferred items. */
    val M2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ADD COLUMN with REFERENCES is allowed because the implicit default is NULL.
            db.execSQL("ALTER TABLE `recipe` ADD COLUMN `foodId` INTEGER REFERENCES `food`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_recipe_foodId` ON `recipe` (`foodId`)")
            db.execSQL("ALTER TABLE `log_entry` ADD COLUMN `meal` TEXT")
            db.execSQL("ALTER TABLE `pending_item` ADD COLUMN `meal` TEXT")
        }
    }

    /** v4: logged workouts. */
    val M3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `activity_entry` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, " +
                    "`time` TEXT NOT NULL, `type` TEXT NOT NULL, `minutes` REAL NOT NULL, `distanceKm` REAL, `avgHr` INTEGER, " +
                    "`intensity` TEXT, `description` TEXT NOT NULL, `kcal` REAL NOT NULL, `source` TEXT NOT NULL, " +
                    "`formulaKcal` REAL, `claudeKcal` REAL, `note` TEXT, `createdAt` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_entry_date` ON `activity_entry` (`date`)")
        }
    }

    /**
     * v5: day lifecycle (started / closed). Days that already have food or workouts count as started
     * and stay open, so the user closes them explicitly.
     */
    val M4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `day_status` ADD COLUMN `startedAt` INTEGER")
            db.execSQL("ALTER TABLE `day_status` ADD COLUMN `closedAt` INTEGER")
            val days = "SELECT `date` FROM `log_entry` UNION SELECT `date` FROM `activity_entry`"
            db.execSQL("INSERT OR IGNORE INTO `day_status` (`date`, `incomplete`) SELECT `date`, 0 FROM ($days)")
            db.execSQL("UPDATE `day_status` SET `startedAt` = ${System.currentTimeMillis()} WHERE `date` IN ($days)")
        }
    }

    val ALL: Array<Migration> = arrayOf(M1_2, M2_3, M3_4, M4_5)
}
