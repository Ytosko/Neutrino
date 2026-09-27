package dev.ytosko.neutrino.ui.ai

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.runtime.key
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiConfig
import dev.ytosko.neutrino.data.ai.AiLineup
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.AiRole
import dev.ytosko.neutrino.data.settings.AppSettings
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.ui.components.AlertButton
import dev.ytosko.neutrino.ui.components.AlertStyle
import dev.ytosko.neutrino.ui.components.IconBadge
import dev.ytosko.neutrino.ui.components.IosAlert
import dev.ytosko.neutrino.ui.components.LiftedContextMenu
import dev.ytosko.neutrino.ui.components.MenuAction
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class AiModelsViewModel(private val settings: SettingsRepository) : ViewModel() {

    val appSettings: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun makePrimary(id: String) = change { AiLineup.makePrimary(it, id) }
    fun makeFallback(id: String) = change { AiLineup.makeFallback(it, id) }
    fun unset(id: String) = change { AiLineup.unset(it, id) }
    fun move(from: Int, to: Int) = change { AiLineup.move(it, from, to) }

    fun delete(id: String) {
        viewModelScope.launch { settings.deleteAiConfig(id) }
    }

    fun setVoiceEnabled(on: Boolean) {
        viewModelScope.launch { settings.setVoiceEnabled(on) }
    }

    fun setSpeakReplies(on: Boolean) {
        viewModelScope.launch { settings.setSpeakReplies(on) }
    }

    fun deleteVoice() {
        viewModelScope.launch { settings.deleteVoiceConfig() }
    }

    fun setCuisine(cuisine: String?) {
        viewModelScope.launch { settings.setCuisine(cuisine) }
    }

    fun setNotes(notes: String) {
        viewModelScope.launch { settings.setAiNotes(notes) }
    }

    private fun change(transform: (List<AiConfig>) -> List<AiConfig>) {
        viewModelScope.launch { settings.updateAiLineup(transform) }
    }
}

/**
 * Settings → AI models: the models set up, in answering order (Primary, then Fallback 1, 2, …).
 * + adds one; tap to edit; press and hold for Make primary / Make fallback / Unset / Delete; drag
 * the handle to reorder. The cuisine and notes hints apply to every model.
 */
