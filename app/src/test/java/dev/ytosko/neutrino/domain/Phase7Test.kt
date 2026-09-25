package dev.ytosko.neutrino.domain

import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import dev.ytosko.neutrino.data.reminders.WeeklySummary
import dev.ytosko.neutrino.domain.insights.MealGlucose
import dev.ytosko.neutrino.domain.insights.MealGlucoseInsights
import dev.ytosko.neutrino.domain.insights.TimedReading
import dev.ytosko.neutrino.domain.report.ReportBuilder
import dev.ytosko.neutrino.domain.report.ReportMeal
import dev.ytosko.neutrino.domain.report.ReportReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class Phase7Test {

    private val zone: ZoneId = ZoneOffset.ofHours(6)
    private fun at(h: Int, m: Int = 0, day: Int = 25): Instant = LocalDateTime.of(2026, 9, day, h, m).atZone(zone).toInstant()

    // ---- Glucose units ----

    @Test
    fun `mg per dL conversion and formatting`() {
        assertEquals("99", GlucoseUnit.MgDl.format(5.5))
        assertEquals("180", GlucoseUnit.MgDl.format(10.0))
        assertEquals("5.5", GlucoseUnit.MmolL.format(5.5))
        assertEquals(10.0, GlucoseUnit.MgDl.toMmol(180.16), 0.01)
        assertEquals(600.0, GlucoseUnit.MgDl.fromMmol(33.3), 1.0)
    }

    @Test
    fun `default unit follows the region`() {
        assertEquals(GlucoseUnit.MgDl, GlucoseUnit.defaultFor(Locale.US))
        assertEquals(GlucoseUnit.MgDl, GlucoseUnit.defaultFor(Locale.forLanguageTag("en-IN")))
        assertEquals(GlucoseUnit.MmolL, GlucoseUnit.defaultFor(Locale.forLanguageTag("bn-BD")))
        assertEquals(GlucoseUnit.MmolL, GlucoseUnit.defaultFor(Locale.UK))
    }

    // ---- Meals and glucose ----

    @Test
    fun `before is the last reading up to an hour before, after is closest to two hours`() {
        val readings = listOf(
            TimedReading(at(11, 0), 5.0), // too early
            TimedReading(at(12, 40), 5.8), // before
            TimedReading(at(14, 10), 9.4), // 1h10 after: 50 min from the 2 h mark, the closest
            TimedReading(at(15, 55), 7.0), // 2h55 after: 55 min from the 2 h mark
        )
        val m = MealGlucose.match(at(13, 0), readings)
        assertEquals(5.8, m.before!!.mmolPerL, 1e-9)
        assertEquals(9.4, m.after!!.mmolPerL, 1e-9)
        assertEquals(3.6, m.rise!!, 1e-9)
    }

    @Test
    fun `no readings around a meal gives nothing`() {
        val m = MealGlucose.match(at(13, 0), listOf(TimedReading(at(8, 0), 5.0), TimedReading(at(17, 0), 6.0)))
        assertTrue(m.isEmpty)
        assertNull(m.rise)
    }

    @Test
    fun `biggest rises group by meal name`() {
        val readings = listOf(
            TimedReading(at(12, 50), 5.0), TimedReading(at(15, 0), 10.0), // rice lunch: +5
            TimedReading(at(12, 50, day = 24), 5.0), TimedReading(at(15, 0, day = 24), 8.0), // rice lunch: +3
            TimedReading(at(18, 50), 6.0), TimedReading(at(21, 0), 7.0), // salad: +1
        )
        val meals = listOf("Rice and curry" to at(13, 0), "rice and curry " to at(13, 0, day = 24), "Salad" to at(19, 0))
        val rises = MealGlucoseInsights.biggestRises(meals, readings)
        assertEquals(listOf("Rice and curry", "Salad"), rises.map { it.name })
        assertEquals(4.0, rises[0].averageRise, 1e-9)
        assertEquals(2, rises[0].times)
    }

    // ---- Doctor report ----

    @Test
    fun `report sums per day and glucose stats`() {
        val from = LocalDate.of(2026, 9, 24)
        val to = LocalDate.of(2026, 9, 25)
        val report = ReportBuilder.build(
            from, to, zone,
            meals = listOf(
                ReportMeal(at(9, day = 24), 40.0, 10.0, 5.0, 300.0),
                ReportMeal(at(9), 60.0, 20.0, 10.0, 500.0),
                ReportMeal(at(13), 80.0, 20.0, 10.0, 700.0),
                ReportMeal(at(13, day = 20), 999.0, 0.0, 0.0, 9999.0), // outside the period
            ),
            water = listOf(at(10) to 500, at(11) to 250),
            readings = listOf(
                ReportReading(at(8), 3.5, GlucoseRelation.Fasting, true),
                ReportReading(at(15), 12.0, GlucoseRelation.AfterMeal, true),
                ReportReading(at(8, day = 24), 6.0, GlucoseRelation.Fasting, false),
            ),
            low = 4.0, high = 10.0,
        )
        val food = report.food!!
        assertEquals(2, food.daysLogged)
        assertEquals(90.0, food.carbsPerDay, 1e-9)
        assertEquals(750, food.waterPerDay)
        val g = report.glucose!!
        assertEquals(3, g.readings)
        assertEquals(1.0 / 3, g.belowShare, 1e-9)
        assertEquals(1.0 / 3, g.aboveShare, 1e-9)
        assertEquals(4.75, g.byRelation.first { it.first == GlucoseRelation.Fasting }.second, 1e-9)
        assertEquals(listOf(to, from), report.days.map { it.date })
        val today = report.days.first()
        assertEquals(140.0, today.carbsG, 1e-9)
        assertEquals(750, today.waterMl)
        assertEquals(3.5, today.glucoseMin!!, 1e-9)
        assertEquals(12.0, today.glucoseMax!!, 1e-9)
    }

    @Test
    fun `empty period gives an empty report`() {
        val d = LocalDate.of(2026, 9, 25)
        assertTrue(ReportBuilder.build(d, d, zone, emptyList(), emptyList(), emptyList(), 4.0, 10.0).isEmpty)
    }

    // ---- Weekly summary schedule ----

    @Test
    fun `weekly summary fires next Sunday at 7 PM`() {
        val friday = LocalDateTime.of(2026, 9, 25, 12, 0).atZone(zone)
        val next = WeeklySummary.nextTrigger(friday)
        assertEquals(DayOfWeek.SUNDAY, next.dayOfWeek)
        assertEquals(LocalDate.of(2026, 9, 27), next.toLocalDate())
        assertEquals(19, next.hour)
        // Sunday after 7 PM: the following Sunday.
        val sundayNight = LocalDateTime.of(2026, 9, 27, 20, 0).atZone(zone)
        assertEquals(LocalDate.of(2026, 10, 4), WeeklySummary.nextTrigger(sundayNight).toLocalDate())
    }
}
