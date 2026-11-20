package app.grammovka.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.grammovka.data.ai.AiProvider
import app.grammovka.data.ai.QwenEndpoint
import app.grammovka.data.keys.KeyCheck
import app.grammovka.data.settings.Secret
import app.grammovka.di.AppContainer
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import app.grammovka.ui.common.input
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

@Composable
fun KeysScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: KeysViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                KeysViewModel(
                    container.secretStore,
                    mapOf(
                        Secret.ANTHROPIC_KEY to container.anthropicKeyVerifier,
                        Secret.QWEN_KEY to container.qwenKeyVerifier,
                        Secret.USDA_KEY to container.usdaKeyVerifier,
                    ),
                )
            }
        },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(app.grammovka.data.settings.AppSettings())
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(GIcons.Back, contentDescription = "Назад") }
            Text("Ключи API и ИИ", style = MaterialTheme.typography.headlineSmall)
        }
        ProviderPicker(settings.aiProvider) { p -> scope.launch { container.settingsStore.setAiProvider(p) } }
        when (settings.aiProvider) {
            AiProvider.ANTHROPIC -> KeyCard(
                vm, Secret.ANTHROPIC_KEY, ui.getValue(Secret.ANTHROPIC_KEY),
                title = "Anthropic (Claude)",
                hint = "Нужен для разбора текста, поиска продуктов и фото этикеток. Без ключа ручной ввод и каталог работают.",
            )
            AiProvider.QWEN -> {
                QwenEndpointCard(settings.qwenBaseUrl) { url -> scope.launch { container.settingsStore.setQwenBaseUrl(url) } }
                KeyCard(
                    vm, Secret.QWEN_KEY, ui.getValue(Secret.QWEN_KEY),
                    title = "Alibaba Cloud (Qwen)",
                    hint = "Ключ из Model Studio, регион Singapore (вкладка API Key). Нужен для разбора текста, поиска продуктов и фото. " +
                        "Без ключа ручной ввод и каталог работают.",
                )
            }
        }
        KeyCard(
            vm, Secret.USDA_KEY, ui.getValue(Secret.USDA_KEY),
            title = "USDA FoodData Central",
            hint = "Ключ api.data.gov, бесплатный. Без него поиск в USDA идёт через общий DEMO_KEY: 30 запросов в час.",
        )
        ClaudeSettings(container)
        Text(
            "Ключи хранятся на телефоне в зашифрованном виде и не попадают в резервную копию.",
            Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = GTheme.extra.textMuted,
        )
    }
}

@Composable
private fun KeyCard(vm: KeysViewModel, secret: Secret, state: KeyUi, title: String, hint: String) {
    val isSet by vm.isSet.getValue(secret).collectAsStateWithLifecycle()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(
                if (isSet) "сохранён" else "не задан",
                style = MaterialTheme.typography.labelMedium,
                color = if (isSet) GTheme.extra.accentText else GTheme.extra.textMuted,
            )
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted)
        OutlinedTextField(
            value = state.draft,
            onValueChange = { vm.onDraft(secret, it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (isSet) "Заменить ключ" else "Ключ") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { vm.save(secret) }, enabled = state.draft.isNotBlank(), shape = MaterialTheme.shapes.small) {
                Text("Сохранить")
            }
            if (state.checking) {
                OutlinedButton(onClick = { vm.stop(secret) }, shape = MaterialTheme.shapes.small) { Text("Остановить") }
            } else {
                OutlinedButton(onClick = { vm.check(secret) }, enabled = isSet, shape = MaterialTheme.shapes.small) {
                    Text("Проверить ключ")
                }
            }
            if (isSet) {
                TextButton(onClick = { vm.delete(secret) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (state.checking) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Проверяю…", style = MaterialTheme.typography.bodySmall)
            }
        }
        state.result?.let { ResultLine(it) }
    }
}

@Composable
private fun ResultLine(r: KeyCheck) {
    val (ok, text) = describe(r)
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (ok) GIcons.Check else GIcons.Warning, null, Modifier.size(18.dp),
            tint = if (ok) GTheme.extra.success else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok) Color.Unspecified else MaterialTheme.colorScheme.error)
    }
}

/** Errors say what happened and what to do, without apologies. */
internal fun describe(r: KeyCheck): Pair<Boolean, String> = when (r) {
    KeyCheck.Ok -> true to "Ключ работает."
    KeyCheck.Invalid -> false to "Сервис не принял ключ. Проверьте, что он скопирован целиком, и сохраните заново."
    KeyCheck.NoPermission -> false to "Ключ действует, но у него нет доступа. Проверьте права ключа в консоли."
    KeyCheck.RateLimited -> false to "Сервис ограничил частоту запросов или исчерпан лимит расходов. Повторите через минуту."
    is KeyCheck.ServiceDown -> false to "Сервис временно недоступен (HTTP ${r.code}). Повторите позже."
    is KeyCheck.Unexpected -> false to "Сервис ответил неожиданно (HTTP ${r.code}). Повторите позже."
    is KeyCheck.Network -> false to "Нет связи с сервисом. Проверьте интернет и повторите."
}


