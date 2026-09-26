package dev.ytosko.neutrino.ui.insights

import dev.ytosko.neutrino.domain.insights.GmiCalculator
import dev.ytosko.neutrino.domain.insights.Gmi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ytosko.neutrino.data.meal.MealRepository
import dev.ytosko.neutrino.data.glucose.GlucoseRepository
import dev.ytosko.neutrino.data.glucose.relationEnum
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.insights.GlucoseInsights
import dev.ytosko.neutrino.domain.insights.GlucosePoint
import dev.ytosko.neutrino.domain.insights.GlucoseSummary
import dev.ytosko.neutrino.domain.insights.MealGlucoseInsights
import dev.ytosko.neutrino.domain.insights.MealRise
import dev.ytosko.neutrino.domain.insights.TimedReading
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import dev.ytosko.neutrino.domain.insights.InsightRange
import dev.ytosko.neutrino.domain.insights.InsightSummary
import dev.ytosko.neutrino.domain.insights.Insights
import dev.ytosko.neutrino.domain.insights.Period
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

/** Daily goals the charts draw as a dashed line; null means no goal. */
data class ChartGoals(val kcal: Int?, val carbs: Int?, val protein: Int?, val fat: Int?, val waterMl: Int?)

/**
 * The Health page: one calendar day, week, month or year at a time, stepped with the ‹ › arrows.
 * Everything on the page follows [period].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealthViewModel(
    private val meals: MealRepository,
    private val glucose: GlucoseRepository,
    private val settings: SettingsRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek,
) : ViewModel() {

    private val _today = MutableStateFlow(LocalDate.now(zone))
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    private val _period = MutableStateFlow(Period.containing(InsightRange.Day, _today.value, firstDayOfWeek))
    val period: StateFlow<Period> = _period.asStateFlow()

    val range: StateFlow<InsightRange> = _period.map { it.range }
        .stateIn(viewModelScope, SharingStarted.Eagerly, InsightRange.Day)

    val goals: StateFlow<ChartGoals?> = settings.settings
        .map { ChartGoals(it.kcalGoal, it.carbGoalG, it.proteinGoalG, it.fatGoalG, it.waterGoalMl) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val summary: StateFlow<InsightSummary?> = combine(_period, _today) { period, day -> period to day }
        .flatMapLatest { (period, day) ->
            meals.observeRange(period.start, period.end.minusDays(1), zone).map { data ->
                Insights.summarize(period, day, data.meals, data.water, data.topFoods)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Blood glucose for the same period; empty until readings exist. */
    val glucoseSummary: StateFlow<GlucoseSummary?> = _period
        .flatMapLatest { period ->
            combine(
                glucose.observeBetween(period.start, period.end.minusDays(1), zone),
                settings.settings.map { it.glucoseLow to it.glucoseHigh }.distinctUntilChanged(),
            ) { readings, (low, high) ->
                val points = readings.map {
                    val at = Instant.ofEpochMilli(it.measuredAtEpochMs).atZone(zone)
                    GlucosePoint(at.toLocalDate(), it.mmolPerL, it.relationEnum, at.hour)
                }
                GlucoseInsights.summarize(period, points, low, high)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Estimated A1c from the last 90 days of readings, whatever period is shown; null until there's enough. */
    val gmi: StateFlow<Gmi?> = _today
        .flatMapLatest { day ->
            glucose.observeBetween(day.minusDays(GmiCalculator.WINDOW_DAYS - 1), day, zone).map { readings ->
                GmiCalculator.from(readings.map { Instant.ofEpochMilli(it.measuredAtEpochMs).atZone(zone).toLocalDate() to it.mmolPerL })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Meals in the period with a glucose reading before and ~2 h after, biggest average rise first. */
    val mealRises: StateFlow<Pair<Period, List<MealRise>>?> = _period
        .flatMapLatest { period ->
            combine(
                meals.observeRange(period.start, period.end.minusDays(1), zone),
                // One extra day, for "after" readings of late dinners.
                glucose.observeBetween(period.start, period.end, zone),
            ) { data, readings ->
                val timed = readings.map { TimedReading(Instant.ofEpochMilli(it.measuredAtEpochMs), it.mmolPerL) }
                val eaten = data.meals.mapNotNull { m -> m.at?.let { m.name to it } }
                period to MealGlucoseInsights.biggestRises(eaten, timed)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Switching tabs keeps the date you were looking at: last week's Month tab is last week's month. */
    fun setRange(range: InsightRange) {
        val current = _period.value
        val anchor = minOf(current.end.minusDays(1), _today.value)
        _period.value = Period.containing(range, anchor, firstDayOfWeek, current.hourly)
    }

    fun setHourly(hourly: Boolean) {
        _period.value = _period.value.copy(hourly = hourly)
    }

    fun previous() {
        _period.value = _period.value.previous()
    }

    /** Never past the period holding today. */
    fun next() {
        val next = _period.value.next()
        if (next.start <= _today.value) _period.value = next
    }

    /** Call on resume so the current period moves on at midnight. */
    fun refreshDate() {
        val old = _today.value
        val now = LocalDate.now(zone)
        _today.value = now
        // Still on the old "today"? Follow it to the new one.
        if (now != old && _period.value.contains(old) && !_period.value.contains(now)) {
            _period.value = Period.containing(_period.value.range, now, firstDayOfWeek, _period.value.hourly)
        }
    }
}
