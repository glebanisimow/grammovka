package app.grammovka.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.grammovka.data.db.FoodEntity
import app.grammovka.domain.model.Meal
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme

/**
 * «+ позиция» in a meal card: pick a food from the catalog (frequent first, the same forgiving search as the
 * catalog screen); the grams sheet follows. Typing or dictating free text stays one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodPickerSheet(vm: CatalogViewModel, meal: Meal, onDismiss: () -> Unit, onPick: (FoodEntity) -> Unit, onTypeInstead: () -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val list by vm.list.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.tab.value = CatalogTab.ACTIVE }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Text(
                "Добавить в ${meal.ru}", Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true, placeholder = { Text("Поиск в каталоге") },
                leadingIcon = { Icon(GIcons.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { vm.query.value = "" }) { Icon(GIcons.Close, "Очистить поиск") } }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onTypeInstead).heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(GIcons.Mic, null, Modifier.size(18.dp), tint = GTheme.extra.accentText)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Нет в каталоге или несколько сразу — ввести текстом или голосом",
                    style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.accentText,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (list.items.isEmpty()) {
                Text(
                    if (query.isBlank()) "Каталог пуст: продукты появляются в нём после первой записи." else "В каталоге такого нет.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(list.items, key = { it.id }) { f ->
                    FoodRow(f) { onPick(f) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
