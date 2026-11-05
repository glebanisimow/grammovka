package app.grammovka.domain.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTextTest {
    private val table = listOf(
        "творог 150 грамм банан 2 штуки" to "творог 150 грамм, банан 2 штуки",
        "творог 150 и банан" to "творог 150, банан",
        "гречка 200 г курица 150 г огурец" to "гречка 200 г, курица 150 г, огурец",
        "творог 5% 150 кофе с молоком" to "творог 5% 150, кофе с молоком",
        "молоко 3,2% 200 мл кофе" to "молоко 3,2% 200 мл, кофе",
        "2 яйца и хлеб 30" to "2 яйца, хлеб 30",
        "творог 150 г 2 яйца" to "творог 150 г, 2 яйца",
        "кофе с молоком" to "кофе с молоком",
        "на завтрак творог 150 банан" to "на завтрак творог 150, банан",
        "плов 1 порция салат 100" to "плов 1 порция, салат 100",
    )

    @Test
    fun table() {
        table.forEach { (spoken, typed) -> assertEquals(spoken, typed, VoiceText.toInput(spoken)) }
    }

    @Test
    fun parsesLikeTyped() {
        val f = InputParser.parse(VoiceText.toInput("творог 150 грамм банан 2 штуки"))
        assertEquals(listOf("творог", "банан"), f.map { it.name })
        assertEquals(listOf(QtyUnit.G, QtyUnit.PIECE), f.map { it.unit })
    }
}
