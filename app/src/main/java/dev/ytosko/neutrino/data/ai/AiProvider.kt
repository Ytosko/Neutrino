package dev.ytosko.neutrino.data.ai

import dev.ytosko.neutrino.domain.FoodEstimate
import dev.ytosko.neutrino.domain.MealAnalysis
import dev.ytosko.neutrino.domain.MealAnalysisParser
import dev.ytosko.neutrino.domain.TokenUsage

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
    /** The model answered but gave no usable result (blocked, refused, or unparseable). */
    class NoResult : AiException("No usable answer in the response")
}

/** Raw model reply: the JSON text plus token usage. */
data class JsonReply(val text: String, val usage: TokenUsage?)

/** An image to send with a prompt. */
class ImageInput(val jpeg: ByteArray, val detail: PhotoDetail)

interface AiClient {
    /** Lists vision-capable models available to [apiKey]. Doubles as a key check. */
    suspend fun listModels(apiKey: String): ModelChoices

    /** Sends [prompt] (and optionally an image) and returns the model's JSON reply. */
    suspend fun generateJson(apiKey: String, model: String, prompt: String, schema: JsonSchema, image: ImageInput? = null): JsonReply
}

/** Analyses a meal photo; the reply lists each food with its portion and nutrition. */
suspend fun AiClient.analyzeMeal(
    apiKey: String,
    model: String,
    jpeg: ByteArray,
    detail: PhotoDetail,
    hints: PromptHints = PromptHints(),
): MealAnalysis {
    val reply = generateJson(apiKey, model, AnalysisPrompt.meal(hints), AnalysisPrompt.MEAL_SCHEMA, ImageInput(jpeg, detail))
    return MealAnalysisParser.parse(reply.text)?.copy(usage = reply.usage) ?: throw AiException.NoResult()
}

/** Estimates nutrition for a food the user typed (text only, far cheaper than a photo). */
suspend fun AiClient.estimateFood(
    apiKey: String,
    model: String,
    name: String,
    hints: PromptHints = PromptHints(),
): Pair<FoodEstimate, TokenUsage?> {
    val reply = generateJson(apiKey, model, AnalysisPrompt.customFood(name, hints), AnalysisPrompt.FOOD_SCHEMA)
    val estimate = MealAnalysisParser.parseFoodEstimate(reply.text) ?: throw AiException.NoResult()
    return estimate to reply.usage
}
