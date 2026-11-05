package app.grammovka.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.grammovka.data.settings.AppSettings
import app.grammovka.data.settings.ReminderSettings
import app.grammovka.di.AppContainer
import app.grammovka.notify.Notifications
import app.grammovka.notify.Reminder
import app.grammovka.ui.icons.GIcons
import app.grammovka.ui.theme.GTheme
import kotlinx.coroutines.launch

/** Morning question «вчера всё записано?»: on/off and time. No weigh-in reminders (user's choice). */
@Composable
fun ReminderSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settingsStore.settings.collectAsStateWithLifecycle(AppSettings())
    val r = settings.reminder

    fun save(next: ReminderSettings) {
        scope.launch {
            container.settingsStore.setReminder(next.copy(offered = true))
            Reminder.schedule(context, next)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        save(r.copy(enabled = granted))
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(GIcons.Bell, null, tint = GTheme.extra.textMuted)
            Text("Утренний вопрос", Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleMedium)
            Switch(checked = r.enabled, onCheckedChange = { on ->
                if (on && Build.VERSION.SDK_INT >= 33 && !Notifications.allowed(context)) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    save(r.copy(enabled = on))
                }
            })
        }
        Text(
            "«Вчера всё записано?» с кнопками «да» и «нет — неполный». Спрашивает, только если вчера были записи.",
            style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
        if (r.enabled) {
            val time = "%d:%02d".format(r.minuteOfDay / 60, r.minuteOfDay % 60)
            TextButton(onClick = {
                TimePickerDialog(context, { _, h, m -> save(r.copy(minuteOfDay = h * 60 + m)) }, r.minuteOfDay / 60, r.minuteOfDay % 60, true).show()
            }) { Text("Время: $time", color = GTheme.extra.accentText) }
            if (!Notifications.allowed(context)) {
                Text("Уведомления запрещены в настройках Android.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
