package app.grammovka.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.grammovka.domain.engine.BudgetMode
import app.grammovka.domain.engine.Profile
import app.grammovka.domain.engine.Sex
import app.grammovka.domain.model.AiPurpose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** File name is referenced by res/xml backup rules: this store is backed up. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Body and goal settings. [sex], [birthYear] and [heightCm] are required before the model can run. */
data class ProfileSettings(
    val sex: Sex? = null,
    val birthYear: Int? = null,
    val heightCm: Double? = null,
    val activityFactor: Double = 1.4,
    val ratePercentPerWeek: Double = 0.5,
    val proteinPerKg: Double = 1.8,
    val budgetMode: BudgetMode = BudgetMode.WEEKLY,
    /** The user's own daily fiber target; null = by age and sex ([FiberTarget]). */
    val fiberTargetG: Double? = null,
) {
    val complete: Boolean get() = sex != null && birthYear != null && heightCm != null

    /** Daily fiber target in grams: the user's own, or the age and sex norm; null until sex and birth year are known. */
    fun fiberTarget(today: LocalDate): Double? = fiberTargetG ?: autoFiberTarget(today)

    fun autoFiberTarget(today: LocalDate): Double? =
        if (sex != null && birthYear != null) app.grammovka.domain.engine.FiberTarget.grams(sex, today.year - birthYear) else null

    fun toProfile(today: LocalDate): Profile? {
        if (!complete) return null
        return Profile(sex!!, today.year - birthYear!!, heightCm!!, activityFactor, ratePercentPerWeek, proteinPerKg, budgetMode)
    }
}

/**
 * Models per AI purpose (spec: AiConfig), per provider. Claude IDs checked against
 * https://platform.claude.com/docs/en/about-claude/models/overview on 2026-11-05. Qwen IDs checked on a real key
 * 2026-11-20 (Singapore): qwen-plus kept every fragment in PARSE where qwen3.7-flash dropped one; qwen3.6-plus takes
 * images, tools and web search and needs no thinking mode for JSON (https://www.alibabacloud.com/help/en/model-studio/models).
 */
object AiDefaults {
    const val FAST = "claude-haiku-5-5"
    const val STRONG = "claude-sonnet-5-5"
    const val QWEN_FAST = "qwen-plus"
    const val QWEN_STRONG = "qwen3.6-plus"

    fun model(p: AiPurpose): String = model(app.grammovka.data.ai.AiProvider.ANTHROPIC, p)

    fun model(provider: app.grammovka.data.ai.AiProvider, p: AiPurpose): String = when (provider) {
        app.grammovka.data.ai.AiProvider.ANTHROPIC -> if (p == AiPurpose.PARSE) FAST else STRONG
        app.grammovka.data.ai.AiProvider.QWEN -> if (p == AiPurpose.PARSE) QWEN_FAST else QWEN_STRONG
    }
}

/** Morning question «закрыть вчерашний день?». Off until the user turns it on (notification permission is asked then). */
data class ReminderSettings(
    val enabled: Boolean = false,
    val minuteOfDay: Int = 9 * 60,
    /** The one-time offer on Today was answered (either way). */
    val offered: Boolean = false,
)

/**
 * Weekly review: a notification when the week's Sunday is closed (user request 2026-11-15; it used to come on
 * Sunday at 20:00, before the week was over). On by default. [notifiedWeek] is the Monday of the last week
 * offered, so reopening and closing Sunday again does not notify twice.
 */
data class WeeklyReviewSettings(
    val enabled: Boolean = true,
    val notifiedWeek: LocalDate? = null,
)

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reminder: ReminderSettings = ReminderSettings(),
    val weekly: WeeklyReviewSettings = WeeklyReviewSettings(),
    /** Health Connect connected by the user: weights are synced when the app opens. */
    val healthConnect: Boolean = false,
    val aiMonthlyLimitUsd: Double = 5.0,
    val profile: ProfileSettings = ProfileSettings(),
    /** Who answers AI requests; [models] are the active provider's. */
    val aiProvider: app.grammovka.data.ai.AiProvider = app.grammovka.data.ai.AiProvider.ANTHROPIC,
    /** Qwen base URL (already normalized, see [app.grammovka.data.ai.QwenEndpoint]). */
    val qwenBaseUrl: String = app.grammovka.data.ai.QwenEndpoint.SHARED,
    val models: Map<AiPurpose, String> = AiPurpose.entries.associateWith { AiDefaults.model(aiProvider, it) },
)

