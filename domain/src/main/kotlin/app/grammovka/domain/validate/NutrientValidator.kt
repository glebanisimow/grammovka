package app.grammovka.domain.validate

import app.grammovka.domain.model.Nutrients
import kotlin.math.abs

/**
 * Checks a food draft (domain invariant 5). Problems are warnings for the confirm screen, never a block.
 */
object NutrientValidator {
    const val ENERGY_TOLERANCE = 0.15
    const val MAX_KCAL_PER_100G = 900.0
    const val MAX_MASS_PER_100G = 100.0

    sealed interface Issue {
        /** Declared kcal differs from 4·P + 4·C + 9·F + 2·fiber by more than [ENERGY_TOLERANCE]. */
        data class EnergyMismatch(val declared: Double, val computed: Double, val relDiff: Double) : Issue
        data object KcalTooHigh : Issue
        data object MassOver100 : Issue
        data object Negative : Issue
    }

    fun computedKcal(n: Nutrients): Double =
        4 * n.protein + 4 * n.carbs + 9 * n.fat + 2 * (n.fiber ?: 0.0)

    fun check(n: Nutrients): List<Issue> {
        val issues = mutableListOf<Issue>()
        if (listOfNotNull(n.kcal, n.protein, n.fat, n.carbs, n.fiber).any { it < 0 }) issues += Issue.Negative
        if (n.kcal > MAX_KCAL_PER_100G) issues += Issue.KcalTooHigh
        if (n.protein + n.fat + n.carbs + (n.fiber ?: 0.0) > MAX_MASS_PER_100G) issues += Issue.MassOver100
        val computed = computedKcal(n)
        if (n.kcal > 0) {
            val rel = abs(n.kcal - computed) / n.kcal
            if (rel > ENERGY_TOLERANCE) issues += Issue.EnergyMismatch(n.kcal, computed, rel)
        } else if (computed > 0) {
            issues += Issue.EnergyMismatch(n.kcal, computed, 1.0)
        }
        return issues
    }
}
