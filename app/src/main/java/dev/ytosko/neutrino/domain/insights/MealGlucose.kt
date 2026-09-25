package dev.ytosko.neutrino.domain.insights

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** A glucose reading reduced to what matching needs: when, and the value in mmol/L. */
data class TimedReading(val at: Instant, val mmolPerL: Double)

/** Readings around one meal: the last one before eating and the one closest to [MealGlucose.AFTER] later. */
data class MealGlucose(val before: TimedReading?, val after: TimedReading?) {
    /** How much higher (or lower) the after reading was, in mmol/L; null unless both exist. */
    val rise: Double? get() = if (before != null && after != null) after.mmolPerL - before.mmolPerL else null
    val isEmpty: Boolean get() = before == null && after == null

    companion object {
        /** Before: up to an hour before eating (or a few minutes after, while sitting down to eat). */
        val BEFORE_WINDOW: Duration = Duration.ofMinutes(60)
        val BEFORE_GRACE: Duration = Duration.ofMinutes(10)

        /** After: the reading closest to 2 hours later, anywhere from 1 to 3 hours after eating. */
        val AFTER: Duration = Duration.ofMinutes(120)
        val AFTER_FROM: Duration = Duration.ofMinutes(60)
        val AFTER_TO: Duration = Duration.ofMinutes(180)

        fun match(mealAt: Instant, readings: List<TimedReading>): MealGlucose {
            val before = readings
                .filter { it.at >= mealAt - BEFORE_WINDOW && it.at <= mealAt + BEFORE_GRACE }
                .maxByOrNull { it.at }
            val target = mealAt + AFTER
            val after = readings
                .filter { it.at >= mealAt + AFTER_FROM && it.at <= mealAt + AFTER_TO }
                .minByOrNull { abs(Duration.between(target, it.at).toMinutes()) }
            return MealGlucose(before, after)
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
            .mapNotNull { (name, at) -> MealGlucose.match(at, sorted).rise?.let { name to it } }
            .groupBy({ it.first.trim().lowercase() }, { it })
            .map { (_, rows) -> MealRise(rows.first().first.trim(), rows.map { it.second }.average(), rows.size) }
            .filter { it.times >= minTimes }
            .sortedByDescending { it.averageRise }
            .take(limit)
    }
}
