package dev.ytosko.neutrino.data.ai

import dev.ytosko.neutrino.domain.TokenUsage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.io.encoding.Base64

@Serializable
data class OpenRouterArchitecture(
    @SerialName("input_modalities") val inputModalities: List<String> = emptyList(),
    @SerialName("output_modalities") val outputModalities: List<String> = emptyList(),
)

@Serializable
data class OpenRouterPricing(val prompt: String? = null, val completion: String? = null)

/** One entry of OpenRouter's model list (only what Neutrino needs). */
@Serializable
data class OpenRouterModel(
    val id: String,
    val name: String = "",
    val architecture: OpenRouterArchitecture = OpenRouterArchitecture(),
    val pricing: OpenRouterPricing = OpenRouterPricing(),
    @SerialName("expiration_date") val expiration: JsonElement? = null,
) {
    /** Dollars per token; null when the price varies (routers, marked -1) or is missing. */
    val promptPrice: Double? get() = pricing.prompt?.toDoubleOrNull()?.takeIf { it >= 0 }
    val completionPrice: Double? get() = pricing.completion?.toDoubleOrNull()?.takeIf { it >= 0 }

    /** When OpenRouter will retire it, as epoch seconds (it sends a number or a date). */
    val expiresEpochSeconds: Long?
        get() = (expiration as? JsonPrimitive)?.let { p ->
            p.longOrNull ?: p.contentOrNull?.let { text ->
                runCatching { LocalDate.parse(text.take(10)).atStartOfDay().toEpochSecond(ZoneOffset.UTC) }.getOrNull()
            }
        }
}

@Serializable
private data class OpenRouterModelList(val data: List<OpenRouterModel> = emptyList())

/** Free-model requests left today on this key, from OpenRouter's own counter. */
data class FreeQuota(val used: Int, val limit: Int) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
}

/**
 * OpenRouter (openrouter.ai): many providers' models behind one OpenAI-compatible key. Only models
 * that read images are offered (see [ModelCatalog.fromOpenRouter]).
 *
 * Free models (ids ending in ":free") have a daily request limit. Before each one, the key's
 * remaining free requests are checked (OpenRouter's own counter, refreshed every minute and counted
 * down locally), and at 0 the request isn't sent: [AiException.RateLimited] lets the next model in
 * the chain answer. Free models never cost money; the gate only avoids requests bound to fail.
 *
 * A model OpenRouter no longer lists fails with [AiException.ModelGone], carrying a replacement of
 * the same kind, so the app can switch to it.
 */
class OpenRouterClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    private val clock: () -> Long = System::currentTimeMillis,
) : AiClient {

    private val lock = Mutex()
    private var catalog: Pair<Long, List<AiModel>>? = null
    /** Per key: when the free quota was read, and what's left. */
    private val quotas = mutableMapOf<String, Pair<Long, Int>>()

    override suspend fun listModels(apiKey: String): ModelChoices {
        // The model list is public; ask for the key's details first so a wrong key is caught here.
        freeQuota(apiKey)
        val models = models(apiKey, fresh = true)
        return ModelChoices(models, ModelCatalog.openRouterPick(models, freeOnly = true)?.id)
    }

    /** The photo models on offer now (cached for a few minutes). */
    suspend fun models(apiKey: String, fresh: Boolean = false): List<AiModel> = lock.withLock {
        catalog?.takeIf { !fresh && clock() - it.first < CATALOG_TTL_MS }?.let { return@withLock it.second }
        val request = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $apiKey").build()
        val (code, body) = http.call(request)
        if (code !in 200..299) throw error(code, body)
        val models = ModelCatalog.fromOpenRouter(json.decodeFromString<OpenRouterModelList>(body).data, clock() / 1000)
        catalog = clock() to models
        models
    }

    /** Today's free-model requests on this key; null if OpenRouter doesn't say. Checks the key too. */
    suspend fun freeQuota(apiKey: String): FreeQuota? {
        val request = Request.Builder().url("$baseUrl/key").header("Authorization", "Bearer $apiKey").build()
        val (code, body) = http.call(request)
        if (code !in 200..299) throw error(code, body)
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonObject }.getOrNull() ?: return null
        val daily = data["free_model_daily_requests"] as? JsonObject ?: return null
        val used = (daily["used"] as? JsonPrimitive)?.intOrNull ?: return null
        val limit = (daily["limit"] as? JsonPrimitive)?.intOrNull ?: return null
        return FreeQuota(used, limit).also { lock.withLock { quotas[apiKey] = clock() to it.remaining } }
    }

    override suspend fun generateJson(apiKey: String, model: String, prompt: String, schema: JsonSchema, image: ImageInput?): JsonReply {
        val free = model.endsWith(":free")
        if (free) gate(apiKey)
        val dataUrl = image?.let { "data:image/jpeg;base64," + Base64.encode(it.jpeg) }
        // First asking for the schema; a model that rejects that gets plain JSON instructions only.
        for (withSchema in listOf(true, false)) {
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("HTTP-Referer", "https://neutrino.ytosko.dev")
                .header("X-Title", "Neutrino")
                .post(requestBody(model, prompt, schema, dataUrl, withSchema).toString().toRequestBody(JSON_MEDIA))
                .build()
            val (code, body) = http.call(request)
            when {
                code in 200..299 -> {
                    if (free) spend(apiKey)
                    return parse(body)
                }
                code == 400 && withSchema && !isGone(body) && !dataPolicy(body) -> continue
                code == 429 && free -> {
                    lock.withLock { quotas[apiKey] = clock() to 0 }
                    throw AiException.RateLimited(AiProvider.OpenRouter)
                }
                code == 400 || code == 404 || code == 503 -> throw missingModel(apiKey, model, code, body)
                else -> throw error(code, body)
            }
        }
        throw AiException.NoResult()
    }

    /** Refuses a free-model request when today's free requests are used up. */
    private suspend fun gate(apiKey: String) {
        val known = lock.withLock { quotas[apiKey] }
        val remaining = if (known != null && clock() - known.first < QUOTA_TTL_MS) {
            known.second
        } else {
            runCatching { freeQuota(apiKey)?.remaining }.getOrNull()
        }
        if (remaining != null && remaining <= 0) throw AiException.RateLimited(AiProvider.OpenRouter)
    }

    private suspend fun spend(apiKey: String) = lock.withLock {
        quotas[apiKey]?.let { (at, left) -> quotas[apiKey] = at to (left - 1).coerceAtLeast(0) }
    }

    /**
     * 400/404/503 can mean the model is gone (OpenRouter answers "no endpoints" for both a removed
     * model and a busy one), so the model list decides: missing there means gone.
     */
    private suspend fun missingModel(apiKey: String, model: String, code: Int, body: String): AiException {
        if (dataPolicy(body)) return AiException.DataPolicy()
        val models = runCatching { models(apiKey, fresh = true) }.getOrNull() ?: return error(code, body)
        if (models.any { it.id == model }) return error(code, body)
        return AiException.ModelGone(model, ModelCatalog.openRouterPick(models, freeOnly = model.endsWith(":free")))
    }

    private fun requestBody(model: String, prompt: String, schema: JsonSchema, dataUrl: String?, withSchema: Boolean) =
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
                                putJsonObject("image_url") { put("url", dataUrl) }
                            })
                        }
                    }
                })
            }
            put("max_tokens", MAX_OUTPUT)
            put("temperature", 0.2)
            // Thinking first is slower and adds nothing here; models without it ignore this.
            putJsonObject("reasoning") { put("enabled", false) }
            if (withSchema) {
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "neutrino_reply")
                        put("strict", false)
                        put("schema", schema.openAi())
                    }
                }
            }
        }

    private fun parse(body: String): JsonReply {
        val root = json.parseToJsonElement(body).jsonObject
        val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw AiException.NoResult()
        val raw = (message["content"] as? JsonPrimitive)?.contentOrNull ?: throw AiException.NoResult()
        val text = raw.substringAfterLast("</think>").trim().ifEmpty { throw AiException.NoResult() }
        val usage = root["usage"]?.jsonObject?.let {
            TokenUsage(
                input = (it["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                output = (it["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }
        return JsonReply(text, usage)
    }

    private fun isGone(body: String) = "not a valid model" in body || "No endpoints found" in body
    private fun dataPolicy(body: String) = "data policy" in body || "privacy settings" in body

    private fun error(code: Int, body: String): AiException = when {
        code == 401 -> AiException.InvalidKey()
        code == 402 -> AiException.RateLimited(AiProvider.OpenRouter)
        code == 413 || openAiTooLarge(body) -> AiException.TooLarge()
        code == 429 -> AiException.RateLimited(AiProvider.OpenRouter)
        // 403 is a moderation or guardrail block: the model didn't answer.
        code == 403 -> AiException.NoResult()
        else -> AiException.Unexpected(code)
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        const val MAX_OUTPUT = 1024
        const val CATALOG_TTL_MS = 10 * 60_000L
        const val QUOTA_TTL_MS = 60_000L
    }
}
