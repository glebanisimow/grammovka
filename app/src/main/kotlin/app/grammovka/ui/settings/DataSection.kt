package app.grammovka.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.grammovka.di.AppContainer
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Export / import via Storage Access Framework. Keys are never exported. */
@Composable
fun DataSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val stamp = LocalDate.now().toString()

    fun run(block: suspend () -> String) {
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "Не получилось: ${e.message ?: e.javaClass.simpleName}. Проверьте, что выбран правильный файл."
            }
        }
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run { context.contentResolver.openOutputStream(uri)!!.use { container.backupService.exportJson(it) }; "Полная копия сохранена." }
    }
    val exportEntries = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run { context.contentResolver.openOutputStream(uri)!!.use { container.backupService.exportEntriesCsv(it) }; "CSV записей сохранён." }
    }
    val exportDays = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run { context.contentResolver.openOutputStream(uri)!!.use { container.backupService.exportDaysCsv(it) }; "CSV по дням сохранён." }
    }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmImport = uri }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(GIcons.Catalog, null, tint = GTheme.extra.textMuted)
            Text("Данные", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Полная копия — каталог, записи, вес и настройки без ключей. Резервная копия Android сохраняет базу автоматически.",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportJson.launch("grammovka-$stamp.json") }, shape = MaterialTheme.shapes.small) { Text("Экспорт JSON") }
            OutlinedButton(onClick = { importJson.launch(arrayOf("application/json", "*/*")) }, shape = MaterialTheme.shapes.small) { Text("Импорт JSON") }
            OutlinedButton(onClick = { exportEntries.launch("grammovka-entries-$stamp.csv") }, shape = MaterialTheme.shapes.small) { Text("CSV записей") }
            OutlinedButton(onClick = { exportDays.launch("grammovka-days-$stamp.csv") }, shape = MaterialTheme.shapes.small) { Text("CSV по дням") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Заменить данные?") },
            text = { Text("Каталог, записи и вес на телефоне заменятся содержимым файла. Ключи API и тема не меняются.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = null
                    run {
                        val r = context.contentResolver.openInputStream(uri)!!.use { container.backupService.importJson(it) }
                        "Импортировано: продуктов ${r.foods}, записей ${r.entries}, взвешиваний ${r.weights}."
                    }
                }) { Text("Заменить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text("Отмена") } },
        )
    }
}
