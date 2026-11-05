package app.grammovka.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** File name is referenced by res/xml backup rules: this store is backed up. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Non-secret user settings. Profile and engine settings are added in phase 1. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val aiMonthlyLimitUsd: Double = 5.0,
)

class SettingsStore(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme_mode")
        val aiLimit = doublePreferencesKey("ai_monthly_limit_usd")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            aiMonthlyLimitUsd = p[K.aiLimit] ?: d.aiMonthlyLimitUsd,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[K.theme] = mode.name }
    }

    suspend fun setAiMonthlyLimit(usd: Double) {
        context.settingsDataStore.edit { it[K.aiLimit] = usd }
    }
}
