package dev.ytosko.neutrino.ui.backup

import dev.ytosko.neutrino.ui.components.NeutrinoSnackbarHost
import dev.ytosko.neutrino.ui.components.SegmentedControl
import dev.ytosko.neutrino.ui.components.AlertStyle
import dev.ytosko.neutrino.ui.components.AlertButton
import dev.ytosko.neutrino.ui.components.IosAlert
import android.app.Activity
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.backup.BackupCrypto
import dev.ytosko.neutrino.data.backup.BackupFrequency
import dev.ytosko.neutrino.data.backup.DriveProblem
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing

/** Setup step 3 of 3: a required encrypted backup file, then an optional Google Drive copy. */
@Composable
fun BackupSetupScreen(
    viewModel: BackupViewModel,
    step: Pair<Int, Int>,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = rememberBackupActions(viewModel)
    val configured = state.backup.configured

    SetupScaffold(
        title = stringResource(R.string.backup_title),
        subtitle = stringResource(R.string.backup_body),
        onBack = onBack,
        step = step,
        bottomBar = {
            if (configured) {
                Button(onClick = onFinish, enabled = state.work == null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.backup_finish))
                }
            } else {
                CreateBackupButton(state, actions::createBackup)
            }
            NeutrinoSnackbarHost(actions.snackbar)
        },
    ) {
        if (!state.loaded) return@SetupScaffold
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (!configured) {
                if (!state.backup.passwordSet) PasswordFields(state, viewModel)
                Text(stringResource(R.string.backup_where), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LocalBackupCard(state, onBackUpNow = null, onAllowAccess = actions::createBackup)
                DriveSection(state, viewModel)
            }
            SecurityNote()
        }
    }
}

/** Settings → Backup. Shows the setup form until a backup exists. */
@Composable
fun BackupSettingsScreen(viewModel: BackupViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions = rememberBackupActions(viewModel)
    var changingPassword by remember { mutableStateOf(false) }
    val backup = state.backup

    SetupScaffold(
        title = stringResource(R.string.backup_settings_title),
        subtitle = if (backup.configured) null else stringResource(R.string.backup_body),
        onBack = onBack,
        bottomBar = {
            if (state.loaded && !backup.configured) CreateBackupButton(state, actions::createBackup)
            NeutrinoSnackbarHost(actions.snackbar)
        },
    ) {
        if (!state.loaded) return@SetupScaffold
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            when {
                !backup.passwordSet -> {
                    PasswordFields(state, viewModel)
                    Text(stringResource(R.string.backup_where), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Password known (e.g. after a Drive restore) but storage access is off.
                !backup.configured -> Text(stringResource(R.string.backup_where), style = MaterialTheme.typography.bodyMedium)
                else -> {
                    LocalBackupCard(state, onBackUpNow = viewModel::backUpNow, onAllowAccess = actions::createBackup)
                    DriveSection(state, viewModel)
                    OutlinedButton(
                        onClick = { changingPassword = true },
                        enabled = state.work == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_lock), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(Spacing.xs))
                        Text(stringResource(R.string.backup_change_password))
                    }
                }
            }
            SecurityNote()
        }
    }

    if (changingPassword) {
        ChangePasswordDialog(
            onConfirm = {
                changingPassword = false
                viewModel.changePassword(it)
            },
            onDismiss = { changingPassword = false },
        )
    }
}

// ---- Launchers and messages -------------------------------------------------------------------

/** Holds storage access and the Google consent launcher, and turns messages into snackbars. */
private class BackupActions(val snackbar: SnackbarHostState, private val start: () -> Unit) {
    /** Makes sure Neutrino can write its backup folder, then backs up. */
    fun createBackup() = start()
}

