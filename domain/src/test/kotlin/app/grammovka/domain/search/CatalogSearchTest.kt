package app.grammovka.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSearchTest {
    private fun d(id: Long, name: String, brand: String? = null, vararg aliases: String, usage: Int = 0) =
        CatalogSearch.doc(id, name, brand, aliases.toList(), usage, null)

    private val catalog = listOf(
        d(1, "Творог 5%", "Piątnica", usage = 12),
        d(2, "Творожная масса с изюмом"),
        d(3, "Сыр творожный", usage = 3),
        d(4, "Twaróg półtłusty", "Mlekovita"),
        d(5, "Груша азиатская (наси)", null, "нэши"),
        d(6, "Молоко 3,2%", "Łaciate"),
        d(7, "Кофе с молоком"),
        d(8, "Хлеб ржаной"),
    )

    private fun find(q: String) = CatalogSearch.search(q, catalog)

    @Test fun prefixOfAnyWordInAnyOrder() {
        assertEquals(listOf(1L, 2L, 3L), find("твор"))
        assertEquals(listOf(8L), find("ржан хле"))
    }

    @Test fun partOfAWord() {
        assertEquals(listOf(7L, 6L), find("олок"))
    }

    @Test fun typoAndUnfinishedWord() {
        assertEquals(1L, find("тварог").first())
        assertEquals(listOf(5L), find("грушв"))
        assertEquals(listOf(5L), find("азиатскя"))
    }

    @Test fun diacriticsBrandAndAlias() {
        assertEquals(listOf(4L), find("twarog"))
        assertEquals(listOf(6L), find("laciate"))
        assertEquals(listOf(1L), find("piatnica"))
        assertEquals(listOf(5L), find("нэши"))
    }

    @Test fun numbersMatchFromTheStartOnly() {
        assertEquals(listOf(1L), find("творог 5"))
        assertEquals(listOf(6L), find("молоко 3"))
        assertTrue(find("молоко 2").isEmpty().not()) // «3,2» is two words: «3» and «2»
        assertTrue(find("творог 9").isEmpty())
    }

    @Test fun wrongKeyboardLayout() {
        assertEquals(1L, find("ndjhju").first())
        assertEquals(listOf(4L), find("ецфкщп"))
    }

    @Test fun everyWordMustMatchAndBlankFindsNothing() {
        assertTrue(find("творог банан").isEmpty())
        assertTrue(find("  ").isEmpty())
    }

    @Test fun exactNameBeatsFrequentPartialMatch() {
        val docs = listOf(d(1, "Сырок глазированный", usage = 50), d(2, "Сыр"))
        assertEquals(listOf(2L, 1L), CatalogSearch.search("сыр", docs))
    }

    @Test fun distance() {
        assertEquals(1, CatalogSearch.distance("творог", "тварог", 2))
        assertEquals(1, CatalogSearch.distance("творог", "товрог", 2))
        assertEquals(0, CatalogSearch.distance("сыр", "сыр", 1))
        assertTrue(CatalogSearch.distance("сыр", "хлеб", 1) > 1)
    }
}
