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
import java.time.LocalDate
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

/** Most foods one meal can hold. */
const val MAX_ITEMS = 50

data class ReviewUiState(
    val phase: ReviewPhase = ReviewPhase.Preparing,
    val photo: ByteArray? = null,
    /** Required. Filled in from the AI or the foods until the user edits it. */
    val name: String = "",
    val nameEditedByUser: Boolean = false,
    val items: List<ReviewItem> = emptyList(),
    val mealType: MealType = MealType.Snack,
    val mealTypeChosenByUser: Boolean = false,
    val eatenAt: ZonedDateTime = ZonedDateTime.now(),
    val usage: TokenUsage? = null,
    val model: String? = null,
) {
    val nutrition: Nutrition get() = items.fold(Nutrition.ZERO) { acc, item -> acc + item.nutrition }

    val canAddItem: Boolean get() = items.size < MAX_ITEMS

    val canSave: Boolean
        get() = phase == ReviewPhase.Ready && items.isNotEmpty() && items.all { it.grams != null } && name.isNotBlank()
}

/** Default meal name from its foods: "White rice, Chicken curry and 2 more". */
internal fun autoName(items: List<ReviewItem>): String {
    val names = items.map { it.food.name.substringBefore(" (").substringBefore(",") }.distinct()
    return when {
        names.isEmpty() -> ""
        names.size <= 2 -> names.joinToString(" and ")
        else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
    }.take(80)
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
    /** The day being viewed on Today when logging started; a past day starts the meal on that date. */
    logDate: LocalDate? = null,
) : ViewModel() {

    private val pastDay: LocalDate? = logDate?.takeIf { it != LocalDate.now(zone) }

    private val now = ZonedDateTime.now(zone).let { current ->
        logDate?.takeIf { it != current.toLocalDate() }?.let { current.with(it) } ?: current
    }
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
                            name = if (it.nameEditedByUser) it.name else result.foodName.take(80),
                            items = resolved.take(MAX_ITEMS).map { r -> newItem(r.food, r.portion) },
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

    fun setName(value: String) = _state.update { it.copy(name = value.take(80), nameEditedByUser = true) }

    fun addItem(food: Food, portion: Portion) = updateItems { items ->
        if (items.size >= MAX_ITEMS) items else items + newItem(food, portion)
    }

    /** Swaps the food on a line, keeping the amount when the unit still makes sense. */
    fun replaceFood(key: Long, food: Food, portion: Portion) = updateItem(key) { item ->
        val keep = item.quantity?.let { q -> food.grams(q, item.unit)?.let { Portion(q, item.unit) } }
        val use = keep ?: portion
        item.copy(food = food, quantityText = formatQuantity(use.quantity), unit = use.unit)
    }

    fun setQuantity(key: Long, text: String) = updateItem(key) {
        it.copy(quantityText = text.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6))
    }

    /** Sets amount and unit together (from the amount pop-up). */
    fun setPortion(key: Long, quantity: Double, unit: FoodUnit) = updateItem(key) {
        it.copy(quantityText = formatQuantity(quantity), unit = unit)
    }

    /** Changes the unit and converts the amount so the weight stays the same (1 plate → 250 g). */
    fun setUnit(key: Long, unit: FoodUnit) = updateItem(key) { item ->
        val grams = item.grams
        val perUnit = item.food.grams(1.0, unit)
        val converted = if (grams != null && perUnit != null && perUnit > 0) roundForUnit(grams / perUnit, unit) else item.quantity
        item.copy(unit = unit, quantityText = converted?.let(::formatQuantity) ?: item.quantityText)
    }

    fun removeItem(key: Long) = updateItems { items -> items.filterNot { it.key == key } }

    fun setMealType(type: MealType) = _state.update { it.copy(mealType = type, mealTypeChosenByUser = true) }

    fun setTime(time: LocalTime) = _state.update {
        val eatenAt = it.eatenAt.with(time)
        it.copy(eatenAt = eatenAt, mealType = if (it.mealTypeChosenByUser) it.mealType else mealWindows.mealAt(time))
    }

    fun setDate(date: LocalDate) = _state.update { it.copy(eatenAt = it.eatenAt.with(date)) }

    fun save() {
        val snapshot = _state.value
        if (!snapshot.canSave) return
        viewModelScope.launch {
            _state.update { it.copy(phase = ReviewPhase.Saving) }
            val result = meals.saveMeal(
                MealDraft(
                    name = snapshot.name.trim(),
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

    private fun updateItem(key: Long, transform: (ReviewItem) -> ReviewItem) =
        updateItems { items -> items.map { item -> if (item.key == key) transform(item) else item } }

    /** Changes the item list and keeps an automatic name in sync until the user types one. */
    private fun updateItems(transform: (List<ReviewItem>) -> List<ReviewItem>) = _state.update {
        val items = transform(it.items)
        it.copy(items = items, name = if (it.nameEditedByUser || (it.name.isNotBlank() && it.photo != null)) it.name else autoName(items))
    }

    private fun fail(reason: FailureReason) = _state.update { it.copy(phase = ReviewPhase.Failed(reason)) }

    /** Photo time if it's plausible (in the past, within a week), otherwise now. */
    private fun resolveEatenAt(takenAt: Instant?): ZonedDateTime {
        // Logging for a past day: that day, at the photo's time if it was taken that day.
        pastDay?.let { day ->
            return takenAt?.atZone(zone)?.takeIf { it.toLocalDate() == day } ?: now
        }
        val nowInstant = Instant.now()
        val valid = takenAt?.takeIf { !it.isAfter(nowInstant) && it.isAfter(nowInstant.minusSeconds(7 * 24 * 3600)) }
        return valid?.atZone(zone) ?: now
    }
}

/** Grams and ml as whole numbers; household units to the nearest quarter. */
internal fun roundForUnit(value: Double, unit: FoodUnit): Double =
    if (unit.isMass || unit.isVolume) {
        if (unit == FoodUnit.Kilogram || unit == FoodUnit.Liter) (value * 100).roundToInt() / 100.0 else value.roundToInt().toDouble()
    } else {
        ((value * 4).roundToInt() / 4.0).coerceAtLeast(0.25)
    }