@Composable
private fun rememberBackupActions(viewModel: BackupViewModel): BackupActions {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current

    // Storage access can change in system settings while this screen is in the background.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }
    val withAccess = rememberStorageAccess(
        hasAccess = viewModel::hasStorageAccess,
        settingsIntent = viewModel::storageSettingsIntent,
        onChecked = viewModel::refreshAccess,
    )
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(if (result.resultCode == Activity.RESULT_OK) result.data else null)
    }
    LaunchedEffect(viewModel) {
        viewModel.consent.collect { consent.launch(IntentSenderRequest.Builder(it).build()) }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(
                resources.getString(
                    when (message) {
                        BackupMessage.BackedUp -> R.string.backup_done
                        BackupMessage.BackedUpWithDrive -> R.string.backup_done_drive
                        BackupMessage.Failed -> R.string.backup_failed
                        BackupMessage.DriveCancelled -> R.string.backup_drive_cancelled
                        BackupMessage.PasswordChanged -> R.string.backup_password_changed
                    },
                ),
            )
        }
    }
    return BackupActions(snackbar) { withAccess { viewModel.createBackup() } }
}

// ---- Pieces ------------------------------------------------------------------------------------

@Composable
private fun CreateBackupButton(state: BackupUiState, onClick: () -> Unit) {
    val ready = state.backup.passwordSet || state.passwordValid
    Button(
        onClick = onClick,
        enabled = ready && state.work == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        if (state.work != null) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.size(Spacing.xs))
            Text(stringResource(R.string.backup_working))
        } else {
            Icon(painterResource(R.drawable.ic_archive), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(Spacing.xs))
            Text(stringResource(if (state.backup.passwordSet) R.string.backup_allow_storage else R.string.backup_create))
        }
    }
}

