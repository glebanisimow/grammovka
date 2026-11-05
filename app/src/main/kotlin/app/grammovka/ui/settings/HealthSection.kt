package app.grammovka.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.grammovka.data.health.HealthStatus
import app.grammovka.data.settings.AppSettings
import app.grammovka.di.AppContainer
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import kotlinx.coroutines.launch

/** Health Connect: connect in context (permission dialog from here), sync on demand, disconnect. */
@Composable
fun HealthSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = container.healthRepository
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    val status = remember { repo.status() }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var message by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(status, refresh) {
        if (status == HealthStatus.AVAILABLE) granted = runCatching { repo.granted() }.getOrDefault(emptySet())
    }

    fun sync() {
        scope.launch {
            message = try {
                val n = repo.syncWeights()
                if (n == 0) "Новых взвешиваний нет." else "Импортировано взвешиваний: $n."
            } catch (e: Exception) {
                "Не получилось прочитать Health Connect: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        granted = result
        val any = result.any { it in repo.permissions }
        scope.launch { container.settingsStore.setHealthConnect(any) }
        if (any) sync() else message = "Доступ не выдан."
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(GIcons.Pulse, null, tint = GTheme.extra.textMuted)
            Text("Health Connect", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Вес попадает в журнал за дни без ручного взвешивания. Шаги и тренировки видны на «Прогрессе» и не меняют расчёт расхода.",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
        val connected = settings.healthConnect && granted.isNotEmpty()
        when (status) {
            HealthStatus.UNSUPPORTED -> Text("На этом телефоне Health Connect недоступен.", style = MaterialTheme.typography.bodyMedium)
            HealthStatus.NEEDS_INSTALL_OR_UPDATE -> {
                Text("Нужно установить или обновить приложение Health Connect.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { context.startActivity(repo.installIntent()) }, shape = MaterialTheme.shapes.small) { Text("Открыть в Play") }
            }
            HealthStatus.AVAILABLE -> {
                if (connected) {
                    val what = listOfNotNull(
                        "вес".takeIf { granted.any { it.endsWith("READ_WEIGHT") } },
                        "шаги".takeIf { granted.any { it.endsWith("READ_STEPS") } },
                        "тренировки".takeIf { granted.any { it.endsWith("READ_EXERCISE") } },
                    )
                    Text("Подключено: ${what.joinToString(", ")}. Вес синхронизируется при открытии приложения.", style = MaterialTheme.typography.bodyMedium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!connected || granted.size < repo.permissions.size) {
                        OutlinedButton(onClick = { request.launch(repo.permissions) }, shape = MaterialTheme.shapes.small) {
                            Text(if (connected) "Дать остальные разрешения" else "Подключить")
                        }
                    }
                    if (connected) {
                        OutlinedButton(onClick = ::sync, shape = MaterialTheme.shapes.small) { Text("Синхронизировать") }
                        OutlinedButton(onClick = {
                            scope.launch { container.settingsStore.setHealthConnect(false) }
                            message = "Отключено. Уже импортированный вес остаётся. Разрешения можно отозвать в Health Connect."
                            refresh++
                        }, shape = MaterialTheme.shapes.small) { Text("Отключить") }
                    }
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
