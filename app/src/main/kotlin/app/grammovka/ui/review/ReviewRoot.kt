package app.grammovka.ui.review

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.grammovka.di.AppContainer
import app.grammovka.ui.theme.GTheme

/** Review screen wired to the container: takes the pending request and adds the Claude actions. */
@Composable
fun ReviewRoot(container: AppContainer, onBack: () -> Unit) {
    val vm: ReviewViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val req = container.reviewInbox.pending
                container.reviewInbox.pending = null
                ReviewViewModel(req, container.foodRepository, container.logRepository, container.logEvents, container.aiService, container.pendingRepository)
            }
        },
    )
    ReviewScreen(
        vm, onBack,
        extraActions = { item ->
            if (vm.hasKey) {
                AssistChip(onClick = { vm.resolveWithAi(item) }, enabled = !vm.aiRunning, label = { Text("Найти через Claude") })
                AssistChip(onClick = { vm.defer(item) }, label = { Text("Отложить до сети") })
            }
        },
        status = { AiStatus(vm) },
    )
}

@Composable
private fun AiStatus(vm: ReviewViewModel) {
    val status = vm.aiStatus
    val noKeyHint = !vm.hasKey && vm.items.any { it is ReviewItem.Unresolved }
    if (status == null && !noKeyHint) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (vm.aiRunning) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
        }
        Text(
            status ?: "Без ключа Anthropic новые продукты вводятся вручную. Ключ добавляется в «Настройки → Ключи API».",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (vm.aiRunning || noKeyHint) GTheme.extra.textMuted else MaterialTheme.colorScheme.error,
        )
        if (vm.aiRunning) OutlinedButton(onClick = vm::stopAi, shape = MaterialTheme.shapes.small) { Text("Остановить") }
    }
    // Typically a network error: offer to keep the unrecognized lines until the network is back.
    if (!vm.aiRunning && status != null && vm.hasKey && vm.items.any { it is ReviewItem.Unresolved }) {
        OutlinedButton(
            onClick = vm::deferAllUnresolved,
            modifier = Modifier.padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.small,
        ) { Text("Отложить нераспознанное до сети") }
    }
    if (vm.deferred > 0) {
        Text(
            "Отложено: ${vm.deferred}. Когда появится сеть, придёт уведомление, и Claude разберёт отложенное на этом же экране.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = GTheme.extra.textMuted,
        )
    }
}
