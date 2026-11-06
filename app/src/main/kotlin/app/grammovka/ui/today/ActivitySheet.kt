package app.grammovka.ui.today

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import app.grammovka.data.ai.ActivityProposal
import app.grammovka.data.ai.ClaudeException
import app.grammovka.data.ai.userMessage
import app.grammovka.data.db.ActivityEntryEntity
import app.grammovka.domain.activity.ActivityEstimate
import app.grammovka.domain.activity.ActivityInput
import app.grammovka.domain.activity.ActivityMath
import app.grammovka.domain.activity.ActivitySource
import app.grammovka.domain.activity.ActivityType
import app.grammovka.domain.activity.Body
import app.grammovka.domain.activity.EstimateMethod
import app.grammovka.domain.activity.Intensity
import app.grammovka.domain.model.Confidence
import app.grammovka.domain.model.Fmt
import app.grammovka.ui.common.NumberField
import app.grammovka.ui.common.input
import app.grammovka.ui.common.label
import app.grammovka.ui.common.toDecimal
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Which estimate the user keeps for a workout. */
enum class KcalChoice { FORMULA, CLAUDE, MANUAL }

/** Editable state of one workout on the confirm sheet. All kcal shown here are net (above rest). */
class ActivityDraft(
    val id: Long = 0,
    type: ActivityType = ActivityType.BIKE,
    title: String = "",
    minutes: String = "",
    distance: String = "",
    hr: String = "",
    intensity: Intensity? = null,
    /** Claude's total kcal for the workout; net is computed by the app from the current minutes. */
    claudeGross: Double? = null,
    claudeConfidence: Confidence? = null,
    claudeReasoning: String = "",
    choice: KcalChoice = KcalChoice.FORMULA,
    manual: String = "",
    val description: String = "",
) {
    var type by mutableStateOf(type)
    var title by mutableStateOf(title)
    var minutes by mutableStateOf(minutes)
    var distance by mutableStateOf(distance)
    var hr by mutableStateOf(hr)
    var intensity by mutableStateOf(intensity)
    var claudeGross by mutableStateOf(claudeGross)
    var claudeConfidence by mutableStateOf(claudeConfidence)
    var claudeReasoning by mutableStateOf(claudeReasoning)
    var choice by mutableStateOf(choice)
    var manual by mutableStateOf(manual)

    fun input(): ActivityInput? = minutes.toDecimal()?.takeIf { it > 0 }?.let {
        ActivityInput(type, it, distance.toDecimal()?.takeIf { d -> d > 0 }, hr.toDecimal()?.toInt(), intensity)
    }

    fun formula(body: Body?): ActivityEstimate? = body?.let { b -> input()?.let { ActivityMath.estimate(it, b) } }

    fun claudeNet(body: Body?): Double? {
        val g = claudeGross ?: return null
        val m = minutes.toDecimal() ?: return null
        // Without a profile there is no BMR: take rest out at ~1 kcal/kg/h for 75 kg (rough, shown as Claude's anyway).
        return ActivityMath.net(g, m, body?.bmrKcal ?: (75.0 * 24))
    }

    fun chosenKcal(body: Body?): Double? = when (choice) {
        KcalChoice.FORMULA -> formula(body)?.netKcal
        KcalChoice.CLAUDE -> claudeNet(body)
        KcalChoice.MANUAL -> manual.toDecimal()
    }

    fun source(): ActivitySource = when (choice) {
        KcalChoice.FORMULA -> ActivitySource.FORMULA
        KcalChoice.CLAUDE -> ActivitySource.CLAUDE_ESTIMATE
        KcalChoice.MANUAL -> ActivitySource.MANUAL
    }

    fun toEntity(base: ActivityEntryEntity?, body: Body?, date: java.time.LocalDate, time: java.time.LocalTime): ActivityEntryEntity? {
        val inp = input() ?: return null
        val kcal = chosenKcal(body) ?: return null
        return ActivityEntryEntity(
            id = base?.id ?: 0, date = base?.date ?: date, time = base?.time ?: time, type = inp.type, minutes = inp.minutes,
            distanceKm = inp.distanceKm, avgHr = inp.avgHr, intensity = inp.intensity,
            description = description.ifBlank { base?.description.orEmpty() }, kcal = kcal, source = source(),
            formulaKcal = formula(body)?.netKcal, claudeKcal = claudeNet(body), note = title.takeIf { it.isNotBlank() && it != inp.type.ru },
            createdAt = base?.createdAt ?: 0,
        )
    }

    companion object {
        fun from(p: ActivityProposal, text: String, id: Long = 0) = ActivityDraft(
            id = id, type = p.type, title = p.title, minutes = p.minutes?.input().orEmpty(), distance = p.distanceKm?.input().orEmpty(),
            hr = p.avgHr?.toString().orEmpty(), intensity = p.intensity, claudeGross = p.grossKcal, claudeConfidence = p.confidence,
            claudeReasoning = p.reasoning, choice = if (p.grossKcal != null) KcalChoice.CLAUDE else KcalChoice.FORMULA, description = text,
        )

        fun from(e: ActivityEntryEntity) = ActivityDraft(
            id = e.id, type = e.type, title = e.note.orEmpty(), minutes = e.minutes.input(), distance = e.distanceKm?.input().orEmpty(),
            hr = e.avgHr?.toString().orEmpty(), intensity = e.intensity,
            choice = if (e.source == ActivitySource.FORMULA) KcalChoice.FORMULA else KcalChoice.MANUAL,
            manual = if (e.source == ActivitySource.FORMULA) "" else Fmt.kcal(e.kcal), description = e.description,
        )
    }
}