class SettingsStore(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme_mode")
        val aiLimit = doublePreferencesKey("ai_monthly_limit_usd")
        val sex = stringPreferencesKey("profile_sex")
        val birthYear = intPreferencesKey("profile_birth_year")
        val height = doublePreferencesKey("profile_height_cm")
        val activity = doublePreferencesKey("profile_activity")
        val rate = doublePreferencesKey("profile_rate_pct")
        val protein = doublePreferencesKey("profile_protein_per_kg")
        val budgetMode = stringPreferencesKey("profile_budget_mode")
        val fiber = doublePreferencesKey("profile_fiber_target_g")
        val healthConnect = booleanPreferencesKey("health_connect")
        val reminderOn = booleanPreferencesKey("reminder_enabled")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val reminderOffered = booleanPreferencesKey("reminder_offered")
        val weeklyOn = booleanPreferencesKey("weekly_review_enabled")
        val weeklyNotified = stringPreferencesKey("weekly_review_notified_week")
        val provider = stringPreferencesKey("ai_provider")
        val qwenBase = stringPreferencesKey("qwen_base_url")

        /** Claude keys keep their pre-Qwen names, so existing choices survive the update. */
        fun model(provider: app.grammovka.data.ai.AiProvider, p: AiPurpose) =
            stringPreferencesKey(if (provider == app.grammovka.data.ai.AiProvider.ANTHROPIC) "ai_model_${p.name}" else "ai_model_${provider.name}_${p.name}")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val d = AppSettings()
        val provider = p[K.provider]?.let { runCatching { app.grammovka.data.ai.AiProvider.valueOf(it) }.getOrNull() } ?: d.aiProvider
        val dp = d.profile
        AppSettings(
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            aiMonthlyLimitUsd = p[K.aiLimit] ?: d.aiMonthlyLimitUsd,
            healthConnect = p[K.healthConnect] ?: false,
            reminder = ReminderSettings(
                enabled = p[K.reminderOn] ?: false,
                minuteOfDay = p[K.reminderMinute] ?: d.reminder.minuteOfDay,
                offered = p[K.reminderOffered] ?: false,
            ),
            weekly = WeeklyReviewSettings(
                enabled = p[K.weeklyOn] ?: d.weekly.enabled,
                notifiedWeek = p[K.weeklyNotified]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            ),
            profile = ProfileSettings(
                sex = p[K.sex]?.let { runCatching { Sex.valueOf(it) }.getOrNull() },
                birthYear = p[K.birthYear],
                heightCm = p[K.height],
                activityFactor = p[K.activity] ?: dp.activityFactor,
                ratePercentPerWeek = p[K.rate] ?: dp.ratePercentPerWeek,
                proteinPerKg = p[K.protein] ?: dp.proteinPerKg,
                budgetMode = p[K.budgetMode]?.let { runCatching { BudgetMode.valueOf(it) }.getOrNull() } ?: dp.budgetMode,
                fiberTargetG = p[K.fiber],
            ),
            aiProvider = provider,
            qwenBaseUrl = p[K.qwenBase] ?: d.qwenBaseUrl,
            models = AiPurpose.entries.associateWith { p[K.model(provider, it)]?.takeIf(String::isNotBlank) ?: AiDefaults.model(provider, it) },
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[K.theme] = mode.name }
    }

    suspend fun setHealthConnect(on: Boolean) {
        context.settingsDataStore.edit { it[K.healthConnect] = on }
    }

    suspend fun setReminder(r: ReminderSettings) {
        context.settingsDataStore.edit {
            it[K.reminderOn] = r.enabled
            it[K.reminderMinute] = r.minuteOfDay
            it[K.reminderOffered] = r.offered
        }
    }

    suspend fun setWeekly(w: WeeklyReviewSettings) {
        context.settingsDataStore.edit {
            it[K.weeklyOn] = w.enabled
            if (w.notifiedWeek != null) it[K.weeklyNotified] = w.notifiedWeek.toString() else it.remove(K.weeklyNotified)
        }
    }

    suspend fun setAiMonthlyLimit(usd: Double) {
        context.settingsDataStore.edit { it[K.aiLimit] = usd }
    }

    /** Sets the model for the active provider. */
    suspend fun setModel(purpose: AiPurpose, model: String) {
        context.settingsDataStore.edit {
            val provider = it[K.provider]?.let { v -> runCatching { app.grammovka.data.ai.AiProvider.valueOf(v) }.getOrNull() }
                ?: app.grammovka.data.ai.AiProvider.ANTHROPIC
            it[K.model(provider, purpose)] = model.trim()
        }
    }

    suspend fun setAiProvider(provider: app.grammovka.data.ai.AiProvider) {
        context.settingsDataStore.edit { it[K.provider] = provider.name }
    }

    suspend fun setQwenBaseUrl(url: String) {
        context.settingsDataStore.edit { it[K.qwenBase] = url }
    }

    suspend fun setProfile(p: ProfileSettings) {
        context.settingsDataStore.edit {
            if (p.sex != null) it[K.sex] = p.sex.name else it.remove(K.sex)
            if (p.birthYear != null) it[K.birthYear] = p.birthYear else it.remove(K.birthYear)
            if (p.heightCm != null) it[K.height] = p.heightCm else it.remove(K.height)
            it[K.activity] = p.activityFactor
            it[K.rate] = p.ratePercentPerWeek
            it[K.protein] = p.proteinPerKg
            it[K.budgetMode] = p.budgetMode.name
            if (p.fiberTargetG != null) it[K.fiber] = p.fiberTargetG else it.remove(K.fiber)
        }
    }
}
