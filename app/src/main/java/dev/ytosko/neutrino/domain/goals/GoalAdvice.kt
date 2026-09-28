package dev.ytosko.neutrino.domain.goals

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the AI suggests: the day's intakes, a short reason for each, and notes (medicines, doctor). */
data class GoalAdvice(
    val intakes: Intakes,
    val reasons: Map<String, String>,
    val notes: List<String>,
) {
    companion object {
        const val KCAL = "kcal"
        const val CARBS = "carbs"
        const val PROTEIN = "protein"
        const val FAT = "fat"
        const val WATER = "water"
    }
}

/**
 * The plain facts sent to the AI for a goal suggestion. Only what the user entered in Daily goals,
 * My conditions, Workouts and My medicines, plus the numbers worked out on the phone ([GoalMath]).
 */
object GoalFacts {
    /** ", tested 8 months ago" for a test date; empty without one. */
    private fun testAge(epochDay: Long?, today: LocalDate): String {
        val date = epochDay?.let(LocalDate::ofEpochDay) ?: return ""
        val months = java.time.Period.between(date, today).toTotalMonths()
        return if (months < 1) ", tested this month" else ", tested $months months ago"
    }

    fun build(
        p: Physique,
        workouts: List<Workout>,
        conditions: Conditions,
        meterAvgGlucoseMmol: Double?,
        medicines: List<String>,
        today: LocalDate = LocalDate.now(),
        weights: List<WeightEntry> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
        liverTests: List<LiverTest> = emptyList(),
    ): List<String> {
        val out = mutableListOf<String>()
        fun f(v: Double) = String.format(Locale.US, "%.1f", v)
        p.age(today)?.let { out += "Age: $it years" }
        p.sex?.let { out += "Sex: ${it.name.lowercase()}" }
        p.heightCm?.let { out += "Height: ${it.roundToInt()} cm" }
        p.weightKg?.let { out += "Current weight: ${f(it)} kg" }
        GoalMath.bmi(p)?.let { out += "BMI: ${f(it)}" }
        p.targetKg?.let { out += "Target weight: ${f(it)} kg" }
        // The weigh-ins of the last year (at most 12, spread out), and how fast weight has moved.
        val year = weights.filter { it.epochMs >= nowMs - 365 * 86_400_000L }.sortedBy { it.epochMs }
        if (year.size >= 2) {
            val step = (year.size - 1) / 11.0
            val picked = if (year.size <= 12) year else (0 until 12).map { year[Math.round(it * step).toInt()] }.distinct()
            out += "Weight history: " + picked.joinToString("; ") { "${it.date} ${f(it.kg)} kg" }
            WeightTrend.perWeek(weights, 30, nowMs)?.let { out += "Weight change over the last month: ${String.format(Locale.US, "%+.2f", it)} kg per week" }
            WeightTrend.perWeek(weights, 90, nowMs)?.let { out += "Weight change over the last 3 months: ${String.format(Locale.US, "%+.2f", it)} kg per week" }
        }
        if (p.planLength != null) out += "Plan length: ${p.planLength} ${p.planUnit.name.lowercase()} (${p.planDays} days)"
        GoalMath.paceKgPerWeek(p)?.let {
            out += when {
                abs(it) < 0.05 -> "Goal: keep the current weight"
                it < 0 -> "Needed pace: lose ${f(-it)} kg per week"
                else -> "Needed pace: gain ${f(it)} kg per week"
            }
        }
        GoalMath.restingKcal(p, today)?.let { out += "Resting energy (Mifflin-St Jeor): $it kcal/day" }
        p.weightKg?.let { out += "Energy from workouts, averaged over the week: ${GoalMath.workoutKcalPerDay(workouts, it)} kcal/day" }
        GoalMath.dailyKcal(p, workouts, today)?.let { out += "Estimated daily energy use: $it kcal/day" }
        GoalMath.planKcal(p, workouts, today)?.let { out += "Calories that would reach the target in time: $it kcal/day" }
        out += "Never go below: ${GoalMath.floorKcal(p.sex)} kcal/day"

        val done = workouts.filter { it.filled }
        if (done.isEmpty()) {
            out += "Workouts: none (light daily activity only)"
        } else {
            done.forEach { w ->
                val parts = listOfNotNull(
                    w.steps?.let { "$it steps" },
                    w.distanceKm?.let { "${f(it)} km" },
                    w.minutes?.let { "$it min" },
                ).joinToString(", ")
                out += "Workout: ${w.type.name.lowercase()}, $parts, ${w.daysPerWeek} days a week"
            }
        }

        if (conditions.diabetes) {
            val avg = conditions.avgGlucoseMmol ?: meterAvgGlucoseMmol
            out += "Condition: diabetes" + (avg?.let { ", average blood glucose ${f(it)} mmol/L (${(it * 18.016).roundToInt()} mg/dL)" } ?: "")
        }
        if (conditions.bloodPressure) {
            val bp = if (conditions.systolic != null && conditions.diastolic != null) ", usual blood pressure ${conditions.systolic}/${conditions.diastolic} mmHg" else ""
            out += "Condition: high blood pressure$bp"
        }
        if (conditions.thyroid) {
            val (ft3Unit, ft4Unit) = if (conditions.hormoneUnit == HormoneUnit.Pmol) "pmol/L" to "pmol/L" else "pg/mL" to "ng/dL"
            val values = listOfNotNull(
                conditions.tsh?.let { "TSH ${f(it)} mIU/L" },
                conditions.ft3?.let { "FT3 ${f(it)} $ft3Unit" },
                conditions.ft4?.let { "FT4 ${f(it)} $ft4Unit" },
            )
            out += "Condition: thyroid" + (if (values.isEmpty()) "" else ", " + values.joinToString(", ")) +
                testAge(conditions.thyroidTestDay, today)
        }
        if (conditions.liver) {
            // The whole log, newest last (at most 10), so the AI sees how the liver is progressing.
            val log = liverTests.filterNot { it.isEmpty }.sortedBy { it.epochDay }.takeLast(10)
            out += "Condition: fatty liver" + if (log.isEmpty()) ", no results logged" else ", results below"
            log.forEach { t -> out += "Liver result ${t.date}" + testAge(t.epochDay, today) + ": " + t.describe() }
        }
        if (!conditions.any) out += "Conditions: none given"

        if (medicines.isEmpty()) out += "Medicines: none given" else medicines.forEach { out += "Medicine: $it" }
        return out.map { it.replace(Regex("[\"{}\\n\\r]"), " ").take(200) }
    }
}
