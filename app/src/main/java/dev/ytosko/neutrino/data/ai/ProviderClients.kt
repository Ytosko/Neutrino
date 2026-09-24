package dev.ytosko.neutrino.data.ai

import dev.ytosko.neutrino.domain.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
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
import java.io.IOException
import kotlin.io.encoding.Base64

@Serializable
data class GeminiModelInfo(
    val name: String,
    val displayName: String? = null,
    val supportedGenerationMethods: List<String> = emptyList(),
)

@Serializable
private data class GeminiModelList(val models: List<GeminiModelInfo> = emptyList())

@Serializable
private data class OpenAiModelList(val data: List<OpenAiModelInfo> = emptyList())

@Serializable
private data class OpenAiModelInfo(val id: String)

private val JSON_MEDIA = "application/json".toMediaType()
private const val MAX_OUTPUT_TOKENS = 1024

/**
 * Google Gemini API (generativelanguage.googleapis.com). The key is sent as a header, never in the URL.
 */
class GeminiClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : AiClient {

    override suspend fun listModels(apiKey: String): ModelChoices {
        val request = Request.Builder().url("$baseUrl/models?pageSize=1000").header("x-goog-api-key", apiKey).build()
        val (code, body) = http.call(request)
        if (code !in 200..299) throw error(code, body)
        return ModelCatalog.fromGemini(json.decodeFromString<GeminiModelList>(body).models)
    }

    override suspend fun generateJson(apiKey: String, model: String, prompt: String, schema: JsonSchema, image: ImageInput?): JsonReply {
        val encoded = image?.let { Base64.encode(it.jpeg) }
        // Try the leanest configuration first. If the model rejects an option (e.g. a thinking
        // level it doesn't support), step down; the last attempt uses no optional settings.
        val attempts = thinkingCandidates(model).map { Attempt(lean = true, thinking = it) } + Attempt(lean = false, thinking = null)
        attempts.forEachIndexed { index, attempt ->
            val body = requestBody(prompt, schema, encoded, image?.detail, attempt)
            val request = Request.Builder()
                .url("$baseUrl/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            val (code, response) = http.call(request)
            val canRetry = index < attempts.lastIndex && code == 400 && !isKeyError(response)
            when {
                code in 200..299 -> return parse(response)
                canRetry -> Unit
                else -> throw error(code, response)
            }
        }
        throw AiException.NoResult()
    }

    private data class Attempt(val lean: Boolean, val thinking: JsonObject?)

    private fun requestBody(prompt: String, schema: JsonSchema, image: String?, detail: PhotoDetail?, attempt: Attempt) =
        buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        if (image != null) {
                            add(buildJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", "image/jpeg")
                                    put("data", image)
                                }
                            })
                        }
                        add(buildJsonObject { put("text", prompt) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("maxOutputTokens", MAX_OUTPUT_TOKENS * if (attempt.lean) 1 else 2)
                if (attempt.lean) {
                    put("temperature", 0.2)
                    put("responseSchema", schema.gemini())
                    if (detail != null) {
                        put("mediaResolution", if (detail == PhotoDetail.Low) "MEDIA_RESOLUTION_LOW" else "MEDIA_RESOLUTION_MEDIUM")
                    }
                    attempt.thinking?.let { put("thinkingConfig", it) }
                }
            }
        }

    /**
     * Thinking settings to try, least thinking first. Thinking tokens are billed as output but
     * add little for this task. A null entry means "don't send thinkingConfig".
     */
    private fun thinkingCandidates(model: String): List<JsonObject?> {
        val version = Regex("gemini-(\\d+(?:\\.\\d+)?)").find(model)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return listOf(null)
        fun level(value: String) = buildJsonObject { put("thinkingLevel", value) }
        fun budget(tokens: Int) = buildJsonObject { put("thinkingBudget", tokens) }
        return when {
            version >= 3.0 && "flash" in model -> listOf(level("minimal"), level("low"))
            version >= 3.0 -> listOf(level("low"))
            version >= 2.5 && "pro" in model -> listOf(budget(128))
            version >= 2.5 -> listOf(budget(0))
            else -> listOf(null)
        }
    }

    private fun parse(body: String): JsonReply {
        val root = json.parseToJsonElement(body).jsonObject
        val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: throw AiException.NoResult()
        val text = parts
            .map { it.jsonObject }
            .filterNot { (it["thought"] as? JsonPrimitive)?.booleanOrNull == true }
            .joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val usage = root["usageMetadata"]?.jsonObject?.let {
            TokenUsage(
                input = it.int("promptTokenCount"),
                output = it.int("candidatesTokenCount") + it.int("thoughtsTokenCount"),
            )
        }
        return JsonReply(text, usage)
    }

    private fun isKeyError(body: String) = "API_KEY_INVALID" in body || "API key not valid" in body

    private fun error(code: Int, body: String): AiException = when {
        code == 400 && isKeyError(body) -> AiException.InvalidKey()
        code == 401 || code == 403 -> AiException.InvalidKey()
        code == 429 -> AiException.RateLimited()
        else -> AiException.Unexpected(code)
    }
}

