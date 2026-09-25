package dev.ytosko.neutrino.ui.glucose

import dev.ytosko.neutrino.ui.components.NeutrinoSnackbarHost
import dev.ytosko.neutrino.ui.components.AlertStyle
import dev.ytosko.neutrino.ui.components.AlertButton
import dev.ytosko.neutrino.ui.components.IosAlert
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.glucose.MeterModel
import dev.ytosko.neutrino.data.glucose.PairedMeter
import dev.ytosko.neutrino.glucose.MeterSync
import dev.ytosko.neutrino.domain.GlucoseUnit
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlin.math.abs
import kotlin.math.roundToInt

/** Settings → Glucose meters → one meter: status, Sync now, target range, Forget. */
@Composable
fun MeterScreen(viewModel: MeterViewModel, onBack: () -> Unit, onPairAgain: (MeterModel) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var confirmForget by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(
                resources.getString(
                    when (message) {
                        MeterMessage.Synced -> R.string.meter_msg_synced
                        MeterMessage.NoNewReadings -> R.string.meter_msg_none
                        MeterMessage.ClockSet -> R.string.meter_msg_clock_set
                        MeterMessage.BluetoothOff -> R.string.meter_msg_bluetooth_off
                        MeterMessage.NotPaired -> R.string.meter_msg_not_paired
                        MeterMessage.Failed -> R.string.meter_msg_failed
                    },
                ),
            )
        }
    }

    val meter = state.meter
    // Forgotten (or never existed): nothing to show.
    LaunchedEffect(state.loaded, meter) { if (state.loaded && meter == null) onBack() }
    if (meter == null) return
    val model = meter.meterModel

    SetupScaffold(
        title = model.displayName,
        subtitle = meter.serial?.let { stringResource(R.string.meter_serial, it) },
        onBack = onBack,
        bottomBar = { NeutrinoSnackbarHost(snackbar) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Hero(meter, context)
            if (meter.lastProblem == GlucoseRepository.PROBLEM_NOT_PAIRED) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(stringResource(R.string.meter_msg_not_paired), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        FilledTonalButton(onClick = { onPairAgain(model) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.meter_pair_again))
                        }
                    }
                }
            }
            SyncCard(meter, syncing = state.syncing, onSync = viewModel::syncNow)
            RangeCard(state.glucoseLow, state.glucoseHigh, viewModel::setRange)
            OutlinedButton(
                onClick = { confirmForget = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            ) {
                Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.meter_forget), color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(R.string.meter_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmForget) {
        IosAlert(
            title = stringResource(R.string.meter_forget_title),
            message = stringResource(R.string.meter_forget_body),
            buttons = listOf(
                AlertButton(stringResource(R.string.backup_cancel), AlertStyle.Cancel) { confirmForget = false },
                AlertButton(stringResource(R.string.meter_forget), AlertStyle.Destructive) {
                    confirmForget = false
                    viewModel.forget(onBack)
                },
            ),
            onDismiss = { confirmForget = false },
        )
    }
}

/** The meter's picture with a one-line status under it. */
@Composable
private fun Hero(meter: PairedMeter, context: android.content.Context) {
    val notPaired = meter.lastProblem == GlucoseRepository.PROBLEM_NOT_PAIRED
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        MeterPicture(meter.meterModel, size = 168.dp)
        Spacer(Modifier.size(Spacing.sm))
        Surface(
            shape = CircleShape,
            color = if (notPaired) MaterialTheme.colorScheme.errorContainer else NeutrinoTheme.colors.goodContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tint = if (notPaired) MaterialTheme.colorScheme.onErrorContainer else NeutrinoTheme.colors.good
                Icon(painterResource(if (notPaired) R.drawable.ic_circle_alert else R.drawable.ic_check), contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Text(
                    if (notPaired) stringResource(R.string.meters_needs_pairing) else lastSyncText(context, meter.lastSyncAt),
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                )
            }
        }
    }
}

