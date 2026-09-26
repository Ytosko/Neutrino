package dev.ytosko.neutrino.data.reminders

import dev.ytosko.neutrino.data.medicine.MedicineEntity
import dev.ytosko.neutrino.data.medicine.MedicineKind
import dev.ytosko.neutrino.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DoseRemindersTest {

    private val zone = ZoneId.of("Asia/Dhaka")
    private val metformin = MedicineEntity(id = "m", name = "Metformin", reminderTimes = "08:00,20:00")
    private val insulin = MedicineEntity(id = "i", name = "Insulatard", kind = MedicineKind.Insulin.name, reminderTimes = "22:00")
    private val noTimes = MedicineEntity(id = "n", name = "Napa")

    @Test
    fun `only kinds that are turned on get reminders`() {
        val all = listOf(metformin, insulin, noTimes)
        assertEquals(listOf("m"), DoseReminders.remindable(all, AppSettings(takesMedicine = true)).map { it.id })
        assertEquals(listOf("i"), DoseReminders.remindable(all, AppSettings(usesInsulin = true)).map { it.id })
        assertEquals(emptyList<String>(), DoseReminders.remindable(all, AppSettings()).map { it.id })
    }

    @Test
    fun `next reminder is the earliest time still ahead`() {
        val morning = ZonedDateTime.of(2026, 9, 26, 7, 0, 0, 0, zone)
        assertEquals(morning.withHour(8), DoseReminders.nextTrigger(listOf(metformin, insulin), morning))
        val night = ZonedDateTime.of(2026, 9, 26, 23, 0, 0, 0, zone)
        assertEquals(night.plusDays(1).withHour(8).withMinute(0), DoseReminders.nextTrigger(listOf(metformin, insulin), night))
        assertNull(DoseReminders.nextTrigger(listOf(noTimes), morning))
    }

    @Test
    fun `due means a reminder time in the last 15 minutes, across midnight too`() {
        assertEquals(listOf("m"), DoseReminders.dueAt(listOf(metformin, insulin), LocalTime.of(8, 3)).map { it.id })
        assertEquals(emptyList<String>(), DoseReminders.dueAt(listOf(metformin), LocalTime.of(7, 59)).map { it.id })
        val late = MedicineEntity(id = "l", name = "Late", reminderTimes = "23:55")
        assertEquals(listOf("l"), DoseReminders.dueAt(listOf(late), LocalTime.of(0, 5)).map { it.id })
    }
}
