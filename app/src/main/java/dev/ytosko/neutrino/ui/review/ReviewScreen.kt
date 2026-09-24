package dev.ytosko.neutrino.ui.review

import android.graphics.BitmapFactory
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.food.FoodUnit
import dev.ytosko.neutrino.domain.roundGrams
import dev.ytosko.neutrino.domain.roundKcal
import dev.ytosko.neutrino.ui.components.WobblingWand
import dev.ytosko.neutrino.ui.components.mealTypeLabel
import dev.ytosko.neutrino.ui.food.FoodIcon
import dev.ytosko.neutrino.ui.food.FoodSearchSheet
import dev.ytosko.neutrino.ui.food.FoodSearchViewModel
import dev.ytosko.neutrino.ui.food.formatQuantity
import dev.ytosko.neutrino.ui.food.unitLabel
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import dev.ytosko.neutrino.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the open search sheet will do with the picked food. */
private sealed interface SearchTarget {
    data object Add : SearchTarget
    data class Replace(val key: Long) : SearchTarget
}

@OptIn(ExperimentalMaterial3Api::class)
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
    var editingKey by remember { mutableStateOf<Long?>(null) }
    var showMealDetails by remember { mutableStateOf(false) }
    var showPhoto by remember { mutableStateOf(false) }
    LaunchedEffect(phase) { if (phase is ReviewPhase.Saved) onSaved(phase.syncedToHealthConnect) }

    fun openSearch(target: SearchTarget) {
        searchCount++
        searchTarget = target
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ReviewHeader(
                state = state,
                onBack = onBack,
                onNameChange = viewModel::setName,
                onDetails = { showMealDetails = true },
                onPhoto = { showPhoto = true },
                onSave = viewModel::save,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = Spacing.md,
                end = Spacing.md,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + Spacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val width = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            item(key = "totals") { TotalsBar(state, width) }

            when (phase) {
                ReviewPhase.Preparing, is ReviewPhase.Analyzing -> item(key = "status") {
                    AnalyzingBanner((phase as? ReviewPhase.Analyzing)?.model, width)
                }
                is ReviewPhase.Failed -> item(key = "status") {
                    FailureBanner(
                        reason = phase.reason,
                        onRetry = viewModel::analyze,
                        onManual = viewModel::enterManually,
                        onOpenAiSettings = onOpenAiSettings,
                        modifier = width,
                    )
                }
                else -> Unit
            }

            if (phase == ReviewPhase.Ready || phase == ReviewPhase.Saving) {
                item(key = "add") {
                    FilledTonalButton(
                        onClick = { openSearch(SearchTarget.Add) },
                        enabled = state.canAddItem,
                        contentPadding = PaddingValues(vertical = 0.dp, horizontal = Spacing.md),
                        modifier = width.heightIn(min = 44.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_plus), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(Spacing.xs))
                        Text(stringResource(if (state.canAddItem) R.string.review_add_food else R.string.review_max_items, MAX_ITEMS))
                    }
                }
                if (state.items.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.review_no_items),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = width.padding(horizontal = Spacing.xs),
                        )
                    }
                } else {
                    item(key = "items") {
                        Card(
                            modifier = width,
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                            border = CardDefaults.outlinedCardBorder(),
                        ) {
                            state.items.forEachIndexed { index, item ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                ItemRow(
                                    item = item,
                                    onChangeFood = { openSearch(SearchTarget.Replace(item.key)) },
                                    onEditAmount = { editingKey = item.key },
                                    onRemove = { viewModel.removeItem(item.key) },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "footer") { Footer(state, width) }
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

    editingKey?.let { key ->
        state.items.firstOrNull { it.key == key }?.let { item ->
            AmountDialog(
                item = item,
                onConfirm = { quantity, unit ->
                    viewModel.setPortion(key, quantity, unit)
                    editingKey = null
                },
                onDismiss = { editingKey = null },
            )
        } ?: run { editingKey = null }
    }

    if (showMealDetails) {
        MealDetailsDialog(
            state = state,
            onMealType = viewModel::setMealType,
            onDate = viewModel::setDate,
            onTime = viewModel::setTime,
            onDismiss = { showMealDetails = false },
        )
    }

    if (showPhoto) {
        state.photo?.let { PhotoDialog(it) { showPhoto = false } } ?: run { showPhoto = false }
    }
}

// ---- Header ---------------------------------------------------------------------------------

@Composable
private fun ReviewHeader(
    state: ReviewUiState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onDetails: () -> Unit,
    onPhoto: () -> Unit,
    onSave: () -> Unit,
) {
    val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = Spacing.xxs, end = Spacing.md, top = Spacing.xs, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.action_back))
            }
            MealThumbnail(state, onPhoto)
            Spacer(Modifier.size(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                val nameMissing = state.name.isBlank() && state.items.isNotEmpty()
                BasicTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                    decorationBox = { inner ->
                        Box {
                            if (state.name.isEmpty()) {
                                Text(
                                    stringResource(if (nameMissing) R.string.review_name_required else R.string.review_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = if (nameMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.review_edit_details), onClick = onDetails)
                        .padding(vertical = Spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${mealTypeLabel(state.mealType)} · ${state.eatenAt.format(dateFormatter)} · ${state.eatenAt.format(timeFormatter)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        painterResource(R.drawable.ic_chevron_down),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.size(Spacing.xs))
            Button(
                onClick = onSave,
                enabled = state.canSave,
                contentPadding = PaddingValues(horizontal = Spacing.md),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                if (state.phase == ReviewPhase.Saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(stringResource(R.string.review_save_short))
                }
            }
        }
    }
}

/** Photo if there is one; otherwise the wand while analysing, or the first food's icon. */
@Composable
private fun MealThumbnail(state: ReviewUiState, onPhoto: () -> Unit) {
    val bitmap = remember(state.photo) { state.photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (bitmap != null) Modifier.clickable(role = Role.Image, onClick = onPhoto) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(bitmap, stringResource(R.string.review_photo_desc), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            state.items.isNotEmpty() -> FoodIcon(state.items.first().food.category, size = 44.dp)
            else -> Icon(painterResource(R.drawable.ic_utensils), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

// ---- Totals & status ---------------------------------------------------------------------------

/** One slim strip: value over label for carbs, protein, fat and kcal. */
@Composable
private fun TotalsBar(state: ReviewUiState, modifier: Modifier) {
    val colors = NeutrinoTheme.colors
    val totals = state.nutrition
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = Spacing.xs),
    ) {
        TotalCell(grams(totals.carbsG), stringResource(R.string.macro_carbs), colors.carbs, Modifier.weight(1f))
        TotalCell(grams(totals.proteinG), stringResource(R.string.macro_protein), colors.protein, Modifier.weight(1f))
        TotalCell(grams(totals.fatG), stringResource(R.string.macro_fat), colors.fat, Modifier.weight(1f))
        TotalCell("${totals.calories.roundKcal()}", stringResource(R.string.macro_energy), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
    }
}

@Composable
private fun TotalCell(value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun grams(value: Double): String = "${value.roundGrams().let { if (it >= 100) it.toInt().toString() else it.toString().removeSuffix(".0") }}g"

@Composable
private fun AnalyzingBanner(model: String?, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        WobblingWand()
        Text(
            if (model == null) stringResource(R.string.review_preparing) else stringResource(R.string.review_analyzing, model),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun FailureBanner(
    reason: FailureReason,
    onRetry: () -> Unit,
    onManual: () -> Unit,
    onOpenAiSettings: () -> Unit,
    modifier: Modifier,
) {
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
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(Spacing.md)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_circle_alert), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            when (reason) {
                FailureReason.AiNotSetUp -> TextButton(onClick = onOpenAiSettings) { Text(stringResource(R.string.home_set_up)) }
                FailureReason.PhotoUnreadable -> Unit
                else -> TextButton(onClick = onRetry) { Text(stringResource(R.string.review_retry)) }
            }
            TextButton(onClick = onManual) { Text(stringResource(R.string.home_add_manually)) }
        }
    }
}

// ---- Item row ----------------------------------------------------------------------------------

/** Two compact lines: food name + kcal, then the amount chip and macros. */
@Composable
private fun ItemRow(item: ReviewItem, onChangeFood: () -> Unit, onEditAmount: () -> Unit, onRemove: () -> Unit) {
    val colors = NeutrinoTheme.colors
    val n = item.nutrition
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.sm, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FoodIcon(item.food.category, size = 30.dp)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.food.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.review_change_food), onClick = onChangeFood),
                )
                Text(
                    "${n.calories.roundKcal()} kcal",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AmountChip(item, onEditAmount)
                Text(
                    "C ${n.carbsG.roundGrams().fmt()} · P ${n.proteinG.roundGrams().fmt()} · F ${n.fatG.roundGrams().fmt()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
            Icon(
                painterResource(R.drawable.ic_x),
                contentDescription = stringResource(R.string.review_remove_item, item.food.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun Double.fmt(): String = toString().removeSuffix(".0")

@Composable
private fun AmountChip(item: ReviewItem, onClick: () -> Unit) {
    val invalid = item.grams == null
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (invalid) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.review_edit_amount), onClick = onClick)
            .heightIn(min = 28.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${item.quantityText.ifBlank { "?" }} ${unitLabel(item.unit)}",
            style = MaterialTheme.typography.labelMedium,
            color = if (invalid) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, modifier = Modifier.size(14.dp))
    }
}

// ---- Dialogs -----------------------------------------------------------------------------------

@Composable
private fun AmountDialog(item: ReviewItem, onConfirm: (Double, FoodUnit) -> Unit, onDismiss: () -> Unit) {
    var unit by remember { mutableStateOf(item.unit) }
    var text by remember { mutableStateOf(item.quantityText) }
    val quantity = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val step = when {
        unit == FoodUnit.Kilogram || unit == FoodUnit.Liter -> 0.1
        unit.isMass || unit.isVolume -> 10.0
        else -> 0.5
    }
    fun changeUnit(new: FoodUnit) {
        // Keep the same weight when switching unit (1 plate -> 250 g).
        val grams = quantity?.let { item.food.grams(it, unit) }
        val perUnit = item.food.grams(1.0, new)
        if (grams != null && perUnit != null && perUnit > 0) text = formatQuantity(roundForUnit(grams / perUnit, new))
        unit = new
    }
    val kcal = quantity?.let { item.food.nutrition(it, unit)?.calories?.roundKcal() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.food.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    OutlinedButton(onClick = { text = formatQuantity(((quantity ?: step) - step).coerceAtLeast(step)) }, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                        Icon(painterResource(R.drawable.ic_minus), contentDescription = stringResource(R.string.review_less))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                        singleLine = true,
                        isError = quantity == null,
                        textStyle = MaterialTheme.typography.titleLarge,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { text = formatQuantity((quantity ?: 0.0) + step) }, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                        Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.review_more))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    item.food.availableUnits.forEach { option ->
                        val grams = item.food.grams(1.0, option)
                        FilterChip(
                            selected = unit == option,
                            onClick = { changeUnit(option) },
                            leadingIcon = if (unit == option) { { CheckIcon() } } else null,
                            label = {
                                Text(
                                    if (option.isMass || option.isVolume || grams == null) unitLabel(option)
                                    else "${unitLabel(option)} · ${grams.roundGrams().fmt()} g",
                                )
                            },
                        )
                    }
                }
                if (kcal != null) {
                    Text("$kcal kcal", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { quantity?.let { onConfirm(it, unit) } }, enabled = quantity != null) {
                Text(stringResource(R.string.review_time_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MealDetailsDialog(
    state: ReviewUiState,
    onMealType: (MealType) -> Unit,
    onDate: (LocalDate) -> Unit,
    onTime: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val dateFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_details_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(stringResource(R.string.review_meal_type), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    listOf(MealType.Breakfast, MealType.Lunch, MealType.Snack, MealType.Dinner).forEach { type ->
                        FilterChip(
                            selected = state.mealType == type,
                            onClick = { onMealType(type) },
                            label = { Text(mealTypeLabel(type)) },
                            leadingIcon = if (state.mealType == type) { { CheckIcon() } } else null,
                        )
                    }
                }
                Text(stringResource(R.string.review_time), style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(Spacing.xs))
                        Text(state.eatenAt.format(dateFormatter))
                    }
                    OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_clock), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(Spacing.xs))
                        Text(state.eatenAt.format(timeFormatter))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_done)) } },
    )

    if (pickDate) {
        val today = LocalDate.now(state.eatenAt.zone)
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.eatenAt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // Meals can be logged up to 30 days back, never in the future.
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !date.isAfter(today) && !date.isBefore(today.minusDays(30))
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text(stringResource(R.string.review_time_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.home_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }

    if (pickTime) {
        val pickerState = rememberTimePickerState(
            initialHour = state.eatenAt.hour,
            initialMinute = state.eatenAt.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    onTime(LocalTime.of(pickerState.hour, pickerState.minute))
                    pickTime = false
                }) { Text(stringResource(R.string.review_time_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.home_cancel)) } },
            text = { TimePicker(state = pickerState) },
        )
    }
}

@Composable
private fun CheckIcon() {
    Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(16.dp))
}

@Composable
private fun PhotoDialog(jpeg: ByteArray, onDismiss: () -> Unit) {
    val bitmap = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() } ?: return
    Dialog(onDismissRequest = onDismiss) {
        Image(
            bitmap,
            stringResource(R.string.review_photo_desc),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onDismiss),
        )
    }
}

@Composable
private fun Footer(state: ReviewUiState, modifier: Modifier) {
    val usage = state.usage
    val text = buildString {
        if (usage != null) append(stringResource(R.string.review_tokens, state.model.orEmpty(), usage.total, usage.input, usage.output)).append(" · ")
        append(stringResource(R.string.review_disclaimer_short))
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs),
    )
}
