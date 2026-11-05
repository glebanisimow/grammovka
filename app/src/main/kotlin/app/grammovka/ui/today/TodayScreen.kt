package app.grammovka.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import app.grammovka.ui.theme.NumberHero

/** Phase 0 layout skeleton of the Today screen; data and input arrive in phase 1. */
@Composable
fun TodayScreen() {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("ОСТАЛОСЬ", style = MaterialTheme.typography.labelMedium, color = GTheme.extra.textMuted)
            Text("—", style = NumberHero, color = GTheme.extra.accentText)
            Text("ккал на сегодня", style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted)
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Белок", style = MaterialTheme.typography.titleSmall)
                Text("— / — г", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(32.dp))
            Text(
                "Записей пока нет. Ввод продуктов, каталог и расчёт бюджета появятся в фазе 1.",
                style = MaterialTheme.typography.bodyMedium,
                color = GTheme.extra.textMuted,
            )
        }
        InputBarPlaceholder()
    }
}

@Composable
private fun InputBarPlaceholder() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(48.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("творог 150, кофе с молоком…", color = GTheme.extra.textMuted)
        }
        Icon(GIcons.Barcode, null, Modifier.padding(start = 12.dp).size(28.dp), tint = GTheme.extra.textMuted)
        Icon(GIcons.Camera, null, Modifier.padding(start = 12.dp).size(28.dp), tint = GTheme.extra.textMuted)
    }
}
