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

    /** The glucose card shows once a meter is paired, or on any day that has readings. */
    val meterPaired: StateFlow<Boolean> = glucose.meter
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val glucoseRange: StateFlow<ClosedFloatingPointRange<Double>> = settings.settings
        .map { it.glucoseLow..it.glucoseHigh }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 4.0..10.0)

    fun editGlucose(id: String, relation: GlucoseRelation, time: Instant) {
        viewModelScope.launch { glucose.edit(id, relation, time) }
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
