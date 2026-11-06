package app.grammovka.domain.activity

import app.grammovka.domain.engine.Sex
import kotlin.math.max

enum class ActivityType(val ru: String) {
    WALK("Ходьба"), RUN("Бег"), BIKE("Велосипед"), STRENGTH("Силовая"), OTHER("Другое"),
}

enum class Intensity(val ru: String) { LIGHT("легко"), MODERATE("средне"), HARD("тяжело") }

/** Where the kcal of a workout came from. */
enum class ActivitySource { FORMULA, CLAUDE_ESTIMATE, MANUAL }

/** What the user said about one workout. [minutes] is required for any estimate. */
data class ActivityInput(
    val type: ActivityType,
    val minutes: Double,
    val distanceKm: Double? = null,
    val avgHr: Int? = null,
    val intensity: Intensity? = null,
)

/** Body data for the formulas; [bmrKcal] is the Mifflin–St Jeor BMR the energy model uses. */
data class Body(val sex: Sex, val ageYears: Int, val weightKg: Double, val bmrKcal: Double)

enum class EstimateMethod { HEART_RATE, MET }

/**
 * [netKcal] is energy above rest: the resting part of those minutes is already inside the daily norm,
 * so only the extra goes on top (otherwise rest is counted twice).
 */
data class ActivityEstimate(val grossKcal: Double, val netKcal: Double, val method: EstimateMethod, val met: Double?)

/**
 * Deterministic workout estimate, works offline (invariant 6).
 *
 * Heart rate (cardio types only, avg HR 90–200): Keytel et al. 2005, J Sports Sci 23(3):289–297, without VO2max:
 *   male   kcal/min = (−55.0969 + 0.6309·HR + 0.1988·kg + 0.2017·age) / 4.184
 *   female kcal/min = (−20.4022 + 0.4472·HR − 0.1263·kg + 0.074·age) / 4.184
 * Otherwise MET from the Adult Compendium of Physical Activities (2024): gross = MET × kg × hours.
 * Strength always uses MET: HR formulas overestimate interval effort with long rests.
 */
object ActivityMath {
    private val HR_RANGE = 90..200

    fun estimate(input: ActivityInput, body: Body): ActivityEstimate? {
        if (input.minutes <= 0) return null
        val restingPerMin = body.bmrKcal / 1440
        val hr = input.avgHr?.takeIf { it in HR_RANGE && input.type != ActivityType.STRENGTH }
        if (hr != null) {
            val perMin = when (body.sex) {
                Sex.MALE -> (-55.0969 + 0.6309 * hr + 0.1988 * body.weightKg + 0.2017 * body.ageYears) / 4.184
                Sex.FEMALE -> (-20.4022 + 0.4472 * hr - 0.1263 * body.weightKg + 0.074 * body.ageYears) / 4.184
            }
            // Below ~resting the regression is meaningless; fall back to MET then.
            if (perMin > restingPerMin * 1.5) {
                val gross = perMin * input.minutes
                return ActivityEstimate(gross, net(gross, input.minutes, body.bmrKcal), EstimateMethod.HEART_RATE, null)
            }
        }
        val met = met(input)
        val gross = met * body.weightKg * input.minutes / 60
        return ActivityEstimate(gross, net(gross, input.minutes, body.bmrKcal), EstimateMethod.MET, met)
    }

    /** Energy above rest for [minutes] of activity with [grossKcal] total; never negative. */
    fun net(grossKcal: Double, minutes: Double, bmrKcal: Double): Double = max(0.0, grossKcal - bmrKcal / 1440 * minutes)

    /** Speed in km/h when both distance and time are known. */
    fun speedKmh(input: ActivityInput): Double? =
        input.distanceKm?.takeIf { it > 0 && input.minutes > 0 }?.let { it / (input.minutes / 60) }

    /** MET by type, speed and intensity (Compendium 2024 codes 17xxx walking, 12xxx running, 01xxx cycling, 02xxx conditioning). */
    fun met(input: ActivityInput): Double {
        val v = speedKmh(input)
        return when (input.type) {
            ActivityType.WALK -> when {
                v == null -> byIntensity(input.intensity, 3.0, 3.5, 5.0)
                v < 3.2 -> 2.0
                v < 4.0 -> 2.8
                v < 4.8 -> 3.5
                v < 5.6 -> 4.3
                v < 6.4 -> 5.0
                v < 7.2 -> 7.0
                else -> 8.3
            }
            ActivityType.RUN -> when {
                v == null -> byIntensity(input.intensity, 8.0, 9.8, 11.5)
                else -> interpolate(v, RUN_TABLE)
            }
            ActivityType.BIKE -> when {
                v == null -> byIntensity(input.intensity, 4.0, 7.5, 10.0)
                v < 16.0 -> 4.0
                v < 19.3 -> 6.8
                v < 22.5 -> 8.0
                v < 25.7 -> 10.0
                v < 30.6 -> 12.0
                else -> 15.8
            }
            ActivityType.STRENGTH -> byIntensity(input.intensity, 3.5, 5.0, 6.0)
            ActivityType.OTHER -> byIntensity(input.intensity, 3.0, 4.5, 7.0)
        }
    }

    private fun byIntensity(i: Intensity?, light: Double, moderate: Double, hard: Double) = when (i) {
        Intensity.LIGHT -> light
        Intensity.HARD -> hard
        else -> moderate
    }

    /** km/h → MET, Compendium running codes 12020–12134. */
    private val RUN_TABLE = listOf(6.4 to 6.0, 8.0 to 8.3, 9.7 to 9.8, 10.8 to 10.5, 11.3 to 11.0, 12.1 to 11.8, 12.9 to 12.3, 13.8 to 12.8, 14.5 to 14.5, 16.1 to 16.0, 17.7 to 19.0)

    private fun interpolate(x: Double, t: List<Pair<Double, Double>>): Double {
        if (x <= t.first().first) return t.first().second
        if (x >= t.last().first) return t.last().second
        val i = t.indexOfFirst { it.first >= x }
        val (x0, y0) = t[i - 1]
        val (x1, y1) = t[i]
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }
}
