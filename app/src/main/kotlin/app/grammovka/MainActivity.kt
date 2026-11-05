package app.grammovka

import android.content.Intent
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
        (application as GrammovkaApp).container.syncHealth()
    }

    /** Debug-only test hook: `adb shell am start ... --es debug_input "творог 150"` submits text on Today. */
    private fun handleDebugInput(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val c = (application as GrammovkaApp).container
        intent?.getStringExtra("debug_input")?.let { c.debugInput.value = it }
        intent?.getStringExtra("debug_voice")?.let { c.debugInput.value = "voice:$it" }
        intent?.getStringExtra("debug_barcode")?.let { c.debugCapture.value = "barcode:$it" }
        intent?.getStringExtra("debug_label")?.let { c.debugCapture.value = "label:$it" }
        intent?.getStringExtra("debug_photo")?.let { c.debugCapture.value = "photo:$it" }
    }

    private fun handleWidget(intent: Intent?) {
        intent?.getStringExtra(app.grammovka.widget.GrammovkaWidget.EXTRA_ACTION)?.let {
            (application as GrammovkaApp).container.widgetAction.value = it
            intent.removeExtra(app.grammovka.widget.GrammovkaWidget.EXTRA_ACTION)
        }
    }

    private fun handleOpenPending(intent: Intent?) {
        if (intent?.getBooleanExtra(app.grammovka.notify.Notifications.EXTRA_OPEN_PENDING, false) == true) {
            (application as GrammovkaApp).container.openPending.value = true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugInput(intent)
        handleOpenPending(intent)
        handleWidget(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDebugInput(intent)
        handleOpenPending(intent)
        handleWidget(intent)
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
