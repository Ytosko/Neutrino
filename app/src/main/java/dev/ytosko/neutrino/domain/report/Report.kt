package dev.ytosko.neutrino.domain.report

import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One logged meal, as the report needs it. */
data class ReportMeal(val at: Instant, val carbsG: Double, val proteinG: Double, val fatG: Double, val kcal: Double)

/** One glucose reading, as the report needs it. */
data class ReportReading(val at: Instant, val mmolPerL: Double, val relation: GlucoseRelation, val fromMeter: Boolean)

/** One row of the daily table. */
data class ReportDay(
    val date: LocalDate,
    val meals: Int,
    val carbsG: Double,
    val kcal: Double,
    val waterMl: Int,
    val readings: Int,
    val glucoseAverage: Double?,
    val glucoseMin: Double?,
    val glucoseMax: Double?,
)

data class GlucoseStats(
    val readings: Int,
    val average: Double,
    val min: Double,
    val max: Double,
    val belowShare: Double,
    val inRangeShare: Double,
    val aboveShare: Double,
    val byRelation: List<Pair<GlucoseRelation, Double>>,
    /** Estimated A1c over the report's period; null with readings on fewer than 14 days. */
    val gmi: dev.ytosko.neutrino.domain.insights.Gmi? = null,
)

data class FoodStats(
    val daysLogged: Int,
    val carbsPerDay: Double,
    val proteinPerDay: Double,
    val fatPerDay: Double,
    val kcalPerDay: Double,
    /** Average per day that has water logged; null if none. */
    val waterPerDay: Int?,
)

/** A medicine or insulin dose in a report; [unit] is a DoseUnit name. */
data class ReportDose(val at: Instant, val name: String, val amount: Double, val unit: String, val insulin: Boolean)

/** Everything in a doctor report for [from]..[to] (inclusive). */
data class Report(
    val from: LocalDate,
    val to: LocalDate,
    val low: Double,
    val high: Double,
    val glucose: GlucoseStats?,
    val food: FoodStats?,
    /** Newest day first, only days with something logged. */
    val days: List<ReportDay>,
    /** Newest first. */
    val readings: List<ReportReading>,
    /** Newest first; empty unless medicines are turned on. */
    val doses: List<ReportDose> = emptyList(),
) {
    val isEmpty: Boolean get() = glucose == null && food == null && doses.isEmpty()
}

object ReportBuilder {

    fun build(
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId,
        meals: List<ReportMeal>,
        water: List<Pair<Instant, Int>>,
        readings: List<ReportReading>,
        low: Double,
        high: Double,
        doses: List<ReportDose> = emptyList(),
    ): Report {
        fun day(at: Instant) = at.atZone(zone).toLocalDate()
        fun inPeriod(at: Instant) = day(at).let { !it.isBefore(from) && !it.isAfter(to) }
        val m = meals.filter { inPeriod(it.at) }
        val w = water.filter { inPeriod(it.first) }
        val r = readings.filter { inPeriod(it.at) }.sortedByDescending { it.at }

        val glucose = if (r.isEmpty()) null else {
            val values = r.map { it.mmolPerL }
            GlucoseStats(
                readings = r.size,
                average = values.average(),
                min = values.min(),
                max = values.max(),
                belowShare = values.count { it < low }.toDouble() / r.size,
                inRangeShare = values.count { it in low..high }.toDouble() / r.size,
                aboveShare = values.count { it > high }.toDouble() / r.size,
                byRelation = GlucoseRelation.entries.mapNotNull { rel ->
                    r.filter { it.relation == rel }.takeIf { it.isNotEmpty() }?.let { rel to it.map { x -> x.mmolPerL }.average() }
                },
                gmi = dev.ytosko.neutrino.domain.insights.GmiCalculator.from(r.map { day(it.at) to it.mmolPerL }),
            )
        }

        val mealDays = m.groupBy { day(it.at) }
        val waterDays = w.groupBy({ day(it.first) }, { it.second })
        val food = if (mealDays.isEmpty()) null else {
            val n = mealDays.size
            FoodStats(
                daysLogged = n,
                carbsPerDay = m.sumOf { it.carbsG } / n,
                proteinPerDay = m.sumOf { it.proteinG } / n,
                fatPerDay = m.sumOf { it.fatG } / n,
                kcalPerDay = m.sumOf { it.kcal } / n,
                waterPerDay = if (waterDays.isEmpty()) null else waterDays.values.sumOf { it.sum() } / waterDays.size,
            )
        }

        val readingDays = r.groupBy { day(it.at) }
        val dates = (mealDays.keys + waterDays.keys + readingDays.keys).sortedDescending()
        val days = dates.map { date ->
            val dm = mealDays[date].orEmpty()
            val dr = readingDays[date].orEmpty().map { it.mmolPerL }
            ReportDay(
                date = date,
                meals = dm.size,
                carbsG = dm.sumOf { it.carbsG },
                kcal = dm.sumOf { it.kcal },
                waterMl = waterDays[date].orEmpty().sum(),
                readings = dr.size,
                glucoseAverage = dr.takeIf { it.isNotEmpty() }?.average(),
                glucoseMin = dr.minOrNull(),
                glucoseMax = dr.maxOrNull(),
            )
        }
        return Report(from, to, low, high, glucose, food, days, r, doses.filter { inPeriod(it.at) }.sortedByDescending { it.at })
    }
}
