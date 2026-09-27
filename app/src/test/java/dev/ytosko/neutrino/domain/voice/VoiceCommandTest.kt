package dev.ytosko.neutrino.domain.voice

import dev.ytosko.neutrino.data.ai.ModelCatalog
import dev.ytosko.neutrino.data.ai.PromptHints
import dev.ytosko.neutrino.data.voice.VoiceContext
import dev.ytosko.neutrino.data.voice.VoicePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class VoiceCommandTest {

    private val now = LocalDateTime.of(2026, 9, 28, 14, 5)

    @Test
    fun `new meal with time water glucose and medicine`() {
        val reply = """
            {"reply": "Added rice and chicken curry.", "items": [
              {"action": "add", "item": 0, "name": "White rice, cooked", "quantity": 1, "unit": "plate", "grams": 250, "calories": 325, "protein_g": 6, "carbs_g": 70, "fat_g": 1},
              {"action": "add", "item": 0, "name": "Chicken curry", "quantity": 2, "unit": "piece", "grams": 200, "calories": 360, "protein_g": 30, "carbs_g": 8, "fat_g": 22}],
             "meal_time": "2026-09-28 12:05", "water_ml": 250, "glucose_value": 8.4, "glucose_unit": "", "glucose_relation": "after_meal",
             "glucose_time": "", "medicines": [{"name": "Metformin", "strength": "500", "amount": 0, "time": ""}]}
        """.trimIndent()
        val command = VoiceCommandParser.parse(reply, now, itemCount = 0)!!
        assertEquals(2, command.items.size)
        assertTrue(command.items.all { it is ItemChange.Add })
        assertEquals(LocalDateTime.of(2026, 9, 28, 12, 5), command.mealTime)
        assertEquals(250, command.waterMl)
        assertEquals(8.4, command.glucose!!.mmolPerL, 1e-9)
        assertEquals(SpokenRelation.AfterMeal, command.glucose!!.relation)
        assertEquals("Metformin", command.medicines.single().name)
    }

    @Test
    fun `edits point at existing lines only`() {
        val reply = """{"reply": "Changed.", "items": [
            {"action": "set", "item": 2, "name": "Burger", "quantity": 3, "unit": "piece", "grams": 450, "calories": 900, "protein_g": 45, "carbs_g": 90, "fat_g": 40},
            {"action": "remove", "item": 1, "name": "", "quantity": 0, "unit": "", "grams": 0, "calories": 0, "protein_g": 0, "carbs_g": 0, "fat_g": 0},
            {"action": "remove", "item": 9, "name": "", "quantity": 0, "unit": "", "grams": 0, "calories": 0, "protein_g": 0, "carbs_g": 0, "fat_g": 0}],
            "meal_time": "", "water_ml": 0, "glucose_value": 0, "glucose_unit": "", "glucose_relation": "", "glucose_time": "", "medicines": []}"""
        val command = VoiceCommandParser.parse(reply, now, itemCount = 2)!!
        assertEquals(listOf(ItemChange.Set::class, ItemChange.Remove::class), command.items.map { it::class })
        assertEquals(1, (command.items[0] as ItemChange.Set).index)
        assertEquals(0, (command.items[1] as ItemChange.Remove).index)
        assertNull(command.mealTime)
    }

    @Test
    fun `nothing understood is empty`() {
        val reply = """{"reply": "", "items": [], "meal_time": "", "water_ml": 0, "glucose_value": 0, "glucose_unit": "", "glucose_relation": "", "glucose_time": "", "medicines": []}"""
        assertTrue(VoiceCommandParser.parse(reply, now, 0)!!.isEmpty)
        assertNull(VoiceCommandParser.parse("not json", now, 0))
    }

    @Test
    fun `a later time today means yesterday`() {
        assertEquals(LocalDateTime.of(2026, 9, 27, 21, 0), VoiceCommandParser.time("21:00", now))
        assertEquals(LocalDateTime.of(2026, 9, 28, 13, 0), VoiceCommandParser.time("13:00", now))
        assertNull(VoiceCommandParser.time("", now))
    }

    @Test
    fun `glucose unit from the value when not said`() {
        assertEquals(7.77, SpokenGlucose(140.0, null, SpokenRelation.General, null).mmolPerL, 0.01)
        assertEquals(7.8, SpokenGlucose(7.8, null, SpokenRelation.General, null).mmolPerL, 1e-9)
        assertEquals(5.0, SpokenGlucose(90.09, "mgdl", SpokenRelation.General, null).mmolPerL, 0.01)
    }

    private val list = listOf(
        KnownMedicine("1", "Oramet SR", "Metformin Hydrochloride", "500 mg"),
        KnownMedicine("2", "Comet", "Metformin Hydrochloride", "850 mg"),
        KnownMedicine("3", "Napa", "Paracetamol", "500 mg"),
    )

    @Test
    fun `generic name and strength find the brand`() {
        val found = MedicineMatcher.match(SpokenMedicine("metformin", "500", 0.0, null), list)
        assertEquals(listOf("1"), found.map { it.id })
    }

    @Test
    fun `generic without strength offers every brand`() {
        val found = MedicineMatcher.match(SpokenMedicine("metformin", "", 0.0, null), list)
        assertEquals(listOf("1", "2"), found.map { it.id })
    }

    @Test
    fun `brand misheard slightly still matches, unknown never does`() {
        assertEquals(listOf("1"), MedicineMatcher.match(SpokenMedicine("Oramate", "", 0.0, null), list).map { it.id })
        assertTrue(MedicineMatcher.match(SpokenMedicine("Seclo", "20", 0.0, null), list).isEmpty())
    }

    @Test
    fun `voice models per provider`() {
        val openAi = ModelCatalog.voiceFromOpenAi(listOf("gpt-5-mini", "whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe", "gpt-realtime-transcribe", "tts-1"))
        assertEquals(listOf("gpt-4o-mini-transcribe", "gpt-4o-transcribe", "whisper-1"), openAi.models.map { it.id })
        assertEquals("gpt-4o-mini-transcribe", openAi.recommended)
        val groq = ModelCatalog.voiceFromGroq(listOf("qwen/qwen3.8-27b", "whisper-large-v3-turbo", "whisper-large-v3"))
        assertEquals("whisper-large-v3", groq.recommended)
        assertEquals("Whisper Large v3 Turbo", groq.models[1].displayName)
    }

    @Test
    fun `the prompt numbers the medicine list and isn't indented`() {
        val prompt = VoicePrompt.command(
            "I took metformin 500",
            VoiceContext(now, medicines = listOf("Oramet SR 500 mg (Metformin Hydrochloride)", "Napa 500 mg (Paracetamol)")),
            PromptHints(),
        )
        assertTrue("1. Oramet SR 500 mg (Metformin Hydrochloride)" in prompt)
        assertTrue("2. Napa 500 mg (Paracetamol)" in prompt)
        assertTrue(prompt.lines().none { it.startsWith("            ") })
        val off = VoicePrompt.command("I took metformin", VoiceContext(now), PromptHints())
        assertTrue("doesn't keep a medicine log" in off)
    }

    @Test
    fun `the medicine the AI picked from the list`() {
        val reply = """{"reply": "Logged.", "items": [], "meal_time": "", "water_ml": 0, "glucose_value": 0, "glucose_unit": "",
            "glucose_relation": "", "glucose_time": "", "medicines": [{"medicine": 2, "name": "metformin", "strength": "500", "amount": 1, "time": ""}]}"""
        assertEquals(2, VoiceCommandParser.parse(reply, now, 0)!!.medicines.single().listNumber)
    }

    @Test
    fun `a vague correction asks back, and the answer carries the question`() {
        val reply = """{"reply": "", "question": "About how much did the three chicken burgers weigh?", "items": [], "meal_time": "", "water_ml": 0,
            "glucose_value": 0, "glucose_unit": "", "glucose_relation": "", "glucose_time": "", "medicines": []}"""
        val command = VoiceCommandParser.parse(reply, now, 1)!!
        assertEquals("About how much did the three chicken burgers weigh?", command.question)
        assertTrue(!command.isEmpty)
        val next = VoicePrompt.command(
            "maximum 100 gram",
            VoiceContext(now, mealLines = listOf("Chicken burger / sandwich: 3 piece (561 g)"), asked = "it's not that big" to command.question),
            PromptHints(),
        )
        assertTrue("you asked: \"About how much did the three chicken burgers weigh?\"" in next)
    }
}
