package app.grammovka.domain.activity

import app.grammovka.domain.engine.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityMathTest {
    /** 80 kg man, 40 years; Mifflin BMR ≈ 1750. */
    private val man = Body(Sex.MALE, 40, 80.0, 1750.0)
    private val woman = Body(Sex.FEMALE, 35, 62.0, 1350.0)

    @Test
    fun longRideWithHeartRate() {
        val e = ActivityMath.estimate(ActivityInput(ActivityType.BIKE, 240.0, 100.0, avgHr = 135), man)!!
        assertEquals(EstimateMethod.HEART_RATE, e.method)
        // Keytel: (−55.0969 + 0.6309·135 + 0.1988·80 + 0.2017·40) / 4.184 ≈ 12.92 kcal/min.
        assertEquals(12.92 * 240, e.grossKcal, 15.0)
        assertEquals(e.grossKcal - 1750.0 / 1440 * 240, e.netKcal, 0.01)
    }

    @Test
    fun rideWithoutHeartRateUsesSpeed() {
        val e = ActivityMath.estimate(ActivityInput(ActivityType.BIKE, 240.0, 100.0), man)!!
        assertEquals(EstimateMethod.MET, e.method)
        assertEquals(10.0, e.met!!, 0.0) // 25 km/h
        assertEquals(10.0 * 80 * 4, e.grossKcal, 0.01)
    }

    @Test
    fun walkBySpeed() {
        val e = ActivityMath.estimate(ActivityInput(ActivityType.WALK, 60.0, 5.0), man)!!
        assertEquals(4.3, e.met!!, 0.0)
        assertEquals((4.3 - 1750.0 / 1440 / 80 * 60) * 80, e.netKcal, 1.0)
    }

    @Test
    fun runInterpolates() {
        val m = ActivityMath.met(ActivityInput(ActivityType.RUN, 60.0, 10.25))
        assertTrue(m in 9.8..10.5)
    }

    @Test
    fun strengthIgnoresHeartRate() {
        val e = ActivityMath.estimate(ActivityInput(ActivityType.STRENGTH, 60.0, avgHr = 150, intensity = Intensity.HARD), man)!!
        assertEquals(EstimateMethod.MET, e.method)
        assertEquals(6.0 * 80, e.grossKcal, 0.01)
    }

    @Test
    fun femaleHeartRate() {
        val e = ActivityMath.estimate(ActivityInput(ActivityType.RUN, 30.0, avgHr = 160), woman)!!
        assertEquals(EstimateMethod.HEART_RATE, e.method)
        val perMin = (-20.4022 + 0.4472 * 160 - 0.1263 * 62 + 0.074 * 35) / 4.184
        assertEquals(perMin * 30, e.grossKcal, 0.01)
    }

    @Test
    fun implausibleHeartRateFallsBackToMet() {
        val e = ActivityMath.estimate(ActivityInput(ActivityType.WALK, 60.0, avgHr = 60), man)!!
        assertEquals(EstimateMethod.MET, e.method)
    }

    @Test
    fun noTimeNoEstimate() {
        assertNull(ActivityMath.estimate(ActivityInput(ActivityType.RUN, 0.0, 5.0), man))
    }

    @Test
    fun netNeverNegative() {
        assertEquals(0.0, ActivityMath.net(10.0, 60.0, 1750.0), 0.0)
    }
}
