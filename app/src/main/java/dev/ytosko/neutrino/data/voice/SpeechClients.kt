package dev.ytosko.neutrino.data.voice

import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.GeminiModelInfo
import dev.ytosko.neutrino.data.ai.ModelCatalog
import dev.ytosko.neutrino.data.ai.ModelChoices
import dev.ytosko.neutrino.data.ai.call
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.io.encoding.Base64

/** Turns recorded speech into text with the user's own key. */
interface SpeechClient {
    /** Speech-to-text models [apiKey] can use. Doubles as a key check. */
    suspend fun listModels(apiKey: String): ModelChoices

    /**
     * Transcribes [wav] (16 kHz mono). [hint] is a few words of likely vocabulary (food names…),
     * which helps with local dish names. Returns the text, or blank if nothing was said.
     */
    suspend fun transcribe(apiKey: String, model: String, wav: ByteArray, hint: String): String
}

@Serializable
private data class ModelList(val data: List<ModelInfo> = emptyList())

@Serializable
private data class ModelInfo(val id: String, val active: Boolean = true)

@Serializable
private data class Transcript(val text: String = "")

@Serializable
private data class GeminiModels(val models: List<GeminiModelInfo> = emptyList())

private val WAV = "audio/wav".toMediaType()
private val JSON_MEDIA = "application/json".toMediaType()

/**
 * OpenAI's and Groq's /audio/transcriptions endpoint (Groq's is OpenAI-compatible). The audio is
 * sent once and nothing is kept on the phone.
 */
class OpenAiSpeechClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val provider: AiProvider,
    private val baseUrl: String = when (provider) {
        AiProvider.Groq -> "https://api.groq.com/openai/v1"
        else -> "https://api.openai.com/v1"
    },
) : SpeechClient {

    override suspend fun listModels(apiKey: String): ModelChoices {
        val request = Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $apiKey").build()
        val (code, body) = http.call(request)
        if (code !in 200..299) throw error(code)
        val ids = json.decodeFromString<ModelList>(body).data.filter { it.active }.map { it.id }
        return if (provider == AiProvider.Groq) ModelCatalog.voiceFromGroq(ids) else ModelCatalog.voiceFromOpenAi(ids)
    }

    override suspend fun transcribe(apiKey: String, model: String, wav: ByteArray, hint: String): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply { if (hint.isNotBlank()) addFormDataPart("prompt", hint) }
            .build()
        val request = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(form)
            .build()
        val (code, body) = http.call(request)
        if (code !in 200..299) throw error(code)
        return runCatching { json.decodeFromString<Transcript>(body).text }.getOrElse { throw AiException.NoResult() }.trim()
    }

    private fun error(code: Int): AiException = when (code) {
        401, 403 -> AiException.InvalidKey()
        413 -> AiException.TooLarge()
        429 -> AiException.RateLimited(provider.takeIf { it == AiProvider.Groq })
        else -> AiException.Unexpected(code)
    }
}

/** Gemini hears audio directly: the recording goes in with a "write down what was said" prompt. */
class GeminiSpeechClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : SpeechClient {

    override suspend fun listModels(apiKey: String): ModelChoices {
        val request = Request.Builder().url("$baseUrl/models?pageSize=1000").header("x-goog-api-key", apiKey).build()
        val (code, body) = http.call(request)
        if (code !in 200..299) throw error(code, body)
        return ModelCatalog.voiceFromGemini(json.decodeFromString<GeminiModels>(body).models)
    }

    override suspend fun transcribe(apiKey: String, model: String, wav: ByteArray, hint: String): String {
        val audio = Base64.encode(wav)
        // With the least thinking first; a model that doesn't take that setting gets none.
        for (lean in listOf(true, false)) {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            add(buildJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", "audio/wav")
                                    put("data", audio)
                                }
                            })
                            add(buildJsonObject { put("text", prompt(hint)) })
                        }
                    })
                }
                putJsonObject("generationConfig") {
                    put("temperature", 0.0)
                    put("maxOutputTokens", 400)
                    if (lean && "transcribe" !in model) {
                        putJsonObject("thinkingConfig") {
                            if (Regex("gemini-[3-9]").containsMatchIn(model)) put("thinkingLevel", "minimal") else put("thinkingBudget", 0)
                        }
                    }
                }
            }
            val request = Request.Builder()
                .url("$baseUrl/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            val (code, response) = http.call(request)
            when {
                code in 200..299 -> return text(response)
                code == 400 && lean && !isKeyError(response) -> continue
                else -> throw error(code, response)
            }
        }
        throw AiException.NoResult()
    }

    private fun prompt(hint: String) = buildString {
        append("Write down exactly what is said in this recording, in the language and script it was spoken in (English, Bangla or a mix). ")
        append("Return only the words, with no notes. If nothing is said, return nothing.")
        if (hint.isNotBlank()) append("\nWords that may come up: ").append(hint)
    }

    private fun text(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return ""
        return parts.map { it.jsonObject }
            .filterNot { (it["thought"] as? JsonPrimitive)?.booleanOrNull == true }
            .joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
            .trim()
    }

    private fun isKeyError(body: String) = "API_KEY_INVALID" in body || "API key not valid" in body

    private fun error(code: Int, body: String): AiException = when {
        code == 400 && isKeyError(body) -> AiException.InvalidKey()
        code == 401 || code == 403 -> AiException.InvalidKey()
        code == 429 -> AiException.RateLimited()
        else -> AiException.Unexpected(code)
    }
}
