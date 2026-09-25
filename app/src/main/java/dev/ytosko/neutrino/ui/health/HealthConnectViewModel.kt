package dev.ytosko.neutrino.ui.health

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
    val granted: Boolean = false,
    /** The user dismissed or denied the permission sheet at least once. */
    val denied: Boolean = false,
)

class HealthConnectViewModel(
    private val manager: HealthConnectManager,
    /** Runs once access is granted, to send anything saved while disconnected. */
    private val onConnected: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(HealthConnectUiState())
    val state: StateFlow<HealthConnectUiState> = _state.asStateFlow()

    val permissions: Set<String> get() = manager.permissions
    fun permissionContract() = manager.permissionContract()
    fun installIntent() = manager.installIntent()
    fun settingsIntent() = manager.settingsIntent()

    /** Re-check on resume: the user may have installed Health Connect or changed permissions. */
    fun refresh() {
        viewModelScope.launch {
            val availability = manager.availability()
            val granted = availability == HealthConnectAvailability.Available && manager.hasAllPermissions()
            _state.update { it.copy(availability = availability, granted = granted) }
            if (granted) onConnected()
        }
    }

    fun onPermissionResult(granted: Set<String>) {
        val all = granted.containsAll(manager.permissions)
        _state.update { it.copy(granted = all, denied = !all) }
        if (all) viewModelScope.launch { onConnected() }
    }
}
