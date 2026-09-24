package dev.ytosko.neutrino.ui.review

import dev.ytosko.neutrino.data.meal.PhotoProcessor
import dev.ytosko.neutrino.domain.Nutrition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReviewLogicTest {

    @Test
    fun `fields round kcal to whole numbers and grams to one decimal`() {
        val fields = Nutrition(calories = 540.6, proteinG = 34.0, carbsG = 63.25, fatG = 18.04).toFields()
        assertEquals("541", fields[Macro.Calories])
        assertEquals("34", fields[Macro.Protein])
        assertEquals("63.3", fields[Macro.Carbs])
        assertEquals("18", fields[Macro.Fat])
    }

    @Test
    fun `nutrition is read back from fields, accepting comma decimals`() {
        val state = ReviewUiState(
            phase = ReviewPhase.Ready,
            name = "Dal",
            fields = mapOf(Macro.Calories to "200", Macro.Carbs to "30,5", Macro.Protein to "12", Macro.Fat to ""),
        )
        assertEquals(Nutrition(200.0, 12.0, 30.5, 0.0), state.nutrition)
    }

    @Test
    fun `cannot save an empty or unnamed meal`() {
        val empty = ReviewUiState(phase = ReviewPhase.Ready, name = "Dal")
        assertFalse(empty.canSave)
        val unnamed = ReviewUiState(phase = ReviewPhase.Ready, name = " ", fields = Nutrition(100.0, 1.0, 1.0, 1.0).toFields())
        assertFalse(unnamed.canSave)
    }

    @Test
    fun `exif time uses the offset tag when present, else the given zone`() {
        assertEquals(
            Instant.parse("2026-09-25T14:42:10Z"),
            PhotoProcessor.parseTakenAt("2026:09:25 20:42:10", "+06:00"),
        )
        assertEquals(
            Instant.parse("2026-09-25T14:42:10Z"),
            PhotoProcessor.parseTakenAt("2026:09:25 20:42:10", null, ZoneId.of("Asia/Dhaka")),
        )
        assertNull(PhotoProcessor.parseTakenAt("garbage", null))
        assertNull(PhotoProcessor.parseTakenAt(null, null))
    }
}
