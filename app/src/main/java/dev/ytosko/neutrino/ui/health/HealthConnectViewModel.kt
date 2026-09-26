package dev.ytosko.neutrino.ui.health

import dev.ytosko.neutrino.data.health.HealthKind
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.health.HealthConnectAvailability
import dev.ytosko.neutrino.data.health.HealthConnectManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HealthConnectUiState(
    val availability: HealthConnectAvailability? = null,
    /** What's allowed right now, checked on every resume. */
    val allowed: Set<HealthKind> = emptySet(),
    /** The user dismissed or denied the permission sheet at least once. */
    val denied: Boolean = false,
    /** Allowed to read blood glucose (only asked for when glucose import is turned on). */
    val readsGlucose: Boolean = false,
) {
    val granted: Boolean get() = allowed.size == HealthKind.entries.size
    val missing: List<HealthKind> get() = HealthKind.entries.filter { it !in allowed }
}

class HealthConnectViewModel(
    private val manager: HealthConnectManager,
    /** Runs once access is granted, to send anything saved while disconnected. */
    private val onConnected: suspend () -> Unit = {},
    /** Brings in glucose from other apps right away, once import is on. */
    private val onGlucoseImport: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(HealthConnectUiState())
    val state: StateFlow<HealthConnectUiState> = _state.asStateFlow()

    /** Only what isn't allowed yet, so the sheet asks for just that. */
    val missingPermissions: Set<String> get() = _state.value.missing.mapTo(LinkedHashSet(), manager::permissionFor)
    fun permissionContract() = manager.permissionContract()
    fun installIntent() = manager.installIntent()
    fun settingsIntent() = manager.settingsIntent()

    /** Re-check on resume: the user may have installed Health Connect or changed permissions. */
    fun refresh() {
        viewModelScope.launch {
            val availability = manager.availability()
            val allowed = if (availability == HealthConnectAvailability.Available) manager.grantedKinds() else emptySet()
            val reads = availability == HealthConnectAvailability.Available && manager.canReadGlucose()
            _state.update { it.copy(availability = availability, allowed = allowed, readsGlucose = reads) }
            // Sends anything saved while a kind was off, e.g. glucose readings once glucose is allowed.
            if (allowed.isNotEmpty()) onConnected()
        }
    }

    val glucoseReadPermission: String get() = manager.glucoseReadPermission

    /** After asking to read glucose: turn import on (via [enable]) only if Health Connect allowed it. */
    @Suppress("UNUSED_PARAMETER")
    fun onGlucoseReadResult(granted: Set<String>, enable: suspend () -> Unit) {
        viewModelScope.launch {
            val reads = manager.canReadGlucose()
            _state.update { it.copy(readsGlucose = reads) }
            if (reads) {
                enable()
                onGlucoseImport()
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onPermissionResult(granted: Set<String>) {
        viewModelScope.launch {
            val allowed = manager.grantedKinds()
            val all = allowed.size == HealthKind.entries.size
            _state.update { it.copy(allowed = allowed, denied = !all) }
            if (allowed.isNotEmpty()) onConnected()
        }
    }
}
