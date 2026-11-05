package app.grammovka.domain.model

import kotlin.math.round

/** A catalog food as the domain sees it. Nutrients are per 100 g. */
data class CatalogFood(
    val id: Long,
    val name: String,
    val brand: String?,
    val aliases: List<String>,
    val per100: Nutrients,
    val source: FoodSource,
    val confidence: Confidence?,
    val usageCount: Int,
    val lastUsedAt: Long?,
) {
    val displayName: String get() = if (brand.isNullOrBlank()) name else "$name, $brand"
}

/** Nutrients of an actual eaten amount. */
data class Amount(val kcal: Double, val protein: Double, val fat: Double, val carbs: Double) {
    operator fun plus(o: Amount) = Amount(kcal + o.kcal, protein + o.protein, fat + o.fat, carbs + o.carbs)

    companion object {
        val ZERO = Amount(0.0, 0.0, 0.0, 0.0)
    }
}

/** Grams × nutrients: the only place this multiplication happens (invariant 3). */
object Nutrition {
    fun forGrams(per100: Nutrients, grams: Double): Amount {
        val k = grams / 100.0
        return Amount(per100.kcal * k, per100.protein * k, per100.fat * k, per100.carbs * k)
    }

    fun sum(items: Iterable<Amount>): Amount = items.fold(Amount.ZERO, Amount::plus)

    /** Nutrients per 100 g from a label that states them per serving. */
    fun per100FromServing(perServing: Nutrients, servingG: Double): Nutrients {
        require(servingG > 0)
        val k = 100.0 / servingG
        return Nutrients(
            perServing.kcal * k, perServing.protein * k, perServing.fat * k, perServing.carbs * k,
            perServing.fiber?.let { it * k },
        )
    }

    /** kJ → kcal (1 kcal = 4.184 kJ). */
    fun kcalFromKj(kj: Double): Double = kj / 4.184
}

/** Display rounding: no needless decimals (UX principle). */
object Fmt {
    fun kcal(v: Double): String = round(v).toLong().toString()

    /** Grams of macros: one decimal below 10, whole above. */
    fun grams(v: Double): String = if (v < 10 && v != round(v)) "%.1f".format(v).replace('.', ',') else round(v).toLong().toString()

    fun kg(v: Double): String = "%.1f".format(v).replace('.', ',')
}

/**
 * A food about to enter the catalog: from a label, OFF, USDA, the web, Claude or manual input.
 * Invariant 1: [source] is always set; [sourceRef] is required for OFF (EAN), USDA (fdcId) and WEB (URL).
 */
data class FoodDraft(
    val name: String,
    val brand: String? = null,
    val aliases: List<String> = emptyList(),
    val per100: Nutrients,
    val basis: FoodBasis = FoodBasis.AS_SOLD,
    val source: FoodSource,
    val sourceRef: String? = null,
    val confidence: Confidence? = null,
    val barcode: String? = null,
    val portions: List<Pair<String, Double>> = emptyList(),
) {
    /** Problems that block saving (as opposed to validator warnings, which never block). */
    fun blockingProblems(): List<String> = buildList {
        if (name.isBlank()) add("Укажите название.")
        if (source in setOf(FoodSource.OFF, FoodSource.USDA, FoodSource.WEB) && sourceRef.isNullOrBlank()) {
            add("Для источника ${source.name} нужна ссылка на источник.")
        }
        if (source == FoodSource.CLAUDE_ESTIMATE && confidence == Confidence.HIGH) {
            add("Оценка Claude не может иметь уверенность «высокая».")
        }
    }
}
