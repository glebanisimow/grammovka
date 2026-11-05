package app.grammovka.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.grammovka.data.settings.AppSettings
import app.grammovka.data.settings.ThemeMode
import app.grammovka.di.AppContainer
import app.grammovka.ui.common.ScreenTitle
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onOpenKeys: () -> Unit) {
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Настройки")
        SettingsRow(GIcons.Key, "Ключи API", "Anthropic и USDA: ввод и проверка", onOpenKeys)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(GIcons.Theme, null, tint = GTheme.extra.textMuted)
            Text("Тема", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
        }
        val modes = listOf(ThemeMode.SYSTEM to "Система", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = settings.themeMode == mode,
                    onClick = { scope.launch { container.settingsStore.setThemeMode(mode) } },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size, MaterialTheme.shapes.small),
                    icon = {},
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = GTheme.extra.textMuted)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
        }
        Icon(GIcons.Next, null, Modifier.size(20.dp), tint = GTheme.extra.textMuted)
    }
}
