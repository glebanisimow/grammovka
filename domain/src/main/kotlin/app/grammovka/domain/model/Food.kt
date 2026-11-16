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

/**
 * Nutrients of an actual eaten amount. [fiber] is the known part only; [fiberUnknown] counts the items whose
 * fiber is unknown, so a sum with [fiberUnknown] > 0 is a lower bound.
 */
data class Amount(
    val kcal: Double,
    val protein: Double,
    val fat: Double,
    val carbs: Double,
    val fiber: Double = 0.0,
    val fiberUnknown: Int = 0,
) {
    operator fun plus(o: Amount) =
        Amount(kcal + o.kcal, protein + o.protein, fat + o.fat, carbs + o.carbs, fiber + o.fiber, fiberUnknown + o.fiberUnknown)

    companion object {
        val ZERO = Amount(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Many sources leave fiber out. A missing value on a food with almost no carbohydrate (meat, fish, eggs, oil,
 * most cheese, black coffee) is taken as zero; on anything else it stays unknown and the day's total is shown as
 * a lower bound.
 */
object Fiber {
    /** Carbohydrate per 100 g below which a missing fiber value counts as zero. */
    const val NO_CARBS_PER_100G = 1.0

    fun unknown(fiber: Double?, carbsPer100: Double): Boolean = fiber == null && carbsPer100 >= NO_CARBS_PER_100G
}

/** Grams × nutrients: the only place this multiplication happens (invariant 3). */
object Nutrition {
    fun forGrams(per100: Nutrients, grams: Double): Amount {
        val k = grams / 100.0
        return Amount(
            per100.kcal * k, per100.protein * k, per100.fat * k, per100.carbs * k,
            fiber = (per100.fiber ?: 0.0) * k,
            fiberUnknown = if (Fiber.unknown(per100.fiber, per100.carbs)) 1 else 0,
        )
    }

    /** A logged entry's stored snapshot as an [Amount]; [fiber] null means the source had no fiber value. */
    fun snapshot(kcal: Double, protein: Double, fat: Double, carbs: Double, fiber: Double?, grams: Double): Amount {
        val carbsPer100 = if (grams > 0) carbs / grams * 100.0 else 0.0
        return Amount(kcal, protein, fat, carbs, fiber ?: 0.0, if (Fiber.unknown(fiber, carbsPer100)) 1 else 0)
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

    /** « · Кл 3» on a food line; « · Кл ?» when the value is missing on a food with carbohydrate; nothing otherwise. */
    fun fiberPart(fiberPer100: Double?, carbsPer100: Double): String = when {
        fiberPer100 != null -> " · Кл ${grams(fiberPer100)}"
        Fiber.unknown(null, carbsPer100) -> " · Кл ?"
        else -> ""
    }

    /** Fiber of a sum: «≥» when some items have no fiber value (a lower bound), «?» when nothing is known. */
    fun fiber(a: Amount): String = when {
        a.fiberUnknown == 0 -> grams(a.fiber)
        a.fiber < 0.05 -> "?"
        else -> "≥ " + grams(a.fiber)
    }
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
