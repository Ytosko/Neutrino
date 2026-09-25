package dev.ytosko.neutrino.ui.glucose

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.glucose.MeterCompanion
import dev.ytosko.neutrino.data.glucose.MeterScan
import dev.ytosko.neutrino.data.glucose.MeterSyncOutcome
import dev.ytosko.neutrino.data.glucose.PairedMeter
import dev.ytosko.neutrino.data.glucose.PickedMeter
import dev.ytosko.neutrino.data.health.HealthConnectManager
import dev.ytosko.neutrino.data.settings.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MeterWork { Pairing, WaitingForPin, Syncing }

enum class MeterMessage { Synced, NoNewReadings, ClockSet, BluetoothOff, NotPaired, Failed, PinTimeout, PickerFailed }

data class MeterUiState(
    val loaded: Boolean = false,
    val meter: PairedMeter? = null,
    val work: MeterWork? = null,
    val lastNewReadings: Int? = null,
    val glucoseLow: Double = 4.0,
    val glucoseHigh: Double = 10.0,
    val healthConnectAllowed: Boolean = true,
)

class MeterViewModel(
    private val appContext: Context,
    private val glucose: GlucoseRepository,
    private val settings: SettingsRepository,
    private val healthConnect: HealthConnectManager,
) : ViewModel() {

    private val _state = MutableStateFlow(MeterUiState())
    val state: StateFlow<MeterUiState> = _state.asStateFlow()

    private val _messages = Channel<MeterMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { glucose.meter.collect { m -> _state.update { it.copy(loaded = true, meter = m) } } }
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(glucoseLow = s.glucoseLow, glucoseHigh = s.glucoseHigh) } }
        }
        refreshHealthConnect()
    }

    fun refreshHealthConnect() {
        viewModelScope.launch {
            val allowed = runCatching { healthConnect.hasGlucosePermission() }.getOrDefault(false)
            _state.update { it.copy(healthConnectAllowed = allowed) }
            if (allowed) glucose.syncWithHealthConnect()
        }
    }

    val glucosePermission: String get() = healthConnect.glucosePermission
    fun permissionContract() = healthConnect.permissionContract()

    fun hasBluetoothPermission() = glucose.hasBluetoothPermission()
    fun isBluetoothOn() = glucose.isBluetoothOn()

    fun pickerStarted() = _state.update { it.copy(work = MeterWork.Pairing) }
    fun pickerFailed() {
        _state.update { it.copy(work = null) }
        viewModelScope.launch { _messages.send(MeterMessage.PickerFailed) }
    }

    /**
     * The user chose a meter in the system picker: pair (the meter shows a PIN to type on the
     * phone), then watch for it in the background and download its readings.
     */
    @SuppressLint("MissingPermission")
    fun onPicked(picked: PickedMeter?) {
        if (picked == null) {
            _state.update { it.copy(work = null) }
            return
        }
        viewModelScope.launch {
            val device = glucose.device(picked.address)
            if (device != null && device.bondState != BluetoothDevice.BOND_BONDED) {
                _state.update { it.copy(work = MeterWork.WaitingForPin) }
                runCatching { device.createBond() }
                // The user types the PIN from the meter into Android's pairing dialog.
                var waited = 0
                while (device.bondState != BluetoothDevice.BOND_BONDED && waited < PIN_TIMEOUT_MS) {
                    delay(500)
                    waited += 500
                    // Cancelled or rejected: stop waiting.
                    if (device.bondState == BluetoothDevice.BOND_NONE && waited > 3_000) break
                }
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    // Don't keep a half-set-up meter: drop the association the picker created.
                    MeterCompanion.forget(appContext, picked.asUnsynced())
                    _state.update { it.copy(work = null) }
                    _messages.send(MeterMessage.PinTimeout)
                    return@launch
                }
            }
            // Only a paired meter is remembered.
            glucose.savePairing(picked.address, picked.name, picked.associationId)
            glucose.meter.first()?.let { MeterCompanion.observe(appContext, it) }
            MeterScan.start(appContext)
            _state.update { it.copy(work = null) }
            syncNow()
        }
    }

    fun syncNow() {
        if (_state.value.work == MeterWork.Syncing) return
        _state.update { it.copy(work = MeterWork.Syncing) }
        viewModelScope.launch {
            val outcome = glucose.sync()
            _state.update { it.copy(work = null, lastNewReadings = (outcome as? MeterSyncOutcome.Synced)?.newReadings) }
            _messages.send(
                when (outcome) {
                    is MeterSyncOutcome.Synced -> when {
                        outcome.clockWasSet -> MeterMessage.ClockSet
                        outcome.newReadings > 0 -> MeterMessage.Synced
                        else -> MeterMessage.NoNewReadings
                    }
                    MeterSyncOutcome.BluetoothOff -> MeterMessage.BluetoothOff
                    MeterSyncOutcome.NotPaired -> MeterMessage.NotPaired
                    else -> MeterMessage.Failed
                },
            )
        }
    }

    fun forget() {
        viewModelScope.launch {
            glucose.meter.first()?.let { MeterCompanion.forget(appContext, it) }
            MeterScan.stop(appContext)
            glucose.forgetMeter()
        }
    }

    fun setRange(low: Double, high: Double) {
        viewModelScope.launch { settings.setGlucoseRange(low, high) }
    }

    private companion object {
        const val PIN_TIMEOUT_MS = 120_000
    }
}

private fun PickedMeter.asUnsynced() = PairedMeter(
    address = address, name = name, model = null, serial = null, associationId = associationId,
    lastSequence = null, lastSyncAt = null, clockOffsetSeconds = null, clockWritable = null, clockSetAt = null, lastProblem = null,
)
