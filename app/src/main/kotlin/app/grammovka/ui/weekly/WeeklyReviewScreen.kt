package app.grammovka.ui.weekly

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.grammovka.data.ai.ClaudeErrorKind
import app.grammovka.data.ai.ClaudeException
import app.grammovka.data.ai.userMessage
import app.grammovka.data.repo.WeekView
import app.grammovka.data.repo.WeeklyReport
import app.grammovka.di.AppContainer
import app.grammovka.domain.log.DayLedger
import app.grammovka.domain.model.Fmt
import app.grammovka.domain.review.DigestDay
import app.grammovka.domain.review.DigestStatus
import app.grammovka.ui.common.SectionLabel
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import app.grammovka.ui.today.balanceWords
import app.grammovka.ui.today.signedKcal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ru = Locale.forLanguageTag("ru")
private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", ru)
private val stamp = DateTimeFormatter.ofPattern("d MMMM, H:mm", ru)
private val weekdays = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/** «2–8 ноября», «26 октября – 1 ноября». */
fun weekTitle(monday: LocalDate): String {
    val sunday = monday.plusDays(6)
    return if (monday.month == sunday.month) "${monday.dayOfMonth}–${dayMonth.format(sunday)}" else "${dayMonth.format(monday)} – ${dayMonth.format(sunday)}"
}

/**
 * Weekly review (spec, phase 3). The week's numbers come from the app; Claude writes the prose, streamed onto
 * the screen, and only when the user is here: [auto] starts it for a notification tap, otherwise a button does.
 */
class WeeklyReviewViewModel(private val c: AppContainer, initial: LocalDate?, auto: Boolean) : ViewModel() {
    private val thisWeek = DayLedger.weekStart(LocalDate.now())
    private val monday = MutableStateFlow(initial?.takeIf { !it.isAfter(thisWeek) }?.let(DayLedger::weekStart) ?: thisWeek)
    private var autoPending = auto
    private var job: Job? = null

    var view by mutableStateOf<WeekView?>(null)
        private set
    var text by mutableStateOf("")
        private set
    var report by mutableStateOf<WeeklyReport?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasKey by mutableStateOf(true)
        private set
    var firstWeek by mutableStateOf(thisWeek)
        private set

    val canPrev: Boolean get() = view?.monday?.isAfter(firstWeek) == true
    val canNext: Boolean get() = view?.monday?.isBefore(thisWeek) == true

    init {
        viewModelScope.launch {
            hasKey = c.aiService.hasKey()
            firstWeek = c.weeklyReviewRepository.firstWeek() ?: thisWeek
            combine(monday, c.modelHub.state, c.settingsStore.settings.map { it.profile }.distinctUntilChanged(), ::Triple)
                .collectLatest { (m, model, profile) ->
                    val v = c.weeklyReviewRepository.week(m, LocalDate.now(), model, profile)
                    val changed = view?.monday != m
                    view = v
                    if (changed) showSaved(m)
                    // From the notification: write the review at once, when the model's numbers are in.
                    if (autoPending && hasKey && model != null && v.hasData && report == null && !running) {
                        autoPending = false
                        generate()
                    }
                }
        }
    }

    private suspend fun showSaved(m: LocalDate) {
        report = c.weeklyReviewRepository.saved(m)
        text = report?.text.orEmpty()
    }

    fun shift(weeks: Long) {
        val next = monday.value.plusWeeks(weeks)
        if (next.isBefore(firstWeek) || next.isAfter(thisWeek)) return
        job?.cancel()
        autoPending = false
        error = null
        monday.value = next
    }

