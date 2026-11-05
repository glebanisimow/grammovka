package app.grammovka.domain.recipe

import app.grammovka.domain.model.Nutrients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeMathTest {
    private val buckwheatDry = Nutrients(313.0, 12.6, 3.3, 57.1, 10.0)
    private val butter = Nutrients(748.0, 0.5, 82.5, 0.8)

    @Test fun cookedWeightDilutes() {
        // 100 g dry buckwheat + 10 g butter, cooked to 300 g.
        val n = RecipeMath.per100(listOf(Ingredient(buckwheatDry, 100.0), Ingredient(butter, 10.0)), 300.0)!!
        assertEquals((313.0 + 74.8) / 3, n.kcal, 1e-9)
        assertEquals((12.6 + 0.05) / 3, n.protein, 1e-9)
        assertEquals((3.3 + 8.25) / 3, n.fat, 1e-9)
        assertEquals(10.0 / 3, n.fiber!!, 1e-9)
    }

    @Test fun sameWeightKeepsAverage() {
        val n = RecipeMath.per100(listOf(Ingredient(butter, 50.0), Ingredient(butter, 50.0)), 100.0)!!
        assertEquals(748.0, n.kcal, 1e-9)
        assertNull(n.fiber)
    }

    @Test fun emptyOrZero() {
        assertNull(RecipeMath.per100(emptyList(), 100.0))
        assertNull(RecipeMath.per100(listOf(Ingredient(butter, 10.0)), 0.0))
        assertNull(RecipeMath.per100(listOf(Ingredient(butter, 0.0)), 100.0))
    }

    @Test fun rawWeight() = assertEquals(110.0, RecipeMath.rawWeight(listOf(Ingredient(buckwheatDry, 100.0), Ingredient(butter, 10.0))), 0.0)
}
