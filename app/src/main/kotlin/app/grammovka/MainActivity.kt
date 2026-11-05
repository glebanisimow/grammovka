package app.grammovka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.grammovka.data.settings.AppSettings
import app.grammovka.data.settings.ThemeMode
import app.grammovka.ui.nav.AppRoot
import app.grammovka.ui.theme.GrammovkaTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as GrammovkaApp).container.modelHub.refreshDate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GrammovkaApp).container
        setContent {
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            GrammovkaTheme(dark = dark) {
                AppRoot(container)
            }
        }
    }
}
