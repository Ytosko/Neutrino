package dev.ytosko.neutrino.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiConfig
import dev.ytosko.neutrino.data.ai.AiLineup
import dev.ytosko.neutrino.data.ai.AiModel
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.ModelCatalog
import dev.ytosko.neutrino.data.ai.OpenRouterClient
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.ui.components.AlertButton
import dev.ytosko.neutrino.ui.components.AlertStyle
import dev.ytosko.neutrino.ui.components.IosAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The OpenRouter model in use that OpenRouter no longer offers, and what to switch to (if anything fits). */
data class GoneModel(val config: AiConfig, val replacement: AiModel?)

/**
 * Once per app start, before any photo: if an OpenRouter model is in use, checks that OpenRouter
 * still offers it. Listing models costs no request. Offline or no answer: skipped quietly.
 */
class OpenRouterCheckViewModel(
    private val settings: SettingsRepository,
    private val client: OpenRouterClient,
) : ViewModel() {

    private val _gone = MutableStateFlow<GoneModel?>(null)
    val gone: StateFlow<GoneModel?> = _gone.asStateFlow()

    init {
        if (!checked) {
            checked = true
            viewModelScope.launch { check() }
        }
    }

    private suspend fun check() {
        val config = settings.settings.first().aiConfigs.firstOrNull { it.inUse && it.providerEnum == AiProvider.OpenRouter } ?: return
        val key = settings.aiKey(config.id) ?: return
        val models = runCatching { client.models(key, fresh = true) }.getOrNull() ?: return
        if (models.any { it.id == config.model }) return
        // Never from free to paid: a free model, or a free plan, is replaced by a free one.
        val freeOnly = config.freePlan || config.model.endsWith(":free")
        _gone.value = GoneModel(config, ModelCatalog.openRouterPick(models, freeOnly))
    }

    /** Switches to the suggested model; an automatic name follows it, one the user typed stays. */
    fun confirm() {
        val gone = _gone.value ?: return
        val replacement = gone.replacement ?: return
        _gone.value = null
        viewModelScope.launch {
            val all = settings.settings.first().aiConfigs
            val name = if (gone.config.customName) gone.config.name else AiLineup.autoName(all, replacement.displayName, gone.config.id)
            settings.saveAiConfig(gone.config.copy(model = replacement.id, name = name), null)
        }
    }

    fun dismiss() {
        _gone.value = null
    }

    private companion object {
        /** Checked this app start already (the Home screen can be created again). */
        @Volatile var checked = false
    }
}

/** "This model isn't available anymore": Confirm switches, Choose manually opens its setup page. */
@Composable
fun OpenRouterGoneDialog(viewModel: OpenRouterCheckViewModel, onChoose: (configId: String) -> Unit) {
    val gone by viewModel.gone.collectAsStateWithLifecycle()
    val model = gone ?: return
    val replacement = model.replacement
    IosAlert(
        title = stringResource(R.string.openrouter_gone_title),
        message = if (replacement != null) {
            stringResource(R.string.openrouter_gone_body, model.config.name, replacement.displayName)
        } else {
            stringResource(R.string.openrouter_gone_body_none, model.config.name)
        },
        buttons = buildList {
            add(AlertButton(stringResource(R.string.openrouter_choose), AlertStyle.Cancel) {
                viewModel.dismiss()
                onChoose(model.config.id)
            })
            if (replacement != null) add(AlertButton(stringResource(R.string.openrouter_confirm), AlertStyle.Default) { viewModel.confirm() })
        },
        onDismiss = viewModel::dismiss,
    )
}
