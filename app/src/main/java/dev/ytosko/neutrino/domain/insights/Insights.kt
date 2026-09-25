package dev.ytosko.neutrino.domain.insights

import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/** The tabs on the Health page: one calendar day, week, month or year at a time. */
enum class InsightRange {
    Day, Week, Month, Year;

    /** First day of the period that contains [date]. */
    fun periodStart(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate = when (this) {
        Day -> date
        Week -> date.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        Month -> date.withDayOfMonth(1)
        Year -> date.withDayOfYear(1)
    }

    fun next(start: LocalDate): LocalDate = when (this) {
        Day -> start.plusDays(1)
        Week -> start.plusWeeks(1)
        Month -> start.plusMonths(1)
        Year -> start.plusYears(1)
    }

    fun previous(start: LocalDate): LocalDate = when (this) {
        Day -> start.minusDays(1)
        Week -> start.minusWeeks(1)
        Month -> start.minusMonths(1)
        Year -> start.minusYears(1)
    }
}

/**
 * One period on the Health page, e.g. the week of 21 Sep 2026. A day splits into four 6-hour
 * blocks, or 24 hours when [hourly]; a week or month into days; a year into months.
 */
data class Period(val range: InsightRange, val start: LocalDate, val hourly: Boolean = false) {
    /** Exclusive. */
    val end: LocalDate get() = range.next(start)

    fun previous(): Period = copy(start = range.previous(start))
    fun next(): Period = copy(start = range.next(start))

    /** Whether [today] falls inside, so there's nothing newer to go to. */
    fun contains(date: LocalDate): Boolean = date >= start && date < end

    /** Days up to and including [today]: "logged on 3 of 5 days" in the current week. */
    fun daysSoFar(today: LocalDate): Int =
        ChronoUnit.DAYS.between(start, minOf(end, today.plusDays(1))).toInt().coerceAtLeast(0)

    /** The bars, oldest first. */
    fun slots(): List<Slot> = when (range) {
        InsightRange.Day -> {
            val step = if (hourly) 1 else 6
            (0 until 24 step step).map { Slot(start, end, it, it + step) }
        }
        InsightRange.Week, InsightRange.Month ->
            generateSequence(start) { it.plusDays(1) }.takeWhile { it < end }.map { Slot(it, it.plusDays(1)) }.toList()
        InsightRange.Year ->
            (0 until 12).map { Slot(start.plusMonths(it.toLong()), start.plusMonths(it + 1L)) }
    }

    companion object {
        fun containing(range: InsightRange, date: LocalDate, firstDayOfWeek: DayOfWeek, hourly: Boolean = false) =
            Period(range, range.periodStart(date, firstDayOfWeek), hourly)
    }
}

/** One bar's span: days from [start] to [end] (exclusive), and within them hours [fromHour] to [toHour]. */
data class Slot(val start: LocalDate, val end: LocalDate, val fromHour: Int = 0, val toHour: Int = 24) {
    fun holds(date: LocalDate, hour: Int): Boolean = date >= start && date < end && hour >= fromHour && hour < toHour

    /** Part of a day rather than whole days. */
    val isPartOfDay: Boolean get() = fromHour != 0 || toHour != 24
}

/** A logged meal, reduced to what the charts need. [hour] is the local hour it was eaten. */
data class MealPoint(
    val date: LocalDate,
    val type: MealType,
    val nutrition: Nutrition,
    val name: String = "",
    val at: java.time.Instant? = null,
    val hour: Int = 12,
)

data class WaterPoint(val date: LocalDate, val ml: Int, val hour: Int = 12)

data class FoodCount(val name: String, val category: String, val times: Int)

/** One bar: part of a day, a day, or a month. */
data class Bucket(
    val slot: Slot,
    val nutrition: Nutrition,
    val waterMl: Int,
    /** Days with at least one meal, and with any water. */
    val daysLogged: Int,
    val waterDays: Int,
    val meals: Int,
) {
    val start: LocalDate get() = slot.start

    /** Per logged day, for bars that span a month (the Year tab). */
    val perDayNutrition: Nutrition
        get() = if (daysLogged <= 1) nutrition else nutrition * (1.0 / daysLogged)

    val perDayWaterMl: Int get() = if (waterDays <= 1) waterMl else waterMl / waterDays
}

data class InsightSummary(
    val period: Period,
    val buckets: List<Bucket>,
    val totals: Nutrition,
    val waterMl: Int,
    val daysLogged: Int,
    val waterDays: Int,
    /** Days in the period up to today. */
    val totalDays: Int,
    val meals: Int,
    val byMealType: Map<MealType, Nutrition>,
    val topFoods: List<FoodCount>,
) {
    val range: InsightRange get() = period.range

    /** Calories from each macro: 4 kcal/g for carbs and protein, 9 for fat. */
    val macroKcal: Triple<Double, Double, Double>
        get() = Triple(totals.carbsG * 4, totals.proteinG * 4, totals.fatG * 9)

    /** Average per day that has at least one meal, so empty days don't drag it down. */
    fun perLoggedDay(value: Double): Double = if (daysLogged == 0) 0.0 else value / daysLogged

    val waterPerDay: Int get() = if (waterDays == 0) 0 else waterMl / waterDays

    val isEmpty: Boolean get() = meals == 0 && waterMl == 0
}

object Insights {

