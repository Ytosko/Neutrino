package dev.ytosko.neutrino.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FoodGlucoseInsightsTest {

    private val start = Instant.parse("2026-09-01T07:00:00Z")

    /** A meal on [day] with readings [before] just before and [after] two hours later. */
    private fun meal(day: Long, foods: List<String>, before: Double?, after: Double?): Pair<MealFoods, List<TimedReading>> {
        val at = start + Duration.ofDays(day)
        val readings = listOfNotNull(
            before?.let { TimedReading(at - Duration.ofMinutes(10), it) },
            after?.let { TimedReading(at + Duration.ofMinutes(120), it) },
        )
        return MealFoods(at, foods.map { it to it.replaceFirstChar(Char::uppercase) }) to readings
    }

    @Test
    fun `each food in a meal gets that meal's change, averaged over at least 3 meals`() {
        val data = listOf(
            meal(0, listOf("rice", "dal"), 5.0, 9.0),
            meal(1, listOf("rice", "fish"), 5.0, 8.0),
            meal(2, listOf("rice", "dal"), 6.0, 9.0),
            meal(3, listOf("dal"), 5.0, 6.0),
            meal(4, listOf("rice"), 5.0, null), // no after reading: doesn't count
        )
        val rises = FoodGlucoseInsights.rises(data.map { it.first }, data.flatMap { it.second })
        assertEquals(listOf("rice", "dal"), rises.map { it.key })
        val rice = rises.first { it.key == "rice" }
        assertEquals(3, rice.times)
        assertEquals((4.0 + 3.0 + 3.0) / 3, rice.averageRise, 1e-9)
        assertEquals("Rice", rice.name)
        assertTrue("fish was only eaten once", rises.none { it.key == "fish" })
    }

    @Test
    fun `a food listed twice in one meal counts once`() {
        val data = (0L..2L).map { meal(it, listOf("roti", "roti"), 5.0, 7.0) }
        val roti = FoodGlucoseInsights.rises(data.map { it.first }, data.flatMap { it.second }).single()
        assertEquals(3, roti.times)
        assertEquals(2.0, roti.averageRise, 1e-9)
    }
}
