package dev.ytosko.neutrino.data.ai

/**
 * Turns a provider's raw model list into the short list shown to users: models that can
 * read images and generate text, newest stable first, with a sensible cost/quality default
 * ("flash" for Gemini, "mini" for OpenAI).
 *
 * Neither API reports vision support directly, so filtering is by model family name.
 */
object ModelCatalog {

    private val geminiExclude =
        Regex("embedding|tts|image|live|audio|robotics|computer-use|aqa|learnlm|gemma|veo|imagen", RegexOption.IGNORE_CASE)
    private val openAiInclude = Regex("^(gpt-4o|gpt-4\\.1|gpt-5|o3|o4)")
    private val openAiExclude =
        Regex("audio|realtime|transcribe|tts|search|image|embedding|instruct|codex|deep-research|preview|chat")
    private val datedSnapshot = Regex("-\\d{4}-\\d{2}-\\d{2}$")
    private val version = Regex("(?:gemini|gpt)-(\\d+(?:\\.\\d+)?)")

    fun fromGemini(models: List<GeminiModelInfo>): ModelChoices {
        val usable = models
            .filter { "generateContent" in it.supportedGenerationMethods }
            .map { it.name.removePrefix("models/") to it.displayName }
            .filter { (id, _) -> id.startsWith("gemini-") && !geminiExclude.containsMatchIn(id) }
            .distinctBy { it.first }
            .sortedWith(
                compareBy<Pair<String, String?>> { isPreview(it.first) }
                    .thenByDescending { versionOf(it.first) }
                    .thenBy { geminiFamilyRank(it.first) }
                    .thenBy { it.first },
            )
            .map { (id, name) -> AiModel(id, name ?: id) }
        val recommended = usable.firstOrNull { !isPreview(it.id) && geminiFamilyRank(it.id) == 0 } ?: usable.firstOrNull()
        return ModelChoices(usable, recommended?.id)
    }

    fun fromOpenAi(ids: List<String>): ModelChoices {
        val usable = ids
            .filter { openAiInclude.containsMatchIn(it) && !openAiExclude.containsMatchIn(it) && !datedSnapshot.containsMatchIn(it) }
            .distinct()
            .sortedWith(
                compareBy<String> { it.startsWith("o") } // reasoning models are slower/costlier
                    .thenByDescending { versionOf(it) }
                    .thenBy { openAiFamilyRank(it) }
                    .thenBy { it },
            )
            .map { AiModel(it, it) }
        val recommended = usable.firstOrNull { !it.id.startsWith("o") && openAiFamilyRank(it.id) == 0 } ?: usable.firstOrNull()
        return ModelChoices(usable, recommended?.id)
    }

    /**
     * Groq lists every model it hosts (speech, guard, text…) without saying which read images, so
     * only its known photo models are offered: Qwen 3.x multimodal and Llama 4 Scout/Maverick.
     */
    private val groqVision = Regex("""^(qwen/qwen3\.\d+-27b|meta-llama/llama-4-(scout|maverick)-.*)$""")

    fun fromGroq(ids: List<String>): ModelChoices {
        val usable = ids.filter { groqVision.matches(it) }.distinct().sortedWith(compareBy<String> { !it.startsWith("qwen/") }.thenByDescending { it })
            .map { AiModel(it, groqName(it)) }
        return ModelChoices(usable, usable.firstOrNull()?.id)
    }

    /**
     * Voice models: ones that turn speech into text. Every Gemini model offered for photos also
     * accepts audio, and Gemini's own "transcribe" models are made for it (suggested first).
     */
    fun voiceFromGemini(models: List<GeminiModelInfo>): ModelChoices {
        val all = fromGemini(models).models
        val sorted = all.sortedBy { if ("transcribe" in it.id) 0 else 1 }
        val recommended = sorted.firstOrNull { "transcribe" in it.id && !isPreview(it.id) }
            ?: sorted.firstOrNull { !isPreview(it.id) && geminiFamilyRank(it.id) == 0 } ?: sorted.firstOrNull()
        return ModelChoices(sorted, recommended?.id)
    }

    private val openAiSpeech = Regex("^(whisper-|gpt-.*transcribe)")

