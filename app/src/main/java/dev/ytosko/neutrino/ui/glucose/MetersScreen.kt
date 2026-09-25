package dev.ytosko.neutrino.ui.glucose

import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.glucose.MeterModel
import dev.ytosko.neutrino.data.glucose.PairedMeter
import dev.ytosko.neutrino.data.health.HealthConnectManager
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MetersViewModel(
    glucose: GlucoseRepository,
    private val healthConnect: HealthConnectManager,
) : ViewModel() {
    /** Null until loaded, so the empty state doesn't flash. */
    val meters: StateFlow<List<PairedMeter>?> = glucose.meters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _healthConnectAllowed = MutableStateFlow(true)
    val healthConnectAllowed: StateFlow<Boolean> = _healthConnectAllowed.asStateFlow()

    val glucosePermission: String get() = healthConnect.glucosePermission
    fun permissionContract() = healthConnect.permissionContract()

    fun refreshHealthConnect() {
        viewModelScope.launch {
            _healthConnectAllowed.value = runCatching { healthConnect.hasGlucosePermission() }.getOrDefault(false)
        }
    }
}

/** Settings → Glucose meters: every paired meter, and + to add one (up to five). */
@Composable
fun MetersScreen(viewModel: MetersViewModel, onBack: () -> Unit, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    val meters by viewModel.meters.collectAsStateWithLifecycle()
    val healthConnectAllowed by viewModel.healthConnectAllowed.collectAsStateWithLifecycle()
    val healthPermission = androidx.activity.compose.rememberLauncherForActivityResult(viewModel.permissionContract()) {
        viewModel.refreshHealthConnect()
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshHealthConnect()
        onPauseOrDispose { }
    }
    val list = meters
    val canAdd = list != null && list.size < GlucoseRepository.MAX_METERS

    SetupScaffold(
        title = stringResource(R.string.meters_title),
        subtitle = stringResource(R.string.meter_body),
        onBack = onBack,
        actions = {
            if (canAdd) {
                IconButton(onClick = onAdd) {
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.meters_add))
                }
            }
        },
    ) {
        if (list == null) return@SetupScaffold
        if (list.isEmpty()) {
            EmptyMeters(onAdd)
            return@SetupScaffold
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (!healthConnectAllowed) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = NeutrinoTheme.colors.glucoseContainer),
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
            list.forEach { meter -> MeterRow(meter, onClick = { onOpen(meter.id) }) }
            Text(
                if (canAdd) stringResource(R.string.meters_count, list.size, GlucoseRepository.MAX_METERS)
                else stringResource(R.string.meters_limit, GlucoseRepository.MAX_METERS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun MeterRow(meter: PairedMeter, onClick: () -> Unit) {
    val context = LocalContext.current
    val notPaired = meter.lastProblem == GlucoseRepository.PROBLEM_NOT_PAIRED
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            MeterPicture(meter.meterModel, size = 72.dp)
            Column(Modifier.weight(1f)) {
                Text(meter.meterModel.displayName, style = MaterialTheme.typography.titleMedium)
                meter.serial?.let {
                    Text(stringResource(R.string.meter_serial, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (notPaired) {
                    Text(stringResource(R.string.meters_needs_pairing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(lastSyncText(context, meter.lastSyncAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun EmptyMeters(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The three supported meters, fanned out.
        Row(verticalAlignment = Alignment.CenterVertically) {
            MeterPicture(MeterModel.ContourPlusOne, size = 76.dp, modifier = Modifier.offset(x = 14.dp))
            MeterPicture(MeterModel.ContourPlusElite, size = 96.dp, modifier = Modifier.zIndex(1f), cutout = true)
            MeterPicture(MeterModel.ContourPlusBlue, size = 76.dp, modifier = Modifier.offset(x = (-14).dp))
        }
        Spacer(Modifier.size(Spacing.lg))
        Text(stringResource(R.string.meters_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.meters_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Spacer(Modifier.size(Spacing.lg))
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 52.dp)) {
            Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Spacing.xs))
            Text(stringResource(R.string.meters_add))
        }
    }
}

/** "Last sync: 5 minutes ago", "Last sync: just now" or "Not synced yet". */
fun lastSyncText(context: Context, at: Long?): String = when {
    at == null -> context.getString(R.string.meter_never_synced)
    System.currentTimeMillis() - at < DateUtils.MINUTE_IN_MILLIS -> context.getString(R.string.meter_last_sync, context.getString(R.string.meter_just_now))
    else -> context.getString(
        R.string.meter_last_sync,
        DateUtils.getRelativeDateTimeString(context, at, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString(),
    )
}
