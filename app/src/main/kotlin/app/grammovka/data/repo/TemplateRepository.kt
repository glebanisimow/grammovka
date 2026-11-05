package app.grammovka.data.repo

import androidx.room.withTransaction
import app.grammovka.data.db.AppDatabase
import app.grammovka.data.db.FoodLineRow
import app.grammovka.data.db.MealTemplateEntity
import app.grammovka.data.db.MealTemplateItemEntity
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.model.Nutrition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class TemplateView(val id: Long, val name: String, val lines: List<FoodLineRow>) {
    val kcal: Double get() = lines.sumOf { Nutrition.forGrams(it.per100(), it.grams).kcal }
}

/** Meal templates: a named set of catalog foods with grams, logged in one tap with current catalog values. */
class TemplateRepository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.templateDao()

    fun observe(): Flow<List<TemplateView>> = combine(dao.observeAll(), dao.observeItems()) { ts, items ->
        val byOwner = items.groupBy { it.ownerId }
        ts.map { TemplateView(it.id, it.name, byOwner[it.id].orEmpty()) }.filter { it.lines.isNotEmpty() }
    }

    suspend fun create(name: String, items: List<Pair<Long, Double>>): Long = db.withTransaction {
        require(name.isNotBlank() && items.isNotEmpty())
        val id = dao.insert(MealTemplateEntity(name = name.trim(), createdAt = clock()))
        dao.insertItems(items.map { (f, g) -> MealTemplateItemEntity(templateId = id, foodId = f, grams = g) })
        id
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun entries(id: Long): List<NewEntry> =
        dao.items(id).map { NewEntry(it.foodId, it.displayName, it.grams, it.per100(), InputMethod.TEMPLATE) }
}
