package app.grammovka.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.grammovka.data.settings.ProfileSettings
import app.grammovka.di.AppContainer
import app.grammovka.domain.engine.BudgetMode
import app.grammovka.domain.engine.EngineConfig
import app.grammovka.domain.engine.Sex
import app.grammovka.ui.common.NumberField
import app.grammovka.ui.common.input
import app.grammovka.ui.common.toDecimal
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import kotlinx.coroutines.launch
import java.time.LocalDate

private val activityLevels = listOf(
    1.2 to "Сидячий",
    1.375 to "Лёгкая",
    1.55 to "Средняя",
    1.725 to "Высокая",
)

@Composable
fun ProfileScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var sex by remember { mutableStateOf<Sex?>(null) }
    var birthYear by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf("1,4") }
    var rate by remember { mutableStateOf("0,5") }
    var protein by remember { mutableStateOf("1,8") }
    var mode by remember { mutableStateOf(BudgetMode.WEEKLY) }

    LaunchedEffect(Unit) {
        val p = container.settingsStore.current().profile
        sex = p.sex
        birthYear = p.birthYear?.toString().orEmpty()
        height = p.heightCm?.input().orEmpty()
        activity = p.activityFactor.input()
        rate = p.ratePercentPerWeek.input()
        protein = p.proteinPerKg.input()
        mode = p.budgetMode
        loaded = true
    }

    val year = birthYear.toIntOrNull()
    val thisYear = LocalDate.now().year
    val yearOk = year == null || year in (thisYear - 100)..(thisYear - 14)
    val heightOk = height.toDecimal()?.let { it in 120.0..230.0 } ?: height.isEmpty()
    val activityOk = activity.toDecimal()?.let { it in 1.0..2.5 } == true
    val rateVal = rate.toDecimal()
    val rateOk = rateVal?.let { it in 0.0..2.0 } == true
    val proteinOk = protein.toDecimal()?.let { it in 0.5..3.5 } == true
    val valid = yearOk && heightOk && activityOk && rateOk && proteinOk

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(GIcons.Back, contentDescription = "Назад") }
            Text("Профиль и цель", style = MaterialTheme.typography.headlineSmall)
        }
        if (!loaded) return@Column
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Нужны для априорной оценки расхода (формула Миффлина — Сан Жеора). Через 2–4 недели записей и взвешиваний расход считается по вашим данным.",
                style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
            )
            Text("Пол", style = MaterialTheme.typography.titleSmall)
            val sexes = listOf(Sex.MALE to "Мужской", Sex.FEMALE to "Женский")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sexes.forEachIndexed { i, (s, l) ->
                    SegmentedButton(
                        selected = sex == s, onClick = { sex = s },
                        shape = SegmentedButtonDefaults.itemShape(i, sexes.size, MaterialTheme.shapes.small), icon = {},
                    ) { Text(l) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(birthYear, { birthYear = it.filter(Char::isDigit).take(4) }, "Год рождения", Modifier.weight(1f), isError = !yearOk)
                NumberField(height, { height = it }, "Рост", Modifier.weight(1f), suffix = "см", isError = !heightOk)
            }

            Text("Активность вне тренировок", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                activityLevels.forEach { (f, l) ->
                    FilterChip(selected = activity.toDecimal() == f, onClick = { activity = f.toString().replace('.', ',') }, label = { Text(l) })
                }
            }
            NumberField(activity, { activity = it }, "Коэффициент активности", Modifier.fillMaxWidth(), isError = !activityOk)

            Text("Цель", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(rate, { rate = it }, "Темп снижения", Modifier.weight(1f), suffix = "% в нед.", isError = !rateOk)
                NumberField(protein, { protein = it }, "Белок", Modifier.weight(1f), suffix = "г/кг", isError = !proteinOk)
            }
            if (rateVal != null && rateVal > EngineConfig().maxSafeRatePercent) {
                Text(
                    "Темп выше 1 % массы в неделю: будет предупреждение. Дефицит всё равно ограничен 25 % расхода, цель не ниже BMR.",
                    style = MaterialTheme.typography.bodySmall, color = GTheme.extra.accentText,
                )
            }

            Text("Бюджет", style = MaterialTheme.typography.titleSmall)
            val modes = listOf(BudgetMode.WEEKLY to "Недельный", BudgetMode.DAILY to "Дневной")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (m, l) ->
                    SegmentedButton(
                        selected = mode == m, onClick = { mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size, MaterialTheme.shapes.small), icon = {},
                    ) { Text(l) }
                }
            }
            Text(
                if (mode == BudgetMode.WEEKLY) {
                    "Недоел или переел — остаток недели перераспределяется по оставшимся дням (от 75 до 125 % дневной цели). Неделя с понедельника."
                } else {
                    "Каждый день — одна и та же цель."
                },
                style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
            )
            Button(
                onClick = {
                    scope.launch {
                        container.settingsStore.setProfile(
                            ProfileSettings(
                                sex = sex, birthYear = year, heightCm = height.toDecimal(),
                                activityFactor = activity.toDecimal()!!, ratePercentPerWeek = rate.toDecimal()!!,
                                proteinPerKg = protein.toDecimal()!!, budgetMode = mode,
                            ),
                        )
                        onBack()
                    }
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                shape = MaterialTheme.shapes.small,
            ) { Text("Сохранить") }
        }
    }
}
