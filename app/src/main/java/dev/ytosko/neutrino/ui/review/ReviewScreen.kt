package dev.ytosko.neutrino.ui.review

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.roundGrams
import dev.ytosko.neutrino.domain.roundKcal
import dev.ytosko.neutrino.ui.components.MacroStat
import dev.ytosko.neutrino.ui.components.SetupScaffold
import dev.ytosko.neutrino.ui.components.mealTypeLabel
import dev.ytosko.neutrino.ui.food.FoodIcon
import dev.ytosko.neutrino.ui.food.FoodSearchSheet
import dev.ytosko.neutrino.ui.food.FoodSearchViewModel
import dev.ytosko.neutrino.ui.food.unitLabel
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the open search sheet will do with the picked food. */
private sealed interface SearchTarget {
    data object Add : SearchTarget
    data class Replace(val key: Long) : SearchTarget
}

@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    searchViewModel: @Composable (key: String, mealType: MealType) -> FoodSearchViewModel,
    onBack: () -> Unit,
    onSaved: (syncedToHealthConnect: Boolean) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase = state.phase
    var searchTarget by remember { mutableStateOf<SearchTarget?>(null) }
    var searchCount by remember { mutableIntStateOf(0) }
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
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            state.photo?.let { MealPhoto(it) }
            when (phase) {
                ReviewPhase.Preparing -> Progress(stringResource(R.string.review_preparing))
                is ReviewPhase.Analyzing -> Progress(stringResource(R.string.review_analyzing, phase.model))
                is ReviewPhase.Failed -> FailureCard(phase.reason, viewModel::analyze, viewModel::enterManually, onOpenAiSettings)
                else -> ReviewForm(
                    state = state,
                    viewModel = viewModel,
                    onAddFood = { searchCount++; searchTarget = SearchTarget.Add },
                    onChangeFood = { key -> searchCount++; searchTarget = SearchTarget.Replace(key) },
                )
            }
        }
    }

    searchTarget?.let { target ->
        FoodSearchSheet(
            viewModel = searchViewModel("food-search-$searchCount", state.mealType),
            onPicked = { pick ->
                when (target) {
                    SearchTarget.Add -> viewModel.addItem(pick.food, pick.portion)
                    is SearchTarget.Replace -> viewModel.replaceFood(target.key, pick.food, pick.portion)
                }
                searchTarget = null
            },
            onDismiss = { searchTarget = null },
        )
    }
}

@Composable
private fun MealPhoto(jpeg: ByteArray) {
    val bitmap = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, stringResource(R.string.review_photo_desc), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            when (reason) {
                FailureReason.AiNotSetUp -> Button(onClick = onOpenAiSettings) { Text(stringResource(R.string.home_set_up)) }
                FailureReason.PhotoUnreadable -> Unit
                else -> OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(R.string.review_retry))
                }
            }
            TextButton(onClick = onManual, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.home_add_manually)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewForm(
    state: ReviewUiState,
    viewModel: ReviewViewModel,
    onAddFood: () -> Unit,
    onChangeFood: (Long) -> Unit,
) {
    val context = LocalContext.current
    var showTimePicker by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Section(stringResource(R.string.review_items)) {
            if (state.items.isEmpty()) {
                Text(stringResource(R.string.review_no_items), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.items.forEach { item ->
                ItemCard(
                    item = item,
                    onChangeFood = { onChangeFood(item.key) },
                    onQuantity = { viewModel.setQuantity(item.key, it) },
                    onUnit = { viewModel.setUnit(item.key, it) },
                    onRemove = { viewModel.removeItem(item.key) },
                )
            }
            FilledTonalButton(onClick = onAddFood, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.xs))
                Text(stringResource(R.string.review_add_food))
            }
        }

        if (state.items.isNotEmpty()) TotalsCard(state)

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text(stringResource(R.string.review_name)) },
            placeholder = { Text(state.displayName.ifBlank { stringResource(R.string.review_name_hint) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemCard(
    item: ReviewItem,
    onChangeFood: () -> Unit,
    onQuantity: (String) -> Unit,
    onUnit: (dev.ytosko.neutrino.domain.food.FoodUnit) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.review_change_food), onClick = onChangeFood)
                        .padding(Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    FoodIcon(item.food.category, size = 36.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.food.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${item.nutrition.calories.roundKcal()} kcal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        painterResource(R.drawable.ic_x),
                        contentDescription = stringResource(R.string.review_remove_item, item.food.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = item.quantityText,
                    onValueChange = onQuantity,
                    label = { Text(stringResource(R.string.review_amount)) },
                    singleLine = true,
                    isError = item.grams == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.weight(1.2f)) {
                    OutlinedTextField(
                        value = unitLabel(item.unit),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.review_unit)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        singleLine = true,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        item.food.availableUnits.forEach { unit ->
                            val grams = item.food.grams(1.0, unit)
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (unit.isMass || unit.isVolume || grams == null) unitLabel(unit)
                                        else "${unitLabel(unit)} (${grams.roundGrams().toString().removeSuffix(".0")} g)",
                                    )
                                },
                                onClick = {
                                    onUnit(unit)
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsCard(state: ReviewUiState) {
    val colors = NeutrinoTheme.colors
    val totals = state.nutrition
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(R.string.review_total), style = MaterialTheme.typography.titleSmall)
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                MacroStat(grams(totals.carbsG), stringResource(R.string.macro_carbs), colors.carbs, Modifier.weight(1f))
                MacroStat(grams(totals.proteinG), stringResource(R.string.macro_protein), colors.protein, Modifier.weight(1f))
                MacroStat(grams(totals.fatG), stringResource(R.string.macro_fat), colors.fat, Modifier.weight(1f))
                MacroStat("${totals.calories.roundKcal()}", stringResource(R.string.macro_energy), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            }
        }
    }
}

private fun grams(value: Double): String = "${value.roundGrams().let { if (it >= 100) it.toInt().toString() else it.toString().removeSuffix(".0") }}g"

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}