/** OpenAI Chat Completions API (api.openai.com). */
class OpenAiClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://api.openai.com/v1",
) : AiClient {

    override suspend fun listModels(apiKey: String): ModelChoices {
        val request = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $apiKey").build()
        val (code, body) = http.call(request)
        if (code !in 200..299) throw error(code)
        return ModelCatalog.fromOpenAi(json.decodeFromString<OpenAiModelList>(body).data.map { it.id })
    }

    override suspend fun generateJson(apiKey: String, model: String, prompt: String, schema: JsonSchema, image: ImageInput?): JsonReply {
        val dataUrl = image?.let { "data:image/jpeg;base64," + Base64.encode(it.jpeg) }
        for (lean in listOf(true, false)) {
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody(model, prompt, schema, dataUrl, image?.detail, lean).toString().toRequestBody(JSON_MEDIA))
                .build()
            val (code, body) = http.call(request)
            when {
                code in 200..299 -> return parse(body)
                code == 400 && lean -> continue
                else -> throw error(code)
            }
        }
        throw AiException.NoResult()
    }

    private fun requestBody(model: String, prompt: String, schema: JsonSchema, dataUrl: String?, detail: PhotoDetail?, lean: Boolean) =
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
                        if (dataUrl != null) {
                            add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", dataUrl)
                                    put("detail", if (detail == PhotoDetail.Low) "low" else "high")
                                }
                            })
                        }
                    }
                })
            }
            put("max_completion_tokens", MAX_OUTPUT_TOKENS * if (lean) 1 else 2)
            if (lean) {
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "neutrino_reply")
                        put("strict", true)
                        put("schema", schema.openAi())
                    }
                }
                if (model.startsWith("gpt-5") || model.startsWith("o")) put("reasoning_effort", "minimal")
            } else {
                putJsonObject("response_format") { put("type", "json_object") }
            }
        }

    private fun parse(body: String): JsonReply {
        val root = json.parseToJsonElement(body).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw AiException.NoResult()
        val text = (message["content"] as? JsonPrimitive)?.contentOrNull ?: throw AiException.NoResult()
        val usage = root["usage"]?.jsonObject?.let {
            TokenUsage(input = it.int("prompt_tokens"), output = it.int("completion_tokens"))
        }
        return JsonReply(text, usage)
    }

    private fun error(code: Int): AiException = when (code) {
        401, 403 -> AiException.InvalidKey()
        429 -> AiException.RateLimited()
        else -> AiException.Unexpected(code)
    }
}

/** Gemini's OpenAPI-subset dialect (upper-case types). */
internal fun JsonSchema.gemini(): JsonElement = when (this) {
    JsonSchema.Str -> buildJsonObject { put("type", "STRING") }
    JsonSchema.Num -> buildJsonObject { put("type", "NUMBER") }
    is JsonSchema.Arr -> buildJsonObject {
        put("type", "ARRAY")
        put("items", items.gemini())
    }
    is JsonSchema.Obj -> buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema.gemini()) } }
        putJsonArray("required") { properties.forEach { add(it.first) } }
    }
}

/** OpenAI strict JSON schema: every property required, no extras. */
internal fun JsonSchema.openAi(): JsonElement = when (this) {
    JsonSchema.Str -> buildJsonObject { put("type", "string") }
    JsonSchema.Num -> buildJsonObject { put("type", "number") }
    is JsonSchema.Arr -> buildJsonObject {
        put("type", "array")
        put("items", items.openAi())
    }
    is JsonSchema.Obj -> buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (name, schema) -> put(name, schema.openAi()) } }
        put("required", buildJsonArray { properties.forEach { add(it.first) } })
        put("additionalProperties", false)
    }
}

private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

/** Executes [request] off the main thread; returns status code and body. Network failures become [AiException.Network]. */
internal suspend fun OkHttpClient.call(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
    try {
        newCall(request).execute().use { it.code to it.body.string() }
    } catch (e: IOException) {
        throw AiException.Network(e)
    }
}
