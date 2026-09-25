package dev.ytosko.neutrino.ui.review

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.food.FoodUnit
import dev.ytosko.neutrino.domain.roundGrams
import dev.ytosko.neutrino.domain.roundKcal
import dev.ytosko.neutrino.ui.components.WobblingWand
import dev.ytosko.neutrino.ui.components.mealTypeColors
import dev.ytosko.neutrino.ui.components.mealTypeIcon
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

/**
 * The meal editor used for photo scans and manual entry. Back is the system gesture/button;
 * Save sits in the header like the settings icon on Today.
 */
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    searchViewModel: @Composable (key: String, mealType: MealType) -> FoodSearchViewModel,
    onSaved: (syncedToHealthConnect: Boolean) -> Unit,
    onOpenAiSettings: () -> Unit,
    onDiscard: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val phase = state.phase
    var searchTarget by remember { mutableStateOf<SearchTarget?>(null) }
    var searchCount by remember { mutableIntStateOf(0) }
    var editingKey by remember { mutableStateOf<Long?>(null) }
    var showMealDetails by remember { mutableStateOf(false) }
    var showPhoto by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(phase) { if (phase is ReviewPhase.Saved) onSaved(phase.syncedToHealthConnect) }

    // Back closes the screen straight away when there's nothing to lose; otherwise ask first.
    val hasWork = if (state.editing) state.changed else state.items.isNotEmpty() || state.nameEditedByUser
    BackHandler(enabled = hasWork && phase !is ReviewPhase.Saved) { confirmDiscard = true }

    fun openSearch(target: SearchTarget) {
        searchCount++
        searchTarget = target
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ReviewHeader(
                state = state,
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
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val width = Modifier.widthIn(max = 640.dp).fillMaxWidth()
            item(key = "totals") { TotalsBar(state.nutrition, width) }

            when (phase) {
                ReviewPhase.Preparing, is ReviewPhase.Analyzing -> item(key = "status") {
                    AnalyzingBanner((phase as? ReviewPhase.Analyzing)?.model, width.padding(top = Spacing.xs))
                }
                is ReviewPhase.Failed -> item(key = "status") {
                    FailureBanner(phase.reason, viewModel::analyze, viewModel::enterManually, onOpenAiSettings, width.padding(top = Spacing.xs))
                }
                else -> Unit
            }

            if (phase == ReviewPhase.Ready || phase == ReviewPhase.Saving) {
                item(key = "add") {
                    FilledTonalButton(
                        onClick = { openSearch(SearchTarget.Add) },
                        enabled = state.canAddItem,
                        modifier = width.padding(vertical = Spacing.xs).heightIn(min = 48.dp),
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
                            textAlign = TextAlign.Center,
                            modifier = width.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        )
                    }
                }
                items(state.items, key = { it.key }) { item ->
                    ItemCard(item = item, onClick = { editingKey = item.key }, modifier = width)
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
        val item = state.items.firstOrNull { it.key == key }
        if (item == null) {
            editingKey = null
        } else {
            ItemDialog(
                item = item,
                onConfirm = { quantity, unit ->
                    viewModel.setPortion(key, quantity, unit)
                    editingKey = null
                },
                onChangeFood = {
                    editingKey = null
                    openSearch(SearchTarget.Replace(key))
                },
                onRemove = {
                    viewModel.removeItem(key)
                    editingKey = null
                },
                onDismiss = { editingKey = null },
            )
        }
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
        val photo = state.photo
        if (photo == null) showPhoto = false else PhotoDialog(photo) { showPhoto = false }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(if (state.editing) R.string.review_discard_changes_title else R.string.review_discard_title)) },
            text = { Text(stringResource(if (state.editing) R.string.review_discard_changes_body else R.string.review_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }) {
                    Text(stringResource(R.string.review_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.review_keep_editing)) }
            },
        )
    }
}

// ---- Header ---------------------------------------------------------------------------------

