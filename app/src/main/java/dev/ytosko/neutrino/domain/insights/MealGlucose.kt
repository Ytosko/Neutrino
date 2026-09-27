package dev.ytosko.neutrino.domain.insights

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * A glucose reading reduced to what matching needs: when, the value in mmol/L, and how the user
 * linked it: to a meal ([linkedMealId]), or kept out of automatic matching ([autoMatch] false).
 */
data class TimedReading(
    val at: Instant,
    val mmolPerL: Double,
    val id: String = "",
    val linkedMealId: String? = null,
    val autoMatch: Boolean = true,
)

/** A meal to match readings to: its id and when it was eaten. */
data class MealTime(val id: String, val at: Instant)

/**
 * Readings around one meal: the last one before eating, and the one after it (closest to
 * [MealGlucose.AFTER], anywhere from [MealGlucose.AFTER_SHOWN_FROM] to [MealGlucose.AFTER_TO]).
 * A reading the user linked to this meal always counts.
 */
data class MealGlucose(val before: TimedReading?, val after: TimedReading?, val mealAt: Instant? = null) {
    /** How much higher (or lower) the after reading was, in mmol/L; null unless both exist. */
    val rise: Double? get() = if (before != null && after != null) after.mmolPerL - before.mmolPerL else null

    /** Minutes from eating to the after reading, e.g. 35 or 125. */
    val afterMinutes: Long? get() = if (after != null && mealAt != null) Duration.between(mealAt, after.at).toMinutes() else null

    /**
     * The rise, only when the after reading is 1–3 hours after eating, so averages compare like with
     * like (a 30-minute reading is taken before glucose peaks).
     */
    val comparableRise: Double?
        get() = rise?.takeIf { afterMinutes?.let { it in COMPARABLE_FROM.toMinutes()..AFTER_TO.toMinutes() } == true }

    val isEmpty: Boolean get() = before == null && after == null

    companion object {
        /** Before: up to an hour before eating (or a few minutes after, while sitting down to eat). */
        val BEFORE_WINDOW: Duration = Duration.ofMinutes(60)
        val BEFORE_GRACE: Duration = Duration.ofMinutes(10)

        /** After: the reading closest to 2 hours later, shown from 30 minutes to 3 hours after eating. */
        val AFTER: Duration = Duration.ofMinutes(120)
        val AFTER_SHOWN_FROM: Duration = Duration.ofMinutes(30)
        val AFTER_TO: Duration = Duration.ofMinutes(180)

        /** Averages only use after readings from 1 hour on. */
        val COMPARABLE_FROM: Duration = Duration.ofMinutes(60)

        /** The same as [matchAll] for a single meal with no ids (every reading matched by time). */
        fun match(mealAt: Instant, readings: List<TimedReading>): MealGlucose =
            matchAll(listOf(MealTime("", mealAt)), readings.map { it.copy(linkedMealId = null) })[""] ?: MealGlucose(null, null, mealAt)

        /**
         * Before and after readings for each of [meals], by meal id. Readings linked to one of these
         * meals go to that meal only; readings linked to a meal that isn't here (e.g. deleted) are
         * matched by time like any other, unless the user took them out of automatic matching.
         */
        fun matchAll(meals: List<MealTime>, readings: List<TimedReading>): Map<String, MealGlucose> {
            val ids = meals.mapTo(HashSet()) { it.id }
            val sorted = readings.sortedBy { it.at }
            val free = sorted.filter { (it.linkedMealId == null || it.linkedMealId !in ids) && it.autoMatch }
            val starts = meals.map { it.at }.sorted()
            return meals.associate { meal ->
                val linked = sorted.filter { it.linkedMealId == meal.id }
                // By time, an after reading must come before the next meal started: later ones show that meal instead.
                val nextMeal = starts.firstOrNull { it > meal.at }
                val afterLimit = listOfNotNull(meal.at + AFTER_TO, nextMeal?.plus(BEFORE_GRACE)).min()
                val beforeLimit = meal.at + BEFORE_GRACE
                val target = meal.at + AFTER
                fun closestToTwoHours(list: List<TimedReading>) = list.minByOrNull { abs(Duration.between(target, it.at).toMinutes()) }
                val before = linked.filter { it.at <= beforeLimit }.maxByOrNull { it.at }
                    ?: free.filter { it.at >= meal.at - BEFORE_WINDOW && it.at <= beforeLimit }.maxByOrNull { it.at }
                val after = closestToTwoHours(linked.filter { it.at > beforeLimit })
                    ?: closestToTwoHours(free.filter { it.at >= meal.at + AFTER_SHOWN_FROM && it.at <= afterLimit })
                meal.id to MealGlucose(before, after, meal.at)
            }
        }
    }
}

/** How a food (by meal name) tends to move glucose: the average rise over [times] meals. */
data class MealRise(val name: String, val averageRise: Double, val times: Int)

object MealGlucoseInsights {

    /**
     * Meals eaten at least [minTimes] times with a before and after reading, highest average rise
     * first. Only the user's own numbers: no judgement of what is good or bad.
     */
    fun biggestRises(
        meals: List<Pair<String, Instant>>,
        readings: List<TimedReading>,
        minTimes: Int = 1,
        limit: Int = 5,
    ): List<MealRise> {
        val sorted = readings.sortedBy { it.at }
        return meals
            .mapNotNull { (name, at) -> MealGlucose.match(at, sorted).comparableRise?.let { name to it } }
            .groupBy({ it.first.trim().lowercase() }, { it })
            .map { (_, rows) -> MealRise(rows.first().first.trim(), rows.map { it.second }.average(), rows.size) }
            .filter { it.times >= minTimes }
            .sortedByDescending { it.averageRise }
            .take(limit)
    }
}

/** How glucose tends to move after meals that include one food: its average change over [times] meals. */
data class FoodRise(val key: String, val name: String, val averageRise: Double, val times: Int)

/** A meal reduced to its id, when it was eaten and its foods as (key, name). */
data class MealFoods(val at: Instant, val foods: List<Pair<String, String>>, val id: String = "")

object FoodGlucoseInsights {
    /** Below this many meals a food's "usual" change would mostly be noise. */
    const val MIN_TIMES = 3

    /**
     * For every meal with a reading before and 1–3 hours after (by time, or linked by the user), its
     * change counts for each food in it (a meal of rice and dal counts for both). Foods seen at least
     * [minTimes] times, biggest average rise first. The user's own numbers only; no judgement.
     */
    fun rises(meals: List<MealFoods>, readings: List<TimedReading>, minTimes: Int = MIN_TIMES): List<FoodRise> {
        val keyed = meals.mapIndexed { i, m -> if (m.id.isEmpty()) m.copy(id = "#$i") else m }
        val matched = MealGlucose.matchAll(keyed.map { MealTime(it.id, it.at) }, readings)
        return keyed
            .flatMap { meal ->
                val rise = matched[meal.id]?.comparableRise ?: return@flatMap emptyList()
                meal.foods.distinctBy { it.first }.map { (key, name) -> Triple(key, name, rise) }
            }
            .groupBy { it.first }
            .map { (key, rows) -> FoodRise(key, rows.last().second, rows.map { it.third }.average(), rows.size) }
            .filter { it.times >= minTimes }
            .sortedByDescending { it.averageRise }
    }
}
