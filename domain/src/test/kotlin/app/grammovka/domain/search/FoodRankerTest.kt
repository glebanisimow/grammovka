package app.grammovka.domain.search

import app.grammovka.domain.text.Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodRankerTest {
    private fun c(id: Long, vararg names: String, usage: Int = 0, last: Long? = null) =
        Candidate(id, names.toList(), usage, last)

    @Test
    fun exactBeatsPrefixBeatsUsage() {
        val ranked = FoodRanker.rank(
            "творог",
            listOf(
                c(1, "творожная масса", usage = 50),
                c(2, "творог 5%", usage = 10),
                c(3, "Творог", usage = 1),
            ),
        )
        assertEquals(listOf(3L, 2L, 1L), ranked.map { it.candidate.foodId })
    }

    @Test
    fun aliasMatchesAndYoIsE() {
        val ranked = FoodRanker.rank("мед", listOf(c(1, "Мёд гречишный", "мёд")))
        assertEquals(MatchKind.EXACT, ranked.single().kind)
    }

    @Test
    fun usageThenRecencyBreakTies() {
        val ranked = FoodRanker.rank(
            "сыр",
            listOf(c(1, "сыр гауда", usage = 3, last = 10), c(2, "сыр моцарелла", usage = 3, last = 20), c(3, "сыр бри", usage = 9)),
        )
        assertEquals(listOf(3L, 2L, 1L), ranked.map { it.candidate.foodId })
    }

    @Test
    fun unambiguous() {
        val one = FoodRanker.rank("творог 5%", listOf(c(1, "творог 5%"), c(2, "творог 5% пятёрочка")))
        assertEquals(1L, FoodRanker.unambiguous(one)?.foodId)
        val two = FoodRanker.rank("творог", listOf(c(1, "творог"), c(2, "Творог")))
        assertNull(FoodRanker.unambiguous(two))
        val single = FoodRanker.rank("груша", listOf(c(7, "груша азиатская")))
        assertEquals(7L, FoodRanker.unambiguous(single)?.foodId)
        val many = FoodRanker.rank("груша", listOf(c(7, "груша азиатская"), c(8, "груша конференция")))
        assertNull(FoodRanker.unambiguous(many))
    }

    @Test
    fun normalizeAndFts() {
        assertEquals("молоко 3 2", Normalize.text("Молоко 3,2%"))
        assertEquals("\"молоко\"* \"3\"* \"2\"*", Normalize.ftsMatch("Молоко 3,2%"))
        assertEquals("еж", Normalize.text("Ёж!"))
        assertNull(Normalize.ftsMatch(" ,.; "))
    }
}