    fun summarize(
        period: Period,
        today: LocalDate,
        meals: List<MealPoint>,
        water: List<WaterPoint>,
        topFoods: List<FoodCount> = emptyList(),
    ): InsightSummary {
        val inRange = meals.filter { period.contains(it.date) }
        val waterInRange = water.filter { period.contains(it.date) }

        val buckets = period.slots().map { slot ->
            val bucketMeals = inRange.filter { slot.holds(it.date, it.hour) }
            val bucketWater = waterInRange.filter { slot.holds(it.date, it.hour) }
            Bucket(
                slot = slot,
                nutrition = bucketMeals.fold(Nutrition.ZERO) { acc, m -> acc + m.nutrition },
                waterMl = bucketWater.sumOf { it.ml },
                daysLogged = bucketMeals.mapTo(HashSet()) { it.date }.size,
                waterDays = bucketWater.mapTo(HashSet()) { it.date }.size,
                meals = bucketMeals.size,
            )
        }
        return InsightSummary(
            period = period,
            buckets = buckets,
            totals = inRange.fold(Nutrition.ZERO) { acc, m -> acc + m.nutrition },
            waterMl = waterInRange.sumOf { it.ml },
            daysLogged = inRange.mapTo(HashSet()) { it.date }.size,
            waterDays = waterInRange.mapTo(HashSet()) { it.date }.size,
            totalDays = period.daysSoFar(today),
            meals = inRange.size,
            byMealType = MealType.entries.associateWith { type ->
                inRange.filter { it.type == type }.fold(Nutrition.ZERO) { acc, m -> acc + m.nutrition }
            },
            topFoods = topFoods,
        )
    }
}

/**
 * Short numbers that fit a small tile, at most four characters before the unit:
 * 850 → "850", 1 234 → "1.2k", 12 345 → "12k", 1 234 567 → "1.2M".
 */
fun compactNumber(value: Double): String {
    val v = value.coerceAtLeast(0.0)
    fun one(x: Double): String = String.format(Locale.US, "%.1f", x).removeSuffix(".0")
    return when {
        v < 10 -> one(v)
        v < 1_000 -> v.roundToInt().toString()
        v < 9_950 -> one(v / 1_000) + "k"
        v < 999_500 -> (v / 1_000).roundToInt().toString() + "k"
        v < 9_950_000 -> one(v / 1_000_000) + "M"
        else -> (v / 1_000_000).roundToInt().toString() + "M"
    }
}

/** Grams that switch to kilograms: 85 → "85g", 1 234 → "1.2kg", 12 345 → "12kg". */
fun compactGrams(grams: Double): String {
    val g = grams.coerceAtLeast(0.0)
    fun one(x: Double): String = String.format(Locale.US, "%.1f", x).removeSuffix(".0")
    return when {
        g < 100 -> one(g) + "g"
        g < 1_000 -> g.roundToInt().toString() + "g"
        g < 9_950 -> one(g / 1_000) + "kg"
        g < 999_500 -> (g / 1_000).roundToInt().toString() + "kg"
        else -> one(g / 1_000_000) + "t"
    }
}

/** Water: 500 → "500ml", 2 250 → "2.3L", 45 000 → "45L". */
fun compactMl(ml: Int): String {
    fun one(x: Double): String = String.format(Locale.US, "%.1f", x).removeSuffix(".0")
    return when {
        ml < 1_000 -> "${ml}ml"
        ml < 9_950 -> one(ml / 1_000.0) + "L"
        else -> compactNumber(ml / 1_000.0) + "L"
    }
}

/** Water for the day card: millilitres below a litre, then litres ("750 ml", "1.25 L"). */
fun formatWater(ml: Int): String {
    if (ml < 1_000) return "$ml ml"
    val litres = String.format(Locale.US, "%.2f", ml / 1_000.0).trimEnd('0').trimEnd('.')
    return "$litres L"
}
