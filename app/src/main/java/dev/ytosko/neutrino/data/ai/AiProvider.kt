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
    /** Short label where space is tight (onboarding's provider switch). */
    val shortName: String = displayName,
    /** Offers speech-to-text for the voice model. */
    val hasVoice: Boolean = true,
    /** Only one model of this provider can be set up. */
    val single: Boolean = false,
) {
    Gemini(id = "gemini", displayName = "Google Gemini", keyUrl = "https://aistudio.google.com/apikey", keyPrefix = "AIza…", shortName = "Gemini"),
    OpenAi(id = "openai", displayName = "OpenAI", keyUrl = "https://platform.openai.com/api-keys", keyPrefix = "sk-…"),
    Groq(id = "groq", displayName = "Groq", keyUrl = "https://console.groq.com/keys", keyPrefix = "gsk_…"),
    /** Many providers' models behind one key, several of them free. Photos only; one at most. */
    OpenRouter(
        id = "openrouter", displayName = "OpenRouter", keyUrl = "https://openrouter.ai/settings/keys", keyPrefix = "sk-or-…",
        hasVoice = false, single = true,
    ),
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

/** [free] and [price] ("$0.10 / $0.40 per 1M tokens") are known only for OpenRouter. */
data class AiModel(val id: String, val displayName: String, val free: Boolean = false, val price: String? = null)

/** Models a key can use, plus the one Neutrino suggests by default. */
data class ModelChoices(val models: List<AiModel>, val recommended: String?)

/** Failures surfaced to the user; each maps to a clear message and recovery action. */
sealed class AiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidKey : AiException("The API key was rejected")
    /** [provider] set when it has a known, tight limit worth naming (Groq's free plan). */
    class RateLimited(val provider: AiProvider? = null) : AiException("Rate limit or quota exceeded")
    class Network(cause: Throwable) : AiException("Network error", cause)
    class Unexpected(val code: Int) : AiException("Unexpected response ($code)")
    /**
     * The request is bigger than the model or plan takes (Groq's "request too large", OpenAI's
     * "context length exceeded"). Waiting won't help; a shorter request will.
     */
    class TooLarge : AiException("Request too large for the model or plan")
    /**
     * The model isn't offered anymore (OpenRouter removes models, free ones especially).
     * [replacement] is the suggested model of the same kind (free or not), if there is one.
     */
    class ModelGone(val model: String, val replacement: AiModel?) : AiException("Model $model is no longer available")
    /** OpenRouter's privacy settings block every provider of this (free) model. */
    class DataPolicy : AiException("Blocked by the account's data policy")
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

    /** Sends [prompt] (with any [images], e.g. several photos of one meal) and returns the model's JSON reply. */
    suspend fun generateJson(apiKey: String, model: String, prompt: String, schema: JsonSchema, images: List<ImageInput> = emptyList()): JsonReply
}

/** Analyses a meal photo; the reply lists each food with its portion and nutrition. */
suspend fun AiClient.analyzeMeal(
    apiKey: String,
    model: String,
    jpeg: ByteArray,
    detail: PhotoDetail,
    hints: PromptHints = PromptHints(),
): MealAnalysis {
    val reply = generateJson(apiKey, model, AnalysisPrompt.meal(hints), AnalysisPrompt.MEAL_SCHEMA, listOf(ImageInput(jpeg, detail)))
    return MealAnalysisParser.parse(reply.text)?.copy(usage = reply.usage) ?: throw AiException.NoResult()
}

/**
 * Analyses one or more photos of the same meal. With [logged] (the foods already in the meal),
 * only foods that aren't there yet come back.
 */
suspend fun AiClient.analyzeMealPhotos(
    apiKey: String,
    model: String,
    jpegs: List<ByteArray>,
    detail: PhotoDetail,
    hints: PromptHints = PromptHints(),
    logged: List<String> = emptyList(),
): MealAnalysis {
    val prompt = if (logged.isEmpty()) AnalysisPrompt.meal(hints) else AnalysisPrompt.morePhotos(hints, logged)
    val reply = generateJson(apiKey, model, prompt, AnalysisPrompt.MEAL_SCHEMA, jpegs.map { ImageInput(it, detail) })
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
