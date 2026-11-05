package app.grammovka.di

import android.content.Context
import app.grammovka.data.db.AppDatabase
import app.grammovka.data.keys.AnthropicKeyVerifier
import app.grammovka.data.keys.KeyVerifier
import app.grammovka.data.keys.UsdaKeyVerifier
import app.grammovka.data.settings.SecretStore
import app.grammovka.data.settings.SettingsStore
import app.grammovka.update.UpdateRepository
import app.grammovka.update.UpdateSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI: one instance per process, owned by [app.grammovka.GrammovkaApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val settingsStore by lazy { SettingsStore(appContext) }
    val secretStore by lazy { SecretStore(appContext) }

    /** No logging interceptor on purpose: request headers carry API keys. */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val anthropicKeyVerifier: KeyVerifier by lazy { AnthropicKeyVerifier(httpClient) }
    val usdaKeyVerifier: KeyVerifier by lazy { UsdaKeyVerifier(httpClient) }

    val updateSource: UpdateSource by lazy { UpdateRepository(appContext) }
}
