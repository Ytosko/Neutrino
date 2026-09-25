package dev.ytosko.neutrino.data.reminders

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class MealRemindersTest {

    private val dhaka = ZoneId.of("Asia/Dhaka")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 9, 25, h, m, 30, 0, dhaka)

    @Test
    fun `a reminder later today fires today, one already passed fires tomorrow`() {
        assertEquals(at(10, 0).withSecond(0), MealReminders.nextTrigger(at(6, 15), LocalTime.of(10, 0)))
        assertEquals(at(14, 0).withSecond(0).plusDays(1), MealReminders.nextTrigger(at(14, 0), LocalTime.of(14, 0)))
        assertEquals(at(18, 0).withSecond(0).plusDays(1), MealReminders.nextTrigger(at(21, 5), LocalTime.of(18, 0)))
    }

    @Test
    fun `reminders are at 10, 14 and 18`() {
        assertEquals(
            listOf(LocalTime.of(10, 0), LocalTime.of(14, 0), LocalTime.of(18, 0)),
            MealReminder.entries.map { it.time },
        )
    }
}
