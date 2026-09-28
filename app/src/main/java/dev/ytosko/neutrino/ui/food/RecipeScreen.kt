package dev.ytosko.neutrino.ui.food

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.ai.AiChain
import dev.ytosko.neutrino.data.ai.AiClient
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.AnalysisPrompt
import dev.ytosko.neutrino.data.food.FoodRepository
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.food.Food
import dev.ytosko.neutrino.domain.food.FoodUnit
import dev.ytosko.neutrino.domain.food.Portion
import dev.ytosko.neutrino.domain.food.RecipeParser
import dev.ytosko.neutrino.domain.food.RecipeResult
import dev.ytosko.neutrino.domain.roundKcal
import dev.ytosko.neutrino.ui.ai.aiErrorMessage
import dev.ytosko.neutrino.ui.theme.Spacing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

/** Units a home dish is shared out in. */
val RECIPE_UNITS = listOf(FoodUnit.Plate, FoodUnit.Bowl, FoodUnit.Piece, FoodUnit.Cup, FoodUnit.Serving)

data class RecipeUiState(
    val name: String = "",
    val ingredients: String = "",
    val servings: String = "4",
    val unit: FoodUnit = FoodUnit.Plate,
    val working: Boolean = false,
    val result: RecipeResult? = null,
    val error: AiException? = null,
    val needsAi: Boolean = false,
) {
    val servingsCount: Int? get() = servings.toIntOrNull()?.takeIf { it in 1..100 }
    val canWorkOut: Boolean get() = ingredients.isNotBlank() && servingsCount != null && !working
}

/**
 * A home recipe: the ingredients as the user writes them ("1 kg chicken, 3 tbsp mustard oil…") and
 * how many plates (bowls, pieces…) it makes. The AI estimates each ingredient once; the dish is
 * saved as the user's own food, one plate at a time, and added to the meal.
 */
class RecipeViewModel(
    private val settings: SettingsRepository,
    private val clients: Map<AiProvider, AiClient>,
    private val foods: FoodRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RecipeUiState())
    val state: StateFlow<RecipeUiState> = _state.asStateFlow()

    private val _saved = Channel<FoodPick>(Channel.BUFFERED)
    /** The saved dish, with one serving to add to the meal. */
    val saved = _saved.receiveAsFlow()

    fun setName(value: String) = _state.update { it.copy(name = value.take(60)) }
    fun setIngredients(value: String) = _state.update { it.copy(ingredients = value.take(MAX_INGREDIENTS_TEXT), result = null) }
    fun setServings(value: String) = _state.update { state ->
        val servings = value.filter(Char::isDigit).take(3)
        state.copy(servings = servings, result = state.result?.let { r -> servings.toIntOrNull()?.takeIf { it > 0 }?.let { r.copy(servings = it) } ?: r })
    }
    fun setUnit(unit: FoodUnit) = _state.update { it.copy(unit = unit, result = it.result?.copy(unit = unit)) }

    fun workOut() {
        val snapshot = _state.value
        val servings = snapshot.servingsCount ?: return
        if (!snapshot.canWorkOut) return
        viewModelScope.launch {
            val chain = settings.aiChain()
            if (chain.isEmpty()) {
                _state.update { it.copy(needsAi = true) }
                return@launch
            }
            _state.update { it.copy(working = true, error = null, needsAi = false) }
            val hints = settings.settings.first().promptHints
            val prompt = AnalysisPrompt.recipe(snapshot.name, snapshot.ingredients, hints)
            val fallbackName = snapshot.name.ifBlank { snapshot.ingredients.lineSequence().firstOrNull().orEmpty().take(40) }
            try {
                // A recipe always has ingredients, so an unreadable answer is a failure: the next model tries.
                val (_, result) = AiChain.run(chain, clients) { client, key, config ->
                    val reply = client.generateJson(key, config.model, prompt, AnalysisPrompt.RECIPE_SCHEMA)
                    RecipeParser.parse(reply.text, fallbackName, servings, snapshot.unit) ?: throw AiException.NoResult()
                } ?: return@launch
                _state.update { it.copy(result = result, name = it.name.ifBlank { result.name }) }
            } catch (e: AiException) {
                _state.update { it.copy(error = e) }
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    /** Saves the dish to the user's foods and hands one serving back to add to the meal. */
    fun save() {
        val snapshot = _state.value
        val result = snapshot.result ?: return
        viewModelScope.launch {
            val food: Food = result.copy(name = snapshot.name.ifBlank { result.name }).toFood("recipe-" + UUID.randomUUID())
            val portion = Portion(1.0, result.unit)
            foods.saveOwn(food, portion)
            _saved.send(FoodPick(food, portion))
        }
    }

    private companion object {
        const val MAX_INGREDIENTS_TEXT = 1_500
    }
}

/** Full-screen: write the ingredients, work it out, check one plate's numbers, save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDialog(viewModel: RecipeViewModel, onSaved: (FoodPick) -> Unit, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.saved.collect(onSaved) }
    var unitMenu by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_x), contentDescription = stringResource(R.string.food_search_close)) }
                Text(stringResource(R.string.recipe_title), style = MaterialTheme.typography.titleLarge)
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(stringResource(R.string.recipe_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text(stringResource(R.string.recipe_name)) },
                    placeholder = { Text(stringResource(R.string.recipe_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.ingredients,
                    onValueChange = viewModel::setIngredients,
                    label = { Text(stringResource(R.string.recipe_ingredients)) },
                    placeholder = { Text(stringResource(R.string.recipe_ingredients_hint)) },
                    minLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.recipe_makes), style = MaterialTheme.typography.bodyLarge)
                    OutlinedTextField(
                        value = state.servings,
                        onValueChange = viewModel::setServings,
                        singleLine = true,
                        isError = state.servingsCount == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.widthIn(max = 88.dp),
                    )
                    ExposedDropdownMenuBox(expanded = unitMenu, onExpandedChange = { unitMenu = it }, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = unitLabel(state.unit),
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        )
                        ExposedDropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                            RECIPE_UNITS.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unitLabel(unit)) },
                                    onClick = {
                                        viewModel.setUnit(unit)
                                        unitMenu = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                        }
                    }
                }
                OutlinedButton(onClick = viewModel::workOut, enabled = state.canWorkOut, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.working) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(Spacing.xs))
                        Text(stringResource(R.string.recipe_working))
                    } else {
                        Text(stringResource(R.string.recipe_work_out))
                    }
                }
                state.error?.let { Text(aiErrorMessage(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                if (state.needsAi) Text(stringResource(R.string.food_custom_needs_ai), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                state.result?.let { RecipeResultCard(it) }
            }
            Button(
                onClick = viewModel::save,
                enabled = state.result != null,
                modifier = Modifier.fillMaxWidth().padding(Spacing.md).heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.recipe_save)) }
        }
    }
}

@Composable
private fun RecipeResultCard(result: RecipeResult) {
    val per = result.perServing
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(R.string.recipe_per_serving, unitLabel(result.unit), result.gramsPerServing.roundToInt()),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.recipe_macros, per.calories.roundKcal(), per.carbsG.roundToInt(), per.proteinG.roundToInt(), per.fatG.roundToInt()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = Spacing.xs))
            Text(stringResource(R.string.recipe_ingredients_found, result.ingredients.size), style = MaterialTheme.typography.labelLarge)
            result.ingredients.forEach { ingredient ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(ingredient.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${ingredient.grams.roundToInt()} g · ${ingredient.nutrition.calories.roundKcal()} kcal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                stringResource(R.string.recipe_total, result.total.calories.roundKcal(), result.totalGrams.roundToInt(), result.servings),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}
