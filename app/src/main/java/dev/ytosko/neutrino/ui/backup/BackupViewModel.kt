package dev.ytosko.neutrino.ui.backup

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.backup.BackupCrypto
import dev.ytosko.neutrino.data.backup.BackupFrequency
import dev.ytosko.neutrino.data.backup.BackupRepository
import dev.ytosko.neutrino.data.backup.BackupState
import dev.ytosko.neutrino.data.backup.GoogleDriveAuth
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BackupWork { Creating, BackingUp, ConnectingDrive, ChangingPassword }

enum class BackupMessage { BackedUp, BackedUpWithDrive, Failed, DriveCancelled, PasswordChanged }

data class BackupUiState(
    val loaded: Boolean = false,
    val backup: BackupState = BackupState(),
    val password: String = "",
    val confirm: String = "",
    val passwordVisible: Boolean = false,
    val work: BackupWork? = null,
    val driveAvailable: Boolean = true,
) {
    val passwordTooShort: Boolean get() = password.isNotEmpty() && password.length < BackupCrypto.MIN_PASSWORD_LENGTH
    val passwordMismatch: Boolean get() = confirm.isNotEmpty() && confirm != password
    val passwordValid: Boolean get() = password.length >= BackupCrypto.MIN_PASSWORD_LENGTH && password == confirm
}

/** Drives both the setup step and the Backup settings screen. */
class BackupViewModel(private val backups: BackupRepository) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState(driveAvailable = backups.auth.isAvailable))
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private val _consent = Channel<PendingIntent>(Channel.BUFFERED)
    /** Emits when Google needs the user to pick an account or approve Drive access. */
    val consent = _consent.receiveAsFlow()

    private val _messages = Channel<BackupMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            backups.state.collect { backup -> _state.update { it.copy(loaded = true, backup = backup) } }
        }
    }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }
    fun onConfirmChange(value: String) = _state.update { it.copy(confirm = value) }
    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    /** First backup: locks the key with the typed password, remembers [uri] and writes the file. */
    fun createBackup(uri: Uri) {
        val current = _state.value
        if (current.work != null) return
        val needsPassword = !current.backup.passwordSet
        if (needsPassword && !current.passwordValid) return
        run(BackupWork.Creating) {
            if (needsPassword) backups.setPassword(current.password.toCharArray())
            backups.setLocalTarget(uri)
            val result = backups.backUp()
            backups.schedule()
            _state.update { it.copy(password = "", confirm = "") }
            _messages.send(if (result.local) BackupMessage.BackedUp else BackupMessage.Failed)
        }
    }

    fun changeLocation(uri: Uri) = run(BackupWork.BackingUp) {
        backups.setLocalTarget(uri)
        _messages.send(if (backups.backUp().local) BackupMessage.BackedUp else BackupMessage.Failed)
    }

    fun backUpNow() = run(BackupWork.BackingUp) {
        val result = backups.backUp(forceDrive = true)
        _messages.send(
            when {
                !result.local && result.drive != true -> BackupMessage.Failed
                result.drive == true -> BackupMessage.BackedUpWithDrive
                else -> BackupMessage.BackedUp
            },
        )
    }

    fun connectDrive() = run(BackupWork.ConnectingDrive) {
        when (val outcome = backups.auth.authorize()) {
            is GoogleDriveAuth.Outcome.Token -> backups.connectDrive(outcome.accessToken)
            is GoogleDriveAuth.Outcome.NeedsConsent -> _consent.send(outcome.intent)
        }
    }

    /** Result of the Google consent screen. */
    fun onConsentResult(data: Intent?) {
        val token = backups.auth.tokenFromIntent(data)
        if (token == null) {
            viewModelScope.launch { _messages.send(BackupMessage.DriveCancelled) }
            return
        }
        run(BackupWork.ConnectingDrive) { backups.connectDrive(token) }
    }

    fun setFrequency(frequency: BackupFrequency) {
        viewModelScope.launch { backups.setFrequency(frequency) }
    }

    fun disconnectDrive() {
        viewModelScope.launch { backups.disconnectDrive() }
    }

    fun changePassword(password: String) = run(BackupWork.ChangingPassword) {
        backups.setPassword(password.toCharArray())
        backups.backUp()
        _messages.send(BackupMessage.PasswordChanged)
    }

    private fun run(work: BackupWork, block: suspend () -> Unit) {
        if (_state.value.work != null) return
        _state.update { it.copy(work = work) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(BackupMessage.Failed)
            } finally {
                _state.update { it.copy(work = null) }
            }
        }
    }
}
