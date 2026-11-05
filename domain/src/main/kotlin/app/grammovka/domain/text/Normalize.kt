package app.grammovka.domain.text

/**
 * Search normalization shared by the catalog index and queries:
 * lowercase, ё→е, every non letter/digit becomes a space, spaces collapsed.
 */
object Normalize {
    private val nonWord = Regex("[^\\p{L}\\p{Nd}]+")

    fun text(s: String): String =
        s.lowercase().replace('ё', 'е').replace(nonWord, " ").trim()

    fun tokens(s: String): List<String> = text(s).split(' ').filter { it.isNotEmpty() }

    /** Text stored in the FTS column: name plus aliases, normalized. */
    fun searchText(name: String, aliases: List<String>): String =
        (listOf(name) + aliases).joinToString(" ") { text(it) }.trim()

    /**
     * FTS4 MATCH expression: every token as a quoted prefix query, implicitly AND-ed.
     * Quoting keeps FTS operators (OR, NEAR, -) in user input literal.
     */
    fun ftsMatch(query: String): String? {
        val toks = tokens(query)
        if (toks.isEmpty()) return null
        return toks.joinToString(" ") { "\"$it\"*" }
    }
}
