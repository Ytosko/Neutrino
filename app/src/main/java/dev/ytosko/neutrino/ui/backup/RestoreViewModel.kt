package dev.ytosko.neutrino.ui.backup

import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.backup.BackupHeader
import dev.ytosko.neutrino.data.backup.BackupRepository
import dev.ytosko.neutrino.data.backup.CorruptBackupException
import dev.ytosko.neutrino.data.backup.GoogleDriveAuth
import dev.ytosko.neutrino.data.backup.RestoreSource
import dev.ytosko.neutrino.data.backup.WrongPasswordException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface RestoreProblem {
    data object WrongPassword : RestoreProblem
    data class BadFile(val reason: String) : RestoreProblem
    data object Failed : RestoreProblem
}

/** A backup found on the phone or in a Google account. */
class FoundBackup(val file: ByteArray, val header: BackupHeader, val source: RestoreSource) {
    val id: String get() = when (source) {
        RestoreSource.Phone -> "phone"
        is RestoreSource.Drive -> "drive:${source.email}"
    }
}

enum class CheckState { NotChecked, Checking, None, Found, Failed }

data class RestoreUiState(
    val found: List<FoundBackup> = emptyList(),
    val phone: CheckState = CheckState.NotChecked,
    val drive: CheckState = CheckState.NotChecked,
    /** Accounts checked that had no backup, e.g. "a@gmail.com". */
    val emptyAccounts: List<String> = emptyList(),
    val selectedId: String? = null,
    val password: String = "",
    val passwordVisible: Boolean = false,
    val restoring: Boolean = false,
    val problem: RestoreProblem? = null,
    val driveAvailable: Boolean = true,
) {
    val selected: FoundBackup? get() = found.firstOrNull { it.id == selectedId }
    val latestId: String? get() = found.takeIf { it.size > 1 }?.maxByOrNull { it.header.createdAtEpochMs }?.id
    val busy: Boolean get() = restoring || phone == CheckState.Checking || drive == CheckState.Checking
}

/**
 * First-launch restore: looks in the phone's backup folder and in the Google accounts the user
 * picks, lists every backup found (newest marked), and restores the one chosen.
 */
class RestoreViewModel(private val backups: BackupRepository) : ViewModel() {

    private val _state = MutableStateFlow(RestoreUiState(driveAvailable = backups.auth.isAvailable))
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    private val _consent = Channel<PendingIntent>(Channel.BUFFERED)
    val consent = _consent.receiveAsFlow()

    private val _restored = Channel<Unit>(Channel.BUFFERED)
    /** Emits once everything is restored. */
    val restored = _restored.receiveAsFlow()

    init {
        // With storage access already allowed (e.g. Android 9–10 or a kept permission), look straight away.
        if (backups.local.hasAccess()) checkPhone()
    }

    fun hasStorageAccess(): Boolean = backups.local.hasAccess()
    fun storageSettingsIntent() = backups.local.accessSettingsIntent()

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, problem = null) }
    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }
    fun select(id: String) = _state.update { if (it.restoring) it else it.copy(selectedId = id, password = "", problem = null) }

    /** Reads the fixed phone backup file. Needs storage access. */
    fun checkPhone() {
        if (_state.value.phone == CheckState.Checking) return
        _state.update { it.copy(phone = CheckState.Checking) }
        viewModelScope.launch {
            val result = runCatching { backups.readPhoneBackup()?.let { FoundBackup(it, backups.inspect(it), RestoreSource.Phone) } }
            _state.update { state ->
                val found = result.getOrNull()
                state.copy(
                    phone = when {
                        result.isFailure -> CheckState.Failed
                        found == null -> CheckState.None
                        else -> CheckState.Found
                    },
                ).withFound(found)
            }
        }
    }

    /** Opens Google's account picker ([anotherAccount] always shows it) and looks in that Drive. */
    fun checkDrive(anotherAccount: Boolean) {
        if (_state.value.drive == CheckState.Checking) return
        _state.update { it.copy(drive = CheckState.Checking) }
        viewModelScope.launch {
            try {
                when (val outcome = backups.auth.authorize(chooseAccount = anotherAccount)) {
                    is GoogleDriveAuth.Outcome.Token -> loadFromDrive(outcome.accessToken)
                    is GoogleDriveAuth.Outcome.NeedsConsent -> {
                        _state.update { it.copy(drive = driveSettled()) }
                        _consent.send(outcome.intent)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(drive = CheckState.Failed) }
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        val token = backups.auth.tokenFromIntent(data) ?: return
        _state.update { it.copy(drive = CheckState.Checking) }
        viewModelScope.launch {
            try {
                loadFromDrive(token)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(drive = CheckState.Failed) }
            }
        }
    }

    fun restore() {
        val current = _state.value
        val chosen = current.selected ?: return
        if (current.busy || current.password.isEmpty()) return
        _state.update { it.copy(restoring = true, problem = null) }
        viewModelScope.launch {
            try {
                backups.restore(chosen.file, current.password.toCharArray(), chosen.source)
                _state.update { it.copy(password = "") }
                _restored.send(Unit)
            } catch (_: WrongPasswordException) {
                _state.update { it.copy(problem = RestoreProblem.WrongPassword) }
            } catch (e: CorruptBackupException) {
                _state.update { it.copy(problem = RestoreProblem.BadFile(e.message.orEmpty())) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(problem = RestoreProblem.Failed) }
            } finally {
                _state.update { it.copy(restoring = false) }
            }
        }
    }

    private suspend fun loadFromDrive(token: String) {
        val email = backups.driveEmail(token)
        val file = backups.downloadFromDrive(token)
        _state.update { state ->
            if (file == null) {
                state.copy(
                    drive = driveSettled(state),
                    emptyAccounts = (state.emptyAccounts + (email ?: "")).distinct(),
                )
            } else {
                state.copy(drive = CheckState.Found).withFound(FoundBackup(file, backups.inspect(file), RestoreSource.Drive(email)))
            }
        }
    }

    private fun driveSettled(state: RestoreUiState = _state.value) =
        if (state.found.any { it.source is RestoreSource.Drive }) CheckState.Found else CheckState.None

    /** Adds (or replaces) a found backup and selects the newest when nothing is chosen yet. */
    private fun RestoreUiState.withFound(backup: FoundBackup?): RestoreUiState {
        if (backup == null) return this
        val list = (found.filterNot { it.id == backup.id } + backup).sortedByDescending { it.header.createdAtEpochMs }
        return copy(found = list, selectedId = selectedId ?: list.first().id)
    }
}