/**
 * Confirm sheet for workouts (invariant 2): Claude only pre-fills fields and offers its estimate next to the
 * formula; the user sees both, picks one or types kcal, and every field stays editable.
 */
@Composable
fun ActivitySheet(
    editing: ActivityEntryEntity?,
    body: Body?,
    initialText: String?,
    estimate: suspend (String) -> List<ActivityProposal>,
    onDismiss: () -> Unit,
    onSave: (List<ActivityDraft>) -> Unit,
    onDelete: (ActivityEntryEntity) -> Unit,
) {
    val drafts = remember { mutableStateListOf<ActivityDraft>().apply { add(editing?.let(ActivityDraft::from) ?: ActivityDraft()) } }
    var text by remember { mutableStateOf(initialText.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val voice = rememberVoiceController()
    val context = LocalContext.current

    val run: (String) -> Unit = { t ->
        if (t.isNotBlank()) {
            job?.cancel()
            status = null
            job = scope.launch {
                try {
                    val got = estimate(t)
                    if (got.isEmpty()) {
                        status = "В тексте не нашлось тренировки. Заполните поля вручную."
                    } else {
                        val keepId = drafts.firstOrNull()?.id ?: 0
                        drafts.clear()
                        // Editing keeps one record: only the first workout of the text replaces it.
                        val list = if (editing != null) got.take(1) else got
                        list.forEachIndexed { i, p -> drafts += ActivityDraft.from(p, t, id = if (i == 0) keepId else 0) }
                        if (got.any { it.minutes == null }) status = "Укажите длительность: без неё расход не посчитать."
                    }
                } catch (e: ClaudeException) {
                    status = e.userMessage()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    status = "Не удалось разобрать ответ Claude. Заполните поля вручную."
                } finally {
                    job = null
                }
            }
        }
    }
    LaunchedEffect(initialText) { initialText?.let(run) }

    val listen = {
        voice.start(onFinal = { spoken -> text = spoken; run(spoken) }, onError = { status = it })
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else status = "Без доступа к микрофону голосовой ввод не работает."
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(GIcons.Close, "Закрыть") }
                    Text(if (editing != null) "Тренировка" else "Новая тренировка", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (editing != null) IconButton(onClick = { onDelete(editing) }) { Icon(GIcons.Trash, "Удалить тренировку") }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = if (voice.listening) voice.partial else text, onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(), enabled = !voice.listening,
                        label = { Text("Опишите тренировку") },
                        placeholder = { Text("велик 100 км за 4 часа, пульс 135, ветер") },
                        minLines = 2, maxLines = 5,
                        trailingIcon = {
                            IconButton(onClick = {
                                when {
                                    voice.listening -> voice.stop()
                                    !voice.available -> status = "На телефоне нет службы распознавания речи."
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
                                    else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }) {
                                Icon(
                                    GIcons.Mic, if (voice.listening) "Остановить запись" else "Голосовой ввод",
                                    tint = if (voice.listening) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current,
                                )
                            }
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { run(text) }, enabled = text.isNotBlank() && job == null && !voice.listening, shape = MaterialTheme.shapes.small) {
                            Text("Разобрать и оценить через Claude")
                        }
                        if (job != null) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            TextButton(onClick = { job?.cancel() }) { Text("Остановить") }
                        }
                    }
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GTheme.extra.accentText) }
                    Text(
                        "Считаем только расход сверх покоя: покой уже входит в норму дня. Без Claude поля заполняются вручную, расчёт по формуле.",
                        style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
                    )
                    if (body == null) {
                        Text(
                            "Для формулы нужны профиль и вес. Пока можно взять оценку Claude или ввести ккал вручную.",
                            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.accentText,
                        )
                    }
                    drafts.forEachIndexed { i, d ->
                        DraftCard(d, body, removable = drafts.size > 1 && editing == null, onRemove = { drafts.removeAt(i) })
                    }
                    if (editing == null) {
                        TextButton(onClick = { drafts += ActivityDraft() }) {
                            Icon(GIcons.Plus, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("Ещё тренировка")
                        }
                    }
                }
                HorizontalDivider()
                val total = drafts.map { it.chosenKcal(body) }
                val ready = total.all { it != null } && drafts.all { it.input() != null }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(drafts.toList()) }, enabled = ready && job == null, shape = MaterialTheme.shapes.small) {
                        Text(if (ready) "Записать · +${Fmt.kcal(total.sumOf { it ?: 0.0 })} ккал" else "Записать")
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DraftCard(d: ActivityDraft, body: Body?, removable: Boolean, onRemove: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.title.ifBlank { d.type.ru }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (removable) IconButton(onClick = onRemove) { Icon(GIcons.Close, "Убрать", Modifier.size(18.dp)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityType.entries.forEach { t ->
                FilterChip(selected = d.type == t, onClick = { d.type = t }, label = { Text(t.ru) }, shape = MaterialTheme.shapes.small)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(d.minutes, { d.minutes = it }, "Минуты", Modifier.weight(1f), isError = d.input() == null)
            if (d.type != ActivityType.STRENGTH) NumberField(d.distance, { d.distance = it }, "Км", Modifier.weight(1f))
            NumberField(d.hr, { d.hr = it }, "Пульс", Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Intensity.entries.forEach { i ->
                FilterChip(selected = d.intensity == i, onClick = { d.intensity = if (d.intensity == i) null else i }, label = { Text(i.ru) }, shape = MaterialTheme.shapes.small)
            }
        }
        val f = d.formula(body)
        ChoiceRow(
            selected = d.choice == KcalChoice.FORMULA, enabled = f != null, onClick = { d.choice = KcalChoice.FORMULA },
            title = f?.let { "По формуле: ${Fmt.kcal(it.netKcal)} ккал" } ?: "По формуле: нужны минуты" + if (body == null) " и профиль" else "",
            subtitle = f?.let { describe(it, d) },
        )
        val c = d.claudeNet(body)
        if (d.claudeGross != null) {
            ChoiceRow(
                selected = d.choice == KcalChoice.CLAUDE, enabled = c != null, onClick = { d.choice = KcalChoice.CLAUDE },
                title = c?.let { "Claude: ${Fmt.kcal(it)} ккал" } ?: "Claude: нужны минуты",
                subtitle = listOfNotNull(
                    "оценка Claude" + (d.claudeConfidence?.let { ", уверенность ${it.label()}" } ?: ""),
                    d.claudeReasoning.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                accent = true,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = d.choice == KcalChoice.MANUAL, onClick = { d.choice = KcalChoice.MANUAL })
            NumberField(
                d.manual, { d.manual = it; d.choice = KcalChoice.MANUAL }, "Своё значение", Modifier.weight(1f), suffix = "ккал",
            )
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, enabled: Boolean, onClick: () -> Unit, title: String, subtitle: String?, accent: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (accent) GTheme.extra.accentText else MaterialTheme.colorScheme.onSurface)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted) }
        }
    }
}

private fun describe(e: ActivityEstimate, d: ActivityDraft): String {
    val speed = d.input()?.let(ActivityMath::speedKmh)?.let { " · ${Fmt.kcal(it)} км/ч" }.orEmpty()
    return when (e.method) {
        EstimateMethod.HEART_RATE -> "по пульсу (Keytel)$speed"
        EstimateMethod.MET -> "MET ${e.met?.let { "%.1f".format(it).replace('.', ',') }}$speed"
    } + " · всего ${Fmt.kcal(e.grossKcal)}, сверх покоя ${Fmt.kcal(e.netKcal)}"
}

/** One line in the day's workout card. */
fun ActivityEntryEntity.summary(): String = buildList {
    add("${Fmt.kcal(minutes)} мин")
    distanceKm?.let { add("${it.input()} км") }
    avgHr?.let { add("пульс $it") }
    intensity?.let { add(it.ru) }
}.joinToString(" · ")
