package app.grammovka.domain.parse

/**
 * Speech recognizers return one run-on sentence: «творог 150 грамм банан 2 штуки и кофе».
 * This inserts the separators the text parser expects, then the result goes through [InputParser]
 * like typed input (spec: «Голосовой ввод … в тот же парсер»).
 */
object VoiceText {
    private const val NUM = """\d+(?:[.,]\d+)?"""
    private val UNIT = InputParser.UNIT_PATTERN

    /** « и », « плюс », « ещё » between items. */
    private val conjunction = Regex("""\s+(?:и|плюс|ещё|еще)\s+""", RegexOption.IGNORE_CASE)

    /** A word, then a quantity (with optional unit), then the next word: «банан 2 штуки кофе» → «банан 2 штуки, кофе». */
    private val afterQuantity = Regex("""([\p{L}%)]\s+$NUM(?:\s*$UNIT)?)\s+(?=\p{L})(?!$UNIT(?!\p{L}))""", RegexOption.IGNORE_CASE)

    /** A word, quantity with a unit, then a leading quantity of the next item: «творог 150 г 2 яйца». */
    private val beforeLeadingQuantity = Regex("""([\p{L}%)]\s+$NUM\s*$UNIT)\s+(?=\d)""", RegexOption.IGNORE_CASE)

    fun toInput(spoken: String): String {
        var s = spoken.trim().replace(Regex("""\s+"""), " ")
        s = conjunction.replace(s, ", ")
        // Leading quantities first, so the unit letter of «150 г 2 яйца» is not taken for a word.
        s = beforeLeadingQuantity.replace(s, "$1, ")
        s = afterQuantity.replace(s, "$1, ")
        return s
    }
}
