package app.grammovka.domain.search

import app.grammovka.domain.text.Normalize

/** A catalog hit from FTS, with the data ranking needs. */
data class Candidate(
    val foodId: Long,
    val names: List<String>,
    val usageCount: Int,
    val lastUsedAt: Long?,
)

enum class MatchKind { EXACT, PREFIX, CONTAINS }

data class Ranked(val candidate: Candidate, val kind: MatchKind)

/**
 * Ranking (spec): exact name/alias match, then prefix match, then usage count, then recency.
 * Comparison uses [Normalize.text] on both sides.
 */
object FoodRanker {
    fun rank(query: String, candidates: List<Candidate>): List<Ranked> {
        val q = Normalize.text(query)
        return candidates
            .map { Ranked(it, kindOf(q, it)) }
            .sortedWith(
                compareBy<Ranked> { it.kind.ordinal }
                    .thenByDescending { it.candidate.usageCount }
                    .thenByDescending { it.candidate.lastUsedAt ?: Long.MIN_VALUE },
            )
    }

    /**
     * The fragment maps to one food without asking: a single exact match, or a single hit overall.
     * Several exact matches (e.g. two «творог 5%» brands) stay ambiguous.
     */
    fun unambiguous(ranked: List<Ranked>): Candidate? {
        val exact = ranked.filter { it.kind == MatchKind.EXACT }
        return when {
            exact.size == 1 -> exact.single().candidate
            exact.isEmpty() && ranked.size == 1 -> ranked.single().candidate
            else -> null
        }
    }

    private fun kindOf(q: String, c: Candidate): MatchKind {
        val names = c.names.map(Normalize::text)
        return when {
            names.any { it == q } -> MatchKind.EXACT
            names.any { it.startsWith(q) } -> MatchKind.PREFIX
            else -> MatchKind.CONTAINS
        }
    }
}
