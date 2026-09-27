package dev.ytosko.neutrino.data.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroqClientTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val jpeg = byteArrayOf(1, 2, 3)

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun groq() = GroqClient(OkHttpClient(), json, server.url("/openai/v1").toString().trimEnd('/'))

    private fun respond(code: Int, body: String, retryAfter: String? = null) = server.enqueue(
        MockResponse.Builder().code(code).body(body).apply { if (retryAfter != null) addHeader("retry-after", retryAfter) }.build(),
    )

    private fun reply(content: String) = """
        {"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(content))}}}],
         "usage":{"prompt_tokens":1654,"completion_tokens":219}}
    """.trimIndent()

    private val meal = """{"food_name":"Rice and curry","calories":520,"protein_g":20,"carbs_g":72,"fat_g":18.5}"""

    private fun nextBody(): JsonObject = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test
    fun `sends the photo without OpenAI's detail, best-effort schema, no thinking, under the free output cap`() = runTest {
        respond(200, reply(meal))
        val result = groq().analyzeMeal("KEY", "qwen/qwen3.8-27b", jpeg, PhotoDetail.Low)
        assertEquals("Rice and curry", result.foodName)
        assertEquals(1654, result.usage!!.input)

        val request = server.takeRequest()
        assertEquals("Bearer KEY", request.headers["Authorization"])
        assertTrue(request.url.encodedPath.endsWith("/openai/v1/chat/completions"))
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("none", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue(body["max_completion_tokens"]!!.jsonPrimitive.content.toInt() < 1000)
        val format = body["response_format"]!!.jsonObject
        assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
        assertEquals("false", format["json_schema"]!!.jsonObject["strict"].toString())
        val image = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject
        assertTrue(image["url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
        assertFalse("Groq has no detail setting", "detail" in image)
    }

    @Test
    fun `falls back to plain JSON mode when the schema is refused`() = runTest {
        respond(400, """{"error":{"message":"json_schema not supported"}}""")
        respond(200, reply(meal))
        groq().analyzeMeal("KEY", "meta-llama/llama-4-scout-17b-16e-instruct", jpeg, PhotoDetail.Standard)
        assertEquals("json_schema", nextBody()["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val second = nextBody()
        assertEquals("json_object", second["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse("only Qwen gets reasoning_effort", "reasoning_effort" in second)
    }

    @Test
    fun `waits once for a short rate limit, then says it's Groq's limit`() = runTest {
        respond(429, """{"error":{"message":"rate limit"}}""", retryAfter = "0.01")
        respond(200, reply(meal))
        assertEquals("Rice and curry", groq().analyzeMeal("KEY", "qwen/qwen3.8-27b", jpeg, PhotoDetail.Standard).foodName)

        respond(429, """{"error":{"message":"rate limit"}}""", retryAfter = "45")
        val error = runCatching { groq().analyzeMeal("KEY", "qwen/qwen3.8-27b", jpeg, PhotoDetail.Standard) }.exceptionOrNull()
        assertTrue(error is AiException.RateLimited)
        assertEquals(AiProvider.Groq, (error as AiException.RateLimited).provider)
    }

    @Test
    fun `thinking text before the answer is dropped`() = runTest {
        respond(200, reply("<think>rice, curry…</think>\n$meal"))
        assertEquals("Rice and curry", groq().analyzeMeal("KEY", "qwen/qwen3.8-27b", jpeg, PhotoDetail.Standard).foodName)
    }

    @Test
    fun `only Groq's photo models are offered, Qwen first`() {
        val choices = ModelCatalog.fromGroq(
            listOf("whisper-large-v3", "openai/gpt-oss-120b", "meta-llama/llama-4-scout-17b-16e-instruct", "qwen/qwen3.8-27b", "meta-llama/llama-prompt-guard-2-86m"),
        )
        assertEquals(listOf("qwen/qwen3.8-27b", "meta-llama/llama-4-scout-17b-16e-instruct"), choices.models.map { it.id })
        assertEquals("Qwen 3.8 27B", choices.models.first().displayName)
        assertEquals("qwen/qwen3.8-27b", choices.recommended)
    }
}
