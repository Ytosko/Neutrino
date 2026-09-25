package dev.ytosko.neutrino.domain.insights

import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class InsightsTest {

    private val today = LocalDate.of(2026, 9, 25) // a Friday
    private fun meal(date: LocalDate, kcal: Double, carbs: Double = 0.0, type: MealType = MealType.Lunch) =
        MealPoint(date, type, Nutrition(kcal, 0.0, carbs, 0.0))

    @Test
    fun `day view has 30 daily bars ending today`() {
        val s = Insights.summarize(
            InsightRange.Day, today,
            meals = listOf(meal(today, 500.0), meal(today, 300.0), meal(today.minusDays(29), 100.0), meal(today.minusDays(30), 999.0)),
            water = listOf(WaterPoint(today, 250)),
        )
        assertEquals(30, s.buckets.size)
        assertEquals(today.minusDays(29), s.buckets.first().start)
        assertEquals(today, s.buckets.last().start)
        assertEquals(800.0, s.buckets.last().nutrition.calories, 0.0)
        assertEquals("a meal 30 days ago is outside the range", 900.0, s.totals.calories, 0.0)
        assertEquals(2, s.daysLogged)
        assertEquals(30, s.totalDays)
        assertEquals(450.0, s.perLoggedDay(s.totals.calories), 0.0)
    }

    @Test
    fun `week, month and year buckets line up with calendar periods`() {
        val week = Insights.summarize(InsightRange.Week, today, emptyList(), emptyList(), firstDayOfWeek = DayOfWeek.MONDAY)
        assertEquals(12, week.buckets.size)
        assertEquals(LocalDate.of(2026, 9, 21), week.buckets.last().start)
        assertEquals(DayOfWeek.MONDAY, week.buckets.first().start.dayOfWeek)

        val month = Insights.summarize(InsightRange.Month, today, listOf(meal(LocalDate.of(2025, 10, 31), 700.0)), emptyList())
        assertEquals(12, month.buckets.size)
        assertEquals(LocalDate.of(2025, 10, 1), month.buckets.first().start)
        assertEquals(700.0, month.buckets.first().nutrition.calories, 0.0)

        val year = Insights.summarize(InsightRange.Year, today, emptyList(), emptyList())
        assertEquals(7, year.buckets.size)
        assertEquals(LocalDate.of(2020, 1, 1), year.buckets.first().start)
        assertTrue(year.isEmpty)
    }

    @Test
    fun `totals split by meal type and macro calories`() {
        val s = Insights.summarize(
            InsightRange.Day, today,
            meals = listOf(meal(today, 400.0, carbs = 50.0, type = MealType.Breakfast), meal(today, 600.0, carbs = 80.0, type = MealType.Dinner)),
            water = emptyList(),
        )
        assertEquals(50.0, s.byMealType.getValue(MealType.Breakfast).carbsG, 0.0)
        assertEquals(0.0, s.byMealType.getValue(MealType.Snack).carbsG, 0.0)
        assertEquals(520.0, s.macroKcal.first, 0.0)
    }

    @Test
    fun `big numbers stay short enough for a tile`() {
        assertEquals("850", compactNumber(850.4))
        assertEquals("1.2k", compactNumber(1_234.0))
        assertEquals("10k", compactNumber(9_990.0))
        assertEquals("12k", compactNumber(12_345.0))
        assertEquals("1.2M", compactNumber(1_234_567.0))

        assertEquals("35.5g", compactGrams(35.5))
        assertEquals("850g", compactGrams(850.0))
        assertEquals("1.2kg", compactGrams(1_234.0))
        assertEquals("12kg", compactGrams(12_345.0))

        assertEquals("500ml", compactMl(500))
        assertEquals("2.3L", compactMl(2_250))
        assertEquals("45L", compactMl(45_000))
    }

    @Test
    fun `water switches to litres from one litre`() {
        assertEquals("750 ml", formatWater(750))
        assertEquals("1 L", formatWater(1_000))
        assertEquals("1.25 L", formatWater(1_250))
        assertEquals("2.5 L", formatWater(2_500))
        assertEquals("10 L", formatWater(10_000))
    }
}
