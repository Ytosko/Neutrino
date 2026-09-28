package dev.ytosko.neutrino.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** [Sehri] (before dawn) and [Iftar] (at sunset) are Ramadan's meals, offered in Ramadan mode. */
enum class MealType { Breakfast, Lunch, Snack, Dinner, Sehri, Iftar }

/**
 * Ramadan mode: [sehriEnds] (when the fast begins) and [iftar] (sunset). The user sets them and can
 * change them as the days shift.
 */
data class RamadanTimes(val sehriEnds: LocalTime, val iftar: LocalTime) {
    /** The Sehri reminder goes before the fast begins; the Iftar one a little after breaking it. */
    val sehriReminder: LocalTime get() = sehriEnds.minusMinutes(45)
    val iftarReminder: LocalTime get() = iftar.plusMinutes(30)
}

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
    /** Set while Ramadan mode is on: meals are then Sehri, Iftar, dinner and snacks. */
    val ramadan: RamadanTimes? = null,
) {
    init {
        require(breakfast < lunch && lunch < snack && snack < dinner && dinner < lateNightStart) {
            "Meal windows must be in order: breakfast < lunch < snack < dinner < late night"
        }
    }

    /** Meal type for a wall-clock [time]. */
    fun mealAt(time: LocalTime): MealType = ramadan?.let { ramadanMealAt(time, it) } ?: when {
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

    /**
     * In Ramadan: Sehri from about 3 hours before the fast begins until 30 minutes after, Iftar from
     * 15 minutes before sunset until 90 minutes after, dinner from then until late night, and
     * anything else a snack.
     */
    private fun ramadanMealAt(time: LocalTime, times: RamadanTimes): MealType {
        fun within(from: LocalTime, to: LocalTime) = if (from <= to) time >= from && time < to else time >= from || time < to
        return when {
            within(times.sehriEnds.minusHours(3), times.sehriEnds.plusMinutes(30)) -> MealType.Sehri
            within(times.iftar.minusMinutes(15), times.iftar.plusMinutes(90)) -> MealType.Iftar
            within(times.iftar.plusMinutes(90), lateNightStart) -> MealType.Dinner
            else -> MealType.Snack
        }
    }

    /** The next main meal (not snack) after [time], for "Next up" hints. */
    fun nextMainMeal(time: LocalTime): MealType = ramadan?.let { if (time < it.sehriEnds || time >= it.iftar.plusMinutes(90)) MealType.Sehri else MealType.Iftar } ?: when {
        time < breakfast -> MealType.Breakfast
        time < lunch -> MealType.Lunch
        time < dinner -> MealType.Dinner
        else -> MealType.Breakfast
    }
}
