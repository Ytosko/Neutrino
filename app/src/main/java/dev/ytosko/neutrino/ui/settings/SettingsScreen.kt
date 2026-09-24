package dev.ytosko.neutrino.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.BuildConfig
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.settings.AppSettings
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.health.HealthConnectViewModel
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow

@Composable
fun SettingsScreen(
    settings: Flow<AppSettings>,
    healthViewModel: HealthConnectViewModel,
    onBack: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenHealthConnect: () -> Unit,
) {
    val appSettings by settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val health by healthViewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LifecycleResumeEffect(Unit) {
        healthViewModel.refresh()
        onPauseOrDispose { }
    }
    val privacyUrl = stringResource(R.string.url_privacy)
    val termsUrl = stringResource(R.string.url_terms)
    val sourceUrl = stringResource(R.string.url_source)

    SetupScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        Section(stringResource(R.string.settings_section_ai)) {
            val provider = appSettings.activeProvider
            SettingRow(
                icon = R.drawable.ic_sparkles,
                title = stringResource(R.string.settings_ai_provider),
                value = if (provider != null && appSettings.aiReady) {
                    "${provider.displayName} · ${appSettings.models[provider]}"
                } else {
                    stringResource(R.string.settings_ai_not_set)
                },
                onClick = onOpenAi,
            )
        }
        Section(stringResource(R.string.settings_section_health)) {
            SettingRow(
                icon = R.drawable.ic_heart_pulse,
                title = stringResource(R.string.settings_section_health),
                value = stringResource(
                    if (health.granted) R.string.settings_hc_status_connected else R.string.settings_hc_status_disconnected,
                ),
                onClick = onOpenHealthConnect,
            )
        }
        Section(stringResource(R.string.settings_section_about)) {
            SettingRow(R.drawable.ic_shield_check, stringResource(R.string.settings_privacy), null, external = true) { uriHandler.openUri(privacyUrl) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(R.drawable.ic_info, stringResource(R.string.settings_terms), null, external = true) { uriHandler.openUri(termsUrl) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow(R.drawable.ic_code, stringResource(R.string.settings_source), null, external = true) { uriHandler.openUri(sourceUrl) }
        }
        Text(
            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.lg),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(bottom = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xs),
        )
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = CardDefaults.outlinedCardBorder(),
        ) { Column { content() } }
    }
}

@Composable
private fun SettingRow(
    icon: Int,
    title: String,
    value: String?,
    external: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        IconBadge(icon = icon, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            painterResource(if (external) R.drawable.ic_external_link else R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
