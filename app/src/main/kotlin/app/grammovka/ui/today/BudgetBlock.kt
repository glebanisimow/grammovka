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
            // «Не считаем»: no budget pressure on this day, only what was eaten, if anything.
            ui.incomplete -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("НЕ СЧИТАЕМ", style = MaterialTheme.typography.labelMedium, color = GTheme.extra.textMuted)
                Text(
                    "Этот день не идёт ни в норму, ни в разбор недели. Недельный бюджет считает его ровно по цели.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (ui.eaten.kcal > 0) {
                    Text("Записано ${Fmt.kcal(ui.eaten.kcal)} ккал", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
                }
            }
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
                        FiberBar(ui)
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
                if (ui.eaten.fiberUnknown > 0) {
                    val n = ui.eaten.fiberUnknown
                    Text(
                        "Клетчатка: у $n ${entriesWord(n)} нет данных. Допишите её продуктам в каталоге (там они с «Кл ?»), записи дополнятся.",
                        style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                    )
                }
                Flags(m)
            }
        }
        if (ui.model != null || ui.weightKg != null || ui.incomplete) WeightRow(ui, onWeight)
    }
}

/**
 * The day's weigh-in. Shown in every state of the day, also before «Начать день» (the scale comes first in
 * the morning). Without a weigh-in today it is a visible button, otherwise a quiet line to correct it.
 */
@Composable
fun WeightRow(ui: TodayUi, onWeight: () -> Unit) {
    val trend = ui.model?.snapshot?.trendWeight
    if (ui.weightKg == null && ui.date == ui.today) {
        androidx.compose.material3.OutlinedButton(
            onClick = onWeight, Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = MaterialTheme.shapes.small,
        ) {
            Icon(GIcons.Scale, null, Modifier.size(18.dp), tint = GTheme.extra.accentText)
            Spacer(Modifier.width(8.dp))
            Text("Взвеситься", Modifier.weight(1f), color = GTheme.extra.accentText)
            trend?.let { Text("тренд ${Fmt.kg(it)}", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted) }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Изменить вес", onClick = onWeight).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(GIcons.Scale, null, Modifier.size(20.dp), tint = GTheme.extra.textMuted)
        Spacer(Modifier.width(12.dp))
        Text(
            ui.weightKg?.let { "Вес ${Fmt.kg(it)} кг" } ?: "Вес не введён",
            Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
        )
        trend?.let { Text("тренд ${Fmt.kg(it)}", style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted) }
    }
}

/**
 * Fiber against the daily floor (user request 2026-11-16). A floor, not a limit: the bar fills up to the target
 * and turns green once it is reached. «≥» when some entries have no fiber value.
 */
@Composable
private fun FiberBar(ui: TodayUi) {
    val t = ui.fiberTarget
    val a = ui.eaten
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Клетчатка", style = MaterialTheme.typography.bodySmall)
        Text(
            Fmt.fiber(a) + (t?.let { " / ${Fmt.kcal(it)}" } ?: "") + " г",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (t != null && t > 0) {
        val reached = a.fiber >= t
        LinearProgressIndicator(
            progress = { (a.fiber / t).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = if (reached) GTheme.extra.success else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {},
        )
    }
}

private fun entriesWord(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "записи"
    else -> "записей"
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
    val h = if (ui.incomplete) Headline("Не считаем", "", GTheme.extra.textMuted) else headline(ui, m)
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
