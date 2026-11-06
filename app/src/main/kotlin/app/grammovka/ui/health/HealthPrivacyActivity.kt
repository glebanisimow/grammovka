package app.grammovka.ui.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.grammovka.ui.theme.GrammovkaTheme

/**
 * Permissions rationale shown by Health Connect (required, or the permission dialog is not shown):
 * androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE (Android 13−) and VIEW_PERMISSION_USAGE alias (14+).
 * https://developer.android.com/health-and-fitness/guides/health-connect/develop/get-started
 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GrammovkaTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Protoptera eat и Health Connect", style = MaterialTheme.typography.headlineSmall)
                        Text(TEXT, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    private companion object {
        const val TEXT = "Protoptera eat только читает из Health Connect:\n\n" +
            "• вес — записывается в журнал взвешиваний за дни, где нет веса, введённого вручную;\n" +
            "• шаги и тренировки — показываются на экране «Прогресс» и не влияют на расчёт расхода.\n\n" +
            "Данные хранятся только на телефоне. У приложения нет сервера, и данные Health Connect никуда не отправляются, " +
            "в том числе в Claude. Ничего не записывается обратно в Health Connect.\n\n" +
            "Отозвать доступ можно в настройках Health Connect."
    }
}