    /** OpenAI's speech-to-text models: Whisper and the "…-transcribe" models (live-streaming ones left out). */
    fun voiceFromOpenAi(ids: List<String>): ModelChoices {
        val usable = ids
            .filter { openAiSpeech.containsMatchIn(it) && "realtime" !in it && "diarize" !in it && !datedSnapshot.containsMatchIn(it) }
            .distinct()
            .sortedWith(compareBy<String> { it.startsWith("whisper") }.thenBy { !it.contains("mini") }.thenByDescending { it })
            .map { AiModel(it, it) }
        return ModelChoices(usable, usable.firstOrNull()?.id)
    }

    /** Groq's Whisper models; large-v3 first, the most accurate for Bangla. */
    fun voiceFromGroq(ids: List<String>): ModelChoices {
        val usable = ids.filter { it.startsWith("whisper") }.distinct()
            .sortedWith(compareBy<String> { "turbo" in it || "distil" in it }.thenBy { it })
            .map { AiModel(it, whisperName(it)) }
        return ModelChoices(usable, usable.firstOrNull()?.id)
    }

    /** "whisper-large-v3-turbo" → "Whisper Large v3 Turbo". */
    private fun whisperName(id: String): String =
        id.split('-').joinToString(" ") { part -> if (part.matches(Regex("""v\d+"""))) part else part.replaceFirstChar(Char::uppercase) }

    /**
     * OpenRouter's photo models: they take images and answer in text only, with a real price (routers
     * that pick a model themselves have none and might pick one that can't see), and aren't safety
     * filters, test models or expired. Free ones first.
     */
    fun fromOpenRouter(models: List<OpenRouterModel>, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): List<AiModel> =
        models
            .filter { m ->
                "image" in m.architecture.inputModalities && m.architecture.outputModalities == listOf("text") &&
                    m.promptPrice != null && m.completionPrice != null &&
                    !m.id.startsWith("openrouter/") && !m.id.startsWith("stealth/") &&
                    !openRouterExclude.containsMatchIn(m.id) &&
                    (m.expiresEpochSeconds?.let { it > nowEpochSeconds } ?: true)
            }
            .map { m ->
                val free = m.promptPrice == 0.0 && m.completionPrice == 0.0
                AiModel(
                    id = m.id,
                    displayName = m.name.ifBlank { m.id },
                    free = free,
                    price = if (free) null else "$${perMillion(m.promptPrice!!)} / $${perMillion(m.completionPrice!!)} per 1M tokens",
                )
            }
            .sortedWith(compareBy<AiModel> { !it.free }.thenBy { it.displayName.lowercase() })

    private val openRouterExclude = Regex("guard|safety|moderation|embed|tts|whisper|lyria|image-gen", RegexOption.IGNORE_CASE)

    /** Well-known models that read meals well, in order of preference. */
    private val openRouterPreferred = listOf("qwen/qwen3", "google/gemma", "meta-llama/llama-4", "mistralai/", "google/gemini", "openai/gpt")

    /** The suggested OpenRouter model among [models]; free only when [freeOnly]. Null if none fits. */
    fun openRouterPick(models: List<AiModel>, freeOnly: Boolean): AiModel? {
        val allowed = if (freeOnly) models.filter { it.free } else models
        val stable = allowed.filterNot { "preview" in it.id || "beta" in it.id }.ifEmpty { allowed }
        return openRouterPreferred.firstNotNullOfOrNull { prefix -> stable.firstOrNull { it.id.startsWith(prefix) } } ?: stable.firstOrNull()
    }

    private fun perMillion(perToken: Double): String {
        val value = perToken * 1_000_000
        return if (value >= 10) "%.0f".format(java.util.Locale.US, value) else "%.2f".format(java.util.Locale.US, value)
    }

    /** "qwen/qwen3.8-27b" → "Qwen 3.8 27B". */
    private fun groqName(id: String): String = when {
        id.startsWith("qwen/qwen") -> id.removePrefix("qwen/qwen").let { rest ->
            val (version, size) = rest.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            "Qwen $version ${size.uppercase()}".trim()
        }
        id.startsWith("meta-llama/") -> id.removePrefix("meta-llama/").split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        else -> id
    }

    private fun isPreview(id: String) = "preview" in id || "exp" in id

    private fun versionOf(id: String): Double =
        version.find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun geminiFamilyRank(id: String) = when {
        id.endsWith("-flash") -> 0
        "flash-lite" in id -> 1
        "flash" in id -> 2
        "pro" in id -> 3
        else -> 4
    }

    private fun openAiFamilyRank(id: String) = when {
        id.endsWith("-mini") -> 0
        id.endsWith("-nano") -> 1
        version.matches(id) -> 2 // base model, e.g. gpt-5
        else -> 3
    }
}
