package dev.ytosko.neutrino.domain.goals

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToInt

enum class Sex { Male, Female }

enum class PlanUnit { Days, Months, Years }

enum class HeightUnit { Cm, FtIn }

enum class WeightUnit { Kg, Lb }

/**
 * The body the daily goals are worked out for. Age comes from the birth date, so it stays right
 * as time passes. Everything is optional; an AI suggestion needs [complete].
 */
data class Physique(
    val birthDate: LocalDate? = null,
    val sex: Sex? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val targetKg: Double? = null,
    val planLength: Int? = null,
    val planUnit: PlanUnit = PlanUnit.Months,
    val heightUnit: HeightUnit = HeightUnit.Cm,
    val weightUnit: WeightUnit = WeightUnit.Kg,
) {
    fun age(today: LocalDate = LocalDate.now()): Int? = birthDate?.let { java.time.Period.between(it, today).years }

    val complete: Boolean
        get() = birthDate != null && sex != null && heightCm != null && weightKg != null &&
            targetKg != null && planLength != null

    /** The plan's length in days, e.g. 3 months ≈ 91 days. */
    val planDays: Int?
        get() = planLength?.let {
            when (planUnit) {
                PlanUnit.Days -> it
                PlanUnit.Months -> (it * 30.44).roundToInt()
                PlanUnit.Years -> (it * 365.25).roundToInt()
            }
        }
}

/** One weigh-in. [id] is also its Health Connect record id. */
@Serializable
data class WeightEntry(val id: String, val epochMs: Long, val kg: Double) {
    val date: LocalDate get() = java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
}

object WeightTrend {
    /** kg per week over the entries since [days] ago (first to last); null with fewer than two over a week apart. */
    fun perWeek(entries: List<WeightEntry>, days: Long, nowMs: Long = System.currentTimeMillis()): Double? {
        val from = nowMs - days * 86_400_000L
        val inRange = entries.filter { it.epochMs >= from }.sortedBy { it.epochMs }
        if (inRange.size < 2) return null
        val spanDays = (inRange.last().epochMs - inRange.first().epochMs) / 86_400_000.0
        if (spanDays < 7) return null
        return (inRange.last().kg - inRange.first().kg) / spanDays * 7
    }
}

enum class WorkoutType { Walking, Running, Cycling }

/**
 * One regular workout. Walking has steps, time and distance; running time and distance; cycling
 * time. [daysPerWeek] is how often it's done.
 */
@Serializable
data class Workout(
    val id: String,
    val type: WorkoutType,
    val steps: Int? = null,
    val minutes: Int? = null,
    val distanceKm: Double? = null,
    val daysPerWeek: Int = 7,
) {
    val filled: Boolean
        get() = when (type) {
            WorkoutType.Walking -> (steps ?: 0) > 0 || (minutes ?: 0) > 0 || (distanceKm ?: 0.0) > 0
            WorkoutType.Running -> (minutes ?: 0) > 0 || (distanceKm ?: 0.0) > 0
            WorkoutType.Cycling -> (minutes ?: 0) > 0
        }
}

enum class HormoneUnit { Pmol, Ng }

/** Health conditions the goals should respect, with the user's own recent values. */
@Serializable
data class Conditions(
    val diabetes: Boolean = false,
    /** Average blood sugar the user typed, mmol/L; null means "use the meter's 30-day average". */
    val avgGlucoseMmol: Double? = null,
    val bloodPressure: Boolean = false,
    val systolic: Int? = null,
    val diastolic: Int? = null,
    val thyroid: Boolean = false,
    val tsh: Double? = null,
    val ft3: Double? = null,
    val ft4: Double? = null,
    /** FT3 and FT4 in pmol/L, or FT3 in pg/mL and FT4 in ng/dL. */
    val hormoneUnit: HormoneUnit = HormoneUnit.Pmol,
    /** When the thyroid test was done (epoch day); optional. */
    val thyroidTestDay: Long? = null,
    /** Fatty liver: FibroScan CAP (dB/m) and stiffness (kPa), ALT and AST (U/L). */
    val liver: Boolean = false,
    val cap: Int? = null,
    val kpa: Double? = null,
    val alt: Int? = null,
    val ast: Int? = null,
    /** S0–S3 picked from the report when there's no CAP number. */
    val steatosisGrade: Int? = null,
    /** F0–F4 picked from the report when there's no kPa number. */
    val fibrosisGrade: Int? = null,
    val liverTestDay: Long? = null,
) {
    val any: Boolean get() = diabetes || bloodPressure || thyroid || liver

    /** The fat grade: from CAP when there is one, else the one picked. */
    val steatosis: Int? get() = cap?.let(LiverGrades::steatosis) ?: steatosisGrade

    /** The scarring grade: from kPa when there is one, else the one picked. */
    val fibrosis: Int? get() = kpa?.let(LiverGrades::fibrosis) ?: fibrosisGrade
}

/**
 * FibroScan grades. Fat (CAP, dB/m): S0 under 248, S1 248–267, S2 268–279, S3 280 and over
 * (Karlas et al., 2017). Scarring (kPa, fatty liver): F0–F1 under 8.2, F2 8.2–9.6, F3 9.7–13.5,
 * F4 13.6 and over (Eddowes et al., 2019). F0–F1 are reported together as 1.
 */
object LiverGrades {
    fun steatosis(cap: Int): Int = when {
        cap < 248 -> 0
        cap < 268 -> 1
        cap < 280 -> 2
        else -> 3
    }

    fun fibrosis(kpa: Double): Int = when {
        kpa < 8.2 -> 1
        kpa < 9.7 -> 2
        kpa < 13.6 -> 3
        else -> 4
    }
}

