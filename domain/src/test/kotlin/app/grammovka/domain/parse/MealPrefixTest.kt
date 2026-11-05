package app.grammovka.domain.parse

import app.grammovka.domain.model.Meal
import org.junit.Assert.assertEquals
import org.junit.Test

class MealPrefixTest {
    private fun check(input: String, meal: Meal?, rest: String) {
        val r = MealPrefix.extract(input)
        assertEquals(input, meal, r.meal)
        assertEquals(input, rest, r.rest)
    }

    @Test fun colon() = check("завтрак: творог 200, банан", Meal.BREAKFAST, "творог 200, банан")
    @Test fun preposition() = check("на обед суп 300", Meal.LUNCH, "суп 300")
    @Test fun dash() = check("Ужин — гречка 250", Meal.DINNER, "гречка 250")
    @Test fun snack() = check("перекус яблоко", Meal.SNACK, "яблоко")
    @Test fun noPrefix() = check("творог 200", null, "творог 200")
    @Test fun wordInsideName() = check("обеденный суп 300", null, "обеденный суп 300")
    @Test fun onlyMealWord() = check("ужин", null, "ужин")
    @Test fun multiline() = check("завтрак:\nтворог 200\nбанан", Meal.BREAKFAST, "творог 200\nбанан")
}
