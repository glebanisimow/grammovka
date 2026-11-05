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
    version = 2,
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

    val ALL: Array<Migration> = arrayOf(M1_2)
}
