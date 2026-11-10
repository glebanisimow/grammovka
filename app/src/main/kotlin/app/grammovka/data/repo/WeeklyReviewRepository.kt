package app.grammovka.data.repo

import app.grammovka.data.db.ActivityEntryEntity
import app.grammovka.data.db.AppDatabase
import app.grammovka.data.settings.ProfileSettings
import app.grammovka.domain.log.DayState
import app.grammovka.domain.model.Amount
import app.grammovka.domain.model.Meal
import app.grammovka.domain.review.DigestDay
import app.grammovka.domain.review.DigestDayInput
import app.grammovka.domain.review.DigestEntry
import app.grammovka.domain.review.TopFood
import app.grammovka.domain.review.WeekDigest
import app.grammovka.domain.review.WeekTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.round

/** A generated review, kept so it can be reread without paying for it again. */
@Serializable
data class WeeklyReport(val weekStart: String, val generatedAt: Long, val model: String, val text: String)

/** One week as the review screen shows it; [json] is exactly what the model gets. */
data class WeekView(
    val monday: LocalDate,
    val today: LocalDate,
    val days: List<DigestDay>,
    val totals: WeekTotals,
    val previous: List<WeekTotals>,
    val topFoods: List<TopFood>,
    val workouts: List<ActivityEntryEntity>,
    val json: String,
) {
    val hasData: Boolean get() = days.any { it.eaten.kcal > 0 }
}

/**
 * Weekly review data (spec, phase 3): aggregates for the week and the three before it are computed here and in
 * [WeekDigest]; the model receives only them. Reports are small JSON files in `files/weekly/` — not in Room, so no
 * migration; they can be generated again from the diary.
 */
class WeeklyReviewRepository(private val dir: File, db: AppDatabase) {
    private val logs = db.logDao()
    private val statuses = db.dayStatusDao()
    private val activities = db.activityDao()
    private val json = Json { ignoreUnknownKeys = true }

    /** Monday of the first week with entries; null for an empty diary. */
    suspend fun firstWeek(): LocalDate? = logs.firstDate()?.let(app.grammovka.domain.log.DayLedger::weekStart)

