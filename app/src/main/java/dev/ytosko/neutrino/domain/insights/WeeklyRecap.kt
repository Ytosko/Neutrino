package dev.ytosko.neutrino.domain.insights

import dev.ytosko.neutrino.domain.MealType
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Glucose over the week, only when the user chose to include it. mmol/L, shown in [unitLabel]. */
data class GlucoseWeek(val average: String, val unitLabel: String, val inRangePercent: Int, val readings: Int)

/**
 * The week in plain facts, for the AI to put into a few friendly sentences. Only totals and
 * patterns: no single meal's details, no names of places, and glucose only if the user said so.
 */
object WeeklyRecap {

    fun facts(
        from: LocalDate,
        toInclusive: LocalDate,
        meals: List<MealPoint>,
        water: List<WaterPoint>,
        topFoods: List<FoodCount>,
        kcalGoal: Int?,
        waterGoalMl: Int?,
        glucose: GlucoseWeek?,
    ): List<String> {
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(toInclusive) }.toList()
        val logged = days.filter { day -> meals.any { it.date == day } }
        if (logged.isEmpty()) return emptyList()
        fun dayName(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

        val byDay = logged.associateWith { day -> meals.filter { it.date == day } }
        val kcal = byDay.mapValues { (_, list) -> list.sumOf { it.nutrition.calories } }
        val carbs = byDay.mapValues { (_, list) -> list.sumOf { it.nutrition.carbsG } }
        val protein = byDay.mapValues { (_, list) -> list.sumOf { it.nutrition.proteinG } }
        val fat = byDay.mapValues { (_, list) -> list.sumOf { it.nutrition.fatG } }
        fun avg(values: Collection<Double>) = (values.sum() / values.size).roundToInt()

        return buildList {
            add("Days with meals logged: ${logged.size} of ${days.size}.")
            add("Average per logged day: ${avg(kcal.values)} kcal, ${avg(carbs.values)} g carbs, ${avg(protein.values)} g protein, ${avg(fat.values)} g fat.")
            kcalGoal?.let { goal -> add("Calorie goal $goal kcal: met or under on ${kcal.count { it.value <= goal }} of ${logged.size} days.") }
            val high = kcal.maxBy { it.value }
            val low = kcal.minBy { it.value }
            if (logged.size > 1) add("Most calories on ${dayName(high.key)} (${high.value.roundToInt()} kcal), fewest on ${dayName(low.key)} (${low.value.roundToInt()} kcal).")
            val perType = MealType.entries.mapNotNull { type ->
                val list = meals.filter { it.type == type }
                if (list.isEmpty()) null else "${type.name.lowercase()} ${list.size}× (avg ${avg(list.map { it.nutrition.calories })} kcal, ${avg(list.map { it.nutrition.carbsG })} g carbs)"
            }
            if (perType.isNotEmpty()) add("Meals by type: " + perType.joinToString("; ") + ".")
            val waterByDay = days.associateWith { day -> water.filter { it.date == day }.sumOf { it.ml } }
            val waterDays = waterByDay.filter { it.value > 0 }
            if (waterDays.isNotEmpty()) {
                add("Water: average ${(waterDays.values.sum() / waterDays.size)} ml on ${waterDays.size} days; lowest on ${dayName(waterDays.minBy { it.value }.key)}.")
                waterGoalMl?.let { goal -> add("Water goal $goal ml reached on ${waterByDay.count { it.value >= goal }} days.") }
            } else {
                add("No water logged this week.")
            }
            if (topFoods.isNotEmpty()) add("Most eaten: " + topFoods.take(3).joinToString(", ") { "${it.name} (${it.times}×)" } + ".")
            glucose?.let {
                add("Blood glucose: ${it.readings} readings, average ${it.average} ${it.unitLabel}, ${it.inRangePercent}% in the user's target range.")
            }
        }
    }
}
