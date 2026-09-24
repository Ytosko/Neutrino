package dev.ytosko.neutrino.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private fun gemini(id: String, vararg methods: String = arrayOf("generateContent")) =
        GeminiModelInfo(name = "models/$id", displayName = id, supportedGenerationMethods = methods.toList())

    @Test
    fun `gemini keeps vision chat models and recommends newest stable flash`() {
        val choices = ModelCatalog.fromGemini(
            listOf(
                gemini("gemini-2.0-flash"),
                gemini("gemini-2.5-flash"),
                gemini("gemini-2.5-flash-lite"),
                gemini("gemini-2.5-pro"),
                gemini("gemini-3.0-flash-preview"),
                gemini("gemini-2.5-flash-preview-tts"),
                gemini("gemini-2.5-flash-image"),
                gemini("text-embedding-004", "embedContent"),
                gemini("gemini-embedding-001", "embedContent"),
                gemini("gemma-3-27b-it"),
                gemini("imagen-4.0-generate", "predict"),
            ),
        )
        val ids = choices.models.map { it.id }
        assertEquals("gemini-2.5-flash", choices.recommended)
        assertEquals(
            listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-2.0-flash", "gemini-3.0-flash-preview"),
            ids,
        )
    }

    @Test
    fun `openai drops audio, dated snapshots and non-chat models and recommends mini`() {
        val choices = ModelCatalog.fromOpenAi(
            listOf(
                "gpt-4o", "gpt-4o-mini", "gpt-4o-2024-08-06", "gpt-4o-audio-preview", "gpt-4o-realtime-preview",
                "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
                "gpt-5", "gpt-5-mini", "gpt-5-nano", "gpt-5-codex", "gpt-5-chat-latest",
                "o4-mini", "o3",
                "text-embedding-3-small", "whisper-1", "dall-e-3", "gpt-image-1", "tts-1", "gpt-3.5-turbo",
            ),
        )
        val ids = choices.models.map { it.id }
        assertEquals("gpt-5-mini", choices.recommended)
        assertEquals(listOf("gpt-5-mini", "gpt-5-nano", "gpt-5"), ids.take(3))
        assertTrue("reasoning models listed last", ids.takeLast(2).all { it.startsWith("o") })
        listOf("gpt-4o-2024-08-06", "gpt-4o-audio-preview", "gpt-5-codex", "gpt-5-chat-latest", "whisper-1", "gpt-3.5-turbo")
            .forEach { assertFalse("$it should be filtered", it in ids) }
    }

    @Test
    fun `empty lists give no recommendation`() {
        assertNull(ModelCatalog.fromGemini(emptyList()).recommended)
        assertNull(ModelCatalog.fromOpenAi(listOf("whisper-1")).recommended)
    }
}
