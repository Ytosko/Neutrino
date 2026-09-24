package dev.ytosko.neutrino.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class MealWindowsTest {

    private val windows = MealWindows()
    private val dhaka = ZoneId.of("Asia/Dhaka")

    @Test
    fun `local times map to the expected meal`() {
        mapOf(
            "03:00" to MealType.Snack,
            "05:00" to MealType.Breakfast,
            "08:15" to MealType.Breakfast,
            "11:29" to MealType.Breakfast,
            "11:30" to MealType.Lunch,
            "14:00" to MealType.Lunch,
            "16:00" to MealType.Snack,
            "18:45" to MealType.Snack,
            "19:30" to MealType.Dinner,
            "21:10" to MealType.Dinner,
            "23:30" to MealType.Snack,
            "23:59" to MealType.Snack,
        ).forEach { (time, expected) ->
            assertEquals("at $time", expected, windows.mealAt(LocalTime.parse(time)))
        }
    }

    @Test
    fun `instant is converted to the user's zone, not UTC`() {
        // 14:42 UTC is 20:42 in Dhaka (UTC+6): dinner there, lunch/snack in UTC.
        val instant = Instant.parse("2026-09-25T14:42:00Z")
        assertEquals(MealType.Dinner, windows.mealAt(instant, dhaka))
        assertEquals(MealType.Lunch, windows.mealAt(instant, ZoneId.of("UTC")))
    }

    @Test
    fun `next main meal skips snacks`() {
        assertEquals(MealType.Lunch, windows.nextMainMeal(LocalTime.of(9, 0)))
        assertEquals(MealType.Dinner, windows.nextMainMeal(LocalTime.of(17, 0)))
        assertEquals(MealType.Breakfast, windows.nextMainMeal(LocalTime.of(22, 0)))
        assertEquals(MealType.Breakfast, windows.nextMainMeal(LocalTime.of(2, 0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out of order windows are rejected`() {
        MealWindows(lunch = LocalTime.of(4, 0))
    }
}
