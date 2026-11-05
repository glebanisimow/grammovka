package app.grammovka.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every migration is validated against the exported schema JSON (app/schemas). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2KeepsData() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO food (id, name, aliases, searchText, kcal, protein, fat, carbs, basis, source, usageCount, archived, createdAt, updatedAt) " +
                    "VALUES (1, 'Творог 5%', '', 'творог 5', 121, 17, 5, 1.8, 'AS_SOLD', 'LABEL', 3, 0, 0, 0)",
            )
            execSQL(
                "INSERT INTO log_entry (date, time, foodId, foodName, grams, kcal, protein, fat, carbs, inputMethod) " +
                    "VALUES ('2026-11-05', '08:30', 1, 'Творог 5%', 150, 181.5, 25.5, 7.5, 2.7, 'TEXT')",
            )
            close()
        }
        helper.runMigrationsAndValidate(dbName, 2, true, Migrations.M1_2).close()

        val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .build()
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM log_entry").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate2To3AddsRecipeFoodAndMeal() {
        helper.createDatabase(dbName, 2).apply {
            execSQL(
                "INSERT INTO food (id, name, aliases, searchText, kcal, protein, fat, carbs, basis, source, usageCount, archived, createdAt, updatedAt) " +
                    "VALUES (1, 'Гречка', '', 'гречка', 110, 4, 1, 21, 'COOKED', 'MANUAL', 0, 0, 0, 0)",
            )
            execSQL("INSERT INTO recipe (id, name, cookedWeightG) VALUES (1, 'Плов', 1200)")
            execSQL(
                "INSERT INTO log_entry (date, time, foodId, foodName, grams, kcal, protein, fat, carbs, inputMethod) " +
                    "VALUES ('2026-11-05', '08:30', 1, 'Гречка', 200, 220, 8, 2, 42, 'TEXT')",
            )
            close()
        }
        helper.runMigrationsAndValidate(dbName, 3, true, Migrations.M2_3).use { db ->
            db.query("SELECT foodId FROM recipe WHERE id = 1").use { assertEquals(true, it.moveToFirst() && it.isNull(0)) }
            db.query("SELECT meal FROM log_entry").use { assertEquals(true, it.moveToFirst() && it.isNull(0)) }
            db.execSQL("UPDATE recipe SET foodId = 1 WHERE id = 1")
            db.execSQL("UPDATE log_entry SET meal = 'BREAKFAST'")
        }
    }
}
