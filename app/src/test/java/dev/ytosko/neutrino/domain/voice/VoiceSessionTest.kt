package dev.ytosko.neutrino.domain.voice

import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.PromptHints
import dev.ytosko.neutrino.data.voice.VoiceContext
import dev.ytosko.neutrino.data.voice.VoicePrompt
import dev.ytosko.neutrino.data.voice.withShorterHistory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class VoiceSessionTest {

    @Test
    fun `a weight doesn't change the count, a count does`() {
        // "Those three hamburgers were maximum 100 grams": the AI answering "1 piece" is refused.
        assertFalse(SpokenCount.allows("those three hamburgers were maximum 100 grams", 1.0))
        assertTrue(SpokenCount.allows("those three hamburgers were maximum 100 grams", 3.0))
        assertFalse(SpokenCount.allows("they were no more than 100 g", 1.0))
        assertTrue(SpokenCount.allows("no, I ate three", 3.0))
        assertTrue(SpokenCount.allows("I had 4 burgers", 4.0))
        assertTrue(SpokenCount.allows("one more burger", 4.0))
        assertFalse(SpokenCount.allows("১০০ গ্রাম", 100.0))
        assertTrue(SpokenCount.allows("তিনটা বার্গার", 3.0))
    }

    @Test
    fun `the session keeps the last six turns and clears`() {
        val session = VoiceSession()
        repeat(8) { session.said("turn $it", listOf("Nothing much")) }
        assertEquals(6, session.lines.size)
        assertTrue(session.lines.first().startsWith("You: \"turn 2\""))
        session.clear()
        assertTrue(session.lines.isEmpty())
    }

    @Test
    fun `the hamburger conversation reaches the model in order`() {
        val session = VoiceSession()
        session.said("I ate three hamburgers", listOf("Added: Hamburger, 3 piece (330 g)"))
        session.said("those three hamburgers were maximum 100 grams", listOf("Changed: Hamburger, 3 piece (330 g) → 100 g"))
        session.byHand("Changed: Rice, 1 plate (250 g) → Rice, 150 g")
        val prompt = VoicePrompt.command(
            "undo that",
            VoiceContext(LocalDateTime.of(2026, 9, 28, 14, 0), mealLines = listOf("Hamburger: 100 g (100 g)"), history = session.lines),
            PromptHints(),
        )
        val first = prompt.indexOf("I ate three hamburgers")
        val second = prompt.indexOf("maximum 100 grams")
        assertTrue(first in 0 until second)
        assertTrue("(By hand) Changed: Rice" in prompt)
        assertTrue("undo that" in prompt)
    }

    @Test
    fun `too big drops the oldest turns one at a time`() = runBlocking {
        val history = listOf("a", "b", "c", "d")
        val tried = mutableListOf<List<String>>()
        val result = withShorterHistory(history) { h ->
            tried += h
            if (h.size > 1) throw AiException.TooLarge() else "ok with $h"
        }
        assertEquals(listOf(listOf("a", "b", "c", "d"), listOf("b", "c", "d"), listOf("c", "d"), listOf("d")), tried)
        assertEquals("ok with [d]", result)
    }

    @Test(expected = AiException.TooLarge::class)
    fun `too big even alone reaches the user`() {
        runBlocking { withShorterHistory(listOf("a")) { throw AiException.TooLarge() } }
    }
}
