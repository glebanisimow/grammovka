package app.grammovka.domain.model

/** Nutrients per 100 g. Fiber is optional because many sources omit it. */
data class Nutrients(
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val fiber: Double? = null,
)