/** Daily targets, as the AI suggests them and as the user confirms them. */
data class Intakes(val kcal: Int, val carbsG: Int, val proteinG: Int, val fatG: Int, val waterMl: Int)

/**
 * The plain numbers behind a suggestion, worked out on the phone so the AI starts from the same
 * facts every time: resting energy (Mifflin–St Jeor), energy from workouts, the day's total, the
 * pace the plan needs and the lowest calories Neutrino will ever suggest.
 */
object GoalMath {
    const val KCAL_PER_KG = 7_700.0

    /** A pace faster than this (kg per week, either way) gets a warning before analysing. */
    const val FAST_PACE_KG_WEEK = 1.0

    fun restingKcal(p: Physique, today: LocalDate = LocalDate.now()): Int? {
        val kg = p.weightKg ?: return null
        val cm = p.heightCm ?: return null
        val age = p.age(today) ?: return null
        val sex = p.sex ?: return null
        val base = 10 * kg + 6.25 * cm - 5 * age
        return (base + if (sex == Sex.Male) 5 else -161).roundToInt()
    }

    /** Energy one session of [w] burns for someone weighing [kg] (MET × kg × hours). */
    fun sessionKcal(w: Workout, kg: Double): Int {
        val minutes = w.minutes?.toDouble()
            ?: when (w.type) {
                // Without a time: about 100 steps a minute walking, 5 km/h walking, 6 min/km running.
                WorkoutType.Walking -> w.steps?.let { it / 100.0 } ?: w.distanceKm?.let { it * 12 }
                WorkoutType.Running -> w.distanceKm?.let { it * 6 }
                WorkoutType.Cycling -> null
            } ?: return 0
        val met = when (w.type) {
            WorkoutType.Walking -> {
                val speed = w.distanceKm?.let { d -> w.minutes?.takeIf { it > 0 }?.let { d / (it / 60.0) } }
                when {
                    speed == null -> 3.5
                    speed < 4 -> 3.0
                    speed < 5.5 -> 3.8
                    else -> 5.0
                }
            }
            WorkoutType.Running -> {
                val speed = w.distanceKm?.let { d -> w.minutes?.takeIf { it > 0 }?.let { d / (it / 60.0) } }
                when {
                    speed == null -> 9.0
                    speed < 8 -> 8.0
                    speed < 10 -> 9.8
                    speed < 12 -> 11.0
                    else -> 12.5
                }
            }
            WorkoutType.Cycling -> 7.0
        }
        return (met * kg * minutes / 60.0).roundToInt()
    }

    /** Workout energy averaged over the week, per day. */
    fun workoutKcalPerDay(workouts: List<Workout>, kg: Double): Int =
        (workouts.filter { it.filled }.sumOf { sessionKcal(it, kg) * it.daysPerWeek.coerceIn(1, 7) } / 7.0).roundToInt()

    /** Resting energy with light daily life (×1.2), plus workouts. */
    fun dailyKcal(p: Physique, workouts: List<Workout>, today: LocalDate = LocalDate.now()): Int? {
        val resting = restingKcal(p, today) ?: return null
        return (resting * 1.2).roundToInt() + workoutKcalPerDay(workouts, p.weightKg ?: return null)
    }

    /** kg per week the plan needs; negative is losing. */
    fun paceKgPerWeek(p: Physique): Double? {
        val now = p.weightKg ?: return null
        val target = p.targetKg ?: return null
        val days = p.planDays?.takeIf { it > 0 } ?: return null
        return (target - now) / days * 7
    }

    /** The daily calories the plan works out to before any safety floor. */
    fun planKcal(p: Physique, workouts: List<Workout>, today: LocalDate = LocalDate.now()): Int? {
        val daily = dailyKcal(p, workouts, today) ?: return null
        val pace = paceKgPerWeek(p) ?: return daily
        return (daily + pace * KCAL_PER_KG / 7).roundToInt()
    }

    /** Neutrino never suggests less than this a day. */
    fun floorKcal(sex: Sex?): Int = if (sex == Sex.Male) 1_500 else 1_200

    fun bmi(p: Physique): Double? {
        val kg = p.weightKg ?: return null
        val m = (p.heightCm ?: return null) / 100
        return kg / (m * m)
    }

    /**
     * Keeps an AI suggestion safe and within what the goals accept: calories not under the floor,
     * each value in range, carbs not under 100 g with diabetes (low-carb changes need a doctor,
     * especially with insulin).
     */
    fun safe(i: Intakes, sex: Sex?, diabetes: Boolean): Intakes = Intakes(
        kcal = i.kcal.coerceIn(floorKcal(sex), 10_000),
        carbsG = i.carbsG.coerceIn(if (diabetes) 100 else 50, 1_000),
        proteinG = i.proteinG.coerceIn(10, 500),
        fatG = i.fatG.coerceIn(20, 500),
        waterMl = (Math.round(i.waterMl / 250.0) * 250).toInt().coerceIn(1_000, 6_000),
    )

    const val LB_PER_KG = 2.20462
    const val CM_PER_INCH = 2.54

    fun kgToLb(kg: Double) = kg * LB_PER_KG
    fun lbToKg(lb: Double) = lb / LB_PER_KG

    /** Feet and inches for [cm], inches rounded. */
    fun feetInches(cm: Double): Pair<Int, Int> {
        val inches = (cm / CM_PER_INCH).roundToInt()
        return inches / 12 to inches % 12
    }

    fun cm(feet: Int, inches: Int): Double = (feet * 12 + inches) * CM_PER_INCH
}
