package dev.ytosko.neutrino.ui.review

import dev.ytosko.neutrino.data.ai.AiChain
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
import dev.ytosko.neutrino.data.voice.VoiceContext
import dev.ytosko.neutrino.data.voice.VoiceMeal
import dev.ytosko.neutrino.domain.ScannedItem
import dev.ytosko.neutrino.domain.voice.ItemChange
import dev.ytosko.neutrino.domain.voice.VoiceCommand
import dev.ytosko.neutrino.ui.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.time.LocalDateTime

private const val HIGHLIGHT_MS = 1_600L

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
    /** Editing a saved meal rather than logging a new one. */
    val editing: Boolean = false,
    /** The user changed something since the screen opened (or the meal was loaded). */
    val changed: Boolean = false,
    /** Lines a voice request just changed, lit up for a moment. */
    val highlighted: Set<Long> = emptySet(),
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
    private var mealWindows: MealWindows = MealWindows(),
    private val onPhotoConsumed: () -> Unit = {},
    /** The day being viewed on Today when logging started; a past day starts the meal on that date. */
    logDate: LocalDate? = null,
    /** Opens a saved meal for editing. */
    private val editMealId: String? = null,
    /** A meal said with Log by voice, to open with its foods and time. */
    voiceMeal: VoiceMeal? = null,
    /** Builds the page's voice assistant when voice is on; null hides the mic. */
    voiceFactory: ((CoroutineScope, () -> VoiceContext, suspend (VoiceCommand) -> List<String>) -> VoiceController)? = null,
) : ViewModel() {

    private val pastDay: LocalDate? = logDate?.takeIf { it != LocalDate.now(zone) }

    private val now = ZonedDateTime.now(zone).let { current ->
        logDate?.takeIf { it != current.toLocalDate() }?.let { current.with(it) } ?: current
    }
    private val _state = MutableStateFlow(
        ReviewUiState(
            phase = if (photoUri == null && editMealId == null) ReviewPhase.Ready else ReviewPhase.Preparing,
            eatenAt = now,
            mealType = mealWindows.mealAt(now.toLocalTime()),
            editing = editMealId != null,
        ),
    )
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private var prepared: PreparedPhoto? = null
    private var provider: AiProvider? = null
    private var job: Job? = null
    private var nextKey = 0L

    /** The mic on this page; null when voice isn't set up. */
    val voice: VoiceController? = voiceFactory?.invoke(viewModelScope, ::voiceContext, ::applyVoice)

    init {
        // Use the user's meal times once settings load (the default guess shows until then).
        viewModelScope.launch {
            mealWindows = settings.settings.first().mealWindows
            _state.update {
                if (it.mealTypeChosenByUser || it.editing) it else it.copy(mealType = mealWindows.mealAt(it.eatenAt.toLocalTime()))
            }
        }
        when {
            editMealId != null -> loadForEditing(editMealId)
            photoUri != null -> analyze()
            voiceMeal != null -> openVoiceMeal(voiceMeal)
        }
    }

    /** Fills a new meal from Log by voice: its foods, and the time the user said. */
    private fun openVoiceMeal(meal: VoiceMeal) {
        viewModelScope.launch {
            val items = meal.items.take(MAX_ITEMS).map { ScanFoods.resolve(it, foods.findByName(it.name)) }.map { newItem(it.food, it.portion) }
            val at = meal.eatenAt?.atZone(zone) ?: now
            _state.update {
                it.copy(
                    items = items,
                    name = autoName(items),
                    eatenAt = at,
                    mealType = if (it.mealTypeChosenByUser) it.mealType else mealWindows.mealAt(at.toLocalTime()),
                )
            }
        }
    }

    /** What the assistant sees: the meal's lines as on screen, and its time. */
    private fun voiceContext(): VoiceContext {
        val snapshot = _state.value
        val lines = snapshot.items.map { item ->
            val grams = item.grams?.let { " (${it.roundToInt()} g)" }.orEmpty()
            "${item.food.name}: ${item.quantityText.ifBlank { "?" }} ${item.unit.key}$grams"
        }
        return VoiceContext(now = LocalDateTime.now(zone), mealLines = lines, mealTime = snapshot.eatenAt.toLocalDateTime())
    }

    /**
     * Applies what the user asked for by voice: new foods, changed amounts, removed lines, a new time.
     * Returns the names of lines that couldn't be changed, so the reply can say so.
     */
    private suspend fun applyVoice(command: VoiceCommand): List<String> {
        val before = _state.value.items
        val unchanged = mutableListOf<String>()
        val changed = mutableSetOf<Long>()
        val removed = mutableSetOf<Long>()
        var items = before
        command.items.forEach { change ->
            when (change) {
                is ItemChange.Add -> {
                    val resolved = ScanFoods.resolve(change.item, foods.findByName(change.item.name))
                    val line = newItem(resolved.food, resolved.portion)
                    items = items + line
                    changed += line.key
                }
                is ItemChange.Set -> {
                    val target = before.getOrNull(change.index) ?: return@forEach
                    val updated = withAmount(target, change.item)
                    if (updated == null) {
                        unchanged += target.food.name
                        return@forEach
                    }
                    items = items.map { if (it.key == target.key) updated else it }
                    changed += target.key
                }
                is ItemChange.Remove -> before.getOrNull(change.index)?.let { removed += it.key }
            }
        }
        if (changed.isNotEmpty() || removed.isNotEmpty()) updateItems { items.filterNot { it.key in removed }.take(MAX_ITEMS) }
        command.mealTime?.let { time ->
            val at = time.atZone(zone)
            _state.update {
                it.copy(eatenAt = at, mealType = if (it.mealTypeChosenByUser) it.mealType else mealWindows.mealAt(at.toLocalTime()), changed = true)
            }
        }
        if (changed.isNotEmpty()) {
            viewModelScope.launch {
                _state.update { it.copy(highlighted = changed) }
                delay(HIGHLIGHT_MS)
                _state.update { it.copy(highlighted = emptySet()) }
            }
        }
        return unchanged
    }

    /** The line with the amount the user said; see [voiceAmount]. */
    private fun withAmount(item: ReviewItem, said: ScannedItem): ReviewItem? =
        voiceAmount(item.food, said)?.let { portion -> item.copy(quantityText = formatQuantity(portion.quantity), unit = portion.unit) }

    /** Fills the screen from a saved meal. Foods come from the directory, else are rebuilt from the saved line. */
    private fun loadForEditing(id: String) {
        viewModelScope.launch {
            val stored = meals.loadMeal(id)
            if (stored == null) {
                _state.update { it.copy(phase = ReviewPhase.Ready, editing = false) }
                return@launch
            }
            val items = stored.items.map { line ->
                val unit = FoodUnit.fromKey(line.unit) ?: FoodUnit.Gram
                val food = foods.byId(line.foodId)?.takeIf { it.grams(line.quantity, unit) != null }
                    ?: savedLineFood(line.foodId, line.name, line.category, line.quantity, unit, line.grams,
                        Nutrition(line.calories, line.proteinG, line.carbsG, line.fatG))
                newItem(food, Portion(line.quantity, unit))
            }
            val meal = stored.meal
            _state.update {
                it.copy(
                    phase = ReviewPhase.Ready,
                    photo = stored.thumbnail,
                    name = meal.name,
                    nameEditedByUser = true,
                    items = items,
                    mealType = runCatching { MealType.valueOf(meal.mealType) }.getOrDefault(MealType.Snack),
                    mealTypeChosenByUser = true,
                    eatenAt = Instant.ofEpochMilli(meal.eatenAtEpochMs).atZone(zone),
                    model = meal.model,
                )
            }
        }
    }

    fun analyze() {
        val uri = photoUri ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val current = settings.settings.first()
            // Primary first, then each fallback if one fails.
            val chain = settings.aiChain()
            val primary = chain.firstOrNull()?.first
            if (primary == null) {
                fail(FailureReason.AiNotSetUp)
                return@launch
            }
            provider = primary.providerEnum

            val photo = prepared ?: runCatching { photos.prepare(uri, primary.detail.maxEdgePx) }
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
                    phase = ReviewPhase.Analyzing(primary.name),
                    model = primary.model,
                )
            }

            try {
                val (answered, result) = AiChain.run(
                    chain,
                    clients,
                    onAttempt = { config -> _state.update { it.copy(phase = ReviewPhase.Analyzing(config.name), model = config.model) } },
                ) { client, apiKey, config ->
                    client.analyzeMeal(apiKey, config.model, photo.jpeg, config.detail, current.promptHints)
                } ?: run {
                    fail(FailureReason.AiNotSetUp)
                    return@launch
                }
                provider = answered.providerEnum
                _state.update { it.copy(model = answered.model) }
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

    fun setName(value: String) = _state.update { it.copy(name = value.take(80), nameEditedByUser = true, changed = true) }

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

    fun setMealType(type: MealType) = _state.update { it.copy(mealType = type, mealTypeChosenByUser = true, changed = true) }

    fun setTime(time: LocalTime) = _state.update {
        val eatenAt = it.eatenAt.with(time)
        it.copy(eatenAt = eatenAt, mealType = if (it.mealTypeChosenByUser) it.mealType else mealWindows.mealAt(time), changed = true)
    }

    fun setDate(date: LocalDate) = _state.update { it.copy(eatenAt = it.eatenAt.with(date), changed = true) }

    fun save() {
        val snapshot = _state.value
        if (!snapshot.canSave) return
        viewModelScope.launch {
            _state.update { it.copy(phase = ReviewPhase.Saving) }
            val draft =
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
                )
            val result = if (editMealId != null) meals.updateMeal(editMealId, draft) else meals.saveMeal(draft)
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
        it.copy(items = items, changed = true, name = if (it.nameEditedByUser || (it.name.isNotBlank() && it.photo != null)) it.name else autoName(items))
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

/**
 * A food rebuilt from a saved meal line when it's no longer in the directory, so the line can
 * still be edited: nutrition per 100 g from what was saved, and the unit it was logged in.
 */
internal fun savedLineFood(
    id: String,
    name: String,
    category: String,
    quantity: Double,
    unit: FoodUnit,
    grams: Double,
    nutrition: Nutrition,
): Food {
    val per100g = if (grams > 0) nutrition * (100.0 / grams) else Nutrition.ZERO
    val perUnit = if (quantity > 0) grams / quantity else grams
    return Food(
        id = id,
        name = name,
        category = dev.ytosko.neutrino.domain.food.FoodCategory.fromKey(category),
        per100g = per100g,
        unitGrams = if (unit.isMass || unit.isVolume) emptyMap() else mapOf(unit to perUnit),
        density = if (unit.isVolume) 1.0 else null,
        source = dev.ytosko.neutrino.domain.food.FoodSource.Custom,
    )
}

/**
 * The amount for a line changed by voice. The unit said is kept when the food has it and it agrees
 * with the weight the AI worked out ("3 pieces" when 3 pieces are about that heavy); otherwise the
 * weight wins and the line switches to grams ("they were only 100 g" → 100 g). With only calories
 * said, the weight comes from the food's calories per 100 g. Null when there's nothing to go on.
 */
internal fun voiceAmount(food: Food, said: ScannedItem): Portion? {
    val unit = FoodUnit.fromKey(said.unit)
    val unitGrams = unit?.let { food.grams(said.quantity, it) }
    val grams = when {
        said.grams > 0 -> said.grams
        unitGrams != null -> unitGrams
        said.nutrition.calories > 0 && food.per100g.calories > 0 -> said.nutrition.calories / food.per100g.calories * 100
        else -> return null
    }
    if (grams <= 0) return null
    if (unit != null && unitGrams != null && kotlin.math.abs(unitGrams - grams) <= grams * 0.15) {
        return Portion(roundForUnit(said.quantity, unit), unit)
    }
    return Portion(grams.roundToInt().toDouble().coerceAtLeast(1.0), FoodUnit.Gram)
}
