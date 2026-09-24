package dev.ytosko.neutrino.ui.backup

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
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
    data object NoneInDrive : RestoreProblem
    data object WrongPassword : RestoreProblem
    data class BadFile(val reason: String) : RestoreProblem
    data object Failed : RestoreProblem
}

data class FoundBackup(val file: ByteArray, val header: BackupHeader, val source: RestoreSource) {
    override fun equals(other: Any?) = other is FoundBackup && other.header == header && other.source == source
    override fun hashCode() = header.hashCode()
}

data class RestoreUiState(
    val searching: Boolean = false,
    val restoring: Boolean = false,
    val found: FoundBackup? = null,
    val password: String = "",
    val passwordVisible: Boolean = false,
    val problem: RestoreProblem? = null,
    val driveAvailable: Boolean = true,
) {
    val busy: Boolean get() = searching || restoring
}

class RestoreViewModel(private val backups: BackupRepository) : ViewModel() {

    private val _state = MutableStateFlow(RestoreUiState(driveAvailable = backups.auth.isAvailable))
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    private val _consent = Channel<PendingIntent>(Channel.BUFFERED)
    val consent = _consent.receiveAsFlow()

    private val _restored = Channel<Unit>(Channel.BUFFERED)
    /** Emits once everything is restored. */
    val restored = _restored.receiveAsFlow()

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, problem = null) }
    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }
    fun reset() = _state.update { it.copy(found = null, password = "", problem = null) }

    fun findInDrive() = search {
        when (val outcome = backups.auth.authorize()) {
            is GoogleDriveAuth.Outcome.Token -> loadFromDrive(outcome.accessToken)
            is GoogleDriveAuth.Outcome.NeedsConsent -> _consent.send(outcome.intent)
        }
    }

    fun onConsentResult(data: Intent?) {
        val token = backups.auth.tokenFromIntent(data) ?: return
        search { loadFromDrive(token) }
    }

    fun openFile(uri: Uri) = search {
        val file = backups.readFile(uri)
        _state.update { it.copy(found = FoundBackup(file, backups.inspect(file), RestoreSource.File(uri))) }
    }

    fun restore() {
        val current = _state.value
        val found = current.found ?: return
        if (current.busy || current.password.isEmpty()) return
        _state.update { it.copy(restoring = true, problem = null) }
        viewModelScope.launch {
            try {
                backups.restore(found.file, current.password.toCharArray(), found.source)
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
        val file = backups.downloadFromDrive(token)
        if (file == null) {
            _state.update { it.copy(problem = RestoreProblem.NoneInDrive) }
            return
        }
        val email = backups.driveEmail(token)
        _state.update { it.copy(found = FoundBackup(file, backups.inspect(file), RestoreSource.Drive(email))) }
    }

    private fun search(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(searching = true, problem = null, found = null, password = "") }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CorruptBackupException) {
                _state.update { it.copy(problem = RestoreProblem.BadFile(e.message.orEmpty())) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(problem = RestoreProblem.Failed) }
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
    }
}
