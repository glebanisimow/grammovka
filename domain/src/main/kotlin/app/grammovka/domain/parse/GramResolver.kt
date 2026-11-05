package app.grammovka.domain.parse

import app.grammovka.domain.text.Normalize

/** A named portion of a food, e.g. «шт» = 60 g. */
data class PortionDef(val name: String, val grams: Double)

object PortionNames {
    const val PIECE = "шт"
    const val TBSP = "ст. л."
    const val TSP = "ч. л."

    /** Recipe serving: cooked weight / number of servings. */
    const val SERVING = "порция"

    fun matches(portionName: String, canonical: String): Boolean =
        Normalize.text(portionName) == Normalize.text(canonical)
}

/** Result of turning a fragment's quantity into grams for a matched food. */
sealed interface Grams {
    data class Known(val grams: Double, val pieces: Double? = null) : Grams

    /** Pieces or spoons without a matching portion, or no quantity at all: ask the user. */
    data class Ask(val reason: AskReason) : Grams
}

enum class AskReason { NO_QUANTITY, NO_PIECE_PORTION, NO_SPOON_PORTION }

object GramResolver {
    /** Bare numbers up to this value mean pieces (or servings) when the food has a «шт» (or «порция») portion. */
    const val MAX_IMPLICIT_PIECES = 10.0

    fun resolve(fragment: Fragment, portions: List<PortionDef>): Grams {
        val serving = portions.firstOrNull { PortionNames.matches(it.name, PortionNames.SERVING) }
        val piece = portions.firstOrNull { PortionNames.matches(it.name, PortionNames.PIECE) } ?: serving
        val q = fragment.quantity
        return when (fragment.unit) {
            QtyUnit.G, QtyUnit.ML -> Grams.Known(q!!)
            QtyUnit.KG, QtyUnit.L -> Grams.Known(q!! * 1000)
            QtyUnit.PIECE ->
                if (piece != null) Grams.Known(q!! * piece.grams, q) else Grams.Ask(AskReason.NO_PIECE_PORTION)
            QtyUnit.SERVING ->
                if (serving != null) Grams.Known(q!! * serving.grams, q) else Grams.Ask(AskReason.NO_PIECE_PORTION)
            QtyUnit.TBSP -> spoon(q!!, portions, PortionNames.TBSP)
            QtyUnit.TSP -> spoon(q!!, portions, PortionNames.TSP)
            null -> when {
                q == null -> if (piece != null) Grams.Known(piece.grams, 1.0) else Grams.Ask(AskReason.NO_QUANTITY)
                q <= MAX_IMPLICIT_PIECES && piece != null -> Grams.Known(q * piece.grams, q)
                else -> Grams.Known(q)
            }
        }
    }

    private fun spoon(q: Double, portions: List<PortionDef>, name: String): Grams {
        val p = portions.firstOrNull { PortionNames.matches(it.name, name) } ?: return Grams.Ask(AskReason.NO_SPOON_PORTION)
        return Grams.Known(q * p.grams)
    }
}
