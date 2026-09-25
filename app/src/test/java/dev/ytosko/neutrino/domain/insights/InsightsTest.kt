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

    private fun period(range: InsightRange, hourly: Boolean = false) = Period.containing(range, today, DayOfWeek.MONDAY, hourly)

    @Test
    fun `a day splits into four 6-hour blocks, or 24 hours`() {
        val meals = listOf(
            meal(today, 300.0).copy(hour = 5),
            meal(today, 500.0).copy(hour = 13),
            meal(today, 200.0).copy(hour = 23),
            meal(today.minusDays(1), 999.0).copy(hour = 13),
        )
        val water = listOf(WaterPoint(today, 250, hour = 7), WaterPoint(today, 250, hour = 8))
        val s = Insights.summarize(period(InsightRange.Day), today, meals, water)
        assertEquals(4, s.buckets.size)
        assertEquals(listOf(300.0, 0.0, 500.0, 200.0), s.buckets.map { it.nutrition.calories })
        assertEquals(500, s.buckets[1].waterMl)
        assertEquals("yesterday's meal is outside the day", 1000.0, s.totals.calories, 0.0)
        assertEquals(1, s.totalDays)

        val hourly = Insights.summarize(period(InsightRange.Day, hourly = true), today, meals, water)
        assertEquals(24, hourly.buckets.size)
        assertEquals(500.0, hourly.buckets[13].nutrition.calories, 0.0)
        assertEquals(250, hourly.buckets[7].waterMl)
    }

    @Test
    fun `week, month and year are calendar periods`() {
        val week = Insights.summarize(period(InsightRange.Week), today, listOf(meal(today, 500.0), meal(LocalDate.of(2026, 9, 20), 400.0)), emptyList())
        assertEquals(7, week.buckets.size)
        assertEquals(LocalDate.of(2026, 9, 21), week.buckets.first().start)
        assertEquals(500.0, week.totals.calories, 0.0)
        assertEquals("Monday to Friday so far", 5, week.totalDays)

        val month = Insights.summarize(period(InsightRange.Month), today, listOf(meal(LocalDate.of(2026, 9, 1), 700.0)), emptyList())
        assertEquals(30, month.buckets.size)
        assertEquals(700.0, month.buckets.first().nutrition.calories, 0.0)
        assertEquals(25, month.totalDays)

        val lastMonth = Period.containing(InsightRange.Month, today, DayOfWeek.MONDAY).previous()
        assertEquals(LocalDate.of(2026, 8, 1), lastMonth.start)
        assertEquals(31, Insights.summarize(lastMonth, today, emptyList(), emptyList()).totalDays)

        val year = Insights.summarize(period(InsightRange.Year), today, emptyList(), emptyList())
        assertEquals(12, year.buckets.size)
        assertEquals(LocalDate.of(2026, 1, 1), year.buckets.first().start)
        assertTrue(year.isEmpty)
    }

    @Test
    fun `year bars are per logged day`() {
        val meals = listOf(meal(LocalDate.of(2026, 3, 1), 1_000.0), meal(LocalDate.of(2026, 3, 2), 2_000.0), meal(LocalDate.of(2026, 3, 2), 1_000.0))
        val water = listOf(WaterPoint(LocalDate.of(2026, 3, 1), 1_000), WaterPoint(LocalDate.of(2026, 3, 3), 2_000))
        val s = Insights.summarize(period(InsightRange.Year), today, meals, water)
        val march = s.buckets[2]
        assertEquals(4_000.0, march.nutrition.calories, 0.0)
        assertEquals(2_000.0, march.perDayNutrition.calories, 0.0)
        assertEquals(1_500, march.perDayWaterMl)
    }

    @Test
    fun `totals split by meal type and macro calories`() {
        val s = Insights.summarize(
            period(InsightRange.Day), today,
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
