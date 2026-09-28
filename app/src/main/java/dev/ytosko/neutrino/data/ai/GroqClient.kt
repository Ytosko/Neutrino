package dev.ytosko.neutrino.data.ai

import java.io.IOException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import dev.ytosko.neutrino.domain.TokenUsage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.io.encoding.Base64

@Serializable
private data class GroqModelList(val data: List<GroqModelInfo> = emptyList())

@Serializable
private data class GroqModelInfo(val id: String, val active: Boolean = true)

/**
 * Groq (api.groq.com), OpenAI-compatible Chat Completions. Differences from OpenAI that matter here:
 * no per-image "detail" (the photo is already resized on the phone), JSON schemas are best-effort
 * (`strict: false`), and Qwen models are asked not to "think" first, which is slower and not needed.
 * The free plan allows only a few thousand tokens a minute, about one photo, so 429 (and 413,
 * "request larger than your per-minute limit") say so specifically.
 */
class GroqClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://api.groq.com/openai/v1",
) : AiClient {

    override suspend fun listModels(apiKey: String): ModelChoices {
        val request = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $apiKey").build()
        val (code, body) = http.call(request)
        if (code !in 200..299) throw error(code, body)
        val ids = json.decodeFromString<GroqModelList>(body).data.filter { it.active }.map { it.id }
        return ModelCatalog.fromGroq(ids)
    }

    override suspend fun generateJson(apiKey: String, model: String, prompt: String, schema: JsonSchema, images: List<ImageInput>): JsonReply {
        val dataUrls = images.map { "data:image/jpeg;base64," + Base64.encode(it.jpeg) }
        // First with the schema; if Groq rejects that for this model, plain JSON mode.
        for (withSchema in listOf(true, false)) {
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody(model, prompt, schema, dataUrls, withSchema).toString().toRequestBody(JSON_MEDIA))
                .build()
            var (code, body, retryAfter) = send(request)
            // The free plan counts tokens per minute; a short wait usually clears it, so wait once.
            if (code == 429 && retryAfter != null && retryAfter <= MAX_WAIT_SECONDS) {
                delay((retryAfter * 1000).toLong() + 250)
                send(request).let { code = it.first; body = it.second }
            }
            when {
                code in 200..299 -> return parse(body)
                code == 400 && withSchema && !tooLarge(body) -> continue
                else -> throw error(code, body)
            }
        }
        throw AiException.NoResult()
    }

    private fun requestBody(model: String, prompt: String, schema: JsonSchema, dataUrls: List<String>, withSchema: Boolean) =
        buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })
                        dataUrls.forEach { dataUrl ->
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") { put("url", dataUrl) }
                            })
                        }
                    }
                })
            }
            put("max_completion_tokens", MAX_OUTPUT)
            put("temperature", 0.2)
            if (thinks(model)) put("reasoning_effort", "none")
            if (withSchema) {
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "neutrino_reply")
                        put("strict", false)
                        put("schema", schema.openAi())
                    }
                }
            } else {
                putJsonObject("response_format") { put("type", "json_object") }
            }
        }

    /** Status, body and Groq's suggested wait in seconds (from Retry-After), if any. */
    private suspend fun send(request: Request): Triple<Int, String, Double?> = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                Triple(response.code, response.body.string(), response.header("retry-after")?.toDoubleOrNull())
            }
        } catch (e: IOException) {
            throw AiException.Network(e)
        }
    }

    private fun parse(body: String): JsonReply {
        val root = json.parseToJsonElement(body).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw AiException.NoResult()
        val raw = (message["content"] as? JsonPrimitive)?.contentOrNull ?: throw AiException.NoResult()
        // Should thinking text ever come back inline, keep only the answer after it.
        val text = raw.substringAfterLast("</think>").trim().ifEmpty { throw AiException.NoResult() }
        val usage = root["usage"]?.jsonObject?.let {
            TokenUsage(
                input = (it["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                output = (it["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }
        return JsonReply(text, usage)
    }

    /** 413 is "request larger than your per-minute limit": one request too big, not too many. */
    private fun error(code: Int, body: String): AiException = when {
        code == 401 || code == 403 -> AiException.InvalidKey()
        code == 413 || tooLarge(body) -> AiException.TooLarge()
        code == 429 -> AiException.RateLimited(AiProvider.Groq)
        else -> AiException.Unexpected(code)
    }

    private fun tooLarge(body: String) = "context_length_exceeded" in body || "Request too large" in body

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        /** Under the free plan's 1,000 output tokens a minute; a big meal's reply is about 500. */
        const val MAX_OUTPUT = 900
        const val MAX_WAIT_SECONDS = 20.0

        /** Qwen models reason before answering unless told not to. */
        fun thinks(model: String) = model.startsWith("qwen/")
    }
}
