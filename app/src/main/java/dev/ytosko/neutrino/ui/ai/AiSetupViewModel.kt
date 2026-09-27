package dev.ytosko.neutrino.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.ai.AiClient
import dev.ytosko.neutrino.data.ai.AiConfig
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiLineup
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
import java.util.UUID

sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Valid(val models: List<AiModel>, val recommended: String?) : KeyCheck
    data class Failed(val error: AiException) : KeyCheck
}

/** Another model of the same provider whose key can be reused: its name and the key's last characters. */
data class ExistingKey(val configId: String, val name: String, val tail: String)

data class AiSetupUiState(
    val loaded: Boolean = false,
    /** Editing an existing model (null: adding one). */
    val configId: String? = null,
    val provider: AiProvider = AiProvider.Gemini,
    val nameInput: String = "",
    /** The user typed the name; otherwise it follows the model. */
    val customName: Boolean = false,
    val keyInput: String = "",
    val keyVisible: Boolean = false,
    /** Which model's key is being reused, to say so under the field. */
    val keyFrom: String? = null,
    val existingKeys: List<ExistingKey> = emptyList(),
    val check: KeyCheck = KeyCheck.Idle,
    val selectedModel: String? = null,
    val saving: Boolean = false,
    val photoDetail: PhotoDetail = PhotoDetail.Standard,
) {
    val canCheck: Boolean get() = keyInput.isNotBlank() && check !is KeyCheck.Checking
    val canSave: Boolean get() = check is KeyCheck.Valid && selectedModel != null && !saving

    /** The chosen model's name as the provider shows it, e.g. "Gemini 3.8 Flash". */
    val modelDisplayName: String?
        get() = (check as? KeyCheck.Valid)?.models?.firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel
}

/**
 * One AI model's setup: provider, name, key (typed, or reused from another model of the same
 * provider), model and photo detail. [configId] edits an existing model; otherwise a new one is
 * added (the first becomes the Primary). [provider] is the provider picked for a new model.
 */
class AiSetupViewModel(
    private val settings: SettingsRepository,
    private val clients: Map<AiProvider, AiClient>,
    private val configId: String? = null,
    private val provider: AiProvider? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(AiSetupUiState())
    val state: StateFlow<AiSetupUiState> = _state.asStateFlow()

    private val _saved = Channel<Unit>(Channel.BUFFERED)
    /** Emits once each time the model is saved. */
    val saved = _saved.receiveAsFlow()

    private var checkJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    /** Only while adding (e.g. in onboarding): switching provider starts over. */
    fun selectProvider(provider: AiProvider) {
        if (provider == _state.value.provider || _state.value.configId != null) return
        viewModelScope.launch {
            val existing = existingKeys(provider, exceptId = null)
            _state.update { AiSetupUiState(loaded = true, provider = provider, existingKeys = existing) }
        }
    }

    fun onNameChange(value: String) {
        val name = value.take(40)
        // An empty name goes back to following the model.
        _state.update { it.copy(nameInput = name, customName = name.isNotBlank()) }
    }

    fun onKeyChange(value: String) {
        // Keys never contain whitespace; strip anything picked up while pasting.
        val cleaned = value.filterNot(Char::isWhitespace)
        _state.update { it.copy(keyInput = cleaned, keyFrom = null, check = KeyCheck.Idle) }
    }

    fun toggleKeyVisibility() = _state.update { it.copy(keyVisible = !it.keyVisible) }

    /** Fills in the key of another model of the same provider, then checks it. */
    fun useExistingKey(configId: String) {
        viewModelScope.launch {
            val key = settings.aiKey(configId) ?: return@launch
            val name = _state.value.existingKeys.firstOrNull { it.configId == configId }?.name
            _state.update { it.copy(keyInput = key, keyVisible = false, keyFrom = name, check = KeyCheck.Idle) }
            checkKey()
        }
    }

    fun selectModel(id: String) = _state.update { state ->
        val next = state.copy(selectedModel = id)
        // A name the user didn't type follows the model.
        if (state.customName) next else next.copy(nameInput = next.modelDisplayName.orEmpty())
    }

    fun selectPhotoDetail(detail: PhotoDetail) = _state.update { it.copy(photoDetail = detail) }

    fun checkKey() {
        val snapshot = _state.value
        if (!snapshot.canCheck) return
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.update { it.copy(check = KeyCheck.Checking) }
            try {
                val choices = clients.getValue(snapshot.provider).listModels(snapshot.keyInput)
                _state.update { state ->
                    val keep = state.selectedModel?.takeIf { id -> choices.models.any { it.id == id } }
                    val checked = state.copy(
                        check = KeyCheck.Valid(choices.models, choices.recommended),
                        selectedModel = keep ?: choices.recommended,
                    )
                    if (checked.customName) checked else checked.copy(nameInput = checked.modelDisplayName.orEmpty())
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
            val all = settings.settings.first().aiConfigs
            val existing = all.firstOrNull { it.id == snapshot.configId }
            val id = existing?.id ?: UUID.randomUUID().toString()
            val name = if (snapshot.customName && snapshot.nameInput.isNotBlank()) {
                snapshot.nameInput.trim()
            } else {
                AiLineup.autoName(all, snapshot.modelDisplayName ?: model, exceptId = id)
            }
            val config = (existing ?: AiConfig(id = id, provider = snapshot.provider.id, model = model, name = name)).copy(
                provider = snapshot.provider.id,
                model = model,
                name = name,
                customName = snapshot.customName && snapshot.nameInput.isNotBlank(),
                photoDetail = snapshot.photoDetail.id,
            )
            settings.saveAiConfig(config, snapshot.keyInput)
            _state.update { it.copy(saving = false, keyVisible = false, configId = id) }
            _saved.send(Unit)
        }
    }

    private suspend fun existingKeys(provider: AiProvider, exceptId: String?): List<ExistingKey> =
        settings.settings.first().aiConfigs
            .filter { it.provider == provider.id && it.id != exceptId }
            // One entry per key, so the same key isn't offered twice.
            .distinctBy { it.keyTail.ifEmpty { it.id } }
            .map { ExistingKey(it.id, it.name, it.keyTail) }

    private suspend fun load() {
        val all = settings.settings.first().aiConfigs
        val existing = all.firstOrNull { it.id == configId }
        if (existing == null) {
            val chosen = provider ?: AiProvider.Gemini
            _state.value = AiSetupUiState(loaded = true, provider = chosen, existingKeys = existingKeys(chosen, null))
            return
        }
        val chosen = existing.providerEnum ?: AiProvider.Gemini
        _state.value = AiSetupUiState(
            loaded = true,
            configId = existing.id,
            provider = chosen,
            nameInput = existing.name,
            customName = existing.customName,
            keyInput = settings.aiKey(existing.id).orEmpty(),
            existingKeys = existingKeys(chosen, existing.id),
            selectedModel = existing.model,
            photoDetail = existing.detail,
        )
        // Re-check the stored key so the model list is ready without re-typing it.
        if (_state.value.keyInput.isNotBlank()) checkKey()
    }
}