    fun generate() {
        val v = view ?: return
        if (running) return
        running = true
        error = null
        text = ""
        job = viewModelScope.launch {
            try {
                val sb = StringBuilder()
                c.aiService.weeklyReview(v.json).collect { piece ->
                    sb.append(piece)
                    if (view?.monday == v.monday) text = sb.toString()
                }
                if (sb.isNotBlank()) {
                    val model = c.settingsStore.current().models.getValue(app.grammovka.domain.model.AiPurpose.ANALYSIS)
                    val r = WeeklyReport(v.monday.toString(), System.currentTimeMillis(), model, sb.toString().trim())
                    c.weeklyReviewRepository.save(r)
                    if (view?.monday == v.monday) report = r
                }
            } catch (e: ClaudeException) {
                error = message(e)
                view?.monday?.let { showSaved(it) }
            } catch (e: CancellationException) {
                // «Остановить» or another week: an unfinished text is not kept, the saved one comes back.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { view?.monday?.let { showSaved(it) } }
                throw e
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** The shared messages talk about entering a food by hand; here the way out is different. */
    private fun message(e: ClaudeException): String = when (e.kind) {
        ClaudeErrorKind.NO_KEY -> "Нет ключа Anthropic. Добавьте его в «Настройки → Ключи API»."
        ClaudeErrorKind.OFFLINE -> "Нет связи с Claude. Проверьте интернет и запустите разбор ещё раз."
        ClaudeErrorKind.TIMEOUT -> "Claude не ответил вовремя. Запустите разбор ещё раз."
        else -> e.userMessage()
    }
}

@Composable
fun WeeklyReviewScreen(container: AppContainer, week: LocalDate?, auto: Boolean, onBack: () -> Unit, onOpenKeys: () -> Unit) {
    val vm: WeeklyReviewViewModel = viewModel(factory = viewModelFactory { initializer { WeeklyReviewViewModel(container, week, auto) } })
    val v = vm.view

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(GIcons.Back, contentDescription = "Назад") }
            Text("Разбор недели", style = MaterialTheme.typography.headlineSmall)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.shift(-1) }, enabled = vm.canPrev) { Icon(GIcons.Prev, "Предыдущая неделя") }
            Text(v?.let { weekTitle(it.monday) }.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            IconButton(onClick = { vm.shift(1) }, enabled = vm.canNext) { Icon(GIcons.Next, "Следующая неделя") }
        }
        if (v == null) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (!v.hasData) {
                Text(
                    "За эту неделю записей нет: разбирать нечего.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                )
                return@Column
            }
            Numbers(v)
            SectionLabel("Разбор Claude", Modifier.padding(top = 8.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.text.isNotBlank()) ReportText(vm.text)
                vm.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                when {
                    vm.running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (vm.text.isBlank()) "Claude читает неделю…" else "Claude пишет…",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                        )
                        OutlinedButton(onClick = vm::stop, shape = MaterialTheme.shapes.small) { Text("Остановить") }
                    }
                    !vm.hasKey -> {
                        Text(
                            "Разбор пишет Claude, для него нужен ключ Anthropic. Цифры недели выше считаются и без ключа.",
                            style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
                        )
                        OutlinedButton(onClick = onOpenKeys, shape = MaterialTheme.shapes.small) { Text("Открыть ключи API") }
                    }
                    vm.report == null -> {
                        val today = v.days.lastOrNull()?.takeIf { it.status == DigestStatus.IN_PROGRESS }
                        if (today != null) {
                            Text(
                                "Сегодняшний день ещё открыт: в средние он не входит. Чтобы разбор учёл его целиком, сначала запишите и закройте день.",
                                style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                            )
                        }
                        Button(onClick = vm::generate, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("Разобрать неделю") }
                    }
                    else -> {
                        val r = vm.report!!
                        Text(
                            "Написан ${stamp.format(Instant.ofEpochMilli(r.generatedAt).atZone(ZoneId.systemDefault()))} по цифрам на тот момент.",
                            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                        )
                        OutlinedButton(onClick = vm::generate, shape = MaterialTheme.shapes.small) { Text("Разобрать заново") }
                    }
                }
                Text(
                    "Claude получает только цифры недели: итоги по дням и приёмам, продукты с наибольшим вкладом, тренировки и три прошлые недели. Все суммы считает приложение.",
                    style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                )
            }
        }
    }
}

