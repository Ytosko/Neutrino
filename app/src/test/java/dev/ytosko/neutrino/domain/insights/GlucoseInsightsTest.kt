package dev.ytosko.neutrino.domain.insights

import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import dev.ytosko.neutrino.data.glucose.toHealthConnect
import dev.ytosko.neutrino.data.glucose.toRelation
import dev.ytosko.neutrino.glucose.MealFlag
import androidx.health.connect.client.records.BloodGlucoseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class GlucoseInsightsTest {

    private val today = LocalDate.of(2026, 9, 25)

    private fun point(daysAgo: Long, mmol: Double, relation: GlucoseRelation = GlucoseRelation.General, hour: Int = 12) =
        GlucosePoint(today.minusDays(daysAgo), mmol, relation, hour)

    private val week = Period.containing(InsightRange.Week, today, DayOfWeek.MONDAY)
    private val day = Period.containing(InsightRange.Day, today, DayOfWeek.MONDAY)

    @Test
    fun `averages, time in range and one bar per day of the week`() {
        val points = listOf(
            point(0, 5.0, GlucoseRelation.Fasting),
            point(0, 9.0, GlucoseRelation.AfterMeal),
            point(1, 3.5, GlucoseRelation.Fasting),
            point(2, 12.5),
        )
        val s = GlucoseInsights.summarize(week, points, low = 4.0, high = 10.0)

        assertEquals(4, s.readings)
        assertEquals(7.5, s.average!!, 1e-9)
        assertEquals(0.25, s.belowShare, 1e-9)
        assertEquals(0.5, s.inRangeShare, 1e-9)
        assertEquals(0.25, s.aboveShare, 1e-9)
        assertEquals(7, s.buckets.size)
        val friday = s.buckets[4]
        assertEquals(7.0, friday.average!!, 1e-9)
        assertEquals(2, friday.readings)
        assertNull(s.buckets.first().average)
        assertEquals(
            listOf(GlucoseRelation.General, GlucoseRelation.Fasting, GlucoseRelation.AfterMeal),
            s.byRelation.map { it.first },
        )
        assertEquals(4.25, s.byRelation.first { it.first == GlucoseRelation.Fasting }.second, 1e-9)
    }

    @Test
    fun `range edges count as in range`() {
        val s = GlucoseInsights.summarize(day, listOf(point(0, 4.0), point(0, 10.0)), 4.0, 10.0)
        assertEquals(1.0, s.inRangeShare, 1e-9)
    }

    @Test
    fun `readings outside the period are ignored`() {
        val s = GlucoseInsights.summarize(day, listOf(point(1, 6.0), point(-1, 6.0)), 4.0, 10.0)
        assertTrue(s.isEmpty)
        assertNull(s.average)
    }

    @Test
    fun `day blocks average the readings in them`() {
        val s = GlucoseInsights.summarize(day, listOf(point(0, 5.0, hour = 7), point(0, 7.0, hour = 11), point(0, 9.0, hour = 20)), 4.0, 10.0)
        assertEquals(listOf(null, 6.0, null, 9.0), s.buckets.map { it.average })
        val hourly = GlucoseInsights.summarize(day.copy(hourly = true), listOf(point(0, 5.0, hour = 7)), 4.0, 10.0)
        assertEquals(24, hourly.buckets.size)
        assertEquals(5.0, hourly.buckets[7].average!!, 1e-9)
    }

    @Test
    fun `meter meal flags map to relations`() {
        assertEquals(GlucoseRelation.General, MealFlag.None.toRelation())
        assertEquals(GlucoseRelation.General, MealFlag.Casual.toRelation())
        assertEquals(GlucoseRelation.BeforeMeal, MealFlag.BeforeMeal.toRelation())
        assertEquals(GlucoseRelation.AfterMeal, MealFlag.AfterMeal.toRelation())
        assertEquals(GlucoseRelation.Fasting, MealFlag.Fasting.toRelation())
        assertEquals(GlucoseRelation.Bedtime, MealFlag.Bedtime.toRelation())
    }

    @Test
    fun `relations map to Health Connect`() {
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL, GlucoseRelation.General.toHealthConnect())
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_FASTING, GlucoseRelation.Fasting.toHealthConnect())
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL, GlucoseRelation.BeforeMeal.toHealthConnect())
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL, GlucoseRelation.AfterMeal.toHealthConnect())
        // Health Connect has no bedtime; it becomes general.
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL, GlucoseRelation.Bedtime.toHealthConnect())
    }
}