/** Which service answers AI requests. Each keeps its own key and models; switching does not delete anything. */
@Composable
private fun ProviderPicker(current: AiProvider, onPick: (AiProvider) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("ИИ", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { p ->
                androidx.compose.material3.FilterChip(
                    selected = current == p, onClick = { onPick(p) },
                    label = { Text("${p.title} · ${p.company}") },
                )
            }
        }
        Text(
            "Qwen — для стран, где Claude недоступен. Ключи и модели у каждого свои, переключение ничего не удаляет.",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
    }
}

/** Qwen workspace: a workspace key works only on its own endpoint. */
@Composable
private fun QwenEndpointCard(baseUrl: String, onSave: (String) -> Unit) {
    var draft by androidx.compose.runtime.remember(baseUrl) {
        androidx.compose.runtime.mutableStateOf(QwenEndpoint.workspaceOf(baseUrl) ?: if (baseUrl == QwenEndpoint.SHARED) "" else baseUrl)
    }
    val normalized = QwenEndpoint.normalize(draft)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Рабочее пространство Qwen", style = MaterialTheme.typography.titleMedium)
        Text(
            "Если ключ начинается с sk-ws-, укажите ID пространства (ws-…) или Base URL со страницы API Key. Для обычного ключа sk- оставьте пустым.",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it.trim() }, singleLine = true, modifier = Modifier.weight(1f),
                label = { Text("ws-… или адрес") }, isError = normalized == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            OutlinedButton(onClick = { normalized?.let(onSave) }, enabled = normalized != null && normalized != baseUrl, shape = MaterialTheme.shapes.small) {
                Text("Сохранить")
            }
        }
        Text(
            if (normalized == null) "Это не похоже на ID пространства или адрес." else "Адрес: $baseUrl",
            style = MaterialTheme.typography.bodySmall,
            color = if (normalized == null) MaterialTheme.colorScheme.error else GTheme.extra.textMuted,
        )
    }
}

/** Monthly AI limit, spend so far (from ai_call_log) and model per purpose of the active provider. */
@Composable
private fun ClaudeSettings(container: AppContainer) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(app.grammovka.data.settings.AppSettings())
    val spent by container.aiCostTracker.observeMonthCost().collectAsStateWithLifecycle(0.0)
    var limit by androidx.compose.runtime.remember(settings.aiMonthlyLimitUsd) {
        androidx.compose.runtime.mutableStateOf(settings.aiMonthlyLimitUsd.input())
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("${settings.aiProvider.title}: расход и модели", style = MaterialTheme.typography.titleMedium)
        val over = spent >= settings.aiMonthlyLimitUsd
        Text(
            "В этом месяце: $" + String.format(java.util.Locale.ROOT, "%.2f", spent) + " из $" + settings.aiMonthlyLimitUsd.input() +
                if (over) ". Лимит исчерпан: AI-функции выключены до конца месяца." else ".",
            style = MaterialTheme.typography.bodyMedium,
            color = if (over) MaterialTheme.colorScheme.error else Color.Unspecified,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            app.grammovka.ui.common.NumberField(limit, { limit = it }, "Лимит в месяц", Modifier.weight(1f), suffix = "$")
            OutlinedButton(
                onClick = { limit.replace(',', '.').toDoubleOrNull()?.let { v -> scope.launch { container.settingsStore.setAiMonthlyLimit(v) } } },
                enabled = limit.replace(',', '.').toDoubleOrNull()?.let { it >= 0 && it != settings.aiMonthlyLimitUsd } == true,
                shape = MaterialTheme.shapes.small,
            ) { Text("Сохранить") }
        }
        Text(
            "Цены — в конфиге AiPricing: Claude сверены 2026-11-05, Qwen 2026-11-20. Расход общий для обоих провайдеров.",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
        listOf(
            app.grammovka.domain.model.AiPurpose.PARSE to "Разбор текста",
            app.grammovka.domain.model.AiPurpose.RESOLVE to "Поиск продукта",
            app.grammovka.domain.model.AiPurpose.LABEL to "Фото этикетки",
            app.grammovka.domain.model.AiPurpose.MEAL_PHOTO to "Фото блюда",
            app.grammovka.domain.model.AiPurpose.ACTIVITY to "Тренировки",
            app.grammovka.domain.model.AiPurpose.ANALYSIS to "Разбор недели",
        ).forEach { (p, label) ->
            var model by androidx.compose.runtime.remember(settings.models[p]) { androidx.compose.runtime.mutableStateOf(settings.models[p].orEmpty()) }
            OutlinedTextField(
                value = model, onValueChange = { model = it.trim() }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("Модель: $label") },
                trailingIcon = if (model != settings.models[p] && model.isNotBlank()) ({
                    IconButton(onClick = { scope.launch { container.settingsStore.setModel(p, model) } }) { Icon(GIcons.Check, "Сохранить модель") }
                }) else null,
            )
        }
    }
}
