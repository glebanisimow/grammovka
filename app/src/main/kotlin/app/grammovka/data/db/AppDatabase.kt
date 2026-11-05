package app.grammovka.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate
import java.time.LocalTime

/** ISO strings keep dates sortable and readable in exports. */
class Converters {
    @TypeConverter fun dateToString(d: LocalDate?): String? = d?.toString()
    @TypeConverter fun stringToDate(s: String?): LocalDate? = s?.let(LocalDate::parse)
    @TypeConverter fun timeToString(t: LocalTime?): String? = t?.toString()
    @TypeConverter fun stringToTime(s: String?): LocalTime? = s?.let(LocalTime::parse)
}

/** Phase 0 only needs a smoke query; catalog DAOs arrive in phase 1. */
@Dao
interface MetaDao {
    @Query("SELECT COUNT(*) FROM food")
    suspend fun foodCount(): Int
}

@Database(
    version = 1,
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
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun metaDao(): MetaDao

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
    val ALL: Array<androidx.room.migration.Migration> = emptyArray()
}
