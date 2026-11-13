package app.grammovka.ui.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import app.grammovka.ui.common.DiscardDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.grammovka.di.AppContainer
import app.grammovka.domain.model.FoodDraft
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.Nutrients
import app.grammovka.ui.icons.GIcons
import kotlinx.coroutines.launch

/** Create (id = null) or edit a catalog food. Editing never touches past log entries (invariant 4). */
@Composable
fun FoodEditScreen(container: AppContainer, id: Long?, barcode: String?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<FoodFormState?>(null) }
    var archived by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadedSignature by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleteBlocker by remember { mutableStateOf<String?>(null) }
    val leave = { if (state != null && state?.signature() != loadedSignature) confirmLeave = true else onBack() }
    BackHandler(onBack = leave)
    if (confirmLeave) {
        DiscardDialog(
            "Выйти без сохранения?", "Изменения в продукте не сохранятся.", "Выйти",
            onDiscard = { confirmLeave = false; onBack() }, onStay = { confirmLeave = false },
        )
    }

    LaunchedEffect(id) {
        state = if (id == null) {
            FoodFormState(FoodDraft(name = "", per100 = Nutrients(0.0, 0.0, 0.0, 0.0), source = FoodSource.MANUAL, barcode = barcode))
                .apply { kcal = ""; protein = ""; fat = ""; carbs = "" }
        } else {
            archived = container.foodRepository.byId(id)?.archived == true
            container.foodRepository.draftOf(id)?.let(::FoodFormState)
        }
        loadedSignature = state?.signature()
    }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = leave) { Icon(GIcons.Back, contentDescription = "Назад") }
            Text(if (id == null) "Новый продукт" else "Продукт", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            if (id != null) {
                IconButton(onClick = { scope.launch { deleteBlocker = container.foodRepository.deleteBlocker(id); confirmDelete = true } }) {
                    Icon(GIcons.Trash, "Удалить продукт")
                }
            }
        }
        val s = state ?: return@Column
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FoodFormFields(s)
            if (id != null) {
                Text(
                    "Правка продукта не меняет уже сделанные записи: в них хранятся свои цифры.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = {
                    val problem = s.problem()
                    if (problem != null) {
                        error = problem
                        return@Button
                    }
                    scope.launch {
                        val d = s.toDraft()!!
                        if (id == null) container.foodRepository.create(d) else container.foodRepository.update(id, d)
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
            ) { Text("Сохранить") }
            if (id != null) {
                OutlinedButton(
                    onClick = { scope.launch { container.foodRepository.setArchived(id, !archived); onBack() } },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    shape = MaterialTheme.shapes.small,
                ) { Text(if (archived) "Вернуть из архива" else "В архив") }
            }
        }
    }
    if (confirmDelete && id != null) {
        val blocker = deleteBlocker
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (blocker == null) "Удалить продукт?" else "Нельзя удалить") },
            text = {
                Text(
                    blocker ?: "Продукт исчезнет из каталога и шаблонов. Записи в дневнике останутся со своими цифрами. " +
                        "Если он ещё понадобится, лучше отправить в архив.",
                )
            },
            confirmButton = {
                if (blocker == null) {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch { container.foodRepository.delete(id); onBack() }
                    }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { confirmDelete = false }) { Text("Понятно") }
                }
            },
            dismissButton = if (blocker == null) ({ TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }) else null,
        )
    }

}
