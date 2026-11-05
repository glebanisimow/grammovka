package app.grammovka.domain.parse

import app.grammovka.domain.model.Meal

/**
 * Optional meal marker at the start of the input: «завтрак: творог 200», «на обед суп 300»,
 * «перекус — яблоко». The meal is recorded only when said; otherwise it stays null.
 */
object MealPrefix {
    private val words = mapOf(
        "завтрак" to Meal.BREAKFAST, "обед" to Meal.LUNCH, "ужин" to Meal.DINNER, "перекус" to Meal.SNACK,
    )

    private val prefix = Regex(
        """^\s*(?:на\s+)?(завтрак|обед|ужин|перекус)(?:\s*[:\-—–]?\s+|[:\-—–]\s*)(.*)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    data class Result(val meal: Meal?, val rest: String)

    fun extract(input: String): Result {
        val m = prefix.matchEntire(input) ?: return Result(null, input)
        val rest = m.groupValues[2].trim()
        if (rest.isEmpty()) return Result(null, input)
        return Result(words.getValue(m.groupValues[1].lowercase()), rest)
    }
}
