package dev.ytosko.neutrino.data.ai

import dev.ytosko.neutrino.domain.MealAnalysis

/** AI providers Neutrino can call with the user's own API key. */
enum class AiProvider(
    val id: String,
    val displayName: String,
    /** Where users create an API key. */
    val keyUrl: String,
    /** Typical key prefix, shown as a placeholder. */
    val keyPrefix: String,
) {
    Gemini(id = "gemini", displayName = "Google Gemini", keyUrl = "https://aistudio.google.com/apikey", keyPrefix = "AIza…"),
    OpenAi(id = "openai", displayName = "OpenAI", keyUrl = "https://platform.openai.com/api-keys", keyPrefix = "sk-…"),
    ;

    companion object {
        fun fromId(id: String?): AiProvider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How much image detail the model receives. Images are the largest part of each request,
 * so this is the main cost lever.
 */
enum class PhotoDetail(val id: String, val maxEdgePx: Int) {
    /** Default: good portion accuracy at modest cost. */
    Standard("standard", maxEdgePx = 768),
    /** Cheapest: fewest image tokens. */
    Low("low", maxEdgePx = 512),
    ;

    companion object {
        fun fromId(id: String?): PhotoDetail = entries.firstOrNull { it.id == id } ?: Standard
    }
}

data class AiModel(val id: String, val displayName: String)

/** Models a key can use, plus the one Neutrino suggests by default. */
data class ModelChoices(val models: List<AiModel>, val recommended: String?)

/** Failures surfaced to the user; each maps to a clear message and recovery action. */
sealed class AiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidKey : AiException("The API key was rejected")
    class RateLimited : AiException("Rate limit or quota exceeded")
    class Network(cause: Throwable) : AiException("Network error", cause)
    class Unexpected(val code: Int) : AiException("Unexpected response ($code)")
    /** The model answered but gave no usable nutrition (blocked, refused, or unparseable). */
    class NoResult : AiException("No usable analysis in the response")
}

interface AiClient {
    /** Lists vision-capable models available to [apiKey]. Doubles as a key check. */
    suspend fun listModels(apiKey: String): ModelChoices

    /** Analyses a JPEG meal photo with [prompt] and returns the model's estimate. */
    suspend fun analyze(
        apiKey: String,
        model: String,
        jpeg: ByteArray,
        prompt: String,
        detail: PhotoDetail,
    ): MealAnalysis
}
