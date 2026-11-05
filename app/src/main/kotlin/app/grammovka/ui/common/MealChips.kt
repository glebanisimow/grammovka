package app.grammovka.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.grammovka.domain.model.Meal

/** Optional meal marker: tap to set, tap again to clear (unspecified is the default). */
@Composable
fun MealChips(selected: Meal?, onSelect: (Meal?) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Meal.entries.forEach { m ->
            FilterChip(
                selected = selected == m,
                onClick = { onSelect(if (selected == m) null else m) },
                label = { Text(m.ru) },
            )
        }
    }
}
