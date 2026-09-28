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
            MealReminder.active(dev.ytosko.neutrino.data.settings.AppSettings()).map { it.time },
        )
    }

    /** Ramadan on 26–27 Sep 2026 (for the test), Sehri ends 4:40 and 4:41, Iftar 17:50 and 17:49. */
    private val ramadan = dev.ytosko.neutrino.data.settings.AppSettings(
        ramadan = true,
        ramadanDays = listOf(
            dev.ytosko.neutrino.domain.RamadanDay(java.time.LocalDate.of(2026, 9, 26), LocalTime.of(4, 40), LocalTime.of(17, 50)),
            dev.ytosko.neutrino.domain.RamadanDay(java.time.LocalDate.of(2026, 9, 27), LocalTime.of(4, 41), LocalTime.of(17, 49)),
        ),
    )

    @Test
    fun `Ramadan days use Sehri and Iftar at that day's times, other days the usual three`() {
        val day1 = java.time.LocalDate.of(2026, 9, 26)
        assertEquals(listOf(MealReminder.Sehri, MealReminder.Iftar), MealReminder.active(ramadan, day1))
        assertEquals(LocalTime.of(3, 55), MealReminder.Sehri.timeOn(ramadan, day1))
        assertEquals(LocalTime.of(18, 19), MealReminder.Iftar.timeOn(ramadan, day1.plusDays(1)))
        assertEquals(null, MealReminder.Breakfast.timeOn(ramadan, day1))
        assertEquals(listOf(MealReminder.Breakfast, MealReminder.Lunch, MealReminder.Dinner), MealReminder.active(ramadan, day1.minusDays(1)))
        // Ramadan mode off: never Sehri or Iftar, even on those dates.
        assertEquals(null, MealReminder.Sehri.timeOn(ramadan.copy(ramadan = false), day1))
    }

    @Test
    fun `the first Sehri is booked the evening before, breakfast returns the day after`() {
        // 25 Sep, 21:00 (the evening before Ramadan): Sehri next rings on the 26th at 3:55.
        assertEquals(ZonedDateTime.of(2026, 9, 26, 3, 55, 0, 0, dhaka), MealReminders.nextFor(MealReminder.Sehri, ramadan, at(21, 0)))
        // Breakfast isn't on the 26th or 27th: it comes back on the 28th.
        assertEquals(ZonedDateTime.of(2026, 9, 28, 10, 0, 0, 0, dhaka), MealReminders.nextFor(MealReminder.Breakfast, ramadan, at(21, 0).plusDays(1)))
    }

    @Test
    fun `medicines move with the fast on Ramadan days only`() {
        val day = dev.ytosko.neutrino.domain.RamadanTimes(LocalTime.of(4, 40), LocalTime.of(17, 50))
        val shift = dev.ytosko.neutrino.domain.RamadanMedicine::shift
        assertEquals(LocalTime.of(4, 10), shift(LocalTime.of(8, 0), day))
        assertEquals(LocalTime.of(17, 50), shift(LocalTime.of(14, 0), day))
        assertEquals(LocalTime.of(17, 50), shift(LocalTime.of(16, 30), day))
        assertEquals(LocalTime.of(21, 0), shift(LocalTime.of(21, 0), day))
        assertEquals(LocalTime.of(8, 0), shift(LocalTime.of(8, 0), null))
    }
}
