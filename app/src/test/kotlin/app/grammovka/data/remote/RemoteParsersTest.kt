package app.grammovka.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers against responses recorded from the live APIs (src/test/resources/fixtures). */
class RemoteParsersTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    @Test
    fun offProductWithNutrients() {
        val p = OffClient.parseProduct(fixture("off_product_skyr.json"))!!
        assertEquals("5900531004407", p.code)
        assertTrue(p.brand!!.contains("Piątnica"))
        val n = p.per100!!
        assertEquals(66.0, n.kcal, 1e-9)
        assertEquals(12.0, n.protein, 1e-9)
        assertEquals(0.0, n.fat, 1e-9)
        assertEquals(4.6, n.carbs, 1e-9)
    }

    @Test
    fun offUnknownAndEmpty() {
        assertNull(OffClient.parseProduct(fixture("off_product_unknown.json")))
        val empty = OffClient.parseProduct(fixture("off_product_empty.json"))
        assertEquals("Bez tabeli", empty!!.name)
        assertNull(empty.per100)
    }

    @Test
    fun offKjFallback() {
        val n = OffClient.nutrients(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"energy-kj_100g": 418.4, "proteins_100g": 1, "fat_100g": 2, "carbohydrates_100g": 3}""",
            ) as kotlinx.serialization.json.JsonObject,
        )!!
        assertEquals(100.0, n.kcal, 1e-9)
        assertNull(n.fiber)
    }

    @Test
    fun offSearch() {
        val hits = OffClient.parseSearch(fixture("off_search_skyr.json"))
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.code.isNotEmpty() })
    }

    @Test
    fun usdaSearchAndFood() {
        val foods = UsdaClient.parseSearch(fixture("usda_search_asian_pear.json"))
        val pear = foods.first { it.fdcId == 168177L }
        assertEquals("Pears, asian, raw", pear.description)
        assertEquals(42.0, pear.per100!!.kcal, 1e-9)
        assertEquals(3.6, pear.per100!!.fiber!!, 1e-9)

        val detail = UsdaClient.parseFood(fixture("usda_food_168177.json"))!!
        assertNotNull(detail.per100)
        assertEquals(42.0, detail.per100!!.kcal, 1e-9)
        assertEquals(10.65, detail.per100!!.carbs, 1e-9)
    }
}
