package app.grammovka.update

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.withResumed
import app.grammovka.BuildConfig
import app.grammovka.di.AppContainer
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import kotlinx.coroutines.CancellationException

/** Settings section: check the public GitHub release and install a newer signed APK over this one. */
@Composable
fun AppUpdateSection(container: AppContainer) {
    val viewModel: UpdateViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                UpdateViewModel(container.updateSource, BuildConfig.VERSION_CODE.toLong(), enabled = !BuildConfig.DEBUG)
            }
        },
    )
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) viewModel.install()
        else viewModel.report(UpdateProblem.INSTALL_PERMISSION)
    }
    // A download may finish in the background; open the system installer only while this screen is resumed.
    LaunchedEffect(state.installFile) {
        val file = state.installFile ?: return@LaunchedEffect
        try {
            lifecycle.withResumed {
                viewModel.consumeInstallRequest()
                if (!context.packageManager.canRequestPackageInstalls()) {
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newRawUri("Grammovka update", uri)
                    })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            viewModel.report(UpdateProblem.INSTALL_FAILED)
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(GIcons.Download, null, tint = GTheme.extra.textMuted)
            Text("Обновление", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Установлена ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
        val m = state.manifest
        Text(
            when {
                BuildConfig.DEBUG -> "Отладочная сборка обновляется через adb."
                state.phase == UpdatePhase.CURRENT -> "Установлена последняя версия."
                state.phase == UpdatePhase.CHECKING -> "Проверяю…"
                state.phase == UpdatePhase.DOWNLOADING -> "Скачиваю: ${state.progress} %"
                state.phase == UpdatePhase.READY -> "Обновление скачано и проверено."
                state.phase == UpdatePhase.AVAILABLE && m != null ->
                    "Доступна ${m.versionName} (${m.versionCode}), ${(m.sizeBytes / 1024 / 1024).coerceAtLeast(1)} МБ. Данные сохранятся."
                else -> "Новые сборки появляются после каждого изменения в репозитории."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        state.problem?.let {
            Text(it.message(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (state.busy) {
            if (state.phase == UpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    when (state.phase) {
                        UpdatePhase.AVAILABLE -> viewModel.download()
                        UpdatePhase.READY -> viewModel.install()
                        else -> viewModel.check()
                    }
                },
                enabled = !BuildConfig.DEBUG && !state.busy,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    when (state.phase) {
                        UpdatePhase.AVAILABLE -> "Скачать и установить"
                        UpdatePhase.READY -> "Установить"
                        else -> "Проверить обновление"
                    },
                )
            }
            if (state.phase == UpdatePhase.AVAILABLE || state.phase == UpdatePhase.READY) {
                TextButton(onClick = viewModel::check) { Text("Проверить снова") }
            }
        }
    }
}

/** What happened and what to do. */
private fun UpdateProblem.message(): String = when (this) {
    UpdateProblem.NETWORK -> "Нет связи с GitHub. Проверьте интернет и повторите."
    UpdateProblem.NOT_PUBLISHED -> "Сборка ещё не опубликована. Повторите через несколько минут."
    UpdateProblem.RATE_LIMIT -> "GitHub временно ограничил запросы. Повторите через час."
    UpdateProblem.INVALID_APK -> "Файл обновления не прошёл проверку подписи или контрольной суммы и не будет установлен. Проверьте снова позже."
    UpdateProblem.INCOMPATIBLE -> "Новая версия требует более новый Android."
    UpdateProblem.STORAGE -> "Не хватает места на телефоне. Освободите 50 МБ и повторите."
    UpdateProblem.INSTALL_PERMISSION -> "Нужно разрешение «Установка неизвестных приложений» для Protoptera eat. Включите его и нажмите «Установить»."
    UpdateProblem.INSTALL_FAILED -> "Системный установщик не открылся. Нажмите «Установить» ещё раз."
}
