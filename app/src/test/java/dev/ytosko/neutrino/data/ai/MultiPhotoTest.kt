package dev.ytosko.neutrino.data.ai

import dev.ytosko.neutrino.data.backup.BackupData
import dev.ytosko.neutrino.data.backup.BackupPackage
import dev.ytosko.neutrino.data.backup.SettingsSnapshot
import dev.ytosko.neutrino.domain.TokenUsage
import dev.ytosko.neutrino.ui.review.plus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPhotoTest {

    @Test
    fun `more photos are read with the foods already in the meal`() {
        val prompt = AnalysisPrompt.morePhotos(PromptHints(), listOf("White rice, cooked: 1 plate", "Chicken curry: 2 piece"))
        assertTrue("- White rice, cooked: 1 plate" in prompt)
        assertTrue("- Chicken curry: 2 piece" in prompt)
        assertTrue("Return ONLY foods that are not in this list" in prompt)
        // The added part isn't left indented (the list is inserted after trimming).
        assertTrue(prompt.lines().none { it.startsWith("            ") })
    }

    @Test
    fun `the meal prompt counts a food once across photos and reads labels`() {
        assertTrue("count each food only once" in AnalysisPrompt.MEAL)
        assertTrue("nutrition facts label" in AnalysisPrompt.MEAL)
    }

    @Test
    fun `token use adds up over several requests`() {
        assertEquals(TokenUsage(30, 7), TokenUsage(10, 2).plus(TokenUsage(20, 5)))
        assertEquals(TokenUsage(1, 1), null.plus(TokenUsage(1, 1)))
    }

    @Test
    fun `backups keep a meal's extra photos`() {
        val photos = mapOf(
            "0b3a-meal" to byteArrayOf(1),
            "0b3a-meal~2" to byteArrayOf(2),
            "0b3a-meal~5" to byteArrayOf(5),
        )
        val (_, back) = BackupPackage.unpack(BackupPackage.pack(BackupData(SettingsSnapshot()), photos))
        assertEquals(photos.keys, back.keys)
        assertArrayEquals(byteArrayOf(2), back["0b3a-meal~2"])
    }
}
