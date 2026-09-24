package dev.ytosko.neutrino.ui.backup

import android.app.Activity
import android.text.format.Formatter
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
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing

/** Restore after a reinstall or on a new phone: find a backup, enter its password, done. */
@Composable
fun RestoreScreen(viewModel: RestoreViewModel, onBack: () -> Unit, onRestored: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = NeutrinoTheme.colors

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.openFile(uri)
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(if (result.resultCode == Activity.RESULT_OK) result.data else null)
    }
    LaunchedEffect(viewModel) { viewModel.consent.collect { consent.launch(IntentSenderRequest.Builder(it).build()) } }
    LaunchedEffect(viewModel) { viewModel.restored.collect { onRestored() } }

    val found = state.found
    SetupScaffold(
        title = stringResource(R.string.restore_title),
        subtitle = stringResource(R.string.restore_body),
        onBack = onBack,
        bottomBar = {
            if (found != null) {
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
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (found == null) {
                if (state.driveAvailable) {
                    SourceCard(
                        icon = R.drawable.ic_cloud_upload,
                        title = stringResource(R.string.restore_from_drive),
                        body = stringResource(R.string.restore_from_drive_body),
                        enabled = !state.busy,
                        onClick = viewModel::findInDrive,
                    )
                }
                SourceCard(
                    icon = R.drawable.ic_archive,
                    title = stringResource(R.string.restore_from_file),
                    body = stringResource(R.string.restore_from_file_body),
                    enabled = !state.busy,
                    onClick = { pickFile.launch(arrayOf("*/*")) },
                )
                if (state.searching) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.restore_searching), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                val context = LocalContext.current
                BackupCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(R.drawable.ic_history, container = colors.proteinContainer, content = colors.protein, size = 40.dp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.restore_found), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(
                                    R.string.restore_found_line,
                                    formatWhen(found.header.createdAtEpochMs),
                                    pluralStringResource(R.plurals.restore_meals, found.header.meals, found.header.meals),
                                    pluralStringResource(R.plurals.restore_foods, found.header.foods, found.header.foods),
                                    Formatter.formatShortFileSize(context, found.file.size.toLong()),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
                    isError = state.problem == RestoreProblem.WrongPassword,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = viewModel::reset, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.restore_other))
                }
            }

            state.problem?.let { problem ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(painterResource(R.drawable.ic_circle_alert), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(
                        when (problem) {
                            RestoreProblem.NoneInDrive -> stringResource(R.string.restore_none_in_drive)
                            RestoreProblem.WrongPassword -> stringResource(R.string.restore_wrong_password)
                            is RestoreProblem.BadFile -> stringResource(R.string.restore_bad_file, problem.reason)
                            RestoreProblem.Failed -> stringResource(R.string.restore_failed)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceCard(icon: Int, title: String, body: String, enabled: Boolean, onClick: () -> Unit) {
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
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
