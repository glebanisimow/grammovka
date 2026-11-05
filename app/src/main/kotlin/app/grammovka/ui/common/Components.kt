package app.grammovka.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.CornerRadius
import app.grammovka.domain.model.Confidence
import app.grammovka.domain.model.FoodSource
import app.grammovka.domain.model.Nutrients
import app.grammovka.domain.validate.NutrientValidator
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme

fun FoodSource.label(): String = when (this) {
    FoodSource.LABEL -> "этикетка"
    FoodSource.OFF -> "OFF"
    FoodSource.USDA -> "USDA"
    FoodSource.WEB -> "веб"
    FoodSource.CLAUDE_ESTIMATE -> "≈ оценка Claude"
    FoodSource.MANUAL -> "вручную"
    FoodSource.RECIPE -> "рецепт"
}

fun Confidence.label(): String = when (this) {
    Confidence.HIGH -> "высокая"
    Confidence.MEDIUM -> "средняя"
    Confidence.LOW -> "низкая"
}

/**
 * Source badge shown next to every food (UX: provenance is always visible).
 * A Claude estimate gets a dashed amber outline so it never looks like measured data.
 */
@Composable
fun SourceBadge(source: FoodSource, confidence: Confidence? = null, modifier: Modifier = Modifier) {
    val estimate = source == FoodSource.CLAUDE_ESTIMATE
    val color = if (estimate) GTheme.extra.accentText else GTheme.extra.textMuted
    val text = buildString {
        append(source.label())
        if (estimate && confidence != null) append(" · ${confidence.label()}")
    }
    Box(modifier) {
        if (estimate) {
            val stroke = GTheme.extra.accentText
            Canvas(Modifier.matchParentSize()) {
                drawRoundRect(
                    color = stroke,
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                )
            }
        } else {
            Box(
                Modifier.matchParentSize()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
            )
        }
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Decimal input that accepts both comma and dot. */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter { it.isDigit() || it == ',' || it == '.' }) },
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        isError = isError,
        suffix = suffix?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

fun String.toDecimal(): Double? = trim().replace(',', '.').toDoubleOrNull()

fun Double.input(): String {
    val r = Math.round(this * 10) / 10.0
    return if (r == Math.floor(r)) r.toLong().toString() else r.toString().replace('.', ',')
}

/** Validator result under a nutrient form (invariant 5): a warning, never a block. */
@Composable
fun ValidatorLine(n: Nutrients?) {
    if (n == null) return
    val issues = NutrientValidator.check(n)
    val (ok, text) = if (issues.isEmpty()) {
        true to "Энергия сходится с БЖУ: ${Math.round(NutrientValidator.computedKcal(n))} ккал по формуле."
    } else {
        false to issues.joinToString(" ") {
            when (it) {
                is NutrientValidator.Issue.EnergyMismatch ->
                    "Калории не сходятся с БЖУ: по формуле ${Math.round(it.computed)}, указано ${Math.round(it.declared)} (${Math.round(it.relDiff * 100)} %). Проверьте цифры."
                NutrientValidator.Issue.KcalTooHigh -> "Больше 900 ккал на 100 г — так не бывает."
                NutrientValidator.Issue.MassOver100 -> "Белки, жиры, углеводы и клетчатка вместе больше 100 г."
                NutrientValidator.Issue.Negative -> "Есть отрицательные значения."
            }
        }
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (ok) GIcons.Check else GIcons.Warning, null, Modifier.size(18.dp),
            tint = if (ok) GTheme.extra.success else GTheme.extra.warning,
        )
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok) GTheme.extra.textMuted else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = GTheme.extra.textMuted,
    )
}

/** Small amber dot used as a "needs attention" marker. */
@Composable
fun Dot(modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
}
