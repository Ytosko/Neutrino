package dev.ytosko.neutrino.ui.review

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.ai.AiClient
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.analyzeMeal
import dev.ytosko.neutrino.data.food.FoodRepository
import dev.ytosko.neutrino.data.meal.DraftItem
import dev.ytosko.neutrino.data.meal.MealDraft
import dev.ytosko.neutrino.data.meal.MealRepository
import dev.ytosko.neutrino.data.meal.PhotoProcessor
import dev.ytosko.neutrino.data.meal.PreparedPhoto
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.MealWindows
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.TokenUsage
import dev.ytosko.neutrino.domain.food.Food
import dev.ytosko.neutrino.domain.food.FoodUnit
import dev.ytosko.neutrino.domain.food.Portion
import dev.ytosko.neutrino.domain.food.ScanFoods
import dev.ytosko.neutrino.ui.food.formatQuantity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

sealed interface ReviewPhase {
    data object Preparing : ReviewPhase
    data class Analyzing(val model: String) : ReviewPhase
    /** Items are ready to review (from the photo, or added by hand). */
    data object Ready : ReviewPhase
    data class Failed(val reason: FailureReason) : ReviewPhase
    data object Saving : ReviewPhase
    data class Saved(val syncedToHealthConnect: Boolean) : ReviewPhase
}

sealed interface FailureReason {
    data object PhotoUnreadable : FailureReason
    data object AiNotSetUp : FailureReason
    data object NoFood : FailureReason
    data class Ai(val error: AiException) : FailureReason
}

/** One editable line: which food, and how much ("1.5" "plate"). */
data class ReviewItem(
    val key: Long,
    val food: Food,
    val quantityText: String,
    val unit: FoodUnit,
) {
    val quantity: Double? get() = quantityText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    val grams: Double? get() = quantity?.let { food.grams(it, unit) }
    val nutrition: Nutrition get() = grams?.let { food.per100g * (it / 100.0) } ?: Nutrition.ZERO
}

data class ReviewUiState(
    val phase: ReviewPhase = ReviewPhase.Preparing,
    val photo: ByteArray? = null,
    /** What the user typed; empty means "name it after the foods". */
    val name: String = "",
    val suggestedName: String = "",
    val items: List<ReviewItem> = emptyList(),
    val mealType: MealType = MealType.Snack,
    val mealTypeChosenByUser: Boolean = false,
    val eatenAt: ZonedDateTime = ZonedDateTime.now(),
    val usage: TokenUsage? = null,
    val model: String? = null,
) {
    val nutrition: Nutrition get() = items.fold(Nutrition.ZERO) { acc, item -> acc + item.nutrition }

    val displayName: String
        get() = name.trim().ifEmpty { suggestedName.ifEmpty { items.joinToString(", ") { it.food.name.substringBefore(" (") } } }

    val canSave: Boolean
        get() = phase == ReviewPhase.Ready && items.isNotEmpty() && items.all { it.grams != null } && displayName.isNotBlank()
}

