package app.grammovka.data.repo

import androidx.room.withTransaction
import app.grammovka.data.db.AppDatabase
import app.grammovka.data.db.FoodLineRow
import app.grammovka.data.db.PortionEntity
import app.grammovka.data.db.RecipeEntity
import app.grammovka.data.db.RecipeItemEntity
import app.grammovka.domain.model.FoodBasis
import app.grammovka.domain.model.FoodDraft
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.recipe.Ingredient
import app.grammovka.domain.recipe.RecipeMath

fun FoodLineRow.per100() = Nutrients(kcal, protein, fat, carbs, fiber)
val FoodLineRow.displayName: String get() = if (brand.isNullOrBlank()) name else "$name, $brand"

data class RecipeData(val id: Long?, val name: String, val cookedWeightG: Double, val servings: Int?, val items: List<FoodLineRow>)

/**
 * Recipes live in the catalog as a food with source RECIPE (basis COOKED). Saving recomputes that food
 * from current ingredient values; past log entries keep their own snapshot (invariant 4).
 */
class RecipeRepository(private val db: AppDatabase, private val foods: FoodRepository) {
    private val dao = db.recipeDao()

    suspend fun load(id: Long): RecipeData? {
        val r = dao.byId(id) ?: return null
        val serving = r.foodId?.let { fid -> db.foodDao().portions(fid).firstOrNull { it.name == SERVING } }
        val servings = serving?.let { Math.round(r.cookedWeightG / it.grams).toInt() }?.takeIf { it > 0 }
        return RecipeData(r.id, r.name, r.cookedWeightG, servings, dao.items(id))
    }

    suspend fun byFoodId(foodId: Long): RecipeEntity? = dao.byFoodId(foodId)
    suspend fun foodIdOf(id: Long): Long? = dao.byId(id)?.foodId

    /** Creates or updates the recipe and its catalog food. Returns the recipe id. */
    suspend fun save(id: Long?, name: String, cookedWeightG: Double, servings: Int?, items: List<Pair<Long, Double>>): Long =
        db.withTransaction {
            val existing = id?.let { dao.byId(it) }
            val clean = items.filter { it.second > 0 && it.first != existing?.foodId }
            val ingredients = clean.map { (fid, g) ->
                val f = requireNotNull(foods.byId(fid)) { "food $fid missing" }
                Ingredient(Nutrients(f.kcal, f.protein, f.fat, f.carbs, f.fiber), g)
            }
            val per100 = requireNotNull(RecipeMath.per100(ingredients, cookedWeightG)) { "empty recipe" }
            val recipeId = existing?.id ?: dao.insert(RecipeEntity(name = name.trim(), cookedWeightG = cookedWeightG))
            val oldDraft = existing?.foodId?.let { foods.draftOf(it) }
            val otherPortions = oldDraft?.portions.orEmpty().filter { it.first != SERVING }
            val portions = otherPortions + listOfNotNull(servings?.takeIf { it > 0 }?.let { SERVING to cookedWeightG / it })
            val draft = (oldDraft ?: FoodDraft(name = name, per100 = per100, source = FoodSource.RECIPE)).copy(
                name = name.trim(), per100 = per100, basis = FoodBasis.COOKED, source = FoodSource.RECIPE,
                sourceRef = "recipe:$recipeId", confidence = null, portions = portions,
            )
            val foodId = if (oldDraft != null) {
                foods.update(existing.foodId!!, draft)
                existing.foodId
            } else {
                foods.create(draft)
            }
            dao.update(RecipeEntity(recipeId, name.trim(), cookedWeightG, foodId))
            dao.replaceItems(recipeId, clean.map { (fid, g) -> RecipeItemEntity(recipeId = recipeId, foodId = fid, grams = g) })
            recipeId
        }

    /** Removes the recipe; its catalog food is archived so past entries still resolve. */
    suspend fun delete(id: Long) = db.withTransaction {
        val r = dao.byId(id) ?: return@withTransaction
        r.foodId?.let { foods.setArchived(it, true) }
        dao.delete(id)
    }

    companion object {
        const val SERVING = app.grammovka.domain.parse.PortionNames.SERVING
    }
}
