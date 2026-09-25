package dev.ytosko.neutrino.ui.glucose

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.glucose.AddMeterResult
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.glucose.MeterCompanion
import dev.ytosko.neutrino.data.glucose.MeterModel
import dev.ytosko.neutrino.data.glucose.MeterWake
import dev.ytosko.neutrino.data.glucose.PairedMeter
import dev.ytosko.neutrino.data.glucose.PickedMeter
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class PairStep { Ready, Looking, WaitingForPin, Paired }

enum class PairMessage { PickerFailed, PinTimeout, AlreadyAdded, LimitReached }

/**
 * Pairs one meter of [model]: system picker → PIN → remembered. A meter that is already added but
 * lost its pairing (e.g. reset in Bluetooth settings) is paired again in place.
 */
class PairMeterViewModel(
    private val appContext: Context,
    private val glucose: GlucoseRepository,
    val model: MeterModel,
    /** Starts the first download in the app's scope, so leaving this page doesn't cancel it. */
    private val syncInBackground: (MeterWake) -> Unit,
    /** Restarts the background wake-ups for all meters. */
    private val watchMeters: suspend () -> Unit,
) : ViewModel() {

    private val _step = MutableStateFlow(PairStep.Ready)
    val step: StateFlow<PairStep> = _step.asStateFlow()

    private val _messages = Channel<PairMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun hasBluetoothPermission() = glucose.hasBluetoothPermission()
    fun isBluetoothOn() = glucose.isBluetoothOn()

    fun pickerStarted() {
        _step.value = PairStep.Looking
    }

    fun pickerFailed() {
        _step.value = PairStep.Ready
        _messages.trySend(PairMessage.PickerFailed)
    }

    @SuppressLint("MissingPermission")
    fun onPicked(picked: PickedMeter?) {
        if (picked == null) {
            _step.value = PairStep.Ready
            return
        }
        viewModelScope.launch {
            val existing = glucose.meters.first().firstOrNull { it.address.equals(picked.address, ignoreCase = true) }
            val device = glucose.device(picked.address)
            if (existing != null && device?.bondState == BluetoothDevice.BOND_BONDED) {
                _step.value = PairStep.Ready
                _messages.send(PairMessage.AlreadyAdded)
                return@launch
            }
            if (existing == null && !glucose.canAddMeter()) {
                MeterCompanion.forget(appContext, picked.asUnsaved())
                _step.value = PairStep.Ready
                _messages.send(PairMessage.LimitReached)
                return@launch
            }
            if (device != null && device.bondState != BluetoothDevice.BOND_BONDED) {
                _step.value = PairStep.WaitingForPin
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
                    // Don't keep a half-set-up meter: drop the association the picker just created.
                    if (existing == null) MeterCompanion.forget(appContext, picked.asUnsaved())
                    _step.value = PairStep.Ready
                    _messages.send(PairMessage.PinTimeout)
                    return@launch
                }
            }
            // Only a paired meter is remembered.
            val meterId = if (existing != null) {
                glucose.clearProblem(existing.id)
                existing.id
            } else {
                when (val result = glucose.addMeter(picked.address, picked.name, picked.associationId, model)) {
                    is AddMeterResult.Added -> result.meter.id
                    AddMeterResult.AlreadyAdded -> {
                        _step.value = PairStep.Ready
                        _messages.send(PairMessage.AlreadyAdded)
                        return@launch
                    }
                    AddMeterResult.LimitReached -> {
                        _step.value = PairStep.Ready
                        _messages.send(PairMessage.LimitReached)
                        return@launch
                    }
                }
            }
            watchMeters()
            syncInBackground(MeterWake(meterId = meterId))
            _step.value = PairStep.Paired
        }
    }

    private companion object {
        const val PIN_TIMEOUT_MS = 120_000
    }
}

private fun PickedMeter.asUnsaved() = PairedMeter(id = address, address = address, name = name, associationId = associationId)

/** Pairing one meter: its picture, how to put it in pairing mode, then Pair → PIN → Done. */
@Composable
fun PairMeterScreen(viewModel: PairMeterViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val model = viewModel.model

    // After pairing, Back behaves like Done.
    BackHandler(enabled = step == PairStep.Paired) { onDone() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onPicked(if (result.resultCode == Activity.RESULT_OK) MeterCompanion.result(result.data) else null)
    }
    fun openPicker() {
        viewModel.pickerStarted()
        MeterCompanion.startPicker(
            context,
            onChooser = { picker.launch(IntentSenderRequest.Builder(it).build()) },
            onError = { viewModel.pickerFailed() },
        )
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (viewModel.isBluetoothOn()) openPicker()
    }
    fun startPairing() {
        if (viewModel.isBluetoothOn()) openPicker() else enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (viewModel.hasBluetoothPermission()) startPairing()
    }
    fun pair() {
        if (viewModel.hasBluetoothPermission()) {
            startPairing()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(
                resources.getString(
                    when (message) {
                        PairMessage.PickerFailed -> R.string.meter_msg_picker_failed
                        PairMessage.PinTimeout -> R.string.meter_msg_pin_timeout
                        PairMessage.AlreadyAdded -> R.string.meters_already_added
                        PairMessage.LimitReached -> R.string.meters_limit_reached
                    },
                ),
            )
        }
    }

    SetupScaffold(
        title = model.displayName,
        subtitle = if (step == PairStep.Paired) null else stringResource(R.string.meter_pair_title),
        onBack = if (step == PairStep.Paired) onDone else onBack,
        bottomBar = {
            SnackbarHost(snackbar)
            val working = step == PairStep.Looking || step == PairStep.WaitingForPin
            Button(
                onClick = { if (step == PairStep.Paired) onDone() else pair() },
                enabled = !working,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(if (step == PairStep.WaitingForPin) R.string.meter_waiting_pin else R.string.meter_pairing))
                } else {
                    Text(stringResource(if (step == PairStep.Paired) R.string.meters_done else R.string.meter_pair))
                }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            MeterPicture(model, size = 180.dp)
            Spacer(Modifier.size(Spacing.lg))
            if (step == PairStep.Paired) {
                Surface(shape = CircleShape, color = NeutrinoTheme.colors.proteinContainer) {
                    Icon(
                        painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = NeutrinoTheme.colors.protein,
                        modifier = Modifier.padding(Spacing.sm).size(28.dp),
                    )
                }
                Text(
                    stringResource(R.string.meters_paired_title, model.displayName),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                Text(
                    stringResource(R.string.meters_paired_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    listOf(R.string.meter_step_1, model.pairingModeStep(), R.string.meter_step_3, R.string.meter_step_4)
                        .forEachIndexed { i, text -> StepRow(i + 1, stringResource(text)) }
                }
            }
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 3.dp))
    }
}
