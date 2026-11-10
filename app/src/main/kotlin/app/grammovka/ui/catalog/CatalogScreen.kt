package app.grammovka.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.grammovka.data.db.FoodEntity
import app.grammovka.data.db.PortionEntity
import app.grammovka.data.repo.DayClosedException
import app.grammovka.data.repo.FoodRepository
import app.grammovka.data.repo.LogRepository
import app.grammovka.data.repo.NewEntry
import app.grammovka.di.AppContainer
import app.grammovka.domain.log.MealBlocks
import app.grammovka.domain.model.Fmt
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.InputMethod
import app.grammovka.domain.model.Meal
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.model.Nutrition
import app.grammovka.domain.search.CatalogSearch
import app.grammovka.ui.common.ScreenTitle
import app.grammovka.ui.common.SourceBadge
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import app.grammovka.ui.today.dayShort
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** What the list shows: [archivedHits] counts matches hidden in the archive while searching another tab. */
data class CatalogList(val items: List<FoodEntity> = emptyList(), val archivedHits: Int = 0)

/** Everything the «add to a meal» sheet needs about a food and the day it would be written to. */
data class AddContext(
    val food: FoodEntity,
    val portions: List<PortionEntity>,
    val lastGrams: Double?,
    val day: LocalDate,
    val today: LocalDate,
    val closed: Boolean,
    val meal: Meal,
)

/** A food logged from the catalog, for the «Отменить» snackbar. */
data class CatalogLogged(val ids: List<Long>, val text: String)

