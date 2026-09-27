package dev.ytosko.neutrino.ui.ai

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import dev.ytosko.neutrino.data.ai.AiModel
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import dev.ytosko.neutrino.ui.components.SegmentedControl
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.PhotoDetail
import dev.ytosko.neutrino.ui.theme.Spacing

/**
 * One AI model's form: (provider,) name, key, model and photo detail. The provider switch shows only
 * while adding one from onboarding; in Settings the provider was picked with +.
 */
@Composable
fun AiSetupForm(
    state: AiSetupUiState,
    showProviderSwitch: Boolean,
    onProviderChange: (AiProvider) -> Unit,
    onNameChange: (String) -> Unit,
    onUseExistingKey: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onToggleKeyVisibility: () -> Unit,
    onCheckKey: () -> Unit,
    onModelChange: (String) -> Unit,
    onPhotoDetailChange: (PhotoDetail) -> Unit,
    modifier: Modifier = Modifier,
    /** A voice model: no photo detail to choose. */
    voice: Boolean = false,
    /** OpenRouter's "I'm on a free plan". */
    onFreePlanChange: (Boolean) -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    var choosingKey by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (showProviderSwitch) {
            Text(stringResource(R.string.ai_provider), style = MaterialTheme.typography.titleSmall)
            SegmentedControl(
                options = AiProvider.entries.map { it.shortName },
                selected = AiProvider.entries.indexOf(state.provider),
                onSelect = { onProviderChange(AiProvider.entries[it]) },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                ProviderLogo(state.provider, size = 36.dp)
                Text(state.provider.displayName, style = MaterialTheme.typography.titleMedium)
            }
        }

        OutlinedTextField(
            value = state.nameInput,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.ai_config_name)) },
            placeholder = { Text(state.modelDisplayName ?: stringResource(R.string.ai_config_name_hint)) },
            supportingText = { Text(stringResource(if (state.customName) R.string.ai_config_name_custom else R.string.ai_config_name_auto)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )

        TextButton(
            onClick = { uriHandler.openUri(state.provider.keyUrl) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.ai_get_key, state.provider.displayName))
            Spacer(Modifier.size(Spacing.xs))
            Icon(painterResource(R.drawable.ic_external_link), contentDescription = null, modifier = Modifier.size(16.dp))
        }

        OutlinedTextField(
            value = state.keyInput,
            onValueChange = onKeyChange,
            label = { Text(stringResource(R.string.ai_key_label)) },
            placeholder = { Text(state.provider.keyPrefix) },
            singleLine = true,
            visualTransformation = if (state.keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onCheckKey() }),
            trailingIcon = {
                IconButton(onClick = onToggleKeyVisibility) {
                    Icon(
                        painterResource(if (state.keyVisible) R.drawable.ic_eye_off else R.drawable.ic_eye),
                        contentDescription = stringResource(if (state.keyVisible) R.string.ai_hide_key else R.string.ai_show_key),
                    )
                }
            },
            supportingText = {
                Text(
                    state.keyFrom?.let { stringResource(R.string.ai_key_from, it) }
                        ?: stringResource(R.string.ai_key_helper, state.provider.displayName),
                )
            },
            isError = state.check is KeyCheck.Failed,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.existingKeys.isNotEmpty()) {
            TextButton(
                onClick = {
                    // One other key: use it straight away. Several: let the user pick.
                    if (state.existingKeys.size == 1) onUseExistingKey(state.existingKeys.first().configId) else choosingKey = true
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_key), contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.ai_use_existing_key, state.provider.displayName))
            }
        }

        OutlinedButton(
            onClick = onCheckKey,
            enabled = state.canCheck,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            if (state.check is KeyCheck.Checking) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.ai_checking))
            } else {
                Text(stringResource(R.string.ai_check_key))
            }
        }

        KeyCheckStatus(state.check)

        if (state.isOpenRouter) OpenRouterPlan(state, onFreePlanChange)

        AnimatedVisibility(visible = state.check is KeyCheck.Valid) {
            if (state.models.isNotEmpty()) {
                ModelPicker(
                    models = state.models,
                    recommended = state.recommended,
                    selected = state.selectedModel,
                    onSelect = onModelChange,
                    helper = stringResource(
                        when {
                            voice -> R.string.voice_model_helper
                            state.isOpenRouter -> R.string.openrouter_model_helper
                            else -> R.string.ai_model_helper
                        },
                    ),
                )
            } else if (state.isOpenRouter) {
                Text(stringResource(R.string.openrouter_no_free_models), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }

        if (!voice) {
            Text(stringResource(R.string.ai_photo_detail), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
            SegmentedControl(
                options = PhotoDetail.entries.map { stringResource(if (it == PhotoDetail.Standard) R.string.ai_photo_standard else R.string.ai_photo_low) },
                selected = PhotoDetail.entries.indexOf(state.photoDetail),
                onSelect = { onPhotoDetailChange(PhotoDetail.entries[it]) },
            )
            Text(
                stringResource(R.string.ai_photo_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (choosingKey) {
        AlertDialog(
            onDismissRequest = { choosingKey = false },
            title = { Text(stringResource(R.string.ai_choose_key, state.provider.displayName)) },
            text = {
                Column {
                    state.existingKeys.forEach { key ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    choosingKey = false
                                    onUseExistingKey(key.configId)
                                }
                                .padding(vertical = Spacing.sm, horizontal = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            ProviderLogo(state.provider, size = 28.dp)
                            Column(Modifier.weight(1f)) {
                                Text(key.name, style = MaterialTheme.typography.bodyLarge)
                                if (key.tail.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.ai_key_ending, key.tail),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingKey = false }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
}

/** The provider's logo in a circle. */
@Composable
fun ProviderLogo(provider: AiProvider, size: androidx.compose.ui.unit.Dp = 40.dp) {
    androidx.compose.foundation.Image(
        painter = painterResource(
            when (provider) {
                AiProvider.Gemini -> R.drawable.ai_logo_gemini
                AiProvider.OpenAi -> R.drawable.ai_logo_openai
                AiProvider.Groq -> R.drawable.ai_logo_groq
                AiProvider.OpenRouter -> R.drawable.ai_logo_openrouter
            },
        ),
        contentDescription = provider.displayName,
        modifier = Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape),
    )
}

@Composable
private fun KeyCheckStatus(check: KeyCheck) {
    val (icon, text, color) = when (check) {
        is KeyCheck.Valid ->
            if (check.models.isEmpty()) Triple(R.drawable.ic_alert, stringResource(R.string.ai_no_models), MaterialTheme.colorScheme.error)
            else Triple(R.drawable.ic_check, stringResource(R.string.ai_key_ok, check.models.size), MaterialTheme.colorScheme.tertiary)
        is KeyCheck.Failed -> Triple(R.drawable.ic_alert, aiErrorMessage(check.error), MaterialTheme.colorScheme.error)
        else -> return
    }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
internal fun aiErrorMessage(error: AiException): String = when (error) {
    is AiException.InvalidKey -> stringResource(R.string.ai_error_invalid_key)
    is AiException.RateLimited -> stringResource(
        when (error.provider) {
            dev.ytosko.neutrino.data.ai.AiProvider.Groq -> R.string.ai_error_rate_limited_groq
            dev.ytosko.neutrino.data.ai.AiProvider.OpenRouter -> R.string.ai_error_rate_limited_openrouter
            else -> R.string.ai_error_rate_limited
        },
    )
    is AiException.ModelGone -> stringResource(R.string.ai_error_model_gone, error.model)
    is AiException.DataPolicy -> stringResource(R.string.ai_error_data_policy)
    is AiException.Network -> stringResource(R.string.ai_error_network)
    is AiException.Unexpected -> stringResource(R.string.ai_error_unexpected, error.code)
    is AiException.NoResult -> stringResource(R.string.ai_error_no_result)
    is AiException.TooLarge -> stringResource(R.string.ai_error_too_large)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    models: List<AiModel>,
    recommended: String?,
    selected: String?,
    onSelect: (String) -> Unit,
    helper: String,
) {
    var expanded by remember { mutableStateOf(false) }
    // Long lists (OpenRouter) can be searched: type in the field to narrow them down.
    val searchable = models.size > SEARCH_FROM
    var query by remember { mutableStateOf("") }
    val recommendedLabel = stringResource(R.string.ai_recommended)
    val freeLabel = stringResource(R.string.openrouter_free)
    fun label(model: AiModel): String = buildString {
        append(model.displayName)
        if (model.id == recommended) append(" · ").append(recommendedLabel)
        model.price?.let { append(" · ").append(it) }
    }
    val chosen = models.firstOrNull { it.id == selected }
    val shown = if (searchable && query.isNotBlank()) {
        models.filter { query.lowercase() in it.displayName.lowercase() || query.lowercase() in it.id.lowercase() }
    } else {
        models
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = if (searchable && expanded) query else chosen?.let(::label).orEmpty(),
                onValueChange = { query = it; expanded = true },
                readOnly = !searchable,
                label = { Text(stringResource(R.string.ai_model)) },
                placeholder = if (searchable) {
                    { Text(stringResource(R.string.openrouter_search_models)) }
                } else {
                    null
                },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(if (searchable) ExposedDropdownMenuAnchorType.PrimaryEditable else ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    query = ""
                },
            ) {
                shown.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model.displayName + if (model.id == recommended) " · $recommendedLabel" else "")
                                val detail = model.price ?: freeLabel.takeIf { model.free }
                                if (detail != null) {
                                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        onClick = {
                            onSelect(model.id)
                            expanded = false
                            query = ""
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        Text(
            helper,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
    }
}

private const val SEARCH_FROM = 8

/**
 * OpenRouter's plan: "I'm on a free plan" shows only free models and keeps requests within the
 * daily free limit; off, every photo model is offered with its price. Plus what's left today, and
 * where to control whether free providers may learn from requests.
 */
@Composable
private fun OpenRouterPlan(state: AiSetupUiState, onFreePlanChange: (Boolean) -> Unit) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .toggleable(value = state.freePlan, role = Role.Checkbox, onValueChange = onFreePlanChange)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Checkbox(checked = state.freePlan, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.openrouter_free_plan), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (state.freePlan) R.string.openrouter_free_plan_on else R.string.openrouter_free_plan_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.freeQuota?.let { quota ->
            Text(
                stringResource(R.string.openrouter_free_left, quota.remaining, quota.limit),
                style = MaterialTheme.typography.bodySmall,
                color = if (quota.remaining == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.sm),
            )
        }
        Text(
            stringResource(R.string.openrouter_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm),
        )
        TextButton(onClick = { uriHandler.openUri("https://openrouter.ai/settings/privacy") }) {
            Text(stringResource(R.string.openrouter_privacy_settings))
        }
    }
}
