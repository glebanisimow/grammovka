package app.grammovka.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.grammovka.data.repo.DayModel
import app.grammovka.domain.engine.Warning
import app.grammovka.domain.log.DayState
import app.grammovka.domain.model.Fmt
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import app.grammovka.ui.theme.NumberHero
import kotlin.math.abs

/** The day's headline number: smaller than the old full-width hero so the meal cards fit on the first screen. */
private val HeroCompact = NumberHero.copy(fontSize = 48.sp, lineHeight = 52.sp)

/** Label, number and colour of the headline: what is left on an open day, the result of a closed one. */
private data class Headline(val label: String, val number: String, val color: Color)

@Composable
private fun headline(ui: TodayUi, m: DayModel): Headline {
    val b = m.balance(ui.eaten.kcal)
    return if (ui.state == DayState.CLOSED) {
        // In words, not as a signed «баланс»: a minus sign alone does not say which way is good.
        Headline(
            when {
                b.balance < -0.5 -> "Дефицит дня"
                b.balance > 0.5 -> "Профицит дня"
                else -> "Итог дня"
            },
            Fmt.kcal(abs(b.balance)),
            if (b.balance > 0.5) MaterialTheme.colorScheme.error else GTheme.extra.success,
        )
    } else {
        Headline(
            if (b.left >= 0) "Осталось" else "Сверх бюджета",
            Fmt.kcal(abs(b.left)),
            if (b.left >= 0) GTheme.extra.accentText else MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Budget, protein and weight in one compact block: the number on the left (tap → where it comes from),
 * protein with fat and carbs on the right, one line of context below.
 */
@Composable
fun Budget(ui: TodayUi, onOpenProfile: () -> Unit, onWeight: () -> Unit, onExplain: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val m = ui.model
        when {
            !ui.profileComplete -> Notice("Заполните профиль: пол, год рождения и рост нужны, чтобы считать бюджет.", "Открыть профиль", onOpenProfile)
            m == null -> Notice("Введите вес: с первого взвешивания появится цель на день.", "Ввести вес", onWeight)
            else -> {
                val h = headline(ui, m)
                val b = m.balance(ui.eaten.kcal)
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Откуда это число", onClick = onExplain), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = GTheme.extra.textMuted)
                            Spacer(Modifier.width(6.dp))
                            Icon(GIcons.Info, null, Modifier.size(14.dp), tint = GTheme.extra.textMuted)
                        }
                        Text(h.number, style = HeroCompact, color = h.color)
                    }
                    val p = m.snapshot.proteinTarget
                    Column(Modifier.width(156.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Белок", style = MaterialTheme.typography.titleSmall)
                            Text("${Fmt.kcal(ui.eaten.protein)} / ${Fmt.kcal(p)} г", style = MaterialTheme.typography.titleSmall)
                        }
                        LinearProgressIndicator(
                            progress = { (ui.eaten.protein / p).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            drawStopIndicator = {},
                        )
                        Text(
                            "Жиры ${Fmt.kcal(ui.eaten.fat)} · Углев. ${Fmt.kcal(ui.eaten.carbs)} г",
                            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                        )
                    }
                }
                val workouts = if (m.activity > 0) " + ${Fmt.kcal(m.activity)} тренировки" else ""
                Text(
                    if (ui.state == DayState.CLOSED) {
                        "съедено ${Fmt.kcal(ui.eaten.kcal)} − расход ≈ ${Fmt.kcal(b.expenditure)} · бюджет был ${Fmt.kcal(m.allowance)}$workouts"
                    } else {
                        "из ${Fmt.kcal(m.allowance)}$workouts · съедено ${Fmt.kcal(ui.eaten.kcal)} · расход ≈ ${Fmt.kcal(b.expenditure)}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                )
                Flags(m)
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onWeight).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(GIcons.Scale, null, Modifier.size(20.dp), tint = GTheme.extra.textMuted)
            Spacer(Modifier.width(12.dp))
            Text(
                ui.weightKg?.let { "Вес ${Fmt.kg(it)} кг" } ?: "Вес не введён",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            )
            m?.let { Text("тренд ${Fmt.kg(it.snapshot.trendWeight)}", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted) }
        }
    }
}

@Composable
private fun Flags(m: DayModel) {
    val lines = buildList {
        if (m.snapshot.capped) add("Дефицит ограничен 25 % расхода.")
        if (m.snapshot.bmrFloor) add("Цель поднята до уровня BMR (${Fmt.kcal(m.snapshot.bmr)} ккал).")
        m.warnings.forEach {
            add(
                when (it) {
                    Warning.RATE_TOO_HIGH -> "Темп в профиле выше 1 % массы в неделю."
                    Warning.LOSING_TOO_FAST -> "За 4 недели вес снижается в 1,5 раза быстрее цели. Можно есть больше."
                    Warning.PROTEIN_LOW -> "Белок ниже цели 5 из последних 7 дней."
                },
            )
        }
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = GTheme.extra.accentText) }
}

/** The headline kept on screen while the list is scrolled down to the meals. */
@Composable
fun PinnedBudget(ui: TodayUi, modifier: Modifier = Modifier) {
    val m = ui.model ?: return
    val h = headline(ui, m)
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(dayShort(ui.date), style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted)
            Spacer(Modifier.width(12.dp))
            Text("${h.label} ${h.number}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = h.color)
            Text(
                "Белок ${Fmt.kcal(ui.eaten.protein)} / ${Fmt.kcal(m.snapshot.proteinTarget)} г",
                style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * «Откуда это число»: the chain from expenditure to what is left, in the day's own numbers. The words
 * «расход», «цель» and «бюджет» mean three different things, and this is the one place that says which.
 */
@Composable
fun BudgetDialog(ui: TodayUi, onDismiss: () -> Unit) {
    val m = ui.model ?: return
    val s = m.snapshot
    val b = m.balance(ui.eaten.kcal)
    val shift = m.allowance - s.target
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Откуда это число") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step(
                    "Расход без тренировок", Fmt.kcal(s.tdee),
                    if (s.tdeeRaw != null) "Столько вы тратите за день: посчитано по вашему весу и записям за 4 недели."
                    else "Столько вы тратите за день. Пока по формуле: данных для расчёта по вашему весу ещё мало.",
                )
                Step(
                    "Плановый дефицит", "−" + Fmt.kcal(s.deficit),
                    "По темпу из профиля." + (if (s.capped) " Ограничен 25 % расхода." else "") + (if (s.bmrFloor) " Уменьшен, чтобы цель не опустилась ниже BMR." else ""),
                )
                Step("Цель на день", Fmt.kcal(s.target), "Расход минус дефицит: сколько есть, чтобы идти по плану.", strong = true)
                if (abs(shift) > 0.5) {
                    Step(
                        "Поправка недели", (if (shift > 0) "+" else "−") + Fmt.kcal(abs(shift)),
                        if (shift > 0) "Раньше на этой неделе съедено меньше цели: остаток разделён на оставшиеся дни."
                        else "Раньше на этой неделе съедено больше цели: перебор разделён на оставшиеся дни.",
                    )
                }
                Step("Бюджет дня", Fmt.kcal(m.allowance), if (abs(shift) > 0.5) "Цель с поправкой недели." else "Равен цели: поправки недели нет.", strong = true)
                if (m.activity > 0) Step("Тренировки", "+" + Fmt.kcal(m.activity), "Записанные тренировки дня добавляются сверху.")
                Step("Съедено", "−" + Fmt.kcal(ui.eaten.kcal), null)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Step(if (b.left >= 0) "Осталось" else "Сверх бюджета", Fmt.kcal(abs(b.left)), null, strong = true)
                Text(
                    "Дефицит дня считается от всего расхода, а не от бюджета: съедено ${Fmt.kcal(ui.eaten.kcal)} минус расход ${Fmt.kcal(b.expenditure)}" +
                        (if (m.activity > 0) " (с тренировками)" else "") + " — сейчас ${balanceWords(b.balance)}. " +
                        "Если съесть ровно бюджет, останется ${balanceWords(m.allowance - s.tdee)}.",
                    style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
    )
}

@Composable
private fun Step(label: String, value: String, note: String?, strong: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
            Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted) }
    }
}
