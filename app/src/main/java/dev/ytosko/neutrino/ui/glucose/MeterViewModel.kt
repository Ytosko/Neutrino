package dev.ytosko.neutrino.ui.glucose

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.glucose.MeterCompanion
import dev.ytosko.neutrino.data.glucose.MeterSyncOutcome
import dev.ytosko.neutrino.data.glucose.PairedMeter
import dev.ytosko.neutrino.data.settings.SettingsRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MeterMessage { Synced, NoNewReadings, ClockSet, BluetoothOff, NotPaired, Failed }

data class MeterUiState(
    val loaded: Boolean = false,
    val meter: PairedMeter? = null,
    val syncing: Boolean = false,
    val glucoseLow: Double = 4.0,
    val glucoseHigh: Double = 10.0,
)

/** One paired meter's page: status, Sync now, the (shared) target range and Forget. */
class MeterViewModel(
    private val appContext: Context,
    private val glucose: GlucoseRepository,
    private val settings: SettingsRepository,
    private val meterId: String,
    /** Restarts the background wake-ups for the meters that remain. */
    private val watchMeters: suspend () -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(MeterUiState())
    val state: StateFlow<MeterUiState> = _state.asStateFlow()

    private val _messages = Channel<MeterMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { glucose.meter(meterId).collect { m -> _state.update { it.copy(loaded = true, meter = m) } } }
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(glucoseLow = s.glucoseLow, glucoseHigh = s.glucoseHigh) } }
        }
    }

    fun syncNow() {
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true) }
        viewModelScope.launch {
            val outcome = glucose.sync(meterId)
            _state.update { it.copy(syncing = false) }
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

    /** Forgets this meter, then [onDone] (e.g. go back to the list). */
    fun forget(onDone: () -> Unit) {
        viewModelScope.launch {
            glucose.meters.first().firstOrNull { it.id == meterId }?.let { MeterCompanion.forget(appContext, it) }
            glucose.forgetMeter(meterId)
            watchMeters()
            onDone()
        }
    }

    /** The target range is shared by all meters. */
    fun setRange(low: Double, high: Double) {
        viewModelScope.launch { settings.setGlucoseRange(low, high) }
    }
}
