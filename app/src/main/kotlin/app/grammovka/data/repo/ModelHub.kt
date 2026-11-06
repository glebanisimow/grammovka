package app.grammovka.data.repo

import app.grammovka.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/**
 * App-wide model state: replays the energy model whenever the log, weights, day statuses, profile
 * workouts or the calendar day change (spec: recompute daily and on edits to past days).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ModelHub(
    scope: CoroutineScope,
    settings: SettingsStore,
    logs: LogRepository,
    activities: ActivityRepository,
    weights: WeightRepository,
    private val model: ModelRepository,
) {
    /** Bumped on app resume so a new calendar day triggers a replay. */
    private val today = MutableStateFlow(LocalDate.now())

    fun refreshDate() {
        today.value = LocalDate.now()
    }

    val state: StateFlow<ModelState?> = combine(
        settings.settings.map { it.profile }.distinctUntilChanged(),
        combine(logs.observeChanges(), activities.observeChanges()) { a, b -> a + "|" + b },
        logs.observeStatusChanges(),
        weights.observeAll(),
        today,
    ) { profile, _, _, _, day -> profile to day }
        .debounce(150)
        .mapLatest { (profile, day) -> model.recompute(profile, day) }
        .stateIn(scope, SharingStarted.Eagerly, null)
}