@Composable
private fun PasswordFields(state: BackupUiState, viewModel: BackupViewModel) {
    val transformation = if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation()
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next)
    val toggle: @Composable () -> Unit = {
        IconButton(onClick = viewModel::togglePasswordVisible) {
            Icon(
                painterResource(if (state.passwordVisible) R.drawable.ic_eye_off else R.drawable.ic_eye),
                contentDescription = stringResource(if (state.passwordVisible) R.string.backup_hide_password else R.string.backup_show_password),
            )
        }
    }
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = { Text(stringResource(R.string.backup_password)) },
        singleLine = true,
        visualTransformation = transformation,
        keyboardOptions = keyboard,
        trailingIcon = toggle,
        isError = state.passwordTooShort,
        supportingText = {
            Text(
                if (state.passwordTooShort) stringResource(R.string.backup_password_short, BackupCrypto.MIN_PASSWORD_LENGTH)
                else stringResource(R.string.backup_password_helper, BackupCrypto.MIN_PASSWORD_LENGTH),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.confirm,
        onValueChange = viewModel::onConfirmChange,
        label = { Text(stringResource(R.string.backup_password_confirm)) },
        singleLine = true,
        visualTransformation = transformation,
        keyboardOptions = keyboard.copy(imeAction = ImeAction.Done),
        isError = state.passwordMismatch,
        supportingText = if (state.passwordMismatch) {
            { Text(stringResource(R.string.backup_password_mismatch)) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LocalBackupCard(state: BackupUiState, onBackUpNow: (() -> Unit)?, onAllowAccess: () -> Unit) {
    val backup = state.backup
    val colors = NeutrinoTheme.colors
    val problem = backup.localFailed || !backup.storageAccess
    BackupCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            if (problem) {
                IconBadge(R.drawable.ic_circle_alert, container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.error, size = 40.dp)
            } else {
                IconBadge(R.drawable.ic_smartphone, container = colors.proteinContainer, content = colors.protein, size = 40.dp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(if (onBackUpNow == null && !problem) R.string.backup_saved_title else R.string.backup_on_phone),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    backupLine(backup.lastLocalAt, backup.lastSizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (problem) {
            Text(stringResource(R.string.backup_local_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            FilledTonalButton(onClick = onAllowAccess, enabled = state.work == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_allow_storage))
            }
        } else if (onBackUpNow != null) {
            FilledTonalButton(onClick = onBackUpNow, enabled = state.work == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (state.work == BackupWork.BackingUp) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.backup_now))
                }
            }
        }
    }
}

@Composable
private fun DriveSection(state: BackupUiState, viewModel: BackupViewModel) {
    val backup = state.backup
    var confirmDisconnect by remember { mutableStateOf(false) }
    val colors = NeutrinoTheme.colors
    BackupCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_cloud_upload, container = colors.waterContainer, content = colors.water, size = 40.dp)
            Column(modifier = Modifier.weight(1f)) {
                if (backup.driveConnected) {
                    Text(stringResource(R.string.backup_drive), style = MaterialTheme.typography.titleSmall)
                    Text(
                        backup.driveEmail?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.backup_drive_account),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        backup.lastDriveAt?.let { stringResource(R.string.backup_drive_last, formatWhen(it)) } ?: stringResource(R.string.backup_never),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(stringResource(R.string.backup_drive_pitch), style = MaterialTheme.typography.titleSmall)
                }
            }
        }

        when {
            !state.driveAvailable && !backup.driveConnected ->
                Text(stringResource(R.string.backup_drive_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !backup.driveConnected -> {
                Text(stringResource(R.string.backup_drive_pitch_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(
                    onClick = viewModel::connectDrive,
                    enabled = state.work == null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    if (state.work == BackupWork.ConnectingDrive) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.backup_drive_connect))
                    }
                }
            }
            else -> {
                when (backup.driveProblem) {
                    DriveProblem.SignInNeeded -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.backup_drive_sign_in), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        FilledTonalButton(onClick = viewModel::connectDrive, enabled = state.work == null, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.backup_drive_connect))
                        }
                    }
                    DriveProblem.Failed ->
                        Text(stringResource(R.string.backup_drive_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    null -> Unit
                }
                AnimatedVisibility(visible = state.work == BackupWork.ConnectingDrive) {
                    Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.backup_drive_frequency), style = MaterialTheme.typography.labelLarge)
                val options = BackupFrequency.entries
                SegmentedControl(
                    options = options.map {
                        stringResource(
                            when (it) {
                                BackupFrequency.Daily -> R.string.backup_daily
                                BackupFrequency.Weekly -> R.string.backup_weekly
                                BackupFrequency.Monthly -> R.string.backup_monthly
                            },
                        )
                    },
                    selected = options.indexOf(backup.driveFrequency).coerceAtLeast(0),
                    onSelect = { viewModel.setFrequency(options[it]) },
                )
                TextButton(onClick = { confirmDisconnect = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_drive_disconnect), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDisconnect) {
        IosAlert(
            title = stringResource(R.string.backup_drive_disconnect_title),
            message = stringResource(R.string.backup_drive_disconnect_body),
            buttons = listOf(
                AlertButton(stringResource(R.string.backup_cancel), AlertStyle.Cancel) { confirmDisconnect = false },
                AlertButton(stringResource(R.string.backup_drive_disconnect), AlertStyle.Destructive) {
                    confirmDisconnect = false
                    viewModel.disconnectDrive()
                },
            ),
            onDismiss = { confirmDisconnect = false },
        )
    }
}

@Composable
private fun ChangePasswordDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < BackupCrypto.MIN_PASSWORD_LENGTH
    val mismatch = confirm.isNotEmpty() && confirm != password
    val valid = password.length >= BackupCrypto.MIN_PASSWORD_LENGTH && password == confirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.backup_change_password_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = tooShort,
                    supportingText = { Text(stringResource(R.string.backup_password_short, BackupCrypto.MIN_PASSWORD_LENGTH)) },
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.backup_password_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.backup_password_mismatch)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }, enabled = valid) { Text(stringResource(R.string.backup_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.backup_cancel)) } },
    )
}

@Composable
private fun SecurityNote() {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.xs)) {
        Icon(
            painterResource(R.drawable.ic_shield_check),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(stringResource(R.string.backup_security_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun BackupCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) { content() }
    }
}

@Composable
private fun backupLine(at: Long?, size: Long?): String {
    val context = LocalContext.current
    if (at == null) return stringResource(R.string.backup_never)
    val whenText = formatWhen(at)
    return if (size != null) stringResource(R.string.backup_file_line, whenText, Formatter.formatShortFileSize(context, size)) else whenText
}

/** "Today, 3:12 AM"-style text for a backup time. */
@Composable
internal fun formatWhen(epochMs: Long): String {
    val context = LocalContext.current
    if (System.currentTimeMillis() - epochMs in 0 until DateUtils.MINUTE_IN_MILLIS) return stringResource(R.string.backup_just_now)
    return DateUtils.getRelativeDateTimeString(context, epochMs, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString()
}
