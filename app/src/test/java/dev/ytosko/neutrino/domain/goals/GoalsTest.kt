package dev.ytosko.neutrino.domain.goals

import dev.ytosko.neutrino.data.ai.AnalysisPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoalsTest {
    private val today = LocalDate.of(2026, 9, 28)
    private val man = Physique(
        birthDate = LocalDate.of(1990, 3, 12), sex = Sex.Male, heightCm = 175.0, weightKg = 85.0,
        targetKg = 78.0, planLength = 3, planUnit = PlanUnit.Months,
    )

    @Test
    fun `resting energy follows Mifflin-St Jeor`() {
        // 10×85 + 6.25×175 − 5×36 + 5 = 1768.75
        assertEquals(1769, GoalMath.restingKcal(man, today))
        assertEquals(1603, GoalMath.restingKcal(man.copy(sex = Sex.Female), today))
    }

    @Test
    fun `plan needs a complete physique`() {
        assertTrue(man.complete)
        assertFalse(man.copy(sex = null).complete)
        assertEquals(91, man.planDays)
        assertEquals(365, man.copy(planLength = 1, planUnit = PlanUnit.Years).planDays)
    }

    @Test
    fun `pace and planned calories`() {
        val pace = GoalMath.paceKgPerWeek(man)!!
        assertEquals(-0.54, pace, 0.01)
        val daily = GoalMath.dailyKcal(man, emptyList(), today)!!
        assertEquals((1769 * 1.2).toInt() + 1, daily)
        // A deficit of about 590 kcal a day.
        assertEquals(daily - 592, GoalMath.planKcal(man, emptyList(), today)!!, 5)
    }

    @Test
    fun `workouts are averaged over the week`() {
        val walk = Workout("w", WorkoutType.Walking, steps = 10_000, minutes = 90, distanceKm = 7.0, daysPerWeek = 7)
        val cycle = Workout("c", WorkoutType.Cycling, minutes = 60, daysPerWeek = 2)
        val walkKcal = GoalMath.sessionKcal(walk, 85.0)
        assertEquals((3.8 * 85 * 1.5).toInt(), walkKcal, 2) // 4.7 km/h: brisk walking
        val perDay = GoalMath.workoutKcalPerDay(listOf(walk, cycle), 85.0)
        assertEquals(walkKcal + (7.0 * 85 * 2 / 7).toInt(), perDay, 2)
        // A workout with nothing filled counts for nothing.
        assertEquals(0, GoalMath.workoutKcalPerDay(listOf(Workout("x", WorkoutType.Cycling)), 85.0))
    }

    @Test
    fun `AI numbers are kept safe`() {
        val low = Intakes(kcal = 900, carbsG = 40, proteinG = 5, fatG = 10, waterMl = 1_830)
        val safe = GoalMath.safe(low, Sex.Female, diabetes = true)
        assertEquals(1_200, safe.kcal)
        assertEquals(100, safe.carbsG)
        assertEquals(10, safe.proteinG)
        assertEquals(20, safe.fatG)
        assertEquals(1_750, safe.waterMl)
        assertEquals(1_500, GoalMath.safe(low, Sex.Male, diabetes = false).kcal)
        assertEquals(50, GoalMath.safe(low, Sex.Male, diabetes = false).carbsG)
    }

    @Test
    fun `units convert both ways`() {
        assertEquals(5 to 9, GoalMath.feetInches(175.0))
        assertEquals(175.26, GoalMath.cm(5, 9), 0.01)
        assertEquals(85.0, GoalMath.lbToKg(GoalMath.kgToLb(85.0)), 1e-9)
    }

    @Test
    fun `facts include conditions, the meter average and medicines`() {
        val facts = GoalFacts.build(
            man,
            emptyList(),
            Conditions(diabetes = true, bloodPressure = true, systolic = 160, diastolic = 100, thyroid = true, tsh = 4.5),
            meterAvgGlucoseMmol = 8.2,
            medicines = listOf("Insulin NovoRapid, 6 unit, at 08:00"),
            today = today,
        )
        assertTrue(facts.any { it.startsWith("Condition: diabetes") && "8.2 mmol/L" in it && "148 mg/dL" in it })
        assertTrue(facts.any { "160/100" in it })
        assertTrue(facts.any { "TSH 4.5" in it })
        assertTrue(facts.any { it == "Medicine: Insulin NovoRapid, 6 unit, at 08:00" })
        assertTrue(facts.any { it.startsWith("Workouts: none") })
        assertTrue(facts.any { it.startsWith("Needed pace: lose 0.5") })
        // The typed average wins over the meter's.
        val typed = GoalFacts.build(man, emptyList(), Conditions(diabetes = true, avgGlucoseMmol = 7.0), 8.2, emptyList(), today)
        assertTrue(typed.any { "7.0 mmol/L" in it })
    }

    @Test
    fun `reads the AI reply`() {
        val reply = """Here: {"kcal": 2050.4, "carbs_g": 220, "protein_g": 110, "fat_g": 70, "water_ml": 2500,
            "reasons": {"kcal": "A steady deficit.", "carbs": "Spread over meals."}, "notes": ["Insulin can slow weight loss.", ""]}"""
        val advice = AnalysisPrompt.parseGoalPlan(reply)
        assertNotNull(advice)
        assertEquals(2050, advice!!.intakes.kcal)
        assertEquals(220, advice.intakes.carbsG)
        assertEquals("Spread over meals.", advice.reasons[GoalAdvice.CARBS])
        assertEquals(listOf("Insulin can slow weight loss."), advice.notes)
        assertNull(AnalysisPrompt.parseGoalPlan("""{"kcal": 2000}"""))
    }

    @Test
    fun `liver grades come from CAP and kPa, else the picked ones`() {
        assertEquals(0, LiverGrades.steatosis(240))
        assertEquals(1, LiverGrades.steatosis(250))
        assertEquals(2, LiverGrades.steatosis(270))
        assertEquals(3, LiverGrades.steatosis(300))
        assertEquals(1, LiverGrades.fibrosis(6.0))
        assertEquals(3, LiverGrades.fibrosis(10.5))
        assertEquals(4, LiverGrades.fibrosis(15.0))
        assertEquals(2, Conditions(liver = true, steatosisGrade = 2).steatosis)
        assertEquals(3, Conditions(liver = true, cap = 290, steatosisGrade = 1).steatosis)
        val facts = GoalFacts.build(
            man, emptyList(),
            Conditions(liver = true, cap = 290, kpa = 8.5, alt = 62, liverTestDay = LocalDate.of(2026, 1, 20).toEpochDay()),
            null, emptyList(), today,
        )
        assertTrue(facts.any { it.startsWith("Condition: fatty liver") && "S3 (CAP 290" in it && "F2" in it && "ALT 62" in it && "tested 8 months ago" in it })
    }

    @Test
    fun `weight history and trend go to the AI`() {
        val now = LocalDate.of(2026, 9, 28).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val day = 86_400_000L
        val weights = (0..12).map { w -> WeightEntry("w$w", now - (12 - w) * 7 * day, 88.0 - w * 0.5) }
        assertEquals(-0.5, WeightTrend.perWeek(weights, 90, now)!!, 0.01)
        val facts = GoalFacts.build(man, emptyList(), Conditions(), null, emptyList(), today, weights, now)
        val history = facts.first { it.startsWith("Weight history:") }
        assertTrue(history.count { it == ';' } <= 11)
        assertTrue(facts.any { it.startsWith("Weight change over the last 3 months: -0.50") })
        assertNull(WeightTrend.perWeek(weights.take(1), 90, now))
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected ± $tolerance but was $actual", kotlin.math.abs(expected - actual) <= tolerance)
}
