package dev.ytosko.neutrino.ui.glucose

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.glucose.MeterCompanion
import dev.ytosko.neutrino.data.glucose.PairedMeter
import dev.ytosko.neutrino.glucose.MeterSync
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.roundToInt

/** Settings → Glucose meter: pair, sync, clock status, target range. */
@Composable
fun MeterScreen(viewModel: MeterViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmForget by remember { mutableStateOf(false) }

    LifecycleResumeEffect(viewModel) {
        viewModel.refreshHealthConnect()
        onPauseOrDispose { }
    }

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
    val healthPermission = rememberLauncherForActivityResult(viewModel.permissionContract()) { viewModel.refreshHealthConnect() }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(
                context.getString(
                    when (message) {
                        MeterMessage.Synced -> R.string.meter_msg_synced
                        MeterMessage.NoNewReadings -> R.string.meter_msg_none
                        MeterMessage.ClockSet -> R.string.meter_msg_clock_set
                        MeterMessage.BluetoothOff -> R.string.meter_msg_bluetooth_off
                        MeterMessage.NotPaired -> R.string.meter_msg_not_paired
                        MeterMessage.Failed -> R.string.meter_msg_failed
                        MeterMessage.PinTimeout -> R.string.meter_msg_pin_timeout
                        MeterMessage.PickerFailed -> R.string.meter_msg_picker_failed
                    },
                ),
            )
        }
    }

    SetupScaffold(
        title = stringResource(R.string.meter_title),
        subtitle = stringResource(R.string.meter_body),
        onBack = onBack,
        bottomBar = { SnackbarHost(snackbar) },
    ) {
        if (!state.loaded) return@SetupScaffold
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            val meter = state.meter
            if (meter == null) {
                PairingCard(state, onPair = ::pair)
            } else {
                MeterCard(meter, state, onSync = viewModel::syncNow, onRepair = ::pair, onForget = { confirmForget = true })
            }
            if (!state.healthConnectAllowed) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(stringResource(R.string.meter_hc_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.meter_hc_body), style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(
                            onClick = { runCatching { healthPermission.launch(setOf(viewModel.glucosePermission)) } },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.meter_hc_allow)) }
                    }
                }
            }
            RangeCard(state.glucoseLow, state.glucoseHigh, viewModel::setRange)
            Text(
                stringResource(R.string.meter_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.meter_forget_title)) },
            text = { Text(stringResource(R.string.meter_forget_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    viewModel.forget()
                }) { Text(stringResource(R.string.meter_forget), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.backup_cancel)) } },
        )
    }
}

@Composable
private fun PairingCard(state: MeterUiState, onPair: () -> Unit) {
    MeterCardShell {
        Text(stringResource(R.string.meter_pair_title), style = MaterialTheme.typography.titleMedium)
        listOf(R.string.meter_step_1, R.string.meter_step_2, R.string.meter_step_3, R.string.meter_step_4).forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = onPair, enabled = state.work == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            if (state.work != null) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(if (state.work == MeterWork.WaitingForPin) R.string.meter_waiting_pin else R.string.meter_pairing))
            } else {
                Text(stringResource(R.string.meter_pair))
            }
        }
    }
}

@Composable
private fun MeterCard(meter: PairedMeter, state: MeterUiState, onSync: () -> Unit, onRepair: () -> Unit, onForget: () -> Unit) {
    val colors = NeutrinoTheme.colors
    val context = LocalContext.current
    MeterCardShell {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_activity, container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.tertiary, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(meter.model ?: meter.name ?: stringResource(R.string.meter_unknown_model), style = MaterialTheme.typography.titleMedium)
                meter.serial?.let {
                    Text(stringResource(R.string.meter_serial, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    meter.lastSyncAt?.let {
                        stringResource(
                            R.string.meter_last_sync,
                            if (System.currentTimeMillis() - it < DateUtils.MINUTE_IN_MILLIS) {
                                stringResource(R.string.meter_just_now)
                            } else {
                                DateUtils.getRelativeDateTimeString(context, it, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString()
                            },
                        )
                    } ?: stringResource(R.string.meter_never_synced),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ClockStatus(meter)
        if (meter.lastProblem == "not_paired") {
            Text(stringResource(R.string.meter_msg_not_paired), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            FilledTonalButton(onClick = onRepair, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.meter_pair_again)) }
        }
        Text(
            stringResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.meter_auto_note else R.string.meter_auto_note_old),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            FilledTonalButton(onClick = onSync, enabled = state.work == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                if (state.work == MeterWork.Syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(R.string.meter_syncing))
                } else {
                    Text(stringResource(R.string.meter_sync_now))
                }
            }
            OutlinedButton(onClick = onForget, enabled = state.work == null, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.meter_forget))
            }
        }
        Text(stringResource(R.string.meter_sync_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What Neutrino knows about the meter's clock, and what it did about it. */
@Composable
private fun ClockStatus(meter: PairedMeter) {
    val offset = meter.clockOffsetSeconds
    val recentlySet = meter.clockSetAt != null && meter.lastSyncAt != null && meter.clockSetAt >= meter.lastSyncAt - 1_000
    val (icon, text, isProblem) = when {
        offset == null -> Triple(R.drawable.ic_clock, stringResource(R.string.meter_clock_unknown), false)
        recentlySet -> Triple(R.drawable.ic_check, stringResource(R.string.meter_clock_set), false)
        abs(offset) <= MeterSync.CLOCK_TOLERANCE_SECONDS -> Triple(R.drawable.ic_check, stringResource(R.string.meter_clock_ok), false)
        else -> Triple(
            R.drawable.ic_clock,
            stringResource(
                if (offset > 0) R.string.meter_clock_behind else R.string.meter_clock_ahead,
                duration(abs(offset)),
            ),
            true,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (isProblem) NeutrinoTheme.colors.carbs else NeutrinoTheme.colors.protein,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun duration(seconds: Long): String {
    val minutes = (seconds / 60.0).roundToInt()
    return when {
        minutes >= 24 * 60 -> stringResource(R.string.duration_days, minutes / (24 * 60))
        minutes >= 60 -> stringResource(R.string.duration_hours_minutes, minutes / 60, minutes % 60)
        else -> stringResource(R.string.duration_minutes, minutes.coerceAtLeast(1))
    }
}

@Composable
private fun RangeCard(low: Double, high: Double, onChange: (Double, Double) -> Unit) {
    MeterCardShell {
        Text(stringResource(R.string.meter_range_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.meter_range_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Stepper(stringResource(R.string.meter_range_low), low, onMinus = { onChange(round1(low - 0.1), high) }, onPlus = { onChange(round1(low + 0.1), high) })
        Stepper(stringResource(R.string.meter_range_high), high, onMinus = { onChange(low, round1(high - 0.1)) }, onPlus = { onChange(low, round1(high + 0.1)) })
    }
}

private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

@Composable
private fun Stepper(label: String, value: Double, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) { Icon(painterResource(R.drawable.ic_minus), contentDescription = stringResource(R.string.review_less)) }
        Text("${formatMmol(value)} mmol/L", style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = onPlus) { Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.review_more)) }
    }
}

@Composable
private fun MeterCardShell(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
    }
}
