package app.grammovka.ui.progress

import androidx.compose.runtime.Composable
import app.grammovka.ui.common.ComingLater
import app.grammovka.ui.icons.GIcons

@Composable
fun ProgressScreen() {
    ComingLater(GIcons.Progress, "Прогресс", "Вес, тренд и расход появятся в фазах 1–2.")
}
