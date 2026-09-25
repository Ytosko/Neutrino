package dev.ytosko.neutrino.ui.home

import java.time.LocalTime
import java.time.Instant
import dev.ytosko.neutrino.domain.MealWindows
import dev.ytosko.neutrino.data.meal.StoredMeal
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.meal.DaySummary
import dev.ytosko.neutrino.data.glucose.GlucoseEntity
import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.meal.LoggedMeal
import dev.ytosko.neutrino.domain.insights.MealGlucose
import dev.ytosko.neutrino.domain.insights.TimedReading
import dev.ytosko.neutrino.data.meal.MealRepository
import dev.ytosko.neutrino.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val meals: MealRepository,
    private val settings: SettingsRepository,
    private val glucose: GlucoseRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val today = MutableStateFlow(LocalDate.now(zone))
    private val _date = MutableStateFlow(today.value)
    /** The day on screen; never after today. */
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    val isToday: StateFlow<Boolean> = combine(_date, today) { shown, now -> shown == now }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val day: StateFlow<DaySummary?> = _date
        .flatMapLatest { meals.observeDay(it, zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Blood glucose readings on the shown day, oldest first. */
    val glucoseReadings: StateFlow<List<GlucoseEntity>> = _date
        .flatMapLatest { glucose.observeBetween(it, it, zone) }
        .map { list -> list.sortedBy { it.measuredAtEpochMs } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The glucose card shows once a meter is paired or any reading exists (e.g. typed in by hand). */
    val glucoseVisible: StateFlow<Boolean> = combine(glucose.meters, glucose.hasReadings) { meters, any -> meters.isNotEmpty() || any }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Glucose before and about 2 hours after each meal on the shown day, by meal id. */
    val mealGlucose: StateFlow<Map<String, MealGlucose>> = _date
        .flatMapLatest { date ->
            // A late dinner's "after" reading can fall on the next day.
            combine(meals.observeDay(date, zone), glucose.observeBetween(date, date.plusDays(1), zone)) { day, readings ->
                val timed = readings.map { TimedReading(Instant.ofEpochMilli(it.measuredAtEpochMs), it.mmolPerL) }.sortedBy { it.at }
                day.meals.associate { it.id to MealGlucose.match(it.eatenAt, timed) }.filterValues { !it.isEmpty }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val goals: StateFlow<DailyGoals> = settings.settings
        .map { DailyGoals(it.carbGoalG, it.proteinGoalG, it.fatGoalG, it.kcalGoal, it.waterGoalMl) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyGoals())

    /**
     * Logs a copy of [meal]: today at the current time (meal type from the clock), or [onItsDay]
     * at the same time and type as the original. Returns the new meal's id for Undo.
     */
    suspend fun logAgain(meal: LoggedMeal, onItsDay: Boolean, mealWindows: MealWindows): String? {
        val at = if (onItsDay) meal.eatenAt else Instant.now()
        val type = if (onItsDay) meal.mealType else mealWindows.mealAt(at, zone)
        return meals.logAgain(meal.id, at, zone, type)?.id
    }

    fun isToday(meal: LoggedMeal): Boolean = meal.eatenAt.atZone(zone).toLocalDate() == today.value

    /** The "press and hold" tip shows until it's dismissed or a meal is long-pressed. */
    val mealTipVisible: StateFlow<Boolean> = settings.settings
        .map { !it.mealTipDone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun mealTipDone() {
        viewModelScope.launch { settings.setMealTipDone() }
    }

    /** Now on today, or the current time of day on a past day. */
    fun timeOnShownDay(): Instant {
        val shown = _date.value
        return if (shown == today.value) Instant.now() else shown.atTime(LocalTime.now(zone)).atZone(zone).toInstant()
    }

    fun addGlucose(mmolPerL: Double, relation: GlucoseRelation, time: Instant) {
        viewModelScope.launch { glucose.addManual(mmolPerL, time, relation, zone) }
    }

    val glucoseRange: StateFlow<ClosedFloatingPointRange<Double>> = settings.settings
        .map { it.glucoseLow..it.glucoseHigh }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 4.0..10.0)

    fun editGlucose(id: String, relation: GlucoseRelation, time: Instant, mmolPerL: Double? = null) {
        viewModelScope.launch { glucose.edit(id, relation, time, mmolPerL) }
    }

    /** Deletes right away and returns what Undo needs. */
    suspend fun deleteGlucose(id: String): GlucoseEntity? = glucose.delete(id)

    fun undoGlucoseDelete(reading: GlucoseEntity) {
        viewModelScope.launch { glucose.restore(reading) }
    }

    /** Ask once for notification permission so meal reminders can show. */
    val askForNotifications: StateFlow<Boolean> = settings.settings
        .map { it.remindersEnabled && !it.notificationsAsked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun notificationsAsked() {
        viewModelScope.launch { settings.setNotificationsAsked() }
    }

    val mealWindows: StateFlow<MealWindows> = settings.settings
        .map { it.mealWindows }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealWindows())

    val aiReady: StateFlow<Boolean> = settings.settings
        .map { it.aiReady }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Call on resume: if today was on screen, it rolls over at midnight; a past day stays put. */
    fun refreshDate() {
        val now = LocalDate.now(zone)
        if (_date.value == today.value) _date.value = now
        today.value = now
    }

    fun showDate(value: LocalDate) {
        _date.value = minOf(value, today.value)
    }

    fun previousDay() = showDate(_date.value.minusDays(1))
    fun nextDay() = showDate(_date.value.plusDays(1))
    fun showToday() = showDate(today.value)

    /** The shown day for logging a meal, or null when it's today. */
    fun pastDayEpoch(): Long? = _date.value.takeIf { it != today.value }?.toEpochDay()

    /** Deletes right away and returns what Undo needs. */
    suspend fun deleteMeal(id: String): StoredMeal? = meals.deleteMeal(id)

    fun undoDelete(stored: StoredMeal) {
        viewModelScope.launch { meals.restoreMeal(stored) }
    }

    /** Removes the most recent glass on the shown day (fixes a mistaken tap). */
    fun removeLastWater() {
        val last = day.value?.waterEntries?.lastOrNull() ?: return
        viewModelScope.launch { meals.deleteWater(last) }
    }

    /** Adds a glass to the shown day (a past day gets it at the current time of day), up to [MAX_WATER_ML]. */
    fun addWater(amountMl: Int = GLASS_ML) {
        val current = day.value?.waterMl ?: 0
        if (current + amountMl > MAX_WATER_ML) return
        val shown = _date.value
        val at = if (shown == today.value) Instant.now() else shown.atTime(LocalTime.now(zone)).atZone(zone).toInstant()
        viewModelScope.launch { meals.addWater(amountMl, zone, at) }
    }

    companion object {
        const val GLASS_ML = 250
        /** Most water one day can hold; stops runaway taps. */
        const val MAX_WATER_ML = 10_000
    }
}

/** Optional daily targets; null means no goal. */
data class DailyGoals(
    val carbsG: Int? = null,
    val proteinG: Int? = null,
    val fatG: Int? = null,
    val kcal: Int? = null,
    val waterMl: Int? = null,
)
