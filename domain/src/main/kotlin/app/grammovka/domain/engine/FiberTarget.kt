package app.grammovka.domain.engine

/**
 * Daily fiber target: the Adequate Intake of the US National Academies (IOM, Dietary Reference Intakes 2005),
 * 14 g per 1000 kcal at the median intake of each age and sex group: men 38 g up to 50 years and 30 g after,
 * women 25 g and 21 g, teenagers 14–18 years 38 g (boys) and 26 g (girls). EFSA sets 25 g for all adults;
 * the IOM table is used because it follows age and sex. It is a floor to reach, not a ceiling.
 */
object FiberTarget {
    fun grams(sex: Sex, ageYears: Int): Double = when (sex) {
        Sex.MALE -> if (ageYears <= 50) 38.0 else 30.0
        Sex.FEMALE -> when {
            ageYears <= 18 -> 26.0
            ageYears <= 50 -> 25.0
            else -> 21.0
        }
    }
}