@Composable
private fun SyncCard(meter: PairedMeter, syncing: Boolean, onSync: () -> Unit) {
    SectionCard(title = stringResource(R.string.meter_sync_title)) {
        InfoRow(R.drawable.ic_refresh, NeutrinoTheme.colors.glucose) {
            Text(
                stringResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.meter_auto_note else R.string.meter_auto_note_old),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ClockStatus(meter)
        FilledTonalButton(onClick = onSync, enabled = !syncing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (syncing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.meter_syncing))
            } else {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.meter_sync_now))
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
    val (text, isProblem) = when {
        offset == null -> stringResource(R.string.meter_clock_unknown) to false
        recentlySet -> stringResource(R.string.meter_clock_set) to false
        abs(offset) <= MeterSync.CLOCK_TOLERANCE_SECONDS -> stringResource(R.string.meter_clock_ok) to false
        // Off by more than a day: almost always a reset after changing the battery.
        abs(offset) >= 24 * 3600 -> stringResource(R.string.meter_clock_reset) to true
        else -> stringResource(if (offset > 0) R.string.meter_clock_behind else R.string.meter_clock_ahead, duration(abs(offset))) to true
    }
    InfoRow(R.drawable.ic_clock, if (isProblem) NeutrinoTheme.colors.carbs else NeutrinoTheme.colors.good) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoRow(icon: Int, tint: Color, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Box(Modifier.weight(1f)) { content() }
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

/** Low / high steppers above a bar showing the three bands. Shared by every meter. */
@Composable
private fun RangeCard(low: Double, high: Double, onChange: (Double, Double) -> Unit) {
    SectionCard(title = stringResource(R.string.meter_range_title)) {
        Text(stringResource(R.string.meter_range_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val lowColor = bandColor(GlucoseBand.Low)
        val inColor = bandColor(GlucoseBand.InRange)
        val highColor = bandColor(GlucoseBand.High)
        val unit = LocalGlucoseUnit.current
        val bandsDescription = stringResource(R.string.meter_range_desc, unit.format(low), unit.format(high), unit.label)
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = bandsDescription }) {
            Row(
                Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Box(Modifier.weight(1f).fillMaxHeight().background(lowColor))
                Box(Modifier.weight(2.4f).fillMaxHeight().background(inColor))
                Box(Modifier.weight(1f).fillMaxHeight().background(highColor))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("< ${unit.format(low)}", style = MaterialTheme.typography.labelSmall, color = lowColor, modifier = Modifier.weight(1f))
                Text(
                    "${unit.format(low)}–${unit.format(high)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = inColor,
                    modifier = Modifier.weight(2.4f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text(
                    "> ${unit.format(high)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = highColor,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
        Stepper(stringResource(R.string.meter_range_low), low, lowColor, onMinus = { onChange(unit.step(low, -1), high) }, onPlus = { onChange(unit.step(low, 1), high) })
        Stepper(stringResource(R.string.meter_range_high), high, highColor, onMinus = { onChange(low, unit.step(high, -1)) }, onPlus = { onChange(low, unit.step(high, 1)) })
        Text(stringResource(R.string.meter_range_shared), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One stepper click: 0.1 mmol/L, or 1 mg/dL (kept as whole mg/dL). */
private fun GlucoseUnit.step(mmol: Double, direction: Int): Double = when (this) {
    GlucoseUnit.MmolL -> ((mmol + 0.1 * direction) * 10).roundToInt() / 10.0
    GlucoseUnit.MgDl -> toMmol((fromMmol(mmol).roundToInt() + direction).toDouble())
}

@Composable
private fun Stepper(label: String, value: Double, color: Color, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = onMinus) { Icon(painterResource(R.drawable.ic_minus), contentDescription = stringResource(R.string.review_less)) }
        Text(
            "${glucoseText(value)} ${glucoseUnitLabel()}",
            style = MaterialTheme.typography.titleSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(min = 104.dp),
        )
        FilledTonalIconButton(onClick = onPlus) { Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.review_more)) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