class ReviewViewModel(
    /** Null when adding foods by hand (no photo). */
    private val photoUri: Uri?,
    private val settings: SettingsRepository,
    private val clients: Map<AiProvider, AiClient>,
    private val photos: PhotoProcessor,
    private val meals: MealRepository,
    private val foods: FoodRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val mealWindows: MealWindows = MealWindows(),
    private val onPhotoConsumed: () -> Unit = {},
) : ViewModel() {

    private val now = ZonedDateTime.now(zone)
    private val _state = MutableStateFlow(
        ReviewUiState(
            phase = if (photoUri == null) ReviewPhase.Ready else ReviewPhase.Preparing,
            eatenAt = now,
            mealType = mealWindows.mealAt(now.toLocalTime()),
        ),
    )
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private var prepared: PreparedPhoto? = null
    private var provider: AiProvider? = null
    private var job: Job? = null
    private var nextKey = 0L

    init {
        if (photoUri != null) analyze()
    }

    fun analyze() {
        val uri = photoUri ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val current = settings.settings.first()
            val activeProvider = current.activeProvider
            val model = activeProvider?.let { current.models[it] }
            if (!current.aiReady || activeProvider == null || model == null) {
                fail(FailureReason.AiNotSetUp)
                return@launch
            }
            provider = activeProvider

            val photo = prepared ?: runCatching { photos.prepare(uri, current.photoDetail.maxEdgePx) }
                .onSuccess { onPhotoConsumed() }
                .getOrElse {
                    fail(FailureReason.PhotoUnreadable)
                    return@launch
                }
            prepared = photo
            val eatenAt = resolveEatenAt(photo.takenAt)
            _state.update {
                it.copy(
                    photo = photo.jpeg,
                    eatenAt = eatenAt,
                    mealType = if (it.mealTypeChosenByUser) it.mealType else mealWindows.mealAt(eatenAt.toLocalTime()),
                    phase = ReviewPhase.Analyzing(model),
                    model = model,
                )
            }

            val apiKey = settings.apiKey(activeProvider) ?: run {
                fail(FailureReason.AiNotSetUp)
                return@launch
            }
            try {
                val result = clients.getValue(activeProvider).analyzeMeal(apiKey, model, photo.jpeg, current.photoDetail)
                val resolved = result.items
                    .filter { !it.nutrition.isEmpty }
                    .map { ScanFoods.resolve(it, foods.findByName(it.name)) }
                if (resolved.isEmpty()) {
                    _state.update { it.copy(usage = result.usage) }
                    fail(FailureReason.NoFood)
                } else {
                    _state.update {
                        it.copy(
                            phase = ReviewPhase.Ready,
                            suggestedName = result.foodName,
                            items = resolved.map { r -> newItem(r.food, r.portion) },
                            usage = result.usage,
                        )
                    }
                }
            } catch (e: AiException) {
                fail(FailureReason.Ai(e))
            }
        }
    }

    /** Continue without the AI: the user adds foods from search. */
    fun enterManually() = _state.update { it.copy(phase = ReviewPhase.Ready) }

    fun setName(value: String) = _state.update { it.copy(name = value.take(80)) }

    fun addItem(food: Food, portion: Portion) = _state.update { it.copy(items = it.items + newItem(food, portion)) }

    /** Swaps the food on a line, keeping the amount when the unit still makes sense. */
    fun replaceFood(key: Long, food: Food, portion: Portion) = updateItem(key) { item ->
        val keep = item.quantity?.let { q -> food.grams(q, item.unit)?.let { Portion(q, item.unit) } }
        val use = keep ?: portion
        item.copy(food = food, quantityText = formatQuantity(use.quantity), unit = use.unit)
    }

    fun setQuantity(key: Long, text: String) = updateItem(key) {
        it.copy(quantityText = text.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6))
    }

    /** Changes the unit and converts the amount so the weight stays the same (1 plate → 250 g). */
    fun setUnit(key: Long, unit: FoodUnit) = updateItem(key) { item ->
        val grams = item.grams
        val perUnit = item.food.grams(1.0, unit)
        val converted = if (grams != null && perUnit != null && perUnit > 0) roundForUnit(grams / perUnit, unit) else item.quantity
        item.copy(unit = unit, quantityText = converted?.let(::formatQuantity) ?: item.quantityText)
    }

    fun removeItem(key: Long) = _state.update { it.copy(items = it.items.filterNot { item -> item.key == key }) }

    fun setMealType(type: MealType) = _state.update { it.copy(mealType = type, mealTypeChosenByUser = true) }

    fun setTime(time: LocalTime) = _state.update {
        val eatenAt = it.eatenAt.with(time)
        it.copy(eatenAt = eatenAt, mealType = if (it.mealTypeChosenByUser) it.mealType else mealWindows.mealAt(time))
    }

    fun save() {
        val snapshot = _state.value
        if (!snapshot.canSave) return
        viewModelScope.launch {
            _state.update { it.copy(phase = ReviewPhase.Saving) }
            val result = meals.saveMeal(
                MealDraft(
                    name = snapshot.displayName,
                    items = snapshot.items.map { item ->
                        DraftItem(
                            food = item.food,
                            portion = Portion(item.quantity ?: 0.0, item.unit),
                            grams = item.grams ?: 0.0,
                            nutrition = item.nutrition,
                        )
                    },
                    mealType = snapshot.mealType,
                    eatenAt = snapshot.eatenAt.toInstant(),
                    zone = zone,
                    photoJpeg = snapshot.photo,
                    provider = provider?.id,
                    model = snapshot.model,
                    usage = snapshot.usage,
                ),
            )
            _state.update { it.copy(phase = ReviewPhase.Saved(result.syncedToHealthConnect)) }
        }
    }

    private fun newItem(food: Food, portion: Portion) =
        ReviewItem(key = nextKey++, food = food, quantityText = formatQuantity(portion.quantity), unit = portion.unit)

    private fun updateItem(key: Long, transform: (ReviewItem) -> ReviewItem) = _state.update {
        it.copy(items = it.items.map { item -> if (item.key == key) transform(item) else item })
    }

    private fun fail(reason: FailureReason) = _state.update { it.copy(phase = ReviewPhase.Failed(reason)) }

    /** Photo time if it's plausible (in the past, within a week), otherwise now. */
    private fun resolveEatenAt(takenAt: Instant?): ZonedDateTime {
        val nowInstant = Instant.now()
        val valid = takenAt?.takeIf { !it.isAfter(nowInstant) && it.isAfter(nowInstant.minusSeconds(7 * 24 * 3600)) }
        return (valid ?: nowInstant).atZone(zone)
    }
}

/** Grams and ml as whole numbers; household units to the nearest quarter. */
internal fun roundForUnit(value: Double, unit: FoodUnit): Double =
    if (unit.isMass || unit.isVolume) {
        if (unit == FoodUnit.Kilogram || unit == FoodUnit.Liter) (value * 100).roundToInt() / 100.0 else value.roundToInt().toDouble()
    } else {
        ((value * 4).roundToInt() / 4.0).coerceAtLeast(0.25)
    }
