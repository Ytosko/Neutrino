package dev.ytosko.neutrino.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.PromptHints
import dev.ytosko.neutrino.ui.theme.Spacing

/** Cuisines offered as recognition hints. Stored and sent as the English name. */
val CUISINES = listOf(
    "Bangladeshi", "Indian", "Pakistani", "Nepali", "Sri Lankan", "Chinese", "Thai", "Japanese", "Korean",
    "Indonesian", "Malaysian", "Middle Eastern", "Turkish", "Mediterranean", "Italian", "Mexican", "American", "British",
)

/**
 * Optional hints that help the AI read your food: the cuisine you usually eat and a short note.
 * They're added after Neutrino's own prompt, so they guide recognition without changing the reply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiPreferences(
    cuisine: String?,
    notes: String,
    onCuisineChange: (String?) -> Unit,
    onNotesChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val anyLabel = stringResource(R.string.ai_cuisine_any)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = Spacing.xs))
        Text(stringResource(R.string.ai_hints_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.ai_hints_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = cuisine ?: anyLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ai_cuisine)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                (listOf<String?>(null) + CUISINES).forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option ?: anyLabel) },
                        onClick = {
                            onCuisineChange(option)
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { onNotesChange(it.take(PromptHints.MAX_NOTES)) },
            label = { Text(stringResource(R.string.ai_notes)) },
            placeholder = { Text(stringResource(R.string.ai_notes_hint)) },
            supportingText = { Text("${notes.length} / ${PromptHints.MAX_NOTES}") },
            minLines = 2,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
