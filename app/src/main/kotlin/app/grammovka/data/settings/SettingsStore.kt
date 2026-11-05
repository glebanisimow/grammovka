package app.grammovka.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
) {
    val complete: Boolean get() = sex != null && birthYear != null && heightCm != null

    fun toProfile(today: LocalDate): Profile? {
        if (!complete) return null
        return Profile(sex!!, today.year - birthYear!!, heightCm!!, activityFactor, ratePercentPerWeek, proteinPerKg, budgetMode)
    }
}

/**
 * Models per AI purpose (spec: AiConfig). IDs checked against
 * https://platform.claude.com/docs/en/about-claude/models/overview on 2026-11-05.
 */
object AiDefaults {
    const val FAST = "claude-haiku-5-5"
    const val STRONG = "claude-sonnet-5-5"

    fun model(p: AiPurpose): String = if (p == AiPurpose.PARSE) FAST else STRONG
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val aiMonthlyLimitUsd: Double = 5.0,
    val profile: ProfileSettings = ProfileSettings(),
    val models: Map<AiPurpose, String> = AiPurpose.entries.associateWith(AiDefaults::model),
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
        fun model(p: AiPurpose) = stringPreferencesKey("ai_model_${p.name}")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val d = AppSettings()
        val dp = d.profile
        AppSettings(
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            aiMonthlyLimitUsd = p[K.aiLimit] ?: d.aiMonthlyLimitUsd,
            profile = ProfileSettings(
                sex = p[K.sex]?.let { runCatching { Sex.valueOf(it) }.getOrNull() },
                birthYear = p[K.birthYear],
                heightCm = p[K.height],
                activityFactor = p[K.activity] ?: dp.activityFactor,
                ratePercentPerWeek = p[K.rate] ?: dp.ratePercentPerWeek,
                proteinPerKg = p[K.protein] ?: dp.proteinPerKg,
                budgetMode = p[K.budgetMode]?.let { runCatching { BudgetMode.valueOf(it) }.getOrNull() } ?: dp.budgetMode,
            ),
            models = AiPurpose.entries.associateWith { p[K.model(it)]?.takeIf(String::isNotBlank) ?: AiDefaults.model(it) },
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[K.theme] = mode.name }
    }

    suspend fun setAiMonthlyLimit(usd: Double) {
        context.settingsDataStore.edit { it[K.aiLimit] = usd }
    }

    suspend fun setModel(purpose: AiPurpose, model: String) {
        context.settingsDataStore.edit { it[K.model(purpose)] = model.trim() }
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
        }
    }
}
