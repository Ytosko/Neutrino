package dev.ytosko.neutrino.ui.review

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.components.mealTypeLabel
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    onBack: () -> Unit,
    onSaved: (syncedToHealthConnect: Boolean) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase = state.phase
    LaunchedEffect(phase) { if (phase is ReviewPhase.Saved) onSaved(phase.syncedToHealthConnect) }

    SetupScaffold(
        title = stringResource(R.string.review_title),
        onBack = onBack,
        bottomBar = {
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (phase == ReviewPhase.Saving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(R.string.review_save))
                }
            }
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.review_retake))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            MealPhoto(state.photo)
            when (phase) {
                ReviewPhase.Preparing -> Progress(stringResource(R.string.review_preparing))
                is ReviewPhase.Analyzing -> Progress(stringResource(R.string.review_analyzing, phase.model))
                is ReviewPhase.Failed -> FailureCard(
                    reason = phase.reason,
                    onRetry = viewModel::analyze,
                    onManual = viewModel::enterManually,
                    onOpenAiSettings = onOpenAiSettings,
                )
                else -> ReviewForm(state, viewModel)
            }
        }
    }
}

@Composable
private fun MealPhoto(jpeg: ByteArray?) {
    val bitmap = remember(jpeg) { jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.review_photo_desc),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(painterResource(R.drawable.ic_image), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Progress(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailureCard(reason: FailureReason, onRetry: () -> Unit, onManual: () -> Unit, onOpenAiSettings: () -> Unit) {
    val message = when (reason) {
        FailureReason.AiNotSetUp -> stringResource(R.string.review_ai_not_set)
        FailureReason.NoFood -> stringResource(R.string.review_no_food)
        FailureReason.PhotoUnreadable -> stringResource(R.string.review_photo_error)
        is FailureReason.Ai -> when (val e = reason.error) {
            is AiException.InvalidKey -> stringResource(R.string.ai_error_invalid_key)
            is AiException.RateLimited -> stringResource(R.string.ai_error_rate_limited)
            is AiException.Network -> stringResource(R.string.ai_error_network)
            is AiException.Unexpected -> stringResource(R.string.ai_error_unexpected, e.code)
            is AiException.NoResult -> stringResource(R.string.ai_error_no_result)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(painterResource(R.drawable.ic_alert), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            when (reason) {
                FailureReason.AiNotSetUp -> Button(onClick = onOpenAiSettings) { Text(stringResource(R.string.home_set_up)) }
                FailureReason.PhotoUnreadable -> Unit
                else -> OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(R.string.review_retry))
                }
            }
            if (reason != FailureReason.PhotoUnreadable) {
                TextButton(onClick = onManual, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.review_manual)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewForm(state: ReviewUiState, viewModel: ReviewViewModel) {
    val context = LocalContext.current
    var showTimePicker by remember { mutableStateOf(false) }
    val colors = NeutrinoTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text(stringResource(R.string.review_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )

        Section(stringResource(R.string.review_meal_type)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                listOf(MealType.Breakfast, MealType.Lunch, MealType.Snack, MealType.Dinner).forEach { type ->
                    FilterChip(
                        selected = state.mealType == type,
                        onClick = { viewModel.setMealType(type) },
                        label = { Text(mealTypeLabel(type)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
            val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
            OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_clock), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text("${state.eatenAt.format(dateFormatter)} · ${state.eatenAt.format(timeFormatter)}")
            }
        }

        Section(stringResource(R.string.review_portion)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PORTIONS.forEach { portion ->
                    FilterChip(
                        selected = state.portion == portion,
                        onClick = { viewModel.setPortion(portion) },
                        label = { Text(portionLabel(portion)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }

        Section(stringResource(R.string.review_nutrition)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                NumberField(state, Macro.Calories, R.string.review_calories, MaterialTheme.colorScheme.onSurface, viewModel, Modifier.weight(1f))
                NumberField(state, Macro.Carbs, R.string.review_carbs, colors.carbs, viewModel, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                NumberField(state, Macro.Protein, R.string.review_protein, colors.protein, viewModel, Modifier.weight(1f))
                NumberField(state, Macro.Fat, R.string.review_fat, colors.fat, viewModel, Modifier.weight(1f))
            }
        }

        state.usage?.let { usage ->
            Text(
                stringResource(R.string.review_tokens, state.model.orEmpty(), usage.total, usage.input, usage.output),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.review_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = state.eatenAt.hour,
            initialMinute = state.eatenAt.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setTime(LocalTime.of(pickerState.hour, pickerState.minute))
                    showTimePicker = false
                }) { Text(stringResource(R.string.review_time_ok)) }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.home_cancel)) } },
            title = { Text(stringResource(R.string.review_time)) },
            text = { TimePicker(state = pickerState) },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun NumberField(
    state: ReviewUiState,
    macro: Macro,
    label: Int,
    color: androidx.compose.ui.graphics.Color,
    viewModel: ReviewViewModel,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = state.fields[macro].orEmpty(),
        onValueChange = { viewModel.setField(macro, it) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = color),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun portionLabel(portion: Double): String = when (portion) {
    0.5 -> "½×"
    1.5 -> "1½×"
    else -> "${portion.toInt()}×"
}