/** The week in the app's own numbers: shown with or without a key, and the same numbers Claude reads. */
@Composable
private fun Numbers(v: WeekView) {
    val t = v.totals
    SectionLabel("Цифры недели")
    if (t.countedDays == 0) {
        Text(
            "Полных дней пока нет: средние появятся, когда закончится хотя бы один день.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = GTheme.extra.textMuted,
        )
    } else {
        Fact(
            "Съедено в среднем", "${Fmt.kcal(t.avgKcal!!)} ккал",
            listOfNotNull(t.avgTarget?.let { "цель ${Fmt.kcal(it)}" }, "дней в расчёте: ${t.countedDays}", t.daysOverTarget.takeIf { it > 0 }?.let { "выше цели: $it" }).joinToString(" · "),
        )
        t.sumBalance?.let { sum ->
            Fact("Итог недели", "${balanceWords(sum)} ккал", t.avgBalance?.let { "в среднем за день: ${balanceWords(it)}" })
        }
        Fact(
            "Белок в среднем", "${Fmt.kcal(t.avgProtein!!)} г",
            t.avgProteinTarget?.let { "цель ${Fmt.kcal(it)} · выполнена в ${t.daysProteinMet} из ${t.countedDays} дн." },
        )
    }
    t.trendChange?.let { ch ->
        Fact(
            "Тренд веса", (if (ch > 0.005) "+" else if (ch < -0.005) "−" else "") + "%.2f".format(kotlin.math.abs(ch)).replace('.', ',') + " кг",
            "${Fmt.kg(t.trendStart!!)} → ${Fmt.kg(t.trendEnd!!)} кг · взвешиваний: ${t.weighIns}",
        )
    }
    if (t.workoutKcal > 0) Fact("Тренировки", "${Fmt.kcal(t.workoutKcal)} ккал", "записано: ${v.workouts.size}")

    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 4.dp)) {
        HeadCell("День", 1.1f, TextAlign.Start); HeadCell("Съедено", 1f); HeadCell("Цель", 1f); HeadCell("Итог", 1f)
    }
    v.days.forEach { d -> DayLine(d) }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeadCell(text: String, weight: Float, align: TextAlign = TextAlign.End) {
    Text(text, Modifier.weight(weight), style = MaterialTheme.typography.labelMedium, color = GTheme.extra.textMuted, textAlign = align)
}

@Composable
private fun DayLine(d: DigestDay) {
    val muted = GTheme.extra.textMuted
    val dim = d.status != DigestStatus.COUNTED
    val color = if (dim) muted else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.1f)) {
            Text("${weekdays[d.date.dayOfWeek.value - 1]} ${d.date.dayOfMonth}", style = MaterialTheme.typography.bodyMedium, color = color)
            val mark = when (d.status) {
                DigestStatus.IN_PROGRESS -> "идёт"
                DigestStatus.INCOMPLETE -> "не полностью"
                DigestStatus.EMPTY -> "нет записей"
                DigestStatus.COUNTED -> null
            }
            mark?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
        }
        val none = d.status == DigestStatus.EMPTY
        Text(if (none) "—" else Fmt.kcal(d.eaten.kcal), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = color, textAlign = TextAlign.End)
        Text(d.target?.let { Fmt.kcal(it + d.activity) } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = muted, textAlign = TextAlign.End)
        val b = d.balance
        val balanceColor: Color = when {
            b == null || dim -> muted
            b > 0.5 -> MaterialTheme.colorScheme.error
            else -> GTheme.extra.success
        }
        Text(b?.let(::signedKcal) ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = balanceColor, textAlign = TextAlign.End)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Fact(label: String, value: String, note: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted) }
    }
}

/** The review's plain-text format (prompt WEEKLY_V1): «## » headings, «- » list lines, paragraphs. */
@Composable
private fun ReportText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.lines().forEach { raw ->
            val line = raw.replace("**", "").trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#") -> {
                    Spacer(Modifier.height(6.dp))
                    Text(line.trimStart('#', ' '), style = MaterialTheme.typography.titleMedium, color = GTheme.extra.accentText)
                }
                line.startsWith("- ") || line.startsWith("• ") || line.startsWith("— ") -> Row {
                    Text("—", Modifier.width(20.dp), style = MaterialTheme.typography.bodyLarge, color = GTheme.extra.textMuted)
                    Text(line.drop(2).trim(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
                else -> Text(line, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
