package dev.ytosko.neutrino.data.ai

import dev.ytosko.neutrino.domain.MealAnalysis
import dev.ytosko.neutrino.domain.MealAnalysisParser
import dev.ytosko.neutrino.domain.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
private val NUTRITION_FIELDS = listOf("calories", "protein_g", "carbs_g", "fat_g")

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

    override suspend fun analyze(
        apiKey: String,
        model: String,
        jpeg: ByteArray,
        prompt: String,
        detail: PhotoDetail,
    ): MealAnalysis {
        val image = Base64.encode(jpeg)
        // Try the leanest configuration first. If the model rejects an option (e.g. a thinking
        // level it doesn't support), step down; the last attempt uses no optional settings.
        val attempts = thinkingCandidates(model).map { Attempt(lean = true, thinking = it) } + Attempt(lean = false, thinking = null)
        attempts.forEachIndexed { index, attempt ->
            val request = Request.Builder()
                .url("$baseUrl/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(requestBody(image, prompt, detail, attempt).toString().toRequestBody(JSON_MEDIA))
                .build()
            val (code, body) = http.call(request)
            val canRetry = index < attempts.lastIndex && code == 400 && !isKeyError(body)
            when {
                code in 200..299 -> return parse(body)
                canRetry -> Unit
                else -> throw error(code, body)
            }
        }
        throw AiException.NoResult()
    }

    private data class Attempt(val lean: Boolean, val thinking: JsonObject?)

    private fun requestBody(image: String, prompt: String, detail: PhotoDetail, attempt: Attempt) =
        buildJsonObject {
            val lean = attempt.lean
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/jpeg")
                                put("data", image)
                            }
                        })
                        add(buildJsonObject { put("text", prompt) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("maxOutputTokens", MAX_OUTPUT_TOKENS * if (lean) 1 else 2)
                if (lean) {
                    put("temperature", 0.2)
                    put("responseSchema", geminiSchema())
                    put("mediaResolution", if (detail == PhotoDetail.Low) "MEDIA_RESOLUTION_LOW" else "MEDIA_RESOLUTION_MEDIUM")
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

    private fun geminiSchema() = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            NUTRITION_FIELDS.forEach { putJsonObject(it) { put("type", "NUMBER") } }
            putJsonObject("food_name") { put("type", "STRING") }
        }
        putJsonArray("required") { (NUTRITION_FIELDS + "food_name").forEach { add(it) } }
    }

    private fun parse(body: String): MealAnalysis {
        val root = json.parseToJsonElement(body).jsonObject
        val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
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
        return MealAnalysisParser.parse(text)?.copy(usage = usage) ?: throw AiException.NoResult()
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

    override suspend fun analyze(
        apiKey: String,
        model: String,
        jpeg: ByteArray,
        prompt: String,
        detail: PhotoDetail,
    ): MealAnalysis {
        val dataUrl = "data:image/jpeg;base64," + Base64.encode(jpeg)
        for (lean in listOf(true, false)) {
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody(model, dataUrl, prompt, detail, lean).toString().toRequestBody(JSON_MEDIA))
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

    private fun requestBody(model: String, dataUrl: String, prompt: String, detail: PhotoDetail, lean: Boolean) =
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
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", dataUrl)
                                put("detail", if (detail == PhotoDetail.Low) "low" else "high")
                            }
                        })
                    }
                })
            }
            put("max_completion_tokens", MAX_OUTPUT_TOKENS * if (lean) 1 else 2)
            if (lean) {
                put("response_format", strictSchema())
                if (model.startsWith("gpt-5") || model.startsWith("o")) put("reasoning_effort", "minimal")
            } else {
                putJsonObject("response_format") { put("type", "json_object") }
            }
        }

    private fun strictSchema() = buildJsonObject {
        put("type", "json_schema")
        putJsonObject("json_schema") {
            put("name", "meal_nutrition")
            put("strict", true)
            putJsonObject("schema") {
                put("type", "object")
                putJsonObject("properties") {
                    NUTRITION_FIELDS.forEach { putJsonObject(it) { put("type", "number") } }
                    putJsonObject("food_name") { put("type", "string") }
                }
                put("required", buildJsonArray { (NUTRITION_FIELDS + "food_name").forEach { add(it) } })
                put("additionalProperties", false)
            }
        }
    }

    private fun parse(body: String): MealAnalysis {
        val root = json.parseToJsonElement(body).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw AiException.NoResult()
        val text = (message["content"] as? JsonPrimitive)?.contentOrNull ?: throw AiException.NoResult()
        val usage = root["usage"]?.jsonObject?.let {
            TokenUsage(input = it.int("prompt_tokens"), output = it.int("completion_tokens"))
        }
        return MealAnalysisParser.parse(text)?.copy(usage = usage) ?: throw AiException.NoResult()
    }

    private fun error(code: Int): AiException = when (code) {
        401, 403 -> AiException.InvalidKey()
        429 -> AiException.RateLimited()
        else -> AiException.Unexpected(code)
    }
}

private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/** Executes [request] off the main thread; returns status code and body. Network failures become [AiException.Network]. */
internal suspend fun OkHttpClient.call(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
    try {
        newCall(request).execute().use { it.code to it.body.string() }
    } catch (e: IOException) {
        throw AiException.Network(e)
    }
}
