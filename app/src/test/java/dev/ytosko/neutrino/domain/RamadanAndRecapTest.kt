package dev.ytosko.neutrino.domain

import dev.ytosko.neutrino.data.ai.AnalysisPrompt
import dev.ytosko.neutrino.domain.insights.FoodCount
import dev.ytosko.neutrino.domain.insights.GlucoseWeek
import dev.ytosko.neutrino.domain.insights.MealPoint
import dev.ytosko.neutrino.domain.insights.WaterPoint
import dev.ytosko.neutrino.domain.insights.WeeklyRecap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class RamadanAndRecapTest {

    private val ramadan = MealWindows(ramadan = RamadanTimes(sehriEnds = LocalTime.of(4, 40), iftar = LocalTime.of(17, 50)))

    @Test
    fun `in Ramadan, meals are Sehri, Iftar, dinner and snacks`() {
        assertEquals(MealType.Sehri, ramadan.mealAt(LocalTime.of(3, 30)))
        assertEquals(MealType.Sehri, ramadan.mealAt(LocalTime.of(4, 50)))
        assertEquals(MealType.Iftar, ramadan.mealAt(LocalTime.of(17, 50)))
        assertEquals(MealType.Iftar, ramadan.mealAt(LocalTime.of(19, 0)))
        assertEquals(MealType.Dinner, ramadan.mealAt(LocalTime.of(21, 30)))
        assertEquals(MealType.Snack, ramadan.mealAt(LocalTime.of(13, 0)))
        assertEquals(MealType.Iftar, ramadan.nextMainMeal(LocalTime.of(12, 0)))
        assertEquals(MealType.Sehri, ramadan.nextMainMeal(LocalTime.of(22, 0)))
        // Outside Ramadan mode nothing changes.
        assertEquals(MealType.Lunch, MealWindows().mealAt(LocalTime.of(13, 0)))
    }

    @Test
    fun `Ramadan reminders come before Sehri ends and after Iftar`() {
        val times = RamadanTimes(LocalTime.of(4, 40), LocalTime.of(17, 50))
        assertEquals(LocalTime.of(3, 55), times.sehriReminder)
        assertEquals(LocalTime.of(18, 20), times.iftarReminder)
    }

    private val monday = LocalDate.of(2026, 9, 21)

    @Test
    fun `the week becomes plain facts, glucose only when included`() {
        val meals = listOf(
            MealPoint(monday, MealType.Lunch, Nutrition(600.0, 30.0, 70.0, 20.0)),
            MealPoint(monday.plusDays(2), MealType.Dinner, Nutrition(1000.0, 40.0, 120.0, 30.0)),
        )
        val water = listOf(WaterPoint(monday, 1500), WaterPoint(monday.plusDays(2), 2500))
        val facts = WeeklyRecap.facts(monday, monday.plusDays(6), meals, water, listOf(FoodCount("White rice", "grain", 2)), 2000, 2000, null)
        assertTrue(facts.first() == "Days with meals logged: 2 of 7.")
        assertTrue(facts.any { "Average per logged day: 800 kcal" in it })
        assertTrue(facts.any { "Most calories on Wednesday" in it })
        assertTrue(facts.any { "Water goal 2000 ml reached on 1 days" in it })
        assertTrue(facts.none { "glucose" in it.lowercase() })

        val withGlucose = WeeklyRecap.facts(monday, monday.plusDays(6), meals, water, emptyList(), null, null, GlucoseWeek("7.1", "mmol/L", 64, 12))
        assertTrue(withGlucose.any { "average 7.1 mmol/L, 64% in the user's target range" in it })
        assertTrue(WeeklyRecap.facts(monday, monday.plusDays(6), emptyList(), water, emptyList(), null, null, null).isEmpty())
    }

    @Test
    fun `the recap prompt forbids advice and the reply is read`() {
        val prompt = AnalysisPrompt.weeklyRecap(listOf("Days with meals logged: 2 of 7."), bangla = true)
        assertTrue("- Days with meals logged: 2 of 7." in prompt)
        assertTrue("do not give advice" in prompt)
        assertTrue("Write in Bangla" in prompt)
        assertTrue(prompt.lines().none { it.startsWith("            ") })
        assertEquals("A good week.", AnalysisPrompt.parseRecap("""{"recap": " A good week. "}"""))
    }
}
