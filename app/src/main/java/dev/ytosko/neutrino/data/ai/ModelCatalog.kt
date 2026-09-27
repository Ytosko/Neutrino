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
