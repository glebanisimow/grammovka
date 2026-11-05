package app.grammovka.domain.parse

enum class QtyUnit { G, KG, ML, L, PIECE, TBSP, TSP }

/** One food mention from free-text input, before catalog lookup. */
data class Fragment(
    val raw: String,
    val name: String,
    val quantity: Double? = null,
    val unit: QtyUnit? = null,
)

/**
 * Deterministic offline parser (spec: «Быстрый путь, без сети»).
 * Splits on `;`, newline, `+` and `,` (but not a decimal comma between digits), then reads
 * «название 230», «230 г название», «название 2 шт», «название 0,2 кг», «молоко 3,2% 200 мл».
 * A number followed by `%` belongs to the name.
 */
object InputParser {

    private val splitter = Regex("[;\\n+]|(?<!\\d),|,(?!\\d)")

    private const val NUM = "(\\d+(?:[.,]\\d+)?|\\d+/\\d+|½|¼|¾)"

    private val unitAlternatives = listOf(
        QtyUnit.KG to "кг|килограмм(?:а|ов)?",
        QtyUnit.G to "г|гр|грамм(?:а|ов)?",
        QtyUnit.ML to "мл|миллилитр(?:а|ов)?",
        QtyUnit.L to "л|литр(?:а|ов)?",
        QtyUnit.PIECE to "шт|штук(?:а|и)?",
        QtyUnit.TBSP to "ст\\.?\\s?л|столов(?:ая|ые|ых)\\s+лож(?:ка|ки|ек)",
        QtyUnit.TSP to "ч\\.?\\s?л|чайн(?:ая|ые|ых)\\s+лож(?:ка|ки|ек)",
    )

    private val UNIT = "(" + unitAlternatives.joinToString("|") { "(?:${it.second})" } + ")\\.?"

    /** «name 230», «name 230 г», «name 2шт.» — the quantity is not followed by `%`. */
    private val trailing = Regex("^(.+?)\\s+$NUM\\s*(?:$UNIT)?$", RegexOption.IGNORE_CASE)

    /** «230 г name», «2 яйца». */
    private val leading = Regex("^$NUM\\s*(?:$UNIT)?\\s+(.+)$", RegexOption.IGNORE_CASE)

    fun split(input: String): List<String> =
        input.split(splitter).map { it.trim() }.filter { it.isNotEmpty() }

    fun parse(input: String): List<Fragment> = split(input).map(::parseFragment)

    fun parseFragment(raw: String): Fragment {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        trailing.matchEntire(s)?.let { m ->
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty()) {
                return Fragment(raw, name, number(m.groupValues[2]), unit(m.groupValues[3]))
            }
        }
        leading.matchEntire(s)?.let { m ->
            return Fragment(raw, m.groupValues[3].trim(), number(m.groupValues[1]), unit(m.groupValues[2]))
        }
        return Fragment(raw, s)
    }

    internal fun number(s: String): Double = when (s) {
        "½" -> 0.5
        "¼" -> 0.25
        "¾" -> 0.75
        else -> if ('/' in s) {
            val (a, b) = s.split('/').map { it.toDouble() }
            a / b
        } else {
            s.replace(',', '.').toDouble()
        }
    }

    private fun unit(s: String): QtyUnit? {
        if (s.isEmpty()) return null
        val u = s.lowercase()
        return unitAlternatives.firstOrNull { Regex("^(?:${it.second})$", RegexOption.IGNORE_CASE).matches(u) }?.first
    }
}
