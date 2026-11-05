package app.grammovka

import android.app.Application
import app.grammovka.di.AppContainer

class GrammovkaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
