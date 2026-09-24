package dev.ytosko.neutrino.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.ai.AiClient
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiModel
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.PhotoDetail
import dev.ytosko.neutrino.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Valid(val models: List<AiModel>, val recommended: String?) : KeyCheck
    data class Failed(val error: AiException) : KeyCheck
}

data class AiSetupUiState(
    val loaded: Boolean = false,
    val provider: AiProvider = AiProvider.Gemini,
    val keyInput: String = "",
    val keyVisible: Boolean = false,
    val hasSavedKey: Boolean = false,
    val check: KeyCheck = KeyCheck.Idle,
    val selectedModel: String? = null,
    val saving: Boolean = false,
    val photoDetail: PhotoDetail = PhotoDetail.Standard,
) {
    val canCheck: Boolean get() = (keyInput.isNotBlank() || hasSavedKey) && check !is KeyCheck.Checking
    val canSave: Boolean get() = check is KeyCheck.Valid && selectedModel != null && !saving
}

class AiSetupViewModel(
    private val settings: SettingsRepository,
    private val clients: Map<AiProvider, AiClient>,
    private val onSaved: suspend () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(AiSetupUiState())
    val state: StateFlow<AiSetupUiState> = _state.asStateFlow()

    private val _saved = Channel<Unit>(Channel.BUFFERED)
    /** Emits once each time settings are saved successfully. */
    val saved = _saved.receiveAsFlow()

    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            val current = settings.settings.first()
            load(current.activeProvider ?: AiProvider.Gemini)
        }
    }

    fun selectProvider(provider: AiProvider) {
        if (provider == _state.value.provider) return
        viewModelScope.launch { load(provider) }
    }

    fun onKeyChange(value: String) {
        // Keys never contain whitespace; strip anything picked up while pasting.
        val cleaned = value.filterNot(Char::isWhitespace)
        _state.update { it.copy(keyInput = cleaned, check = KeyCheck.Idle) }
    }

    fun toggleKeyVisibility() = _state.update { it.copy(keyVisible = !it.keyVisible) }

    fun selectModel(id: String) = _state.update { it.copy(selectedModel = id) }

    /** Saved immediately: it's a preference, not part of the key setup. */
    fun selectPhotoDetail(detail: PhotoDetail) {
        _state.update { it.copy(photoDetail = detail) }
        viewModelScope.launch { settings.setPhotoDetail(detail) }
    }

    fun checkKey() {
        val snapshot = _state.value
        if (!snapshot.canCheck) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.update { it.copy(check = KeyCheck.Checking) }
            val key = snapshot.keyInput.ifBlank { null } ?: settings.apiKey(snapshot.provider)
            if (key == null) {
                _state.update { it.copy(check = KeyCheck.Idle, hasSavedKey = false) }
                return@launch
            }
            try {
                val choices = clients.getValue(snapshot.provider).listModels(key)
                _state.update { state ->
                    val keep = state.selectedModel?.takeIf { id -> choices.models.any { it.id == id } }
                    state.copy(
                        check = KeyCheck.Valid(choices.models, choices.recommended),
                        selectedModel = keep ?: choices.recommended,
                    )
                }
            } catch (e: AiException) {
                _state.update { it.copy(check = KeyCheck.Failed(e)) }
            }
        }
    }

    fun save() {
        val snapshot = _state.value
        val model = snapshot.selectedModel
        if (!snapshot.canSave || model == null) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            settings.saveAiProvider(snapshot.provider, snapshot.keyInput.ifBlank { null }, model)
            onSaved()
            _state.update { it.copy(saving = false, keyVisible = false, hasSavedKey = true) }
            _saved.send(Unit)
        }
    }

    private suspend fun load(provider: AiProvider) {
        checkJob?.cancel()
        val current = settings.settings.first()
        // Show the saved key (masked until the eye icon is tapped) so it can be checked or edited.
        val savedKey = if (provider in current.providersWithKey) settings.apiKey(provider) else null
        val hasKey = savedKey != null
        _state.value = AiSetupUiState(
            loaded = true,
            provider = provider,
            keyInput = savedKey.orEmpty(),
            hasSavedKey = hasKey,
            selectedModel = current.models[provider],
            photoDetail = current.photoDetail,
        )
        // Re-validate a stored key so the model list is ready without re-typing it.
        if (hasKey) checkKey()
    }
}
