package app.grammovka.domain.recipe

import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.model.Nutrition

data class Ingredient(val per100: Nutrients, val grams: Double)

/**
 * A dish's nutrients per 100 g of the cooked result: the sum of ingredient amounts divided by the
 * cooked weight (water boils off or is absorbed, so the raw sum of weights is not the dish weight).
 */
object RecipeMath {
    fun rawWeight(items: List<Ingredient>): Double = items.sumOf { it.grams }

    /** Null when there is nothing to compute from (no positive ingredients or cooked weight). */
    fun per100(items: List<Ingredient>, cookedWeightG: Double): Nutrients? {
        val valid = items.filter { it.grams > 0 }
        if (valid.isEmpty() || cookedWeightG <= 0) return null
        val total = Nutrition.sum(valid.map { Nutrition.forGrams(it.per100, it.grams) })
        val k = 100.0 / cookedWeightG
        val fiber = if (valid.all { it.per100.fiber == null }) null else valid.sumOf { (it.per100.fiber ?: 0.0) * it.grams / 100.0 } * k
        return Nutrients(total.kcal * k, total.protein * k, total.fat * k, total.carbs * k, fiber)
    }
}
