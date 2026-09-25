package dev.ytosko.neutrino.ui.backup

import android.app.Activity
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * "Do you have a Neutrino backup?": pick where to look, see what's there, go back and try
 * somewhere else (another Google account, say) until you tap Restore.
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

    val step = state.step
    // From a result, back returns to the choice rather than leaving the screen.
    BackHandler(enabled = step != RestoreStep.Choose) { viewModel.back() }

    SetupScaffold(
        title = stringResource(if (step == RestoreStep.Choose) R.string.restore_title else R.string.restore_result_title),
        subtitle = if (step == RestoreStep.Choose) stringResource(R.string.restore_body) else null,
        onBack = { if (!viewModel.back()) onBack() },
        bottomBar = {
            when (step) {
                is RestoreStep.Found -> Button(
                    onClick = viewModel::restore,
                    enabled = state.password.isNotEmpty() && !state.restoring,
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
                RestoreStep.Choose -> TextButton(onClick = onStartFresh, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.restore_start_fresh))
                }
                else -> Unit
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            when (step) {
                RestoreStep.Choose -> {
                    PlaceCard(
                        icon = R.drawable.ic_smartphone,
                        title = stringResource(R.string.restore_from_phone),
                        body = stringResource(R.string.restore_from_phone_body),
                        onClick = { withAccess { viewModel.lookOnPhone() } },
                    )
                    if (state.driveAvailable) {
                        PlaceCard(
                            icon = R.drawable.ic_cloud_upload,
                            title = stringResource(R.string.restore_from_drive),
                            body = stringResource(R.string.restore_from_drive_body),
                            onClick = viewModel::lookInDrive,
                        )
                    }
                }
                is RestoreStep.Looking -> Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = Spacing.md),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.restore_searching), style = MaterialTheme.typography.bodyLarge)
                }
                is RestoreStep.Found -> {
                    FoundCard(step.backup)
                    PasswordEntry(state, viewModel)
                }
                is RestoreStep.NotFound -> ResultMessage(
                    icon = if (step.place == RestorePlace.Phone) R.drawable.ic_smartphone else R.drawable.ic_cloud_upload,
                    text = when {
                        step.place == RestorePlace.Phone -> stringResource(R.string.restore_none_on_phone)
                        !step.email.isNullOrEmpty() -> stringResource(R.string.restore_none_in_account, step.email)
                        else -> stringResource(R.string.restore_none_in_drive)
                    },
                    onBack = { viewModel.back() },
                )
                is RestoreStep.Failed -> ResultMessage(
                    icon = R.drawable.ic_circle_alert,
                    text = stringResource(R.string.restore_failed),
                    onBack = { viewModel.back() },
                )
            }
        }
    }
}

@Composable
private fun PlaceCard(icon: Int, title: String, body: String, onClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, size = 40.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FoundCard(backup: FoundBackup) {
    val colors = NeutrinoTheme.colors
    val context = LocalContext.current
    val header = backup.header
    val phone = backup.source == RestoreSource.Phone
    BackupCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                if (phone) R.drawable.ic_smartphone else R.drawable.ic_cloud_upload,
                container = if (phone) colors.proteinContainer else colors.waterContainer,
                content = if (phone) colors.protein else colors.water,
                size = 40.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (phone) R.string.restore_found_phone else R.string.restore_found_drive),
                    style = MaterialTheme.typography.titleSmall,
                )
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
        Text(stringResource(R.string.restore_try_elsewhere), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultMessage(icon: Int, text: String, onBack: () -> Unit) {
    BackupCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, container = MaterialTheme.colorScheme.surfaceContainerHigh, content = MaterialTheme.colorScheme.onSurfaceVariant, size = 40.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.restore_look_elsewhere))
        }
    }
}
