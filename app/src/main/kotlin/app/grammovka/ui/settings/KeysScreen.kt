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
import app.grammovka.data.keys.KeyCheck
import app.grammovka.data.settings.Secret
import app.grammovka.di.AppContainer
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme

@Composable
fun KeysScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: KeysViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                KeysViewModel(
                    container.secretStore,
                    mapOf(
                        Secret.ANTHROPIC_KEY to container.anthropicKeyVerifier,
                        Secret.USDA_KEY to container.usdaKeyVerifier,
                    ),
                )
            }
        },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(GIcons.Back, contentDescription = "Назад") }
            Text("Ключи API", style = MaterialTheme.typography.headlineSmall)
        }
        KeyCard(
            vm, Secret.ANTHROPIC_KEY, ui.getValue(Secret.ANTHROPIC_KEY),
            title = "Anthropic (Claude)",
            hint = "Нужен для разбора текста, поиска продуктов и фото этикеток. Без ключа ручной ввод и каталог работают.",
        )
        KeyCard(
            vm, Secret.USDA_KEY, ui.getValue(Secret.USDA_KEY),
            title = "USDA FoodData Central",
            hint = "Ключ api.data.gov, бесплатный. Нужен для поиска сырых продуктов в базе USDA.",
        )
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
