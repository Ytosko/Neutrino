package dev.ytosko.neutrino.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MealLinkTest {

    private val lunch = Instant.parse("2026-09-27T07:30:00Z")
    private val dinner = Instant.parse("2026-09-27T14:00:00Z")
    private val meals = listOf(MealTime("lunch", lunch), MealTime("dinner", dinner))
    private fun reading(minutesFromLunch: Long, value: Double, id: String = "r$minutesFromLunch", link: String? = null, auto: Boolean = true) =
        TimedReading(lunch + Duration.ofMinutes(minutesFromLunch), value, id, link, auto)

    @Test
    fun `a reading 35 minutes after shows, but doesn't count toward averages`() {
        val g = MealGlucose.matchAll(meals, listOf(reading(-10, 5.5), reading(35, 8.4))).getValue("lunch")
        assertEquals(8.4, g.after!!.mmolPerL, 0.0)
        assertEquals(35L, g.afterMinutes)
        assertEquals(2.9, g.rise!!, 1e-9)
        assertNull("30-minute readings aren't comparable", g.comparableRise)
    }

    @Test
    fun `with several after readings, the one closest to 2 hours wins`() {
        val g = MealGlucose.matchAll(meals, listOf(reading(-10, 5.5), reading(35, 8.4), reading(125, 7.9))).getValue("lunch")
        assertEquals(125L, g.afterMinutes)
        assertEquals(2.4, g.comparableRise!!, 1e-9)
    }

    @Test
    fun `a reading linked by hand counts for that meal only, whenever it was taken`() {
        // 4 hours after lunch: outside the time window, but linked to lunch.
        val linked = reading(240, 9.1, link = "lunch")
        val result = MealGlucose.matchAll(meals, listOf(reading(-10, 5.5), linked))
        assertEquals("r240", result.getValue("lunch").after!!.id)
        assertNull("not also used for dinner", result.getValue("dinner").before)
    }

    @Test
    fun `an unlinked reading is left out of automatic matching`() {
        val g = MealGlucose.matchAll(meals, listOf(reading(-10, 5.5), reading(120, 8.0, auto = false))).getValue("lunch")
        assertNull(g.after)
    }

    @Test
    fun `a link to a meal that no longer exists falls back to matching by time`() {
        val g = MealGlucose.matchAll(meals, listOf(reading(120, 8.0, link = "deleted-meal"))).getValue("lunch")
        assertEquals(8.0, g.after!!.mmolPerL, 0.0)
    }

    @Test
    fun `linked before-readings count as before`() {
        val g = MealGlucose.matchAll(meals, listOf(reading(-90, 5.1, link = "lunch"), reading(-20, 6.0))).getValue("lunch")
        assertEquals("the linked one, even though an automatic one is closer", 5.1, g.before!!.mmolPerL, 0.0)
    }

    @Test
    fun `by time, an after reading taken once the next meal started belongs to that meal`() {
        val snack = lunch + Duration.ofMinutes(70)
        val result = MealGlucose.matchAll(
            listOf(MealTime("lunch", lunch), MealTime("snack", snack)),
            listOf(reading(-10, 5.5), reading(105, 8.4)), // 105 min after lunch = 35 min after the snack
        )
        assertNull("not lunch's after reading", result.getValue("lunch").after)
        assertEquals(35L, result.getValue("snack").afterMinutes)
    }
}
