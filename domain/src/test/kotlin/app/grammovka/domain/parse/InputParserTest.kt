package app.grammovka.domain.parse

import app.grammovka.domain.parse.QtyUnit.G
import app.grammovka.domain.parse.QtyUnit.KG
import app.grammovka.domain.parse.QtyUnit.L
import app.grammovka.domain.parse.QtyUnit.ML
import app.grammovka.domain.parse.QtyUnit.PIECE
import app.grammovka.domain.parse.QtyUnit.TBSP
import app.grammovka.domain.parse.QtyUnit.TSP
import org.junit.Assert.assertEquals
import org.junit.Test

class InputParserTest {

    private data class F(val name: String, val qty: Double? = null, val unit: QtyUnit? = null)

    /** Table of Russian inputs → expected fragments (name, quantity, unit). */
    private val table: List<Pair<String, List<F>>> = listOf(
        "азиатская груша 230" to listOf(F("азиатская груша", 230.0)),
        "творог 5% 150" to listOf(F("творог 5%", 150.0)),
        "творог 5% 150, кофе с молоком" to listOf(F("творог 5%", 150.0), F("кофе с молоком")),
        "230 г курица" to listOf(F("курица", 230.0, G)),
        "230г курица" to listOf(F("курица", 230.0, G)),
        "курица 230 г" to listOf(F("курица", 230.0, G)),
        "курица 230г" to listOf(F("курица", 230.0, G)),
        "курица 230 гр" to listOf(F("курица", 230.0, G)),
        "курица 230 грамм" to listOf(F("курица", 230.0, G)),
        "гречка 80 граммов" to listOf(F("гречка", 80.0, G)),
        "яйца 2 шт" to listOf(F("яйца", 2.0, PIECE)),
        "яйца 2шт." to listOf(F("яйца", 2.0, PIECE)),
        "яйцо 3 штуки" to listOf(F("яйцо", 3.0, PIECE)),
        "2 яйца" to listOf(F("яйца", 2.0)),
        "яйцо 2" to listOf(F("яйцо", 2.0)),
        "яблоко" to listOf(F("яблоко")),
        "картофель 0,2 кг" to listOf(F("картофель", 0.2, KG)),
        "картофель 0.2 кг" to listOf(F("картофель", 0.2, KG)),
        "0,5 кг клубники" to listOf(F("клубники", 0.5, KG)),
        "молоко 3,2% 200 мл" to listOf(F("молоко 3,2%", 200.0, ML)),
        "молоко 3,2%" to listOf(F("молоко 3,2%")),
        "кефир 1% 0,5 л" to listOf(F("кефир 1%", 0.5, L)),
        "1,5 л воды" to listOf(F("воды", 1.5, L)),
        "сок 250мл" to listOf(F("сок", 250.0, ML)),
        "масло оливковое 1 ст. л." to listOf(F("масло оливковое", 1.0, TBSP)),
        "масло оливковое 1 ст.л." to listOf(F("масло оливковое", 1.0, TBSP)),
        "масло 2 ст л" to listOf(F("масло", 2.0, TBSP)),
        "сахар 2 ч. л." to listOf(F("сахар", 2.0, TSP)),
        "сахар 1 ч.л" to listOf(F("сахар", 1.0, TSP)),
        "мёд 1 чайная ложка" to listOf(F("мёд", 1.0, TSP)),
        "сметана 2 столовые ложки" to listOf(F("сметана", 2.0, TBSP)),
        "банан ½" to listOf(F("банан", 0.5)),
        "авокадо 1/2" to listOf(F("авокадо", 0.5)),
        "овсянка 50; молоко 100" to listOf(F("овсянка", 50.0), F("молоко", 100.0)),
        "овсянка 50 + банан 1 шт" to listOf(F("овсянка", 50.0), F("банан", 1.0, PIECE)),
        "хлеб 30\nсыр 20" to listOf(F("хлеб", 30.0), F("сыр", 20.0)),
        "рис 150,курица 120" to listOf(F("рис", 150.0), F("курица", 120.0)),
        "  гречка   80  " to listOf(F("гречка", 80.0)),
        "Сыр Гауда 30 г" to listOf(F("Сыр Гауда", 30.0, G)),
        "skyr piątnica 150" to listOf(F("skyr piątnica", 150.0)),
        "кофе с молоком" to listOf(F("кофе с молоком")),
        "хлеб 2 куска" to listOf(F("хлеб 2 куска")),
        "салат цезарь 250 г, морс 300 мл, хлеб 1 шт" to listOf(
            F("салат цезарь", 250.0, G), F("морс", 300.0, ML), F("хлеб", 1.0, PIECE),
        ),
        "лимон 1" to listOf(F("лимон", 1.0)),
        "2 лимона" to listOf(F("лимона", 2.0)),
        "1 л молока" to listOf(F("молока", 1.0, L)),
        "100 гречки" to listOf(F("гречки", 100.0)),
        "печенье 7 days 2 шт" to listOf(F("печенье 7 days", 2.0, PIECE)),
        ",, ;" to emptyList(),
    )

    @Test
    fun table() {
        assert(table.size >= 40)
        val failures = table.mapNotNull { (input, expected) ->
            val actual = InputParser.parse(input).map { F(it.name, it.quantity, it.unit) }
            if (actual != expected) "«$input»: expected $expected, got $actual" else null
        }
        assertEquals("Parser mismatches:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun decimalCommaIsNotASeparator() {
        assertEquals(listOf("картофель 0,2 кг", "лук 30"), InputParser.split("картофель 0,2 кг, лук 30"))
    }
}

class GramResolverTest {
    private val egg = listOf(PortionDef("шт", 55.0))
    private val oil = listOf(PortionDef("ст. л.", 17.0), PortionDef("ч. л.", 5.0))

    private fun r(input: String, portions: List<PortionDef> = emptyList()) =
        GramResolver.resolve(InputParser.parseFragment(input), portions)

    @Test fun gramsAndKilos() {
        assertEquals(Grams.Known(230.0), r("курица 230"))
        assertEquals(Grams.Known(200.0), r("картофель 0,2 кг"))
        assertEquals(Grams.Known(500.0), r("кефир 0,5 л"))
    }

    @Test fun bareSmallNumberIsPiecesWhenFoodHasPiecePortion() {
        assertEquals(Grams.Known(110.0, 2.0), r("яйцо 2", egg))
        assertEquals(Grams.Known(2.0), r("яйцо 2"))
        assertEquals(Grams.Known(55.0 * 10, 10.0), r("яйцо 10", egg))
        assertEquals(Grams.Known(11.0), r("яйцо 11", egg))
    }

    @Test fun piecesAndSpoonsNeedPortions() {
        assertEquals(Grams.Known(165.0, 3.0), r("яйца 3 шт", egg))
        assertEquals(Grams.Ask(AskReason.NO_PIECE_PORTION), r("яйца 3 шт"))
        assertEquals(Grams.Known(34.0), r("масло 2 ст. л.", oil))
        assertEquals(Grams.Known(5.0), r("масло 1 ч. л.", oil))
        assertEquals(Grams.Ask(AskReason.NO_SPOON_PORTION), r("масло 1 ст. л."))
    }

    @Test fun noQuantity() {
        assertEquals(Grams.Known(55.0, 1.0), r("яйцо", egg))
        assertEquals(Grams.Ask(AskReason.NO_QUANTITY), r("яблоко"))
    }
}
