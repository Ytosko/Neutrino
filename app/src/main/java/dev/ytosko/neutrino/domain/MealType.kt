package dev.ytosko.neutrino.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class MealType { Breakfast, Lunch, Snack, Dinner }

/**
 * Start times of each meal window in the user's local time. Windows run from one
 * start to the next; anything from [lateNightStart] until [breakfast] is a snack.
 *
 * Defaults suit South Asian eating times (late lunch and dinner) and are user-configurable.
 */
data class MealWindows(
    val breakfast: LocalTime = LocalTime.of(5, 0),
    val lunch: LocalTime = LocalTime.of(11, 30),
    val snack: LocalTime = LocalTime.of(16, 0),
    val dinner: LocalTime = LocalTime.of(19, 30),
    val lateNightStart: LocalTime = LocalTime.of(23, 30),
) {
    init {
        require(breakfast < lunch && lunch < snack && snack < dinner && dinner < lateNightStart) {
            "Meal windows must be in order: breakfast < lunch < snack < dinner < late night"
        }
    }

    /** Meal type for a wall-clock [time]. */
    fun mealAt(time: LocalTime): MealType = when {
        time < breakfast -> MealType.Snack
        time < lunch -> MealType.Breakfast
        time < snack -> MealType.Lunch
        time < dinner -> MealType.Snack
        time < lateNightStart -> MealType.Dinner
        else -> MealType.Snack
    }

    /** Meal type for an [instant] as experienced in [zone] (e.g. `Asia/Dhaka`). */
    fun mealAt(instant: Instant, zone: ZoneId): MealType =
        mealAt(instant.atZone(zone).toLocalTime())

    /** The next main meal (not snack) after [time], for "Next up" hints. */
    fun nextMainMeal(time: LocalTime): MealType = when {
        time < breakfast -> MealType.Breakfast
        time < lunch -> MealType.Lunch
        time < dinner -> MealType.Dinner
        else -> MealType.Breakfast
    }
}
