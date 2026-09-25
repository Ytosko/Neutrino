package dev.ytosko.neutrino.domain.insights

import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/** What each tab on the Health page covers: one bar per [unit], [count] bars ending today. */
enum class InsightRange(val count: Int) {
    Day(30), Week(12), Month(12), Year(7);

    /** First day of the bucket that contains [date]. */
    fun bucketStart(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate = when (this) {
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

    /** Oldest bucket start: [count] buckets ending with the one holding [today]. */
    fun firstStart(today: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate {
        var start = bucketStart(today, firstDayOfWeek)
        repeat(count - 1) { start = previous(start) }
        return start
    }
}

/** A logged meal, reduced to what the charts need. */
data class MealPoint(
    val date: LocalDate,
    val type: MealType,
    val nutrition: Nutrition,
    val name: String = "",
    val at: java.time.Instant? = null,
)

data class WaterPoint(val date: LocalDate, val ml: Int)

data class FoodCount(val name: String, val category: String, val times: Int)

/** One bar: a day, week, month or year. */
data class Bucket(
    val start: LocalDate,
    /** Exclusive. */
    val end: LocalDate,
    val nutrition: Nutrition,
    val waterMl: Int,
    val daysLogged: Int,
    val meals: Int,
)

data class InsightSummary(
    val range: InsightRange,
    val buckets: List<Bucket>,
    val totals: Nutrition,
    val waterMl: Int,
    val daysLogged: Int,
    val totalDays: Int,
    val byMealType: Map<MealType, Nutrition>,
    val topFoods: List<FoodCount>,
) {
    /** Calories from each macro: 4 kcal/g for carbs and protein, 9 for fat. */
    val macroKcal: Triple<Double, Double, Double>
        get() = Triple(totals.carbsG * 4, totals.proteinG * 4, totals.fatG * 9)

    /** Average per day that has at least one meal, so empty days don't drag it down. */
    fun perLoggedDay(value: Double): Double = if (daysLogged == 0) 0.0 else value / daysLogged

    val isEmpty: Boolean get() = daysLogged == 0 && waterMl == 0
}

object Insights {

    fun summarize(
        range: InsightRange,
        today: LocalDate,
        meals: List<MealPoint>,
        water: List<WaterPoint>,
        topFoods: List<FoodCount> = emptyList(),
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): InsightSummary {
        val first = range.firstStart(today, firstDayOfWeek)
        val end = today.plusDays(1)
        val inRange = meals.filter { it.date >= first && it.date < end }
        val waterInRange = water.filter { it.date >= first && it.date < end }

        val buckets = buildList {
            var start = first
            repeat(range.count) {
                val next = range.next(start)
                val bucketMeals = inRange.filter { it.date >= start && it.date < next }
                add(
                    Bucket(
                        start = start,
                        end = next,
                        nutrition = bucketMeals.fold(Nutrition.ZERO) { acc, m -> acc + m.nutrition },
                        waterMl = waterInRange.filter { it.date >= start && it.date < next }.sumOf { it.ml },
                        daysLogged = bucketMeals.mapTo(HashSet()) { it.date }.size,
                        meals = bucketMeals.size,
                    ),
                )
                start = next
            }
        }
        return InsightSummary(
            range = range,
            buckets = buckets,
            totals = inRange.fold(Nutrition.ZERO) { acc, m -> acc + m.nutrition },
            waterMl = waterInRange.sumOf { it.ml },
            daysLogged = inRange.mapTo(HashSet()) { it.date }.size,
            totalDays = ChronoUnit.DAYS.between(first, end).toInt(),
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
