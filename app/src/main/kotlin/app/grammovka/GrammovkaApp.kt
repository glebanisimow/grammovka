package app.grammovka

import android.app.Application
import app.grammovka.di.AppContainer
import kotlinx.coroutines.launch

class GrammovkaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        app.grammovka.notify.Notifications.createChannels(this)
        container.appScope.launch {
            app.grammovka.notify.Reminder.schedule(this@GrammovkaApp, container.settingsStore.current().reminder)
        }
    }
}
