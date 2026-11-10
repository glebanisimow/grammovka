package app.grammovka

import android.app.Application
import app.grammovka.di.AppContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

@OptIn(kotlinx.coroutines.FlowPreview::class)
class GrammovkaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        app.grammovka.notify.Notifications.createChannels(this)
        container.appScope.launch {
            app.grammovka.notify.Reminder.schedule(this@GrammovkaApp, container.settingsStore.current().reminder)
            app.grammovka.notify.WeeklyReview.schedule(this@GrammovkaApp, container.settingsStore.current().weekly)
        }
        // Keep the home-screen widget in step with the log and the model while the process is alive.
        container.appScope.launch {
            merge(container.logRepository.observeChanges().map { }, container.modelHub.state.map { })
                .drop(1)
                .debounce(500)
                .collect { runCatching { app.grammovka.widget.GrammovkaWidget.refresh(this@GrammovkaApp) } }
        }
    }
}
