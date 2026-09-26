package dev.ytosko.neutrino.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class DoseInsightsTest {

    private val today = LocalDate.of(2026, 9, 25) // a Friday
    private fun dose(daysAgo: Long, id: String, amount: Double, insulin: Boolean, hour: Int = 8) =
        DosePoint(today.minusDays(daysAgo), hour, id, id, amount, insulin)

    @Test
    fun `counts times and days per medicine, insulin first`() {
        val week = Period.containing(InsightRange.Week, today, DayOfWeek.MONDAY)
        val s = DoseInsights.summarize(
            week, today,
            listOf(dose(0, "met", 1.0, false), dose(0, "met", 1.0, false, hour = 20), dose(1, "met", 1.0, false), dose(0, "ins", 10.0, true), dose(9, "ins", 99.0, true)),
        )
        assertEquals(listOf("ins", "met"), s.uses.map { it.medicineId })
        assertEquals(3, s.uses[1].times)
        assertEquals(2, s.uses[1].days)
        assertEquals(10.0, s.insulinTotal, 0.0)
        assertEquals(10.0, s.insulinBuckets[4], 0.0)
        assertEquals(5, s.totalDays)
    }

    @Test
    fun `day blocks and year months`() {
        val day = Period.containing(InsightRange.Day, today, DayOfWeek.MONDAY)
        val s = DoseInsights.summarize(day, today, listOf(dose(0, "ins", 6.0, true, hour = 7), dose(0, "ins", 8.0, true, hour = 19)))
        assertEquals(listOf(0.0, 6.0, 0.0, 8.0), s.insulinBuckets)

        val year = Period.containing(InsightRange.Year, today, DayOfWeek.MONDAY)
        val y = DoseInsights.summarize(year, today, listOf(dose(0, "ins", 10.0, true), dose(1, "ins", 20.0, true)))
        assertEquals("September: 30 units over 2 days", 15.0, y.insulinBuckets[8], 0.0)
        assertTrue(DoseInsights.summarize(year, today, emptyList()).isEmpty)
    }
}
