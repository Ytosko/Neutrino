package dev.ytosko.neutrino.data.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenRouterClientTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private var now = 1_800_000_000_000L

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun client() = OpenRouterClient(OkHttpClient(), json, server.url("/api/v1").toString().trimEnd('/'), clock = { now })

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun model(id: String, input: String = "\"text\",\"image\"", output: String = "\"text\"", prompt: String = "0", completion: String = "0", expires: String = "null") =
        """{"id":"$id","name":"$id","architecture":{"input_modalities":[$input],"output_modalities":[$output]},
            "pricing":{"prompt":"$prompt","completion":"$completion"},"expiration_date":$expires}"""

    private val models = """{"data":[
        ${model("qwen/qwen3.8-27b:free")},
        ${model("google/gemma-4-31b-it:free")},
        ${model("openai/gpt-5-mini", prompt = "0.00000025", completion = "0.000002")},
        ${model("meta-llama/llama-text-only:free", input = "\"text\"")},
        ${model("openrouter/free", prompt = "-1", completion = "-1")},
        ${model("nvidia/content-safety:free")},
        ${model("google/lyria-3-clip-preview", output = "\"text\",\"audio\"")},
        ${model("old/retired:free", expires = "\"2020-01-01\"")}
    ]}"""

    private fun quota(used: Int, limit: Int) = """{"data":{"label":"k","free_model_daily_requests":{"used":$used,"limit":$limit,"remaining":${limit - used}}}}"""

    private val reply = """{"choices":[{"message":{"content":"{\"food_name\":\"Rice\",\"items\":[]}"}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""

    @Test
    fun `only photo models with a price, free first`() = runTest {
        respond(200, quota(0, 50))
        respond(200, models)
        val choices = client().listModels("KEY")
        assertEquals(listOf("google/gemma-4-31b-it:free", "qwen/qwen3.8-27b:free", "openai/gpt-5-mini"), choices.models.map { it.id })
        assertEquals("qwen/qwen3.8-27b:free", choices.recommended)
        val paid = choices.models.last()
        assertFalse(paid.free)
        assertEquals("$0.25 / $2.00 per 1M tokens", paid.price)
    }

    @Test
    fun `the free pick never offers a paid model`() {
        val list = listOf(AiModel("openai/gpt-5-mini", "GPT", free = false), AiModel("x/some-model:free", "X", free = true))
        assertEquals("x/some-model:free", ModelCatalog.openRouterPick(list, freeOnly = true)?.id)
        assertNull(ModelCatalog.openRouterPick(list.take(1), freeOnly = true))
        assertEquals("openai/gpt-5-mini", ModelCatalog.openRouterPick(list.take(1), freeOnly = false)?.id)
    }

    @Test
    fun `a free request is refused before sending when today's free requests are used up`() = runTest {
        respond(200, quota(50, 50))
        try {
            client().generateJson("KEY", "qwen/qwen3.8-27b:free", "hi", AnalysisPrompt.MEAL_SCHEMA)
            fail("expected the gate to refuse")
        } catch (e: AiException.RateLimited) {
            assertEquals(AiProvider.OpenRouter, e.provider)
        }
        assertEquals(1, server.requestCount) // only the quota check, no chat request
    }

    @Test
    fun `free requests go through while some are left, without thinking, with the schema`() = runTest {
        respond(200, quota(10, 50))
        respond(200, reply)
        val result = client().generateJson("KEY", "qwen/qwen3.8-27b:free", "hi", AnalysisPrompt.MEAL_SCHEMA)
        assertTrue("Rice" in result.text)
        server.takeRequest()
        val body = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("false", body["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        assertEquals("json_schema", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a paid model skips the gate`() = runTest {
        respond(200, reply)
        client().generateJson("KEY", "openai/gpt-5-mini", "hi", AnalysisPrompt.MEAL_SCHEMA)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a removed model is reported gone with a free replacement`() = runTest {
        respond(200, quota(0, 50))
        respond(503, """{"error":{"code":503,"message":"No endpoints found for old/model:free."}}""")
        respond(200, models)
        try {
            client().generateJson("KEY", "old/model:free", "hi", AnalysisPrompt.MEAL_SCHEMA)
            fail("expected ModelGone")
        } catch (e: AiException.ModelGone) {
            assertEquals("old/model:free", e.model)
            assertEquals("qwen/qwen3.8-27b:free", e.replacement?.id)
        }
    }

    @Test
    fun `a model that's still listed but busy is not gone`() = runTest {
        respond(200, quota(0, 50))
        respond(503, """{"error":{"code":503,"message":"No endpoints found"}}""")
        respond(200, models)
        try {
            client().generateJson("KEY", "qwen/qwen3.8-27b:free", "hi", AnalysisPrompt.MEAL_SCHEMA)
            fail("expected an error")
        } catch (e: AiException.Unexpected) {
            assertEquals(503, e.code)
        }
    }

    @Test
    fun `the chain swaps a gone model for its replacement and asks it once`() = runTest {
        val config = AiConfig(id = "a", provider = "openrouter", model = "old/model:free", name = "Old")
        val replacement = AiModel("qwen/qwen3.8-27b:free", "Qwen")
        AiChain.repair = { c, gone -> c.copy(model = gone.replacement!!.id, name = gone.replacement!!.displayName) }
        try {
            val asked = mutableListOf<String>()
            val stub = object : AiClient {
                override suspend fun listModels(apiKey: String) = ModelChoices(emptyList(), null)
                override suspend fun generateJson(apiKey: String, model: String, prompt: String, schema: JsonSchema, images: List<ImageInput>): JsonReply {
                    asked += model
                    if (model == "old/model:free") throw AiException.ModelGone(model, replacement)
                    return JsonReply("ok", null)
                }
            }
            val (answered, text) = AiChain.run(listOf(config to "KEY"), mapOf(AiProvider.OpenRouter to stub)) { client, key, c ->
                client.generateJson(key, c.model, "p", AnalysisPrompt.MEAL_SCHEMA).text
            }!!
            assertEquals(listOf("old/model:free", "qwen/qwen3.8-27b:free"), asked)
            assertEquals("qwen/qwen3.8-27b:free", answered.model)
            assertEquals("ok", text)
        } finally {
            AiChain.repair = null
        }
    }
}
