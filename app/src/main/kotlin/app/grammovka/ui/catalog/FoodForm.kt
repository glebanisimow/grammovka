package app.grammovka.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.grammovka.domain.model.Confidence
import app.grammovka.domain.model.FoodBasis
import app.grammovka.domain.model.FoodDraft
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.parse.PortionNames
import app.grammovka.ui.common.NumberField
import app.grammovka.ui.common.SourceBadge
import app.grammovka.ui.common.ValidatorLine
import app.grammovka.ui.common.input
import app.grammovka.ui.common.toDecimal
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme

class PortionRow(name: String, grams: String) {
    var name by mutableStateOf(name)
    var grams by mutableStateOf(grams)
}

/** Editable food: every field can be corrected before saving (invariant 2). */
@Stable
class FoodFormState(d: FoodDraft) {
    var name by mutableStateOf(d.name)
    var brand by mutableStateOf(d.brand.orEmpty())
    var aliases by mutableStateOf(d.aliases.joinToString("\n"))
    var kcal by mutableStateOf(d.per100.kcal.input())
    var protein by mutableStateOf(d.per100.protein.input())
    var fat by mutableStateOf(d.per100.fat.input())
    var carbs by mutableStateOf(d.per100.carbs.input())
    var fiber by mutableStateOf(d.per100.fiber?.input().orEmpty())
    var basis by mutableStateOf(d.basis)
    val source: FoodSource = d.source
    var sourceRef by mutableStateOf(d.sourceRef.orEmpty())
    val confidence: Confidence? = d.confidence
    var barcode by mutableStateOf(d.barcode.orEmpty())
    val portions = mutableStateListOf<PortionRow>().apply { addAll(d.portions.map { PortionRow(it.first, it.second.input()) }) }

    val nutrients: Nutrients?
        get() {
            val k = kcal.toDecimal() ?: return null
            val p = protein.toDecimal() ?: return null
            val f = fat.toDecimal() ?: return null
            val c = carbs.toDecimal() ?: return null
            val fb = if (fiber.isBlank()) null else fiber.toDecimal() ?: return null
            return Nutrients(k, p, f, c, fb)
        }

    fun toDraft(): FoodDraft? {
        val n = nutrients ?: return null
        val ps = portions.mapNotNull { r -> r.grams.toDecimal()?.takeIf { it > 0 && r.name.isNotBlank() }?.let { r.name.trim() to it } }
        return FoodDraft(
            name = name.trim(), brand = brand.trim().ifEmpty { null },
            aliases = aliases.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() },
            per100 = n, basis = basis, source = source, sourceRef = sourceRef.trim().ifEmpty { null },
            confidence = confidence, barcode = barcode.trim().ifEmpty { null }, portions = ps,
        )
    }

    /** Everything the user can change, as one string: differs from the loaded one when there are unsaved edits. */
    fun signature(): String = listOf(
        name, brand, aliases, kcal, protein, fat, carbs, fiber, basis.name, sourceRef, barcode,
        portions.joinToString(";") { it.name + "=" + it.grams },
    ).joinToString("|")

    /** Message for the user when saving is not possible, or null. */
    fun problem(): String? {
        val d = toDraft() ?: return "Заполните калории, белки, жиры и углеводы числами."
        return d.blockingProblems().firstOrNull()
    }
}

@Composable
fun FoodFormFields(state: FoodFormState, showSourceRef: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Источник", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted, modifier = Modifier.weight(1f))
            SourceBadge(state.source, state.confidence)
        }
        OutlinedTextField(state.name, { state.name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
        OutlinedTextField(state.brand, { state.brand = it }, Modifier.fillMaxWidth(), label = { Text("Бренд") }, singleLine = true)
        Text("На 100 г (напитки — на 100 мл)", style = MaterialTheme.typography.labelMedium, color = GTheme.extra.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(state.kcal, { state.kcal = it }, "Ккал", Modifier.weight(1f))
            NumberField(state.protein, { state.protein = it }, "Белки", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(state.fat, { state.fat = it }, "Жиры", Modifier.weight(1f))
            NumberField(state.carbs, { state.carbs = it }, "Углеводы", Modifier.weight(1f))
            NumberField(state.fiber, { state.fiber = it }, "Клетч.", Modifier.weight(1f))
        }
        ValidatorLine(state.nutrients)

        val bases = listOf(FoodBasis.AS_SOLD to "Как продаётся", FoodBasis.RAW to "Сырое", FoodBasis.COOKED to "Готовое")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            bases.forEachIndexed { i, (b, l) ->
                SegmentedButton(
                    selected = state.basis == b, onClick = { state.basis = b },
                    shape = SegmentedButtonDefaults.itemShape(i, bases.size, MaterialTheme.shapes.small), icon = {},
                ) { Text(l, style = MaterialTheme.typography.labelMedium) }
            }
        }
        OutlinedTextField(
            state.aliases, { state.aliases = it }, Modifier.fillMaxWidth(),
            label = { Text("Синонимы (по одному в строке)") }, minLines = 2,
        )
        if (showSourceRef && state.source != FoodSource.MANUAL) {
            OutlinedTextField(
                state.sourceRef, { state.sourceRef = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (state.source == FoodSource.USDA) "USDA fdcId" else if (state.source == FoodSource.WEB) "Ссылка" else "Ссылка на источник") },
            )
        }
        NumberField(state.barcode, { state.barcode = it.filter(Char::isDigit) }, "Штрихкод", Modifier.fillMaxWidth())
        PortionsEditor(state)
    }
}

@Composable
private fun PortionsEditor(state: FoodFormState) {
    Text("Порции", style = MaterialTheme.typography.titleSmall)
    state.portions.forEachIndexed { i, p ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(p.name, { p.name = it }, Modifier.weight(1.4f), singleLine = true, label = { Text("Название") })
            NumberField(p.grams, { p.grams = it }, "Граммы", Modifier.weight(1f))
            IconButton(onClick = { state.portions.removeAt(i) }) { Icon(GIcons.Close, "Удалить порцию", Modifier.size(20.dp)) }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PortionNames.PIECE, PortionNames.TBSP, PortionNames.TSP, "").forEach { n ->
            if (n.isEmpty() || state.portions.none { it.name == n }) {
                AssistChip(onClick = { state.portions.add(PortionRow(n, "")) }, label = { Text(if (n.isEmpty()) "+ своя" else "+ $n") })
            }
        }
    }
}
