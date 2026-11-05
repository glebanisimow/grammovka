package app.grammovka.di

import android.content.Context
import app.grammovka.BuildConfig
import app.grammovka.data.ai.AiCostTracker
import app.grammovka.data.ai.AiService
import app.grammovka.data.ai.ClaudeClient
import app.grammovka.data.remote.OffClient
import app.grammovka.data.remote.RemoteCache
import app.grammovka.data.remote.UsdaClient
import app.grammovka.data.db.AppDatabase
import app.grammovka.data.keys.AnthropicKeyVerifier
import app.grammovka.data.keys.KeyVerifier
import app.grammovka.data.keys.UsdaKeyVerifier
import app.grammovka.data.settings.SecretStore
import app.grammovka.data.settings.SettingsStore
import app.grammovka.data.repo.FoodRepository
import app.grammovka.data.repo.LogRepository
import app.grammovka.data.repo.ModelHub
import app.grammovka.ui.review.LogEvents
import app.grammovka.ui.review.ReviewInbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.grammovka.data.repo.ModelRepository
import app.grammovka.data.repo.WeightRepository
import app.grammovka.domain.log.QuickLogPlanner
import app.grammovka.update.UpdateRepository
import app.grammovka.update.UpdateSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI: one instance per process, owned by [app.grammovka.GrammovkaApp]. */
class AppContainer(context: Context) {
    val appContext: android.content.Context = context.applicationContext

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

    val foodRepository by lazy { FoodRepository(database.foodDao()) }
    val logRepository by lazy { LogRepository(database) }
    val templateRepository by lazy { app.grammovka.data.repo.TemplateRepository(database) }
    val pendingRepository by lazy { app.grammovka.data.repo.PendingRepository(appContext, database.pendingDao()) }

    /** Set by a «сеть появилась» notification tap; AppRoot opens the review of deferred items. */
    val openPending = kotlinx.coroutines.flow.MutableStateFlow(false)
    val recipeRepository by lazy { app.grammovka.data.repo.RecipeRepository(database, foodRepository) }
    val weightRepository by lazy { WeightRepository(database) }
    val modelRepository by lazy { ModelRepository(database, logRepository) }
    val quickLogPlanner by lazy { QuickLogPlanner(foodRepository) }

    /** Lives as long as the process; used for app-wide flows such as the model replay. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val modelHub by lazy { ModelHub(appScope, settingsStore, logRepository, weightRepository, modelRepository) }
    val reviewInbox = ReviewInbox()
    val backupService by lazy { app.grammovka.data.backup.BackupService(database, settingsStore) }

    /** Debug builds only: text injected by adb for end-to-end checks. */
    val debugInput = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Debug builds only: "barcode:<code>" as if ML Kit read it, or "label:<file in app files dir>". */
    val debugCapture = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val remoteCache by lazy { RemoteCache(database.remoteCacheDao()) }
    val offClient by lazy { OffClient(httpClient, remoteCache, "Grammovka/${BuildConfig.VERSION_NAME} (github.com/glebanisimow/grammovka)") }
    val usdaClient by lazy { UsdaClient(httpClient, remoteCache, secretStore) }
    val aiCostTracker by lazy { AiCostTracker(database.aiCallLogDao()) }
    val aiService by lazy { AiService(ClaudeClient(httpClient), secretStore, settingsStore, aiCostTracker, usdaClient, offClient) }
    val logEvents = LogEvents()
}
