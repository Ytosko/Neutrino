package dev.ytosko.neutrino.ui.review

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.ai.AiClient
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.AnalysisPrompt
import dev.ytosko.neutrino.data.meal.MealDraft
import dev.ytosko.neutrino.data.meal.MealRepository
import dev.ytosko.neutrino.data.meal.PhotoProcessor
import dev.ytosko.neutrino.data.meal.PreparedPhoto
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.MealWindows
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.TokenUsage
import dev.ytosko.neutrino.domain.roundGrams
import dev.ytosko.neutrino.domain.roundKcal
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

enum class Macro { Calories, Carbs, Protein, Fat }

sealed interface ReviewPhase {
    data object Preparing : ReviewPhase
    data class Analyzing(val model: String) : ReviewPhase
    /** Values are ready to review (from the AI, or entered manually after a failure). */
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

/** Portion multipliers offered on the review screen. */
val PORTIONS = listOf(0.5, 1.0, 1.5, 2.0)

data class ReviewUiState(
    val phase: ReviewPhase = ReviewPhase.Preparing,
    val photo: ByteArray? = null,
    val name: String = "",
    /** Editable text per macro, always for the currently selected [portion]. */
    val fields: Map<Macro, String> = Macro.entries.associateWith { "" },
    val portion: Double = 1.0,
    val mealType: MealType = MealType.Snack,
    val mealTypeChosenByUser: Boolean = false,
    val eatenAt: ZonedDateTime = ZonedDateTime.now(),
    val usage: TokenUsage? = null,
    val model: String? = null,
) {
    val nutrition: Nutrition
        get() = Nutrition(
            calories = fields.number(Macro.Calories),
            proteinG = fields.number(Macro.Protein),
            carbsG = fields.number(Macro.Carbs),
            fatG = fields.number(Macro.Fat),
        )

    val canSave: Boolean get() = phase == ReviewPhase.Ready && name.isNotBlank() && !nutrition.isEmpty
}

private fun Map<Macro, String>.number(macro: Macro): Double = this[macro]?.replace(',', '.')?.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0

internal fun Nutrition.toFields(): Map<Macro, String> = mapOf(
    Macro.Calories to calories.roundKcal().toString(),
    Macro.Carbs to carbsG.roundGrams().format(),
    Macro.Protein to proteinG.roundGrams().format(),
    Macro.Fat to fatG.roundGrams().format(),
)

private fun Double.format(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

class ReviewViewModel(
    private val photoUri: Uri,
    private val settings: SettingsRepository,
    private val clients: Map<AiProvider, AiClient>,
    private val photos: PhotoProcessor,
    private val meals: MealRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val mealWindows: MealWindows = MealWindows(),
    private val onPhotoConsumed: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(ReviewUiState(eatenAt = ZonedDateTime.now(zone)))
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private var prepared: PreparedPhoto? = null
    private var provider: AiProvider? = null
    private var job: Job? = null

    init {
        analyze()
    }

    fun analyze() {
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

            val photo = prepared ?: runCatching { photos.prepare(photoUri, current.photoDetail.maxEdgePx) }
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
                val result = clients.getValue(activeProvider).analyze(apiKey, model, photo.jpeg, AnalysisPrompt.DEFAULT, current.photoDetail)
                if (result.nutrition.isEmpty) {
                    _state.update { it.copy(usage = result.usage) }
                    fail(FailureReason.NoFood)
                } else {
                    _state.update {
                        it.copy(
                            phase = ReviewPhase.Ready,
                            name = result.foodName,
                            fields = result.nutrition.toFields(),
                            portion = 1.0,
                            usage = result.usage,
                        )
                    }
                }
            } catch (e: AiException) {
                fail(FailureReason.Ai(e))
            }
        }
    }

    /** Lets the user type values themselves when the AI can't help. */
    fun enterManually() = _state.update { it.copy(phase = ReviewPhase.Ready) }

    fun setName(value: String) = _state.update { it.copy(name = value.take(80)) }

    fun setField(macro: Macro, value: String) {
        val cleaned = value.filter { it.isDigit() || it == '.' || it == ',' }.take(7)
        _state.update { it.copy(fields = it.fields + (macro to cleaned)) }
    }

    /** Scales every value by the change in portion, e.g. 1× → ½× halves them. */
    fun setPortion(portion: Double) = _state.update {
        if (portion == it.portion) it
        else it.copy(portion = portion, fields = (it.nutrition * (portion / it.portion)).toFields())
    }

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
                    name = snapshot.name.trim(),
                    nutrition = snapshot.nutrition,
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

    private fun fail(reason: FailureReason) = _state.update { it.copy(phase = ReviewPhase.Failed(reason)) }

    /** Photo time if it's plausible (in the past, within a week), otherwise now. */
    private fun resolveEatenAt(takenAt: Instant?): ZonedDateTime {
        val now = Instant.now()
        val valid = takenAt?.takeIf { !it.isAfter(now) && it.isAfter(now.minusSeconds(7 * 24 * 3600)) }
        return (valid ?: now).atZone(zone)
    }
}
