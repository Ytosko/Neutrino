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
import java.time.LocalDate

class GlucoseInsightsTest {

    private val today = LocalDate.of(2026, 9, 25)

    private fun point(daysAgo: Long, mmol: Double, relation: GlucoseRelation = GlucoseRelation.General) =
        GlucosePoint(today.minusDays(daysAgo), mmol, relation)

    @Test
    fun `averages, time in range and per-day buckets`() {
        val points = listOf(
            point(0, 5.0, GlucoseRelation.Fasting),
            point(0, 9.0, GlucoseRelation.AfterMeal),
            point(1, 3.5, GlucoseRelation.Fasting),
            point(2, 12.5),
        )
        val s = GlucoseInsights.summarize(InsightRange.Day, today, points, low = 4.0, high = 10.0)

        assertEquals(4, s.readings)
        assertEquals(7.5, s.average!!, 1e-9)
        assertEquals(0.25, s.belowShare, 1e-9)
        assertEquals(0.5, s.inRangeShare, 1e-9)
        assertEquals(0.25, s.aboveShare, 1e-9)
        assertEquals(30, s.buckets.size)
        assertEquals(7.0, s.buckets.last().average!!, 1e-9)
        assertEquals(2, s.buckets.last().readings)
        assertNull(s.buckets.first().average)
        assertEquals(
            listOf(GlucoseRelation.General, GlucoseRelation.Fasting, GlucoseRelation.AfterMeal),
            s.byRelation.map { it.first },
        )
        assertEquals(4.25, s.byRelation.first { it.first == GlucoseRelation.Fasting }.second, 1e-9)
    }

    @Test
    fun `range edges count as in range`() {
        val s = GlucoseInsights.summarize(InsightRange.Day, today, listOf(point(0, 4.0), point(0, 10.0)), 4.0, 10.0)
        assertEquals(1.0, s.inRangeShare, 1e-9)
    }

    @Test
    fun `readings outside the window are ignored`() {
        val s = GlucoseInsights.summarize(InsightRange.Day, today, listOf(point(30, 6.0), point(-1, 6.0)), 4.0, 10.0)
        assertTrue(s.isEmpty)
        assertNull(s.average)
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
