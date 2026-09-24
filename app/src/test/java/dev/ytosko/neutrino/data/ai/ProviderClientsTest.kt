package dev.ytosko.neutrino.data.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderClientsTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()
    private val jpeg = byteArrayOf(1, 2, 3)

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun gemini() = GeminiClient(http, json, server.url("/v1beta").toString().trimEnd('/'))
    private fun openAi() = OpenAiClient(http, json, server.url("/v1").toString().trimEnd('/'))

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val geminiOk = """
        {"candidates":[{"content":{"parts":[
          {"text":"thinking...","thought":true},
          {"text":"{\"calories\":540,\"protein_g\":34,\"carbs_g\":63,\"fat_g\":18,\"food_name\":\"Rice and curry\"}"}
        ]}}],
         "usageMetadata":{"promptTokenCount":300,"candidatesTokenCount":40,"thoughtsTokenCount":10}}
    """.trimIndent()

    @Test
    fun `gemini sends key as header, lean config, and parses answer and usage`() = runTest {
        respond(200, geminiOk)
        val result = gemini().analyze("KEY", "gemini-2.5-flash", jpeg, "prompt", PhotoDetail.Low)

        assertEquals("Rice and curry", result.foodName)
        assertEquals(63.0, result.nutrition.carbsG, 0.0)
        assertEquals(300, result.usage!!.input)
        assertEquals(50, result.usage.output) // thoughts count as output

        val request = server.takeRequest()
        assertEquals("KEY", request.headers["x-goog-api-key"])
        assertFalse("key must not be in the URL", request.url.toString().contains("KEY"))
        val config = json.parseToJsonElement(request.body!!.utf8()).jsonObject["generationConfig"]!!.jsonObject
        assertEquals("\"MEDIA_RESOLUTION_LOW\"", config["mediaResolution"].toString())
        assertEquals("0", config["thinkingConfig"]!!.jsonObject["thinkingBudget"].toString())
        assertTrue("responseSchema" in config)
    }

    private fun nextConfig() =
        json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["generationConfig"]!!.jsonObject

    @Test
    fun `gemini 3 flash tries minimal, then low, then no thinking settings`() = runTest {
        respond(400, """{"error":{"message":"thinking level minimal is not supported"}}""")
        respond(400, """{"error":{"message":"Unknown field thinkingLevel"}}""")
        respond(200, geminiOk)
        val result = gemini().analyze("KEY", "gemini-3.8-flash", jpeg, "prompt", PhotoDetail.Standard)
        assertEquals(540.0, result.nutrition.calories, 0.0)

        assertEquals("\"minimal\"", nextConfig()["thinkingConfig"]!!.jsonObject["thinkingLevel"].toString())
        assertEquals("\"low\"", nextConfig()["thinkingConfig"]!!.jsonObject["thinkingLevel"].toString())
        assertFalse("thinkingConfig" in nextConfig())
    }

    @Test
    fun `gemini stops at the first accepted configuration`() = runTest {
        respond(200, geminiOk)
        gemini().analyze("KEY", "gemini-3.8-flash", jpeg, "prompt", PhotoDetail.Standard)
        assertEquals("\"minimal\"", nextConfig()["thinkingConfig"]!!.jsonObject["thinkingLevel"].toString())
        assertEquals(1, server.requestCount)
    }

    @Test(expected = AiException.InvalidKey::class)
    fun `gemini invalid key is not retried`() = runTest {
        respond(400, """{"error":{"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""")
        gemini().analyze("BAD", "gemini-2.5-flash", jpeg, "prompt", PhotoDetail.Standard)
    }

    @Test(expected = AiException.RateLimited::class)
    fun `rate limits are reported`() = runTest {
        respond(429, "{}")
        gemini().analyze("KEY", "gemini-2.5-flash", jpeg, "prompt", PhotoDetail.Standard)
    }

    @Test(expected = AiException.NoResult::class)
    fun `blocked or empty gemini answer is NoResult`() = runTest {
        respond(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        gemini().analyze("KEY", "gemini-2.5-flash", jpeg, "prompt", PhotoDetail.Standard)
    }

    @Test
    fun `openai uses bearer auth, strict schema, image detail and minimal reasoning`() = runTest {
        respond(
            200,
            """{"choices":[{"message":{"content":"{\"calories\":300,\"protein_g\":20,\"carbs_g\":30,\"fat_g\":10,\"food_name\":\"Salad\"}"}}],
               "usage":{"prompt_tokens":120,"completion_tokens":30}}""",
        )
        val result = openAi().analyze("sk-test", "gpt-5-mini", jpeg, "prompt", PhotoDetail.Low)
        assertEquals("Salad", result.foodName)
        assertEquals(150, result.usage!!.total)

        val request = server.takeRequest()
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals("\"minimal\"", body["reasoning_effort"].toString())
        assertEquals("\"json_schema\"", body["response_format"]!!.jsonObject["type"].toString())
        assertTrue(request.body!!.utf8().contains("\"detail\":\"low\""))
    }

    @Test
    fun `openai falls back to json_object without reasoning_effort on 400`() = runTest {
        respond(400, """{"error":{"message":"Unsupported parameter: reasoning_effort"}}""")
        respond(200, """{"choices":[{"message":{"content":"{\"calories\":100,\"protein_g\":1,\"carbs_g\":20,\"fat_g\":1,\"food_name\":\"Apple\"}"}}]}""")
        openAi().analyze("sk-test", "gpt-4o-mini", jpeg, "prompt", PhotoDetail.Standard)

        server.takeRequest()
        val retry = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertFalse("reasoning_effort" in retry)
        assertEquals("\"json_object\"", retry["response_format"]!!.jsonObject["type"].toString())
    }
}