@Composable
fun AiModelsScreen(
    viewModel: AiModelsViewModel,
    onBack: () -> Unit,
    onAdd: (AiProvider) -> Unit,
    onOpen: (configId: String) -> Unit,
    /** Sets up the voice model with this provider (there's only one voice model). */
    onAddVoice: (AiProvider) -> Unit,
    onOpenVoice: () -> Unit,
) {
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val configs = appSettings?.aiConfigs.orEmpty()
    val context = LocalContext.current
    val full = configs.size >= AiLineup.MAX_MODELS
    val fullMessage = pluralStringResource(R.plurals.ai_models_full, AiLineup.MAX_MODELS, AiLineup.MAX_MODELS)
    var picking by remember { mutableStateOf(false) }
    var pickingVoice by remember { mutableStateOf(false) }
    var deletingVoice by remember { mutableStateOf(false) }
    var lifted by remember { mutableStateOf<Pair<AiConfig, Rect>?>(null) }
    var deleting by remember { mutableStateOf<AiConfig?>(null) }

    SetupScaffold(
        title = stringResource(R.string.ai_models_title),
        subtitle = stringResource(R.string.ai_models_body),
        onBack = onBack,
        actions = {
            IconButton(onClick = { if (full) Toast.makeText(context, fullMessage, Toast.LENGTH_LONG).show() else picking = true }) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.ai_models_add))
            }
        },
    ) {
        if (appSettings == null) return@SetupScaffold
        if (configs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IconBadge(R.drawable.ic_sparkles, container = NeutrinoTheme.colors.violet.container, content = NeutrinoTheme.colors.violet.content, size = 64.dp)
                Text(stringResource(R.string.ai_models_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.ai_models_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = { picking = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ai_models_add)) }
            }
        } else {
            if (!appSettings!!.aiReady) {
                Text(
                    stringResource(R.string.ai_models_no_primary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = Spacing.sm),
                )
            }
            ReorderableModelList(
                configs = configs,
                hiddenId = lifted?.first?.id,
                onOpen = onOpen,
                onLongPress = { config, bounds -> lifted = config to bounds },
                onMove = viewModel::move,
            )
            Text(
                if (full) fullMessage else stringResource(R.string.ai_models_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
        VoiceSection(
            settings = appSettings!!,
            onEnabledChange = viewModel::setVoiceEnabled,
            onSpeakChange = viewModel::setSpeakReplies,
            onConfigure = { pickingVoice = true },
            onOpen = onOpenVoice,
            onDelete = { deletingVoice = true },
            modifier = Modifier.padding(top = Spacing.lg),
        )
        AiPreferences(
            cuisine = appSettings?.cuisine,
            notes = appSettings?.aiNotes.orEmpty(),
            onCuisineChange = viewModel::setCuisine,
            onNotesChange = viewModel::setNotes,
            modifier = Modifier.padding(top = Spacing.lg),
        )
    }

    if (picking) {
        ProviderPickerSheet(
            voice = false,
            onPick = { provider ->
                picking = false
                onAdd(provider)
            },
            onDismiss = { picking = false },
        )
    }

    if (pickingVoice) {
        ProviderPickerSheet(
            voice = true,
            onPick = { provider ->
                pickingVoice = false
                onAddVoice(provider)
            },
            onDismiss = { pickingVoice = false },
        )
    }

    if (deletingVoice) {
        val name = appSettings?.voiceConfig?.name.orEmpty()
        IosAlert(
            title = stringResource(R.string.ai_delete_title, name),
            message = stringResource(R.string.ai_delete_body),
            buttons = listOf(
                AlertButton(stringResource(R.string.home_cancel), AlertStyle.Cancel) { deletingVoice = false },
                AlertButton(stringResource(R.string.ai_delete), AlertStyle.Destructive) {
                    viewModel.deleteVoice()
                    deletingVoice = false
                },
            ),
            onDismiss = { deletingVoice = false },
        )
    }

    lifted?.let { (config, bounds) ->
        val role = AiLineup.roles(configs)[config.id] ?: AiRole.Unset
        val othersInUse = configs.count { it.inUse && it.id != config.id }
        val close = { lifted = null }
        val primaryLabel = stringResource(R.string.ai_make_primary)
        val fallbackLabel = stringResource(R.string.ai_make_fallback)
        val unsetLabel = stringResource(R.string.ai_unset)
        val deleteLabel = stringResource(R.string.ai_delete)
        val actions = buildList {
            if (role != AiRole.Primary) add(MenuAction(primaryLabel, R.drawable.ic_star_filled) { close(); viewModel.makePrimary(config.id) })
            // A lone Primary can't become a fallback: there'd be no Primary left.
            if (role == AiRole.Unset || (role == AiRole.Primary && othersInUse > 0)) {
                add(MenuAction(fallbackLabel, R.drawable.ic_arrow_down) { close(); viewModel.makeFallback(config.id) })
            }
            if (role != AiRole.Unset) add(MenuAction(unsetLabel, R.drawable.ic_ban) { close(); viewModel.unset(config.id) })
            add(MenuAction(deleteLabel, R.drawable.ic_trash, destructive = true) { close(); deleting = config })
        }
        LiftedContextMenu(bounds = bounds, actions = actions, onDismiss = close) {
            ModelRow(config, role, onClick = {}, onLongPress = null, dragHandle = null)
        }
    }

    deleting?.let { config ->
        IosAlert(
            title = stringResource(R.string.ai_delete_title, config.name),
            message = stringResource(R.string.ai_delete_body),
            buttons = listOf(
                AlertButton(stringResource(R.string.home_cancel), AlertStyle.Cancel) { deleting = null },
                AlertButton(stringResource(R.string.ai_delete), AlertStyle.Destructive) {
                    viewModel.delete(config.id)
                    deleting = null
                },
            ),
            onDismiss = { deleting = null },
        )
    }
}

/**
 * The models in one card; drag a row by its handle to move it. Rows are the same height, so a drag
 * past half a row swaps with the neighbour; the new order is saved when the finger lifts.
 */
@Composable
private fun ReorderableModelList(
    configs: List<AiConfig>,
    hiddenId: String?,
    onOpen: (String) -> Unit,
    onLongPress: (AiConfig, Rect) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val rowHeight = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    var order by remember(configs) { mutableStateOf(configs) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var startIndex by remember { mutableStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    val roles = AiLineup.roles(order)

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        order.forEachIndexed { index, config ->
            // Keyed by id so a row keeps its drag gesture while it moves through the list.
            key(config.id) {
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(start = Spacing.md + 40.dp + Spacing.md), color = MaterialTheme.colorScheme.outlineVariant)
                val dragging = config.id == draggingId
                Row(
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) offset else 0f
                            shadowElevation = if (dragging) 12f else 0f
                        }
                        .alpha(if (config.id == hiddenId) 0f else 1f),
                ) {
                    ModelRow(
                        config = config,
                        role = roles[config.id] ?: AiRole.Unset,
                        onClick = { onOpen(config.id) },
                        onLongPress = { bounds -> onLongPress(config, bounds) },
                        dragHandle = Modifier.pointerInput(config.id) {
                            detectDragGestures(
                                onDragStart = {
                                    draggingId = config.id
                                    startIndex = order.indexOfFirst { it.id == config.id }
                                    offset = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = {
                                    val end = order.indexOfFirst { it.id == draggingId }
                                    if (startIndex >= 0 && end >= 0 && end != startIndex) onMove(startIndex, end)
                                    draggingId = null
                                    offset = 0f
                                },
                                onDragCancel = {
                                    order = configs
                                    draggingId = null
                                    offset = 0f
                                },
                            ) { change, amount ->
                                change.consume()
                                offset += amount.y
                                val current = order.indexOfFirst { it.id == draggingId }
                                val steps = (offset / rowHeight).roundToInt()
                                val target = (current + steps).coerceIn(0, order.lastIndex)
                                if (target != current) {
                                    order = order.toMutableList().apply { add(target, removeAt(current)) }
                                    offset -= (target - current) * rowHeight
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelRow(
    config: AiConfig,
    role: AiRole,
    onClick: () -> Unit,
    onLongPress: ((Rect) -> Unit)?,
    dragHandle: Modifier?,
) {
    val haptics = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val provider = config.providerEnum ?: AiProvider.Gemini
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongPress?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it(bounds)
                    }
                },
            )
            .padding(start = Spacing.md, end = if (dragHandle != null) 0.dp else Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ProviderLogo(provider, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(config.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${provider.displayName} · ${config.model}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoleBadge(role)
        if (dragHandle != null) {
            Icon(
                painterResource(R.drawable.ic_grip),
                contentDescription = stringResource(R.string.ai_drag_to_reorder),
                tint = MaterialTheme.colorScheme.outline,
                modifier = dragHandle.size(48.dp).padding(12.dp),
            )
        }
    }
}

@Composable
private fun RoleBadge(role: AiRole) {
    val colors = NeutrinoTheme.colors
    val (text, container, content) = when (role) {
        AiRole.Primary -> Triple(stringResource(R.string.ai_role_primary), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        is AiRole.Fallback -> Triple(stringResource(R.string.ai_role_fallback, role.number), colors.violet.container, colors.violet.content)
        AiRole.Unset -> Triple(stringResource(R.string.ai_role_unset), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderPickerSheet(voice: Boolean, onPick: (AiProvider) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(if (voice) R.string.voice_pick_provider else R.string.ai_pick_provider), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = Spacing.xs))
            AiProvider.entries.forEach { provider ->
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(provider) }.padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        ProviderLogo(provider, size = 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(
                                    when (provider) {
                                        AiProvider.Gemini -> if (voice) R.string.voice_provider_gemini_note else R.string.ai_provider_gemini_note
                                        AiProvider.OpenAi -> if (voice) R.string.voice_provider_openai_note else R.string.ai_provider_openai_note
                                        AiProvider.Groq -> if (voice) R.string.voice_provider_groq_note else R.string.ai_provider_groq_note
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

private val ROW_HEIGHT = 72.dp

/**
 * Voice: off by default. On, it offers one voice model (speech to text), whether replies are read
 * aloud, and says plainly where the words go.
 */
@Composable
private fun VoiceSection(
    settings: AppSettings,
    onEnabledChange: (Boolean) -> Unit,
    onSpeakChange: (Boolean) -> Unit,
    onConfigure: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(bottom = Spacing.xs))
        SwitchLine(
            title = stringResource(R.string.voice_switch_title),
            body = stringResource(R.string.voice_switch_body),
            checked = settings.voiceEnabled,
            onChange = onEnabledChange,
        )
        if (!settings.voiceEnabled) return@Column
        val voice = settings.voiceConfig
        if (voice == null) {
            OutlinedButton(onClick = onConfigure, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.voice_configure))
            }
        } else {
            val provider = voice.providerEnum ?: AiProvider.Gemini
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .clickable(role = Role.Button, onClick = onOpen)
                        .padding(start = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    ProviderLogo(provider, size = 40.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(voice.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${provider.displayName} · ${voice.model}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.ic_trash), contentDescription = stringResource(R.string.ai_delete), tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        if (!settings.aiReady) {
            Text(stringResource(R.string.voice_needs_primary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        SwitchLine(
            title = stringResource(R.string.voice_speak_title),
            body = stringResource(R.string.voice_speak_body),
            checked = settings.speakReplies,
            onChange = onSpeakChange,
        )
        Text(
            stringResource(R.string.voice_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchLine(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
