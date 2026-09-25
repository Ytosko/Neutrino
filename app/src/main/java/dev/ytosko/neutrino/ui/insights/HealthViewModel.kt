package dev.ytosko.neutrino.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.meal.MealRepository
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.glucose.relationEnum
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.insights.GlucoseInsights
import dev.ytosko.neutrino.domain.insights.GlucosePoint
import dev.ytosko.neutrino.domain.insights.GlucoseSummary
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import dev.ytosko.neutrino.domain.insights.InsightRange
import dev.ytosko.neutrino.domain.insights.InsightSummary
import dev.ytosko.neutrino.domain.insights.Insights
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/** The Health page: totals and charts for the last 30 days, 12 weeks, 12 months or 7 years. */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModel(
    private val meals: MealRepository,
    private val glucose: GlucoseRepository,
    private val settings: SettingsRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
) : ViewModel() {

    private val _range = MutableStateFlow(InsightRange.Day)
    val range: StateFlow<InsightRange> = _range.asStateFlow()

    private val today = MutableStateFlow(LocalDate.now(zone))

    val summary: StateFlow<InsightSummary?> = combine(_range, today) { range, day -> range to day }
        .flatMapLatest { (range, day) ->
            meals.observeRange(range.firstStart(day, firstDayOfWeek), day, zone).map { data ->
                Insights.summarize(range, day, data.meals, data.water, data.topFoods, firstDayOfWeek)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Blood glucose for the same range; empty until readings exist. */
    val glucoseSummary: StateFlow<GlucoseSummary?> = combine(_range, today) { range, day -> range to day }
        .flatMapLatest { (range, day) ->
            combine(
                glucose.observeBetween(range.firstStart(day, firstDayOfWeek), day, zone),
                settings.settings.map { it.glucoseLow to it.glucoseHigh }.distinctUntilChanged(),
            ) { readings, (low, high) ->
                val points = readings.map {
                    GlucosePoint(Instant.ofEpochMilli(it.measuredAtEpochMs).atZone(zone).toLocalDate(), it.mmolPerL, it.relationEnum)
                }
                GlucoseInsights.summarize(range, day, points, low, high, firstDayOfWeek)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setRange(range: InsightRange) {
        _range.value = range
    }

    /** Call on resume so "last 30 days" moves on at midnight. */
    fun refreshDate() {
        today.value = LocalDate.now(zone)
    }
}
