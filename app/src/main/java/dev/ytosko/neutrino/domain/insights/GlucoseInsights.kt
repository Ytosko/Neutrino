package dev.ytosko.neutrino.domain.insights

import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import java.time.DayOfWeek
import java.time.LocalDate

/** One reading, reduced to what the Health page needs. */
data class GlucosePoint(val date: LocalDate, val mmolPerL: Double, val relation: GlucoseRelation)

/** Readings in one bar (day, week, month or year). */
data class GlucoseBucket(val start: LocalDate, val end: LocalDate, val readings: Int, val average: Double?)

data class GlucoseSummary(
    val range: InsightRange,
    val buckets: List<GlucoseBucket>,
    val readings: Int,
    val average: Double?,
    val low: Double,
    val high: Double,
    /** Share of readings below, inside and above the target range; each 0..1. */
    val belowShare: Double,
    val inRangeShare: Double,
    val aboveShare: Double,
    /** Average per meal mark, only for marks that have readings, in [GlucoseRelation] order. */
    val byRelation: List<Pair<GlucoseRelation, Double>>,
) {
    val isEmpty: Boolean get() = readings == 0
}

object GlucoseInsights {

    fun summarize(
        range: InsightRange,
        today: LocalDate,
        points: List<GlucosePoint>,
        low: Double,
        high: Double,
        firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    ): GlucoseSummary {
        val first = range.firstStart(today, firstDayOfWeek)
        val end = today.plusDays(1)
        val inRange = points.filter { it.date >= first && it.date < end }

        val buckets = buildList {
            var start = first
            repeat(range.count) {
                val next = range.next(start)
                val values = inRange.filter { it.date >= start && it.date < next }.map { it.mmolPerL }
                add(GlucoseBucket(start, next, values.size, values.takeIf { it.isNotEmpty() }?.average()))
                start = next
            }
        }
        val n = inRange.size
        fun share(count: Int) = if (n == 0) 0.0 else count.toDouble() / n
        return GlucoseSummary(
            range = range,
            buckets = buckets,
            readings = n,
            average = inRange.takeIf { it.isNotEmpty() }?.map { it.mmolPerL }?.average(),
            low = low,
            high = high,
            belowShare = share(inRange.count { it.mmolPerL < low }),
            inRangeShare = share(inRange.count { it.mmolPerL in low..high }),
            aboveShare = share(inRange.count { it.mmolPerL > high }),
            byRelation = GlucoseRelation.entries.mapNotNull { relation ->
                val values = inRange.filter { it.relation == relation }.map { it.mmolPerL }
                if (values.isEmpty()) null else relation to values.average()
            },
        )
    }
}
