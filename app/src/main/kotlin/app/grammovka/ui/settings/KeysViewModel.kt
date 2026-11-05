package app.grammovka.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.grammovka.data.keys.KeyCheck
import app.grammovka.data.keys.KeyVerifier
import app.grammovka.data.settings.Secret
import app.grammovka.data.settings.SecretStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Per-key UI state. The stored key itself is never exposed to the UI. */
data class KeyUi(
    val draft: String = "",
    val checking: Boolean = false,
    val result: KeyCheck? = null,
)

class KeysViewModel(
    private val secrets: SecretStore,
    private val verifiers: Map<Secret, KeyVerifier>,
) : ViewModel() {

    private val _ui = MutableStateFlow(Secret.entries.associateWith { KeyUi() })
    val ui: StateFlow<Map<Secret, KeyUi>> = _ui.asStateFlow()

    val isSet: Map<Secret, StateFlow<Boolean>> = Secret.entries.associateWith {
        secrets.isSet(it).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }

    private val jobs = mutableMapOf<Secret, Job>()

    fun onDraft(s: Secret, text: String) = edit(s) { it.copy(draft = text, result = null) }

    /** Saves the draft, then verifies it right away so the user sees one combined result. */
    fun save(s: Secret) {
        val draft = _ui.value.getValue(s).draft.trim()
        if (draft.isEmpty()) return
        viewModelScope.launch {
            secrets.put(s, draft)
            edit(s) { it.copy(draft = "") }
            check(s)
        }
    }

    fun check(s: Secret) {
        jobs[s]?.cancel()
        jobs[s] = viewModelScope.launch {
            val key = secrets.get(s)
            if (key == null) {
                edit(s) { it.copy(result = null) }
                return@launch
            }
            edit(s) { it.copy(checking = true, result = null) }
            val r = verifiers.getValue(s).verify(key)
            edit(s) { it.copy(checking = false, result = r) }
        }
    }

    fun stop(s: Secret) {
        jobs[s]?.cancel()
        edit(s) { it.copy(checking = false) }
    }

    fun delete(s: Secret) {
        jobs[s]?.cancel()
        viewModelScope.launch {
            secrets.clear(s)
            edit(s) { KeyUi() }
        }
    }

    private fun edit(s: Secret, f: (KeyUi) -> KeyUi) = _ui.update { m -> m + (s to f(m.getValue(s))) }
}
