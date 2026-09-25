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
import kotlinx.coroutines.Job
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
class FoundBackup(val file: ByteArray, val header: BackupHeader, val source: RestoreSource)

/** Where the user chose to look. */
enum class RestorePlace { Phone, Drive }

/** One step of the restore flow; back from any step returns to [Choose]. */
sealed interface RestoreStep {
    data object Choose : RestoreStep
    data class Looking(val place: RestorePlace) : RestoreStep
    data class Found(val backup: FoundBackup) : RestoreStep
    /** Nothing there; [email] names the Google account that was checked. */
    data class NotFound(val place: RestorePlace, val email: String? = null) : RestoreStep
    data class Failed(val place: RestorePlace) : RestoreStep
}

data class RestoreUiState(
    val step: RestoreStep = RestoreStep.Choose,
    val password: String = "",
    val passwordVisible: Boolean = false,
    val restoring: Boolean = false,
    val problem: RestoreProblem? = null,
    val driveAvailable: Boolean = true,
)

/**
 * "Do you have a Neutrino backup?": the user picks where to look (this phone, or a Google account
 * via Google's picker), sees what's there, and can go back and try elsewhere as often as they
 * like. Nothing changes until they tap Restore.
 */
class RestoreViewModel(private val backups: BackupRepository) : ViewModel() {

    private val _state = MutableStateFlow(RestoreUiState(driveAvailable = backups.auth.isAvailable))
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    private val _consent = Channel<PendingIntent>(Channel.BUFFERED)
    val consent = _consent.receiveAsFlow()

    private val _restored = Channel<Unit>(Channel.BUFFERED)
    /** Emits once everything is restored. */
    val restored = _restored.receiveAsFlow()

    private var lookJob: Job? = null

    fun hasStorageAccess(): Boolean = backups.local.hasAccess()
    fun storageSettingsIntent() = backups.local.accessSettingsIntent()

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, problem = null) }
    fun togglePasswordVisible() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    /** Back to "where do you want to look?". Returns false when already there. */
    fun back(): Boolean {
        val current = _state.value
        if (current.restoring || current.step == RestoreStep.Choose) return false
        lookJob?.cancel()
        _state.update { it.copy(step = RestoreStep.Choose, password = "", passwordVisible = false, problem = null) }
        return true
    }

    /** Reads the phone backup. Needs storage access. */
    fun lookOnPhone() = look(RestorePlace.Phone) {
        val file = backups.readPhoneBackup()
        if (file == null) RestoreStep.NotFound(RestorePlace.Phone) else RestoreStep.Found(FoundBackup(file, backups.inspect(file), RestoreSource.Phone))
    }

    /** Opens Google's account picker, then looks in that account's Drive. */
    fun lookInDrive() {
        if (_state.value.restoring) return
        _state.update { it.copy(step = RestoreStep.Looking(RestorePlace.Drive), problem = null) }
        lookJob?.cancel()
        lookJob = viewModelScope.launch {
            try {
                when (val outcome = backups.auth.authorize(chooseAccount = true)) {
                    is GoogleDriveAuth.Outcome.Token -> _state.update { it.copy(step = driveStep(outcome.accessToken)) }
                    is GoogleDriveAuth.Outcome.NeedsConsent -> _consent.send(outcome.intent)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(step = RestoreStep.Failed(RestorePlace.Drive)) }
            }
        }
    }

    /** Result of Google's picker/consent; cancelling goes back to the choice. */
    fun onConsentResult(data: Intent?) {
        val token = backups.auth.tokenFromIntent(data)
        if (token == null) {
            _state.update { it.copy(step = RestoreStep.Choose) }
            return
        }
        look(RestorePlace.Drive) { driveStep(token) }
    }

    fun restore() {
        val current = _state.value
        val found = (current.step as? RestoreStep.Found)?.backup ?: return
        if (current.restoring || current.password.isEmpty()) return
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

    private suspend fun driveStep(token: String): RestoreStep {
        val email = backups.driveEmail(token)
        val file = backups.downloadFromDrive(token) ?: return RestoreStep.NotFound(RestorePlace.Drive, email)
        return RestoreStep.Found(FoundBackup(file, backups.inspect(file), RestoreSource.Drive(email)))
    }

    private fun look(place: RestorePlace, block: suspend () -> RestoreStep) {
        if (_state.value.restoring) return
        _state.update { it.copy(step = RestoreStep.Looking(place), password = "", problem = null) }
        lookJob?.cancel()
        lookJob = viewModelScope.launch {
            val step = try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                RestoreStep.Failed(place)
            }
            _state.update { it.copy(step = step) }
        }
    }
}
