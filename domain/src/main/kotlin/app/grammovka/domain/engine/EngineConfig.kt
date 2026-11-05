package app.grammovka.domain.engine

/** Every constant of the energy model (spec: «Расчётный движок»). */
data class EngineConfig(
    /** EMA smoothing of the daily trend weight. */
    val trendAlpha: Double = 0.1,
    /** Observation window for the measured expenditure; ends yesterday. */
    val windowDays: Int = 28,
    val minIncludedDays: Int = 14,
    val minWeighIns: Int = 8,
    /** Days between the first and last weigh-in inside the window. */
    val minWeighInSpanDays: Int = 14,
    val kcalPerKg: Double = 7700.0,
    /** Day-to-day smoothing of the blended expenditure. */
    val tdeeSmoothing: Double = 0.2,
    val maxDeficitFraction: Double = 0.25,
    val weeklyMinFactor: Double = 0.75,
    val weeklyMaxFactor: Double = 1.25,
    /** Warn when the requested pace is above this share of body mass per week, in %. */
    val maxSafeRatePercent: Double = 1.0,
    /** Warn when the 4-week actual pace is faster than the target pace by this factor. */
    val fastLossFactor: Double = 1.5,
    val fastLossWindowDays: Int = 28,
    /** Warn when protein was under target on at least this many of the last 7 days. */
    val proteinLowDays: Int = 5,
)

enum class Sex { MALE, FEMALE }

enum class BudgetMode { WEEKLY, DAILY }

data class Profile(
    val sex: Sex,
    val ageYears: Int,
    val heightCm: Double,
    /** Multiplier on BMR for the prior estimate, e.g. 1.4. */
    val activityFactor: Double,
    /** Target loss, % of trend weight per week. */
    val ratePercentPerWeek: Double,
    val proteinPerKg: Double,
    val budgetMode: BudgetMode = BudgetMode.WEEKLY,
)