@OptIn(FlowPreview::class)
class CatalogViewModel(private val foods: FoodRepository, private val logs: LogRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val tab = MutableStateFlow(CatalogTab.ACTIVE)

    private fun docs(list: List<FoodEntity>) =
        list.map { CatalogSearch.doc(it.id, it.name, it.brand, FoodRepository.aliasesOf(it.aliases), it.usageCount, it.lastUsedAt) }

    private val active = foods.observeCatalog(false).map { it to docs(it) }
    private val archived = foods.observeCatalog(true).map { it to docs(it) }

    /** The whole catalog is in memory (a personal catalog is a few hundred foods), so search is live and forgiving. */
    val list = combine(query.debounce(120), tab, active, archived) { q, t, (act, actDocs), (arc, arcDocs) ->
        fun find(all: List<FoodEntity>, docs: List<CatalogSearch.Doc>): List<FoodEntity> {
            if (q.isBlank()) return all
            val byId = all.associateBy { it.id }
            return CatalogSearch.search(q, docs).mapNotNull(byId::get)
        }
        when (t) {
            CatalogTab.ARCHIVE -> CatalogList(find(arc, arcDocs))
            else -> CatalogList(
                find(act, actDocs).filter { t == CatalogTab.ACTIVE || it.source == FoodSource.RECIPE },
                archivedHits = if (q.isBlank()) 0 else find(arc, arcDocs).size,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogList())

    /** The day the app is filling in (the latest open day, otherwise today) and the meal the input line would use. */
    suspend fun addContext(f: FoodEntity): AddContext {
        val today = LocalDate.now()
        val day = logs.defaultDay(today)
        val entries = logs.observeDay(day).first()
        val meal = if (day == today) {
            MealBlocks.defaultTarget(MealBlocks.group(entries, { it.time }, { it.meal }), { it.time }, LocalTime.now())
        } else {
            Meal.SNACK
        }
        return AddContext(f, foods.portionEntities(f.id), logs.lastGrams(f.id), day, today, logs.isClosed(day), meal)
    }

    /** Writes the food to the day; null when the day was closed meanwhile. */
    suspend fun add(c: AddContext, grams: Double, meal: Meal): CatalogLogged? {
        val f = c.food
        val per100 = Nutrients(f.kcal, f.protein, f.fat, f.carbs, f.fiber)
        val name = if (f.brand.isNullOrBlank()) f.name else "${f.name}, ${f.brand}"
        val time = if (c.day == LocalDate.now()) LocalTime.now().withSecond(0).withNano(0) else MealBlocks.defaultTime(meal)
        val ids = try {
            logs.log(c.day, time, listOf(NewEntry(f.id, name, grams, per100, InputMethod.QUICK, meal = meal)))
        } catch (e: DayClosedException) {
            return null
        }
        val where = if (c.day == c.today) meal.ru else "${meal.ru}, ${dayShort(c.day)}"
        return CatalogLogged(ids, "Записано в $where: ${f.name} ${Fmt.kcal(grams)} г · ${Fmt.kcal(Nutrition.forGrams(per100, grams).kcal)} ккал")
    }

    fun undo(ids: List<Long>) {
        viewModelScope.launch { logs.delete(ids) }
    }
}

enum class CatalogTab(val label: String) { ACTIVE("Продукты"), RECIPES("Рецепты"), ARCHIVE("Архив") }

/**
 * Tap on a food opens the «add to a meal» sheet (the catalog is first of all a way to log); the sheet leads on
 * to the editor through [onOpen] (recipe foods open the recipe editor). [onCreate] gets true for a new recipe.
 */
@Composable
fun CatalogScreen(container: AppContainer, onOpen: (FoodEntity) -> Unit, onCreate: (recipe: Boolean) -> Unit) {
    val vm: CatalogViewModel = viewModel(factory = viewModelFactory { initializer { CatalogViewModel(container.foodRepository, container.logRepository) } })
    val query by vm.query.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val list by vm.list.collectAsStateWithLifecycle()
    val items = list.items
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf<AddContext?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle("Каталог")
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true, placeholder = { Text("Название, бренд или синоним") },
                leadingIcon = { Icon(GIcons.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { vm.query.value = "" }) { Icon(GIcons.Close, "Очистить поиск") } }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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
                        query.isNotBlank() && tab == CatalogTab.RECIPES -> "Среди рецептов такого нет."
                        query.isNotBlank() && tab == CatalogTab.ARCHIVE -> "В архиве такого нет."
                        query.isNotBlank() -> "В каталоге такого нет. Кнопка «+» добавит продукт вручную, а строка ввода на «Сегодня» найдёт его в базах."
                        tab == CatalogTab.ARCHIVE -> "Архив пуст."
                        tab == CatalogTab.RECIPES -> "Рецептов пока нет. Кнопка «+» создаст рецепт: ингредиенты из каталога и вес готового блюда."
                        else -> "Каталог пуст. Продукты появляются здесь после первой записи или кнопкой «+»."
                    },
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(items, key = { it.id }) { f ->
                    FoodRow(f) {
                        // Archived foods are not logged: straight to the editor, where they can be restored.
                        if (f.archived) onOpen(f) else scope.launch { adding = vm.addContext(f) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (list.archivedHits > 0) {
                    item(key = "archive-hint") {
                        Text(
                            "Ещё в архиве: ${list.archivedHits}",
                            Modifier.fillMaxWidth().clickable { vm.tab.value = CatalogTab.ARCHIVE }.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.accentText,
                        )
                    }
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    adding?.let { c ->
        CatalogAddSheet(
            c,
            onDismiss = { adding = null },
            onEdit = { adding = null; onOpen(c.food) },
            onAdd = { grams, meal ->
                adding = null
                scope.launch {
                    val logged = vm.add(c, grams, meal)
                    if (logged == null) {
                        snackbar.showSnackbar("${dayShort(c.day).replaceFirstChar { it.uppercase() }} закрыт. Откройте день на «Сегодня» (меню ⋯) или начните новый.")
                    } else {
                        val r = snackbar.showSnackbar(logged.text, actionLabel = "Отменить", duration = SnackbarDuration.Short)
                        if (r == SnackbarResult.ActionPerformed) vm.undo(logged.ids)
                    }
                }
            },
        )
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
