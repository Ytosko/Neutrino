package dev.ytosko.neutrino.ui.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.domain.food.Food
import dev.ytosko.neutrino.domain.food.FoodSource
import dev.ytosko.neutrino.domain.roundKcal
import dev.ytosko.neutrino.ui.components.mealTypeLabel
import dev.ytosko.neutrino.ui.theme.Spacing

/**
 * Pop-up food search: the user's own foods first (ranked by their habits), then built-in foods,
 * then packaged products online, and finally "add as a new food" (AI estimate).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodSearchSheet(
    viewModel: FoodSearchViewModel,
    onPicked: (FoodPick) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focus = remember { FocusRequester() }
    LaunchedEffect(viewModel) { viewModel.picked.collect(onPicked) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxHeight(0.92f).imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = { Text(stringResource(R.string.food_search_hint)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { state.local.firstOrNull()?.let { viewModel.pick(it.food) } }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_x), contentDescription = stringResource(R.string.food_search_close))
                }
            }

            LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.xl)) {
                val query = state.query.trim()
                val mine = state.local.filter { it.usage.useCount > 0 }
                val builtin = state.local.filter { it.usage.useCount == 0 }

                if (query.isEmpty()) {
                    if (mine.isEmpty()) {
                        item { Hint(stringResource(R.string.food_empty_hint)) }
                    } else {
                        header(R.string.food_section_usual, state = state, withMealType = true)
                        items(mine, key = { "mine-${it.food.id}" }) { FoodRow(it.food, viewModel::pick) }
                    }
                    return@LazyColumn
                }

                if (mine.isNotEmpty()) {
                    header(R.string.food_section_yours, state = state)
                    items(mine, key = { "mine-${it.food.id}" }) { FoodRow(it.food, viewModel::pick) }
                }
                if (builtin.isNotEmpty()) {
                    header(R.string.food_section_builtin, state = state)
                    items(builtin, key = { "cat-${it.food.id}" }) { FoodRow(it.food, viewModel::pick) }
                }

                item(key = "custom") { CustomFoodRow(query, state.custom, viewModel::createCustom) }

                when (val packaged = state.packaged) {
                    PackagedResults.Loading -> item { Progress(stringResource(R.string.food_packaged_loading)) }
                    PackagedResults.Unavailable -> item { Hint(stringResource(R.string.food_packaged_offline)) }
                    is PackagedResults.Loaded -> if (packaged.foods.isNotEmpty()) {
                        header(R.string.food_section_packaged, state = state)
                        items(packaged.foods, key = { "off-${it.id}" }) { FoodRow(it, viewModel::pick) }
                    }
                    PackagedResults.Idle -> Unit
                }
            }
        }
    }
}

private fun LazyListScope.header(title: Int, state: FoodSearchUiState, withMealType: Boolean = false) {
    item(key = "header-$title") {
        val text = if (withMealType) stringResource(title, mealTypeLabel(state.mealType)) else stringResource(title)
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xs)
                .semantics { heading() },
        )
    }
}

@Composable
private fun FoodRow(food: Food, onPick: (Food) -> Unit) {
    val portion = food.suggestedPortion
    val kcal = food.nutrition(portion.quantity, portion.unit)?.calories?.roundKcal() ?: 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button) { onPick(food) }
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        FoodIcon(food.category)
        Column(modifier = Modifier.weight(1f)) {
            Text(food.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = stringResource(R.string.food_per_portion, kcal, portionLabel(portion))
            Text(
                if (food.source == FoodSource.OpenFoodFacts) "${stringResource(R.string.food_source_packaged)} · $detail" else detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CustomFoodRow(query: String, state: CustomFoodState, onCreate: () -> Unit) {
    val estimating = state == CustomFoodState.Estimating
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !estimating, role = Role.Button, onClick = onCreate)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (estimating) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(painterResource(R.drawable.ic_sparkles), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column {
                Text(
                    stringResource(if (estimating) R.string.food_custom_estimating else R.string.food_custom, query),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.food_custom_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val error = when (state) {
            CustomFoodState.NeedsAi -> stringResource(R.string.food_custom_needs_ai)
            is CustomFoodState.Failed -> when (val e = state.error) {
                is AiException.InvalidKey -> stringResource(R.string.ai_error_invalid_key)
                is AiException.RateLimited -> stringResource(R.string.ai_error_rate_limited)
                is AiException.Network -> stringResource(R.string.ai_error_network)
                is AiException.Unexpected -> stringResource(R.string.ai_error_unexpected, e.code)
                is AiException.NoResult -> stringResource(R.string.ai_error_no_result)
            }
            else -> null
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}

@Composable
private fun Progress(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
    )
}
