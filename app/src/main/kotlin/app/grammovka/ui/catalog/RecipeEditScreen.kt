package app.grammovka.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.grammovka.data.repo.displayName
import app.grammovka.data.repo.per100
import app.grammovka.di.AppContainer
import app.grammovka.domain.model.CatalogFood
import app.grammovka.domain.model.Fmt
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.model.Nutrition
import app.grammovka.domain.parse.GramResolver
import app.grammovka.domain.parse.Grams
import app.grammovka.domain.parse.InputParser
import app.grammovka.domain.recipe.Ingredient
import app.grammovka.domain.recipe.RecipeMath
import app.grammovka.ui.common.NumberField
import app.grammovka.ui.common.SectionLabel
import app.grammovka.ui.common.ValidatorLine
import app.grammovka.ui.common.input
import app.grammovka.ui.common.toDecimal
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class IngredientLine(val foodId: Long, val name: String, val per100: Nutrients, grams: String) {
    var grams by mutableStateOf(grams)
}

class RecipeEditViewModel(private val c: AppContainer, private val id: Long?) : ViewModel() {
    var name by mutableStateOf("")
    var cooked by mutableStateOf("")
    var servings by mutableStateOf("")
    val lines = mutableStateListOf<IngredientLine>()
    var query by mutableStateOf("")
    var hits by mutableStateOf<List<CatalogFood>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    private var ownFoodId: Long? = null
    private var searchJob: Job? = null

    init {
        if (id != null) {
            viewModelScope.launch {
                val r = c.recipeRepository.load(id) ?: return@launch
                ownFoodId = c.recipeRepository.foodIdOf(id)
                name = r.name
                cooked = r.cookedWeightG.input()
                servings = r.servings?.toString().orEmpty()
                lines += r.items.map { IngredientLine(it.foodId, it.displayName, it.per100(), it.grams.input()) }
            }
        }
    }

    /** «гречка 100»: the name searches the catalog, the quantity becomes grams of the picked food. */
    fun onQuery(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(150)
            val f = InputParser.parseFragment(q)
            hits = if (f.name.isBlank()) emptyList() else c.foodRepository.search(f.name, 8).filter { it.id != ownFoodId }
        }
    }

    fun add(food: CatalogFood) {
        viewModelScope.launch {
            val g = GramResolver.resolve(InputParser.parseFragment(query), c.foodRepository.portions(food.id))
            lines += IngredientLine(food.id, food.displayName, food.per100, (g as? Grams.Known)?.grams?.input().orEmpty())
            query = ""
            hits = emptyList()
        }
    }

    fun ingredients() = lines.mapNotNull { l -> l.grams.toDecimal()?.let { Ingredient(l.per100, it) } }
    fun rawWeight() = RecipeMath.rawWeight(ingredients())
    fun preview(): Nutrients? = cooked.toDecimal()?.let { RecipeMath.per100(ingredients(), it) }

    fun save(onDone: () -> Unit) {
        val cookedG = cooked.toDecimal()
        error = when {
            name.isBlank() -> "Укажите название."
            lines.isEmpty() -> "Добавьте ингредиенты."
            lines.any { (it.grams.toDecimal() ?: 0.0) <= 0 } -> "Укажите вес каждого ингредиента."
            cookedG == null || cookedG <= 0 -> "Укажите вес готового блюда."
            servings.isNotBlank() && (servings.toIntOrNull() ?: 0) <= 0 -> "Число порций — целое больше нуля."
            else -> null
        }
        if (error != null) return
        busy = true
        viewModelScope.launch {
            c.recipeRepository.save(id, name, cookedG!!, servings.toIntOrNull(), lines.map { it.foodId to it.grams.toDecimal()!! })
            busy = false
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        id ?: return
        viewModelScope.launch {
            c.recipeRepository.delete(id)
            onDone()
        }
    }
}

/**
 * Recipe editor (spec, phase 2: «Рецепты с весом готового блюда»). Ingredients come from the catalog;
 * per-100 g values of the dish are computed by RecipeMath from the cooked weight.
 */
@Composable
fun RecipeEditScreen(container: AppContainer, id: Long?, onBack: () -> Unit) {
    val vm: RecipeEditViewModel = viewModel(factory = viewModelFactory { initializer { RecipeEditViewModel(container, id) } })
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(GIcons.Back, contentDescription = "Назад") }
            Text(if (id == null) "Новый рецепт" else "Рецепт", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            if (id != null) IconButton(onClick = { confirmDelete = true }) { Icon(GIcons.Trash, "Удалить рецепт") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(vm.name, { vm.name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)

            SectionLabel("Ингредиенты")
            vm.lines.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.bodyLarge)
                        val kcal = l.grams.toDecimal()?.let { Nutrition.forGrams(l.per100, it).kcal }
                        Text(
                            kcal?.let { "${Fmt.kcal(it)} ккал" } ?: "${Fmt.kcal(l.per100.kcal)} ккал / 100 г",
                            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                        )
                    }
                    NumberField(l.grams, { l.grams = it }, "Вес", Modifier.size(width = 112.dp, height = 64.dp), suffix = "г", isError = l.grams.isBlank())
                    IconButton(onClick = { vm.lines.remove(l) }) { Icon(GIcons.Close, "Убрать", Modifier.size(18.dp)) }
                }
            }
            OutlinedTextField(
                vm.query, vm::onQuery, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Добавить: «гречка 100»") }, leadingIcon = { Icon(GIcons.Search, null) },
            )
            if (vm.hits.isNotEmpty()) {
                Column {
                    vm.hits.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.add(f) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(f.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("${Fmt.kcal(f.per100.kcal)} ккал / 100 г", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            } else if (vm.query.isNotBlank()) {
                Text(
                    "В каталоге нет. Сначала добавьте продукт в каталог: ингредиенты рецепта берутся только оттуда.",
                    style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                )
            }

            SectionLabel("Готовое блюдо")
            val raw = vm.rawWeight()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(vm.cooked, { vm.cooked = it }, "Вес готового", Modifier.weight(1f), suffix = "г", isError = vm.cooked.isBlank())
                NumberField(vm.servings, { vm.servings = it }, "Порций", Modifier.weight(0.6f))
            }
            if (raw > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Сырые ингредиенты: ${Fmt.kcal(raw)} г. Взвесьте готовое блюдо: при варке вес меняется.",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                    )
                    TextButton(onClick = { vm.cooked = raw.input() }) { Text("= сырой") }
                }
            }
            vm.servings.toIntOrNull()?.takeIf { it > 0 }?.let { n ->
                vm.cooked.toDecimal()?.let { Text("Порция: ${Fmt.kcal(it / n)} г", style = MaterialTheme.typography.bodySmall) }
            }

            vm.preview()?.let { n ->
                SectionLabel("На 100 г готового")
                Text(
                    "${Fmt.kcal(n.kcal)} ккал · Б ${Fmt.grams(n.protein)} · Ж ${Fmt.grams(n.fat)} · У ${Fmt.grams(n.carbs)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                ValidatorLine(n)
            }
            if (id != null) {
                Text(
                    "Правка рецепта пересчитает продукт в каталоге. Уже сделанные записи не меняются.",
                    style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                )
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            Button(
                onClick = { vm.save(onBack) }, enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), shape = MaterialTheme.shapes.small,
            ) { Text("Сохранить") }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить рецепт?") },
            text = { Text("Продукт рецепта уйдёт в архив каталога. Записи с ним останутся.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
