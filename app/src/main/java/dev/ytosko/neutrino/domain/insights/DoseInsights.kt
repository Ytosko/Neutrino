package dev.ytosko.neutrino.domain.insights

import java.time.LocalDate

/** One dose, reduced to what the Health page needs. [insulin] doses also feed the units chart. */
data class DosePoint(
    val date: LocalDate,
    val hour: Int,
    val medicineId: String,
    val name: String,
    val amount: Double,
    val insulin: Boolean,
)

/** One medicine over the period: how often, on how many days, and (for insulin) how many units. */
data class MedicineUse(val medicineId: String, val name: String, val insulin: Boolean, val times: Int, val days: Int, val total: Double)

data class DoseSummary(
    val period: Period,
    val uses: List<MedicineUse>,
    /** Insulin units per bar (same bars as the other charts); per day on the Year tab. */
    val insulinBuckets: List<Double>,
    val insulinTotal: Double,
    val insulinDays: Int,
    val totalDays: Int,
) {
    val range: InsightRange get() = period.range
    val isEmpty: Boolean get() = uses.isEmpty()
    val hasInsulin: Boolean get() = insulinTotal > 0
    val insulinPerDay: Double get() = if (insulinDays == 0) 0.0 else insulinTotal / insulinDays
}

object DoseInsights {

    fun summarize(period: Period, today: LocalDate, doses: List<DosePoint>): DoseSummary {
        val inRange = doses.filter { period.contains(it.date) }
        val insulin = inRange.filter { it.insulin }
        val buckets = period.slots().map { slot ->
            val here = insulin.filter { slot.holds(it.date, it.hour) }
            val total = here.sumOf { it.amount }
            // A month on the Year tab shows its average per day with insulin.
            if (period.range == InsightRange.Year) {
                val days = here.mapTo(HashSet()) { it.date }.size
                if (days == 0) 0.0 else total / days
            } else {
                total
            }
        }
        val uses = inRange.groupBy { it.medicineId }.map { (id, list) ->
            MedicineUse(
                medicineId = id,
                name = list.last().name,
                insulin = list.first().insulin,
                times = list.size,
                days = list.mapTo(HashSet()) { it.date }.size,
                total = list.sumOf { it.amount },
            )
        }.sortedWith(compareByDescending<MedicineUse> { it.insulin }.thenByDescending { it.times })
        return DoseSummary(
            period = period,
            uses = uses,
            insulinBuckets = buckets,
            insulinTotal = insulin.sumOf { it.amount },
            insulinDays = insulin.mapTo(HashSet()) { it.date }.size,
            totalDays = period.daysSoFar(today),
        )
    }
}
