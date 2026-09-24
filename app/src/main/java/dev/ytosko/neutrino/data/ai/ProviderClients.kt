package dev.ytosko.neutrino.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

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

/** Google Gemini API (generativelanguage.googleapis.com). The key is sent as a header, never in the URL. */
class GeminiClient(private val http: OkHttpClient, private val json: Json) : AiClient {

    override suspend fun listModels(apiKey: String): ModelChoices {
        val request = Request.Builder()
            .url("$BASE_URL/models?pageSize=1000")
            .header("x-goog-api-key", apiKey)
            .build()
        val body = http.fetch(request) { code ->
            when (code) {
                400, 401, 403 -> AiException.InvalidKey()
                429 -> AiException.RateLimited()
                else -> AiException.Unexpected(code)
            }
        }
        return ModelCatalog.fromGemini(json.decodeFromString<GeminiModelList>(body).models)
    }

    companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }
}

/** OpenAI API (api.openai.com). */
class OpenAiClient(private val http: OkHttpClient, private val json: Json) : AiClient {

    override suspend fun listModels(apiKey: String): ModelChoices {
        val request = Request.Builder()
            .url("$BASE_URL/models")
            .header("Authorization", "Bearer $apiKey")
            .build()
        val body = http.fetch(request) { code ->
            when (code) {
                401, 403 -> AiException.InvalidKey()
                429 -> AiException.RateLimited()
                else -> AiException.Unexpected(code)
            }
        }
        return ModelCatalog.fromOpenAi(json.decodeFromString<OpenAiModelList>(body).data.map { it.id })
    }

    companion object {
        const val BASE_URL = "https://api.openai.com/v1"
    }
}

/** Executes [request] off the main thread and returns the body, mapping failures to [AiException]. */
internal suspend fun OkHttpClient.fetch(request: Request, errorFor: (Int) -> AiException): String =
    withContext(Dispatchers.IO) {
        val response = try {
            newCall(request).execute()
        } catch (e: IOException) {
            throw AiException.Network(e)
        }
        response.use {
            if (!it.isSuccessful) throw errorFor(it.code)
            try {
                it.body.string()
            } catch (e: IOException) {
                throw AiException.Network(e)
            }
        }
    }