@Composable
private fun ReviewHeader(
    state: ReviewUiState,
    onNameChange: (String) -> Unit,
    onDetails: () -> Unit,
    onPhoto: () -> Unit,
    onSave: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val timeFormatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("d MMM") }
    val (typeColor, typeContainer) = mealTypeColors(state.mealType)
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = Spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            MealThumbnail(state, onPhoto)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val nameMissing = state.name.isBlank() && state.items.isNotEmpty()
                BasicTextField(
                    value = state.name,
                    onValueChange = { onNameChange(it.replace('\n', ' ')) },
                    maxLines = 2,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                    decorationBox = { inner ->
                        Box {
                            if (state.name.isEmpty()) {
                                Text(
                                    stringResource(if (nameMissing) R.string.review_name_required else R.string.review_name),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (nameMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                // Meal type · date · time, tinted in the meal's colour; opens the details pop-up.
                Row(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(typeContainer)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.review_edit_details), onClick = onDetails)
                        .heightIn(min = 32.dp)
                        .padding(start = 8.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(painterResource(mealTypeIcon(state.mealType)), contentDescription = null, tint = typeColor, modifier = Modifier.size(16.dp))
                    Text(
                        "${mealTypeLabel(state.mealType)} · ${state.eatenAt.format(dateFormatter)} · ${state.eatenAt.format(timeFormatter)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
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

/** Photo if there is one (tap to enlarge); otherwise the first food's icon. */
@Composable
private fun MealThumbnail(state: ReviewUiState, onPhoto: () -> Unit) {
    val bitmap = remember(state.photo) { state.photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (bitmap != null) Modifier.clickable(role = Role.Image, onClick = onPhoto) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(bitmap, stringResource(R.string.review_photo_desc), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            state.items.isNotEmpty() -> FoodIcon(state.items.first().food.category, size = 48.dp)
            else -> Icon(painterResource(R.drawable.ic_utensils), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

// ---- Totals & status ---------------------------------------------------------------------------

/** One slim strip: value over label for carbs, protein, fat and kcal. */
@Composable
private fun TotalsBar(totals: Nutrition, modifier: Modifier) {
    val colors = NeutrinoTheme.colors
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = Spacing.sm),
    ) {
        TotalCell(grams(totals.carbsG), stringResource(R.string.macro_carbs), colors.carbs, Modifier.weight(1f))
        TotalCell(grams(totals.proteinG), stringResource(R.string.macro_protein), colors.protein, Modifier.weight(1f))
        TotalCell(grams(totals.fatG), stringResource(R.string.macro_fat), colors.fat, Modifier.weight(1f))
        TotalCell("${totals.calories.roundKcal()}", stringResource(R.string.macro_energy), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
    }
}

@Composable
private fun TotalCell(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Same short format as the Today tiles, so totals match everywhere. */
private fun grams(value: Double): String = dev.ytosko.neutrino.domain.insights.compactGrams(value)

private fun Double.fmt(): String = toString().removeSuffix(".0")

@Composable
private fun AnalyzingBanner(model: String?, modifier: Modifier) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(NeutrinoTheme.colors.violet.container)
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
private fun FailureBanner(reason: FailureReason, onRetry: () -> Unit, onManual: () -> Unit, onOpenAiSettings: () -> Unit, modifier: Modifier) {
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
            TextButton(onClick = onManual) { Text(stringResource(R.string.review_manual)) }
        }
    }
}

// ---- Food cards --------------------------------------------------------------------------------

/** A food in the meal. Tap to change the amount, swap the food or remove it. */
@Composable
private fun ItemCard(item: ReviewItem, onClick: () -> Unit, modifier: Modifier) {
    val colors = NeutrinoTheme.colors
    val n = item.nutrition
    val invalid = item.grams == null
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            FoodIcon(item.food.category, size = 40.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.food.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        "${item.quantityText.ifBlank { "?" }} ${unitLabel(item.unit)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (invalid) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(if (invalid) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    MacroLetter("C", n.carbsG, colors.carbs)
                    MacroLetter("P", n.proteinG, colors.protein)
                    MacroLetter("F", n.fatG, colors.fat)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${n.calories.roundKcal()}", style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.macro_energy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MacroLetter(letter: String, grams: Double, color: Color) {
    Text("$letter ${grams.roundGrams().fmt()}", style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}

// ---- Dialogs -----------------------------------------------------------------------------------

/** Amount (stepper), unit (dropdown), live nutrition, plus change food / remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDialog(
    item: ReviewItem,
    onConfirm: (Double, FoodUnit) -> Unit,
    onChangeFood: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var unit by remember { mutableStateOf(item.unit) }
    var text by remember { mutableStateOf(item.quantityText) }
    var unitMenu by remember { mutableStateOf(false) }
    val quantity = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val step = when {
        unit == FoodUnit.Kilogram || unit == FoodUnit.Liter -> 0.1
        unit.isMass || unit.isVolume -> 10.0
        else -> 0.5
    }
    fun unitText(option: FoodUnit): String {
        val size = item.food.unitSize(option) ?: return ""
        return " · ${size.amount.roundGrams().fmt()} ${if (size.inMl) "ml" else "g"}"
    }
    val nutrition = quantity?.let { item.food.nutrition(it, unit) }
    val colors = NeutrinoTheme.colors

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { FoodIcon(item.food.category, size = 48.dp) },
        title = { Text(item.food.name, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilledTonalIconButton(onClick = { text = formatQuantity(((quantity ?: step) - step).coerceAtLeast(step)) }) {
                        Icon(painterResource(R.drawable.ic_minus), contentDescription = stringResource(R.string.review_less))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                        singleLine = true,
                        isError = quantity == null,
                        label = { Text(stringResource(R.string.review_amount)) },
                        textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalIconButton(onClick = { text = formatQuantity((quantity ?: 0.0) + step) }) {
                        Icon(painterResource(R.drawable.ic_plus), contentDescription = stringResource(R.string.review_more))
                    }
                }

                ExposedDropdownMenuBox(expanded = unitMenu, onExpandedChange = { unitMenu = it }) {
                    OutlinedTextField(
                        value = unitLabel(unit) + unitText(unit),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.review_unit)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        item.food.availableUnits.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(unitLabel(option) + unitText(option)) },
                                onClick = {
                                    // Keep the same weight when switching unit (1 plate -> 250 g).
                                    val grams = quantity?.let { item.food.grams(it, unit) }
                                    val perUnit = item.food.grams(1.0, option)
                                    if (grams != null && perUnit != null && perUnit > 0) text = formatQuantity(roundForUnit(grams / perUnit, option))
                                    unit = option
                                    unitMenu = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                }

                if (nutrition != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .padding(vertical = Spacing.sm),
                    ) {
                        TotalCell("${nutrition.calories.roundKcal()}", stringResource(R.string.macro_energy), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        TotalCell(grams(nutrition.carbsG), stringResource(R.string.macro_carbs), colors.carbs, Modifier.weight(1f))
                        TotalCell(grams(nutrition.proteinG), stringResource(R.string.macro_protein), colors.protein, Modifier.weight(1f))
                        TotalCell(grams(nutrition.fatG), stringResource(R.string.macro_fat), colors.fat, Modifier.weight(1f))
                    }
                }

                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onChangeFood) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.review_change_food))
                    }
                    TextButton(onClick = onRemove) {
                        Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.review_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { quantity?.let { onConfirm(it, unit) } }, enabled = quantity != null) {
                Text(stringResource(R.string.review_done))
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
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, d MMM") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_details_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.review_meal_type), style = MaterialTheme.typography.titleSmall)
                listOf(listOf(MealType.Breakfast, MealType.Lunch), listOf(MealType.Snack, MealType.Dinner)).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        row.forEach { type ->
                            MealTypeTile(type, selected = state.mealType == type, onClick = { onMealType(type) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text(stringResource(R.string.review_when), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    DetailTile(R.drawable.ic_calendar, stringResource(R.string.review_date), state.eatenAt.format(dateFormatter), { pickDate = true }, Modifier.weight(1f))
                    DetailTile(R.drawable.ic_clock, stringResource(R.string.review_time), state.eatenAt.format(timeFormatter), { pickTime = true }, Modifier.weight(1f))
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

/** Half-width tile in the meal's own colour; the selected one is filled and outlined. */
@Composable
private fun MealTypeTile(type: MealType, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val (color, container) = mealTypeColors(type)
    Surface(
        onClick = onClick,
        selected = selected,
        modifier = modifier.heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) container else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (selected) BorderStroke(2.dp, color) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(painterResource(mealTypeIcon(type)), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Text(
                mealTypeLabel(type),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DetailTile(icon: Int, label: String, value: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
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
        modifier = modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
    )
}
