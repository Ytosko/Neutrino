package dev.ytosko.neutrino.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.meal.DaySummary
import dev.ytosko.neutrino.data.meal.MealRepository
import dev.ytosko.neutrino.data.settings.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val meals: MealRepository,
    settings: SettingsRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val date = MutableStateFlow(LocalDate.now(zone))

    val day: StateFlow<DaySummary?> = date
        .flatMapLatest { meals.observeDay(it, zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val aiReady: StateFlow<Boolean> = settings.settings
        .map { it.aiReady }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Call on resume so the screen rolls over at midnight. */
    fun refreshDate() {
        date.value = LocalDate.now(zone)
    }

    fun deleteMeal(id: String) {
        viewModelScope.launch { meals.deleteMeal(id) }
    }

    fun addWater(amountMl: Int = GLASS_ML) {
        viewModelScope.launch { meals.addWater(amountMl, zone) }
    }

    companion object {
        const val GLASS_ML = 250
    }
}
