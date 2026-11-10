package app.grammovka.domain.search

import app.grammovka.domain.text.Normalize

/**
 * Forgiving search for the catalog screen, where the user looks a food up by eye (the input line keeps the
 * strict FTS match: it logs without asking). Every query word must match some word of the food:
 * the whole word, its beginning, a part of it, or the same word with a typo. Latin diacritics are folded
 * («twarog» finds «Twaróg») and a query typed in the wrong keyboard layout is retried in the other one.
 */
object CatalogSearch {

    /** A food prepared for searching: folded words of its name, brand and synonyms. */
    class Doc(val id: Long, val name: String, val tokens: List<String>, val usageCount: Int, val lastUsedAt: Long?)

    fun doc(id: Long, name: String, brand: String?, aliases: List<String>, usageCount: Int, lastUsedAt: Long?): Doc {
        val full = fold(listOfNotNull(name, brand).joinToString(" "))
        val tokens = (listOf(full) + aliases.map(::fold)).flatMap { it.split(' ') }.filter { it.isNotEmpty() }.distinct()
        return Doc(id, full, tokens, usageCount, lastUsedAt)
    }

    /** Ids of matching foods, best first. A blank query matches nothing. */
    fun search(query: String, docs: List<Doc>): List<Long> {
        val direct = run(fold(query), docs)
        if (direct.isNotEmpty()) return direct
        val swapped = swapLayout(query)
        return if (swapped == query) direct else run(fold(swapped), docs)
    }

    private fun run(q: String, docs: List<Doc>): List<Long> {
        val words = q.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return docs.mapNotNull { d ->
            var score = 0
            for (w in words) {
                val s = d.tokens.maxOfOrNull { wordScore(w, it) } ?: 0
                if (s == 0) return@mapNotNull null
                score += s
            }
            if (d.name.startsWith(q)) score += NAME_PREFIX_BONUS
            if (d.name == q) score += NAME_PREFIX_BONUS
            score += minOf(d.usageCount, USAGE_BONUS_MAX)
            d to score
        }.sortedWith(
            compareByDescending<Pair<Doc, Int>> { it.second }
                .thenByDescending { it.first.lastUsedAt ?: Long.MIN_VALUE }
                .thenBy { it.first.name },
        ).map { it.first.id }
    }

    /** How well query word [q] matches food word [t]; 0 = no match. Numbers («5», «3 2») match only from the start. */
    internal fun wordScore(q: String, t: String): Int = when {
        t == q -> 100
        t.startsWith(q) -> 80
        q.all(Char::isDigit) -> 0
        q.length >= 2 && t.contains(q) -> 50
        q.length >= 4 && typoMatch(q, t) -> 30
        else -> 0
    }

    /** [q] is within one edit (two for long words) of [t] or of its beginning: the user is still typing. */
    private fun typoMatch(q: String, t: String): Boolean {
        val max = if (q.length >= 8) 2 else 1
        return (q.length - 1..q.length + 1).any { n -> n in 1..t.length && distance(q, t.substring(0, n), max) <= max } ||
            (t.length < q.length - 1 && distance(q, t, max) <= max)
    }

    /** Optimal string alignment distance (insert, delete, replace, swap of neighbours), cut off above [max]. */
    internal fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
            }
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** [Normalize.text] plus Latin diacritics folded to base letters (Polish product names). */
    fun fold(s: String): String = buildString {
        for (c in Normalize.text(s)) append(DIACRITICS[c] ?: c)
    }

    private val DIACRITICS: Map<Char, Char> = buildMap {
        fun put(from: String, to: Char) = from.forEach { put(it, to) }
        put("ąáàâäã", 'a'); put("ćč", 'c'); put("ęéèêë", 'e'); put("íìîï", 'i'); put("ł", 'l'); put("ńñ", 'n')
        put("óòôöõ", 'o'); put("śš", 's'); put("úùûü", 'u'); put("ýÿ", 'y'); put("źżž", 'z')
    }

    private const val EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
    private const val RU = "йцукенгшщзхъфывапролджэячсмитьбюё"

    /** The same keys in the other layout: «ndjhju» ↔ «творог». Mixed or other text is returned unchanged. */
    internal fun swapLayout(s: String): String {
        val lower = s.lowercase()
        val letters = lower.filter { it.isLetter() }
        val from: String
        val to: String
        when {
            letters.isNotEmpty() && letters.all { it in 'a'..'z' } -> { from = EN; to = RU }
            letters.isNotEmpty() && letters.all { it in RU } -> { from = RU; to = EN }
            else -> return s
        }
        return lower.map { c -> from.indexOf(c).let { if (it >= 0) to[it] else c } }.joinToString("")
    }

    private const val NAME_PREFIX_BONUS = 40
    private const val USAGE_BONUS_MAX = 20
}
