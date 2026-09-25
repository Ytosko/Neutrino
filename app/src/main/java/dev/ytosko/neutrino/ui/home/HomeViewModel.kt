package dev.ytosko.neutrino.ui.home

import dev.ytosko.neutrino.domain.MealWindows
import dev.ytosko.neutrino.data.meal.StoredMeal
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.meal.DaySummary
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

    fun addWater(amountMl: Int = GLASS_ML) {
        viewModelScope.launch { meals.addWater(amountMl, zone) }
    }

    companion object {
        const val GLASS_ML = 250
    }
}
