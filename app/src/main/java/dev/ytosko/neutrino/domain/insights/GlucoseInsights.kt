package dev.ytosko.neutrino.domain.insights

import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import java.time.LocalDate

/** One reading, reduced to what the Health page needs. [hour] is the local hour it was taken. */
data class GlucosePoint(val date: LocalDate, val mmolPerL: Double, val relation: GlucoseRelation, val hour: Int = 12)

/** Readings in one bar: the average of every reading in that block, hour, day or month. */
data class GlucoseBucket(val slot: Slot, val readings: Int, val average: Double?) {
    val start: LocalDate get() = slot.start
}

data class GlucoseSummary(
    val period: Period,
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
    val range: InsightRange get() = period.range
    val isEmpty: Boolean get() = readings == 0
}

object GlucoseInsights {

    fun summarize(
        period: Period,
        points: List<GlucosePoint>,
        low: Double,
        high: Double,
    ): GlucoseSummary {
        val inRange = points.filter { period.contains(it.date) }
        val buckets = period.slots().map { slot ->
            val values = inRange.filter { slot.holds(it.date, it.hour) }.map { it.mmolPerL }
            GlucoseBucket(slot, values.size, values.takeIf { it.isNotEmpty() }?.average())
        }
        val n = inRange.size
        fun share(count: Int) = if (n == 0) 0.0 else count.toDouble() / n
        return GlucoseSummary(
            period = period,
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
