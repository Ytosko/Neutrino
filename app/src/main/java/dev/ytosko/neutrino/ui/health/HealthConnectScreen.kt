package dev.ytosko.neutrino.ui.health

import androidx.compose.ui.graphics.Color
import dev.ytosko.neutrino.data.health.HealthKind
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.health.HealthConnectAvailability
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing

/**
 * Health Connect connection. In onboarding ([step] set) it offers Continue/Skip;
 * from Settings it only manages the connection.
 */
@Composable
fun HealthConnectScreen(
    viewModel: HealthConnectViewModel,
    onBack: (() -> Unit)?,
    onContinue: () -> Unit,
    step: Pair<Int, Int>? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(viewModel.permissionContract()) {
        viewModel.onPermissionResult(it)
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    fun launch(intent: android.content.Intent) {
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { }
    }

    SetupScaffold(
        title = stringResource(R.string.hc_title),
        subtitle = stringResource(R.string.hc_body),
        onBack = onBack,
        step = step,
        bottomBar = {
            when {
                state.granted -> Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) { Text(stringResource(R.string.action_continue)) }

                state.availability == HealthConnectAvailability.Available -> Button(
                    onClick = { permissionLauncher.launch(viewModel.missingPermissions) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(
                        stringResource(
                            when {
                                state.allowed.isEmpty() -> R.string.hc_connect
                                state.missing == listOf(HealthKind.Glucose) -> R.string.hc_allow_glucose
                                else -> R.string.hc_allow_rest
                            },
                        ),
                    )
                }

                state.availability == HealthConnectAvailability.NotInstalled ||
                    state.availability == HealthConnectAvailability.UpdateRequired -> Button(
                    onClick = { launch(viewModel.installIntent()) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Text(
                        stringResource(
                            if (state.availability == HealthConnectAvailability.UpdateRequired) R.string.hc_update else R.string.hc_install,
                        ),
                    )
                }
            }
            if (step != null && !state.granted) {
                TextButton(onClick = onContinue, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.hc_skip))
                }
            }
        },
    ) {
        WritesCard(state.allowed.takeIf { state.availability == HealthConnectAvailability.Available })
        Column(modifier = Modifier.padding(top = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            when {
                state.granted -> StatusRow(
                    icon = R.drawable.ic_check,
                    title = stringResource(R.string.hc_connected),
                    body = stringResource(R.string.hc_connected_body),
                    positive = true,
                )
                state.allowed.isNotEmpty() && !state.denied ->
                    StatusRow(R.drawable.ic_alert, null, stringResource(R.string.hc_partial), positive = false)
                state.denied -> {
                    StatusRow(icon = R.drawable.ic_alert, title = null, body = stringResource(R.string.hc_denied), positive = false)
                    OutlinedButton(onClick = { launch(viewModel.settingsIntent()) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.hc_open_settings))
                    }
                }
                state.availability == HealthConnectAvailability.NotInstalled ->
                    StatusRow(R.drawable.ic_info, null, stringResource(R.string.hc_not_installed), positive = false)
                state.availability == HealthConnectAvailability.UpdateRequired ->
                    StatusRow(R.drawable.ic_info, null, stringResource(R.string.hc_update_required), positive = false)
                state.availability == HealthConnectAvailability.NotSupported ->
                    StatusRow(R.drawable.ic_alert, null, stringResource(R.string.hc_not_supported), positive = false)
            }
        }
    }
}

/** What Neutrino writes, each with whether it's allowed ([allowed] is null until known). */
@Composable
private fun WritesCard(allowed: Set<HealthKind>?) {
    val colors = NeutrinoTheme.colors
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.hc_writes_title), style = MaterialTheme.typography.titleMedium)
            WriteRow(R.drawable.ic_utensils, colors.carbsContainer, colors.carbs, stringResource(R.string.hc_writes_nutrition), allowed?.contains(HealthKind.Nutrition))
            WriteRow(R.drawable.ic_droplet, colors.waterContainer, colors.water, stringResource(R.string.hc_writes_hydration), allowed?.contains(HealthKind.Hydration))
            WriteRow(R.drawable.ic_activity, colors.glucoseContainer, colors.glucose, stringResource(R.string.hc_writes_glucose), allowed?.contains(HealthKind.Glucose))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_shield_check), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.hc_reads_nothing), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun WriteRow(icon: Int, container: Color, content: Color, text: String, allowed: Boolean?) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, container = container, content = content, size = 40.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        when (allowed) {
            true -> Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.hc_allowed),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp),
            )
            false -> Text(
                stringResource(R.string.hc_not_allowed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
    }
}

@Composable
private fun StatusRow(icon: Int, title: String?, body: String, positive: Boolean) {
    val color = if (positive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Column {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = color)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
