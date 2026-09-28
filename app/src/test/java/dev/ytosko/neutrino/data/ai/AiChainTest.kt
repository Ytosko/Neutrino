package dev.ytosko.neutrino.data.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChainTest {

    private class Fake(val fail: AiException?) : AiClient {
        var calls = 0
        override suspend fun listModels(apiKey: String) = ModelChoices(emptyList(), null)
        override suspend fun generateJson(apiKey: String, model: String, prompt: String, schema: JsonSchema, images: List<ImageInput>): JsonReply {
            calls++
            fail?.let { throw it }
            return JsonReply("{}", null)
        }
    }

    private fun cfg(id: String, provider: AiProvider) = AiConfig(id, provider.id, "m", id) to "key-$id"

    @Test
    fun `falls back in order until one answers`() = runTest {
        val gemini = Fake(AiException.RateLimited())
        val groq = Fake(AiException.Network(java.io.IOException()))
        val openAi = Fake(null)
        val tried = mutableListOf<String>()
        val result = AiChain.run(
            listOf(cfg("p", AiProvider.Gemini), cfg("f1", AiProvider.Groq), cfg("f2", AiProvider.OpenAi)),
            mapOf(AiProvider.Gemini to gemini, AiProvider.Groq to groq, AiProvider.OpenAi to openAi),
            onAttempt = { tried += it.id },
        ) { client, key, config -> client.generateJson(key, config.model, "", JsonSchema.Str); config.id }
        assertEquals("f2", result!!.second)
        assertEquals(listOf("p", "f1", "f2"), tried)
    }

    @Test
    fun `a failure that shouldn't fall back stops the chain`() = runTest {
        val first = Fake(AiException.NoResult())
        val second = Fake(null)
        val error = runCatching {
            AiChain.run(
                listOf(cfg("p", AiProvider.Gemini), cfg("f1", AiProvider.OpenAi)),
                mapOf(AiProvider.Gemini to first, AiProvider.OpenAi to second),
                shouldFallBack = { it !is AiException.NoResult },
            ) { client, key, config -> client.generateJson(key, config.model, "", JsonSchema.Str) }
        }.exceptionOrNull()
        assertTrue(error is AiException.NoResult)
        assertEquals(0, second.calls)
    }

    @Test
    fun `when every model fails the last error is reported, and no models gives null`() = runTest {
        val error = runCatching {
            AiChain.run(
                listOf(cfg("p", AiProvider.Gemini), cfg("f1", AiProvider.Groq)),
                mapOf(AiProvider.Gemini to Fake(AiException.InvalidKey()), AiProvider.Groq to Fake(AiException.RateLimited(AiProvider.Groq))),
            ) { client, key, config -> client.generateJson(key, config.model, "", JsonSchema.Str) }
        }.exceptionOrNull()
        assertEquals(AiProvider.Groq, (error as AiException.RateLimited).provider)
        assertNull(AiChain.run(emptyList(), emptyMap()) { _, _, _ -> 1 })
    }
}
