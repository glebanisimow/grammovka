package app.grammovka.domain.validate

import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.validate.NutrientValidator.Issue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrientValidatorTest {
    @Test
    fun consistentLabelPasses() {
        // Cottage cheese 5%: 121 kcal, P 17, F 5, C 1.8
        assertTrue(NutrientValidator.check(Nutrients(121.0, 17.0, 5.0, 1.8)).isEmpty())
    }

    @Test
    fun fiberCountsTwoKcalPerGram() {
        val n = Nutrients(kcal = 60.0, protein = 1.0, fat = 0.0, carbs = 12.0, fiber = 4.0)
        assertEquals(60.0, NutrientValidator.computedKcal(n), 1e-9)
        assertTrue(NutrientValidator.check(n).isEmpty())
    }

    @Test
    fun energyMismatchIsReported() {
        val issues = NutrientValidator.check(Nutrients(300.0, 10.0, 5.0, 20.0))
        assertTrue(issues.single() is Issue.EnergyMismatch)
    }

    @Test
    fun sanityChecks() {
        assertTrue(Issue.KcalTooHigh in NutrientValidator.check(Nutrients(950.0, 0.0, 100.0, 0.0)))
        assertTrue(Issue.MassOver100 in NutrientValidator.check(Nutrients(400.0, 50.0, 0.0, 60.0)))
        assertTrue(Issue.Negative in NutrientValidator.check(Nutrients(10.0, -1.0, 0.0, 3.0)))
    }
}