    suspend fun week(monday: LocalDate, today: LocalDate, model: ModelState?, profile: ProfileSettings): WeekView {
        val from = monday.minusWeeks(PREVIOUS_WEEKS.toLong())
        val to = monday.plusDays(6)
        val entries = logs.between(from, to).map {
            DigestEntry(it.date, it.time, it.foodName, it.grams, Amount(it.kcal, it.protein, it.fat, it.carbs), it.meal)
        }.groupBy { it.date }
        val marks = statuses.between(from, to).associateBy { it.date }
        val trend = model?.snapshots?.mapValues { it.value.trendWeight }.orEmpty()

        fun weekDays(start: LocalDate): List<DigestDay> = (0L..6L).map { start.plusDays(it) }.filter { !it.isAfter(today) }.map { d ->
            val s = model?.snapshots?.get(d)
            val mark = marks[d]
            WeekDigest.day(
                DigestDayInput(
                    date = d,
                    state = when {
                        mark?.closedAt != null -> DayState.CLOSED
                        mark?.startedAt != null -> DayState.OPEN
                        else -> DayState.NONE
                    },
                    incomplete = mark?.incomplete == true,
                    tdee = s?.tdee, target = s?.target, proteinTarget = s?.proteinTarget,
                    activity = model?.days?.get(d)?.activity ?: 0.0,
                    weightKg = model?.weights?.get(d), trendKg = s?.trendWeight,
                ),
                entries[d].orEmpty(), today,
            )
        }

        val days = weekDays(monday)
        val totals = WeekDigest.totals(monday, days, trend)
        val previous = (1..PREVIOUS_WEEKS).map { monday.minusWeeks(it.toLong()) }
            .map { WeekDigest.totals(it, weekDays(it), trend) }
            .filter { it.countedDays > 0 }
        val top = WeekDigest.topFoods((0L..6L).flatMap { entries[monday.plusDays(it)].orEmpty() })
        val workouts = activities.between(monday, to)
        val last = model?.snapshots?.get(minOf(today, to)) ?: model?.snapshots?.values?.lastOrNull { !it.date.isAfter(to) }

        val body = buildJsonObject {
            put("today", today.toString())
            putJsonObject("week") { put("from", monday.toString()); put("to", to.toString()) }
            putJsonObject("goal") {
                put("ratePercentPerWeek", profile.ratePercentPerWeek)
                last?.let { put("targetLossKgPerWeek", r1(profile.ratePercentPerWeek / 100 * it.trendWeight)) }
                last?.let { put("proteinTargetG", r0(it.proteinTarget)) }
            }
            if (last != null) {
                putJsonObject("model") {
                    put("normKcal", r0(last.tdee))
                    put("normSource", if (last.tdeeRaw != null) "measured" else "formula")
                    put("trendWeightKg", r1(last.trendWeight))
                }
            }
            putJsonArray("days") {
                days.forEach { d ->
                    addJsonObject {
                        put("date", d.date.toString())
                        put("weekday", d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, RU))
                        put("status", d.status.name.lowercase())
                        if (d.eaten.kcal > 0) {
                            put("kcal", r0(d.eaten.kcal)); put("protein", r0(d.eaten.protein)); put("fat", r0(d.eaten.fat)); put("carbs", r0(d.eaten.carbs))
                            putJsonObject("meals") {
                                Meal.entries.forEach { m -> d.mealKcal[m]?.let { put(if (m == Meal.SNACK) "snacks" else m.name.lowercase(), r0(it)) } }
                            }
                            put("kcalAfter21", r0(d.lateKcal))
                            d.firstMeal?.let { put("firstMeal", it.toString().take(5)) }
                            d.lastMeal?.let { put("lastMeal", it.toString().take(5)) }
                        }
                        d.target?.let { put("targetKcal", r0(it)) }
                        if (d.activity > 0) put("workoutKcal", r0(d.activity))
                        d.expenditure?.let { put("expenditureKcal", r0(it)) }
                        d.balance?.let { put("balanceKcal", r0(it)) }
                        d.weightKg?.let { put("weightKg", r1(it)) }
                    }
                }
            }
            putJsonObject("weekTotals") { totals(totals) }
            putJsonArray("previousWeeks") { previous.forEach { p -> addJsonObject { totals(p) } } }
            putJsonArray("topFoods") {
                top.forEach { f ->
                    addJsonObject { put("name", f.name); put("times", f.times); put("grams", r0(f.grams)); put("kcal", r0(f.kcal)); put("protein", r0(f.protein)) }
                }
            }
            putJsonArray("workouts") {
                workouts.forEach { w ->
                    addJsonObject { put("date", w.date.toString()); put("title", w.note ?: w.type.ru); put("minutes", r0(w.minutes)); put("kcal", r0(w.kcal)) }
                }
            }
        }
        return WeekView(monday, today, days, totals, previous, top, workouts, body.toString())
    }

    private fun JsonObjectBuilder.totals(t: WeekTotals) {
        put("weekFrom", t.monday.toString())
        put("countedDays", t.countedDays)
        t.avgKcal?.let { put("avgKcal", r0(it)) }
        t.avgTarget?.let { put("avgTargetKcal", r0(it)) }
        t.avgBalance?.let { put("avgBalanceKcal", r0(it)) }
        t.sumBalance?.let { put("sumBalanceKcal", r0(it)) }
        t.avgProtein?.let { put("avgProtein", r0(it)) }
        t.avgProteinTarget?.let { put("proteinTarget", r0(it)) }
        t.avgFat?.let { put("avgFat", r0(it)) }
        t.avgCarbs?.let { put("avgCarbs", r0(it)) }
        put("daysOverTarget", t.daysOverTarget)
        put("daysProteinMet", t.daysProteinMet)
        t.weekdayAvgKcal?.let { put("weekdayAvgKcal", r0(it)) }
        t.weekendAvgKcal?.let { put("weekendAvgKcal", r0(it)) }
        put("workoutKcal", r0(t.workoutKcal))
        put("weighIns", t.weighIns)
        t.trendStart?.let { put("trendStartKg", r1(it)) }
        t.trendEnd?.let { put("trendEndKg", r1(it)) }
        t.trendChange?.let { put("trendChangeKg", round(it * 100) / 100) }
    }

    private fun file(monday: LocalDate) = File(dir, "$monday.json")

    suspend fun saved(monday: LocalDate): WeeklyReport? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(WeeklyReport.serializer(), file(monday).readText()) }.getOrNull()
    }

    suspend fun save(report: WeeklyReport) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        File(dir, "${report.weekStart}.json").writeText(json.encodeToString(WeeklyReport.serializer(), report))
    }

    private fun r0(v: Double): Long = round(v).toLong()
    private fun r1(v: Double): Double = round(v * 10) / 10

    companion object {
        const val PREVIOUS_WEEKS = 3
        private val RU = Locale.forLanguageTag("ru")
    }
}
