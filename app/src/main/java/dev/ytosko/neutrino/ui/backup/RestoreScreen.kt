package dev.ytosko.neutrino.ui.backup

import android.app.Activity
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.backup.RestoreSource
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing

/**
 * "Do you have a Neutrino backup?": checks this phone and the Google accounts the user picks,
 * lists what it finds (newest marked Latest) and restores the chosen one with its password.
 */
@Composable
fun RestoreScreen(viewModel: RestoreViewModel, onBack: () -> Unit, onStartFresh: () -> Unit, onRestored: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val withAccess = rememberStorageAccess(viewModel::hasStorageAccess, viewModel::storageSettingsIntent)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(if (result.resultCode == Activity.RESULT_OK) result.data else null)
    }
    LaunchedEffect(viewModel) { viewModel.consent.collect { consent.launch(IntentSenderRequest.Builder(it).build()) } }
    LaunchedEffect(viewModel) { viewModel.restored.collect { onRestored() } }

    val selected = state.selected
    SetupScaffold(
        title = stringResource(R.string.restore_title),
        subtitle = stringResource(R.string.restore_body),
        onBack = onBack,
        bottomBar = {
            if (selected != null) {
                Button(
                    onClick = viewModel::restore,
                    enabled = state.password.isNotEmpty() && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    if (state.restoring) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.size(Spacing.xs))
                        Text(stringResource(R.string.restore_working))
                    } else {
                        Text(stringResource(R.string.restore_button))
                    }
                }
            }
            TextButton(onClick = onStartFresh, enabled = !state.restoring, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.restore_start_fresh))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (state.found.isNotEmpty()) {
                Text(stringResource(R.string.restore_found_title), style = MaterialTheme.typography.titleSmall)
                state.found.forEach { backup ->
                    FoundCard(
                        backup = backup,
                        selected = backup.id == state.selectedId,
                        latest = backup.id == state.latestId,
                        enabled = !state.restoring,
                        onSelect = { viewModel.select(backup.id) },
                    )
                }
            }

            if (selected != null) {
                PasswordEntry(state, viewModel)
            }

            // Once a backup is found there's nothing more to look for; just restore it.
            if (state.found.isNotEmpty()) return@Column
            Text(stringResource(R.string.restore_look_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
            SourceCard(
                icon = R.drawable.ic_smartphone,
                title = stringResource(R.string.restore_from_phone),
                status = when (state.phone) {
                    CheckState.NotChecked -> stringResource(R.string.restore_from_phone_body)
                    CheckState.Checking -> stringResource(R.string.restore_searching)
                    CheckState.None -> stringResource(R.string.restore_none_on_phone)
                    CheckState.Found -> stringResource(R.string.restore_found_on_phone)
                    CheckState.Failed -> stringResource(R.string.restore_failed)
                },
                busy = state.phone == CheckState.Checking,
                enabled = !state.busy,
                onClick = { withAccess { viewModel.checkPhone() } },
            )
            if (state.driveAvailable) {
                val checkedAny = state.drive != CheckState.NotChecked
                SourceCard(
                    icon = R.drawable.ic_cloud_upload,
                    title = stringResource(if (checkedAny) R.string.restore_drive_another else R.string.restore_from_drive),
                    status = when {
                        state.drive == CheckState.Checking -> stringResource(R.string.restore_searching)
                        state.drive == CheckState.Failed -> stringResource(R.string.restore_failed)
                        state.emptyAccounts.isNotEmpty() -> stringResource(
                            R.string.restore_none_in_accounts,
                            state.emptyAccounts.joinToString { it.ifEmpty { "Google" } },
                        )
                        else -> stringResource(R.string.restore_from_drive_body)
                    },
                    busy = state.drive == CheckState.Checking,
                    enabled = !state.busy,
                    onClick = { viewModel.checkDrive(anotherAccount = checkedAny) },
                )
            }
        }
    }
}

@Composable
private fun FoundCard(backup: FoundBackup, selected: Boolean, latest: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val colors = NeutrinoTheme.colors
    val context = LocalContext.current
    val header = backup.header
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val phone = backup.source == RestoreSource.Phone
            IconBadge(
                if (phone) R.drawable.ic_smartphone else R.drawable.ic_cloud_upload,
                container = if (phone) colors.proteinContainer else colors.waterContainer,
                content = if (phone) colors.protein else colors.water,
                size = 40.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        if (phone) stringResource(R.string.restore_on_phone) else stringResource(R.string.backup_drive),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (latest) {
                        Text(
                            stringResource(R.string.restore_latest),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                (backup.source as? RestoreSource.Drive)?.email?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(
                        R.string.restore_found_line,
                        formatWhen(header.createdAtEpochMs),
                        pluralStringResource(R.plurals.restore_meals, header.meals, header.meals),
                        pluralStringResource(R.plurals.restore_foods, header.foods, header.foods),
                        Formatter.formatShortFileSize(context, backup.file.size.toLong()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun PasswordEntry(state: RestoreUiState, viewModel: RestoreViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.restore_password_prompt), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(stringResource(R.string.backup_password)) },
            singleLine = true,
            enabled = !state.restoring,
            visualTransformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.restore() }),
            trailingIcon = {
                IconButton(onClick = viewModel::togglePasswordVisible) {
                    Icon(
                        painterResource(if (state.passwordVisible) R.drawable.ic_eye_off else R.drawable.ic_eye),
                        contentDescription = stringResource(if (state.passwordVisible) R.string.backup_hide_password else R.string.backup_show_password),
                    )
                }
            },
            isError = state.problem != null,
            supportingText = state.problem?.let { problem ->
                {
                    Text(
                        when (problem) {
                            RestoreProblem.WrongPassword -> stringResource(R.string.restore_wrong_password)
                            is RestoreProblem.BadFile -> stringResource(R.string.restore_bad_file, problem.reason)
                            RestoreProblem.Failed -> stringResource(R.string.restore_failed)
                        },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SourceCard(icon: Int, title: String, status: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, size = 40.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}
