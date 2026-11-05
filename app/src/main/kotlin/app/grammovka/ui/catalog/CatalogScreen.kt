package app.grammovka.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.grammovka.data.db.FoodEntity
import app.grammovka.data.repo.FoodRepository
import app.grammovka.di.AppContainer
import app.grammovka.domain.model.Fmt
import app.grammovka.domain.model.FoodSource
import app.grammovka.ui.common.ScreenTitle
import app.grammovka.ui.common.SourceBadge
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CatalogViewModel(private val foods: FoodRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val tab = MutableStateFlow(CatalogTab.ACTIVE)

    val items = combine(query.debounce(150), tab) { q, t -> q to t }
        .flatMapLatest { (q, t) ->
            val archived = t == CatalogTab.ARCHIVE
            val all = if (q.isBlank()) foods.observeCatalog(archived)
            else flow { emit(foods.searchEntities(q, includeArchived = true).filter { it.archived == archived }) }
            if (t == CatalogTab.RECIPES) all.map { l -> l.filter { it.source == FoodSource.RECIPE } } else all
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

enum class CatalogTab(val label: String) { ACTIVE("Продукты"), RECIPES("Рецепты"), ARCHIVE("Архив") }

/** [onOpen] gets the food; recipe foods open the recipe editor. [onCreate] gets true for a new recipe. */
@Composable
fun CatalogScreen(container: AppContainer, onOpen: (FoodEntity) -> Unit, onCreate: (recipe: Boolean) -> Unit) {
    val vm: CatalogViewModel = viewModel(factory = viewModelFactory { initializer { CatalogViewModel(container.foodRepository) } })
    val query by vm.query.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Каталог")
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true, placeholder = { Text("Поиск по названию и синонимам") },
                leadingIcon = { Icon(GIcons.Search, null) },
            )
            val tabs = CatalogTab.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                tabs.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tab == t, onClick = { vm.tab.value = t },
                        shape = SegmentedButtonDefaults.itemShape(i, tabs.size, MaterialTheme.shapes.small), icon = {},
                    ) { Text(t.label) }
                }
            }
            if (items.isEmpty()) {
                Text(
                    when {
                        query.isNotBlank() -> "Ничего не найдено. Добавьте продукт кнопкой «+» или запишите его на экране «Сегодня»: новый продукт попадёт в каталог после подтверждения."
                        tab == CatalogTab.ARCHIVE -> "Архив пуст."
                        tab == CatalogTab.RECIPES -> "Рецептов пока нет. Кнопка «+» создаст рецепт: ингредиенты из каталога и вес готового блюда."
                        else -> "Каталог пуст. Продукты появляются здесь после первой записи или кнопкой «+»."
                    },
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { f ->
                    FoodRow(f) { onOpen(f) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        FloatingActionButton(
            onClick = { onCreate(tab == CatalogTab.RECIPES) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = MaterialTheme.shapes.medium,
        ) { Icon(GIcons.Plus, if (tab == CatalogTab.RECIPES) "Новый рецепт" else "Добавить продукт") }
    }
}

@Composable
private fun FoodRow(f: FoodEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (f.brand.isNullOrBlank()) f.name else "${f.name}, ${f.brand}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Б ${Fmt.grams(f.protein)} · Ж ${Fmt.grams(f.fat)} · У ${Fmt.grams(f.carbs)}",
                style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${Fmt.kcal(f.kcal)} ккал", style = MaterialTheme.typography.titleSmall)
            SourceBadge(f.source, f.confidence)
        }
    }
}
