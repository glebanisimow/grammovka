package app.grammovka.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction

/** «Назад» with unsaved input: asks before throwing it away. */
@Composable
fun DiscardDialog(title: String, text: String, discard: String, onDiscard: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onStay) { Text("Остаться") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text(discard, color = MaterialTheme.colorScheme.error) } },
    )
}

/** Weight of one day: save, or delete an existing value ([onSave] gets null). Used on «Сегодня» and «Прогресс». */
@Composable
fun WeightDialog(current: Double?, onDismiss: () -> Unit, title: String = "Вес за день", onSave: (Double?) -> Unit) {
    var v by remember { mutableStateOf(current?.input().orEmpty()) }
    val kg = v.toDecimal()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { NumberField(v, { v = it }, "Вес", suffix = "кг", imeAction = ImeAction.Done, isError = v.isNotEmpty() && (kg == null || kg !in 30.0..300.0)) },
        confirmButton = { TextButton(onClick = { onSave(kg) }, enabled = kg != null && kg in 30.0..300.0) { Text("Сохранить") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}
