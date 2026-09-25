package dev.ytosko.neutrino.ui.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.ai.AiClient
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.estimateFood
import dev.ytosko.neutrino.data.food.FoodRepository
import dev.ytosko.neutrino.data.food.OpenFoodFactsClient
import dev.ytosko.neutrino.data.food.RankedFood
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.FoodEstimate
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.food.Food
import dev.ytosko.neutrino.domain.food.FoodCategory
import dev.ytosko.neutrino.domain.food.FoodSource
import dev.ytosko.neutrino.domain.food.FoodUnit
import dev.ytosko.neutrino.domain.food.Portion
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface PackagedResults {
    data object Idle : PackagedResults
    data object Loading : PackagedResults
    data class Loaded(val foods: List<Food>) : PackagedResults
    data object Unavailable : PackagedResults
}

sealed interface CustomFoodState {
    data object Idle : CustomFoodState
    data object Estimating : CustomFoodState
    data object NeedsAi : CustomFoodState
    /** The AI didn't recognise the text as a food or drink. */
    data object NotFound : CustomFoodState
    data class Failed(val error: AiException) : CustomFoodState
}

data class FoodSearchUiState(
    val query: String = "",
    val mealType: MealType = MealType.Snack,
    val local: List<RankedFood> = emptyList(),
    val packaged: PackagedResults = PackagedResults.Idle,
    val custom: CustomFoodState = CustomFoodState.Idle,
)

/** A food chosen in the search sheet, with the amount to start from. */
data class FoodPick(val food: Food, val portion: Portion)

class FoodSearchViewModel(
    private val foods: FoodRepository,
    private val openFoodFacts: OpenFoodFactsClient,
    private val settings: SettingsRepository,
    private val clients: Map<AiProvider, AiClient>,
    mealType: MealType,
) : ViewModel() {

    private val _state = MutableStateFlow(FoodSearchUiState(mealType = mealType))
    val state: StateFlow<FoodSearchUiState> = _state.asStateFlow()

    private val _picked = Channel<FoodPick>(Channel.BUFFERED)
    /** Emits when the user picks (or creates) a food. */
    val picked = _picked.receiveAsFlow()

    private var localJob: Job? = null
    private var remoteJob: Job? = null

    init {
        refreshLocal("")
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query, custom = CustomFoodState.Idle) }
        refreshLocal(query)
        searchPackaged(query)
    }

    fun pick(food: Food) {
        viewModelScope.launch {
            val portion = foods.lastPortion(food.id)?.takeIf { food.grams(it.quantity, it.unit) != null } ?: food.suggestedPortion
            _picked.send(FoodPick(food, portion))
        }
    }

    /** Creates a food from the typed name: the AI estimates it once, then it's reused from the directory. */
    fun createCustom() {
        val name = _state.value.query.trim()
        if (name.isEmpty() || _state.value.custom == CustomFoodState.Estimating) return
        viewModelScope.launch {
            val current = settings.settings.first()
            val provider = current.activeProvider
            val model = provider?.let { current.models[it] }
            val key = provider?.let { settings.apiKey(it) }
            if (!current.aiReady || provider == null || model == null || key == null) {
                _state.update { it.copy(custom = CustomFoodState.NeedsAi) }
                return@launch
            }
            _state.update { it.copy(custom = CustomFoodState.Estimating) }
            try {
                val (estimate, _) = clients.getValue(provider).estimateFood(key, model, name, current.promptHints)
                val food = estimate.toFood(fallbackName = name)
                _state.update { it.copy(custom = CustomFoodState.Idle) }
                _picked.send(FoodPick(food, food.suggestedPortion))
            } catch (_: AiException.NoResult) {
                _state.update { it.copy(custom = CustomFoodState.NotFound) }
            } catch (e: AiException) {
                _state.update { it.copy(custom = CustomFoodState.Failed(e)) }
            }
        }
    }

    private fun refreshLocal(query: String) {
        localJob?.cancel()
        localJob = viewModelScope.launch {
            if (query.isNotEmpty()) delay(LOCAL_DEBOUNCE_MS)
            val results = foods.search(query, _state.value.mealType)
            _state.update { it.copy(local = results) }
        }
    }

    private fun searchPackaged(query: String) {
        remoteJob?.cancel()
        if (query.trim().length < MIN_REMOTE_CHARS) {
            _state.update { it.copy(packaged = PackagedResults.Idle) }
            return
        }
        remoteJob = viewModelScope.launch {
            delay(REMOTE_DEBOUNCE_MS)
            _state.update { it.copy(packaged = PackagedResults.Loading) }
            val result = try {
                PackagedResults.Loaded(openFoodFacts.search(query.trim()))
            } catch (_: AiException) {
                PackagedResults.Unavailable
            } catch (_: kotlinx.serialization.SerializationException) {
                PackagedResults.Unavailable
            }
            _state.update { it.copy(packaged = result) }
        }
    }

    companion object {
        private const val LOCAL_DEBOUNCE_MS = 120L
        private const val REMOTE_DEBOUNCE_MS = 600L
        private const val MIN_REMOTE_CHARS = 3
    }
}

internal fun FoodEstimate.toFood(fallbackName: String): Food {
    val units = unitGrams.mapNotNull { (key, grams) ->
        FoodUnit.fromKey(key)?.takeIf { !it.isMass && !it.isVolume }?.let { it to grams }
    }.toMap()
    return Food(
        id = "custom-${UUID.randomUUID()}",
        name = name.ifBlank { fallbackName },
        category = FoodCategory.fromKey(category),
        per100g = per100g,
        unitGrams = units,
        density = gramsPerMl,
        aliases = if (name.equals(fallbackName, ignoreCase = true)) emptyList() else listOf(fallbackName),
        defaultPortion = units.keys.firstOrNull()?.let { Portion(1.0, it) },
        source = FoodSource.Custom,
    )
}
