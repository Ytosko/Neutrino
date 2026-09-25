package dev.ytosko.neutrino.ui.ai

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

/** Provider, key and model form. Hosted by onboarding and by Settings. */
@Composable
fun AiSetupForm(
    state: AiSetupUiState,
    onProviderChange: (AiProvider) -> Unit,
    onKeyChange: (String) -> Unit,
    onToggleKeyVisibility: () -> Unit,
    onCheckKey: () -> Unit,
    onModelChange: (String) -> Unit,
    onPhotoDetailChange: (PhotoDetail) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(stringResource(R.string.ai_provider), style = MaterialTheme.typography.titleSmall)
        SegmentedControl(
            options = AiProvider.entries.map { it.displayName },
            selected = AiProvider.entries.indexOf(state.provider),
            onSelect = { onProviderChange(AiProvider.entries[it]) },
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
                Text(stringResource(R.string.ai_key_helper, state.provider.displayName))
            },
            isError = state.check is KeyCheck.Failed,
            modifier = Modifier.fillMaxWidth(),
        )

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

        AnimatedVisibility(visible = state.check is KeyCheck.Valid) {
            val valid = state.check as? KeyCheck.Valid
            if (valid != null && valid.models.isNotEmpty()) {
                ModelPicker(
                    valid = valid,
                    selected = state.selectedModel,
                    onSelect = onModelChange,
                )
            }
        }

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

@Composable
private fun KeyCheckStatus(check: KeyCheck) {
    val (icon, text, color) = when (check) {
        is KeyCheck.Valid ->
            if (check.models.isEmpty()) Triple(R.drawable.ic_alert, stringResource(R.string.ai_no_models), MaterialTheme.colorScheme.error)
            else Triple(R.drawable.ic_check, stringResource(R.string.ai_key_ok, check.models.size), MaterialTheme.colorScheme.tertiary)
        is KeyCheck.Failed -> Triple(R.drawable.ic_alert, errorMessage(check.error), MaterialTheme.colorScheme.error)
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
private fun errorMessage(error: AiException): String = when (error) {
    is AiException.InvalidKey -> stringResource(R.string.ai_error_invalid_key)
    is AiException.RateLimited -> stringResource(R.string.ai_error_rate_limited)
    is AiException.Network -> stringResource(R.string.ai_error_network)
    is AiException.Unexpected -> stringResource(R.string.ai_error_unexpected, error.code)
    is AiException.NoResult -> stringResource(R.string.ai_error_no_result)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(valid: KeyCheck.Valid, selected: String?, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val recommendedLabel = stringResource(R.string.ai_recommended)
    fun label(id: String?): String {
        val model = valid.models.firstOrNull { it.id == id } ?: return ""
        return if (model.id == valid.recommended) "${model.displayName} · $recommendedLabel" else model.displayName
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = label(selected),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ai_model)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                valid.models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(label(model.id)) },
                        onClick = {
                            onSelect(model.id)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.ai_model_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md),
        )
    }
}
