package dev.ytosko.neutrino.data.voice

import dev.ytosko.neutrino.data.ai.AiChain
import dev.ytosko.neutrino.data.ai.AiClient
import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.AiProvider
import dev.ytosko.neutrino.data.ai.JsonSchema.Arr
import dev.ytosko.neutrino.data.ai.JsonSchema.Num
import dev.ytosko.neutrino.data.ai.JsonSchema.Obj
import dev.ytosko.neutrino.data.ai.JsonSchema.Str
import dev.ytosko.neutrino.data.ai.PromptHints
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.voice.VoiceCommand
import dev.ytosko.neutrino.domain.voice.VoiceCommandParser
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A meal said with Log by voice, handed to the review page that opens it. */
data class VoiceMeal(val items: List<dev.ytosko.neutrino.domain.ScannedItem>, val eatenAt: LocalDateTime?)

/** The voice model isn't set up (or its key is gone). */
class VoiceNotSetUp : Exception("No voice model")

/**
 * What the assistant knows when the user speaks: the time, and, on the review page, the meal as it
 * is on screen, so "make the rice 100 g" can find the rice.
 */
data class VoiceContext(
    val now: LocalDateTime,
    /** The lines on the review page ("White rice, cooked: 1 plate (250 g)"); null when logging from scratch. */
    val mealLines: List<String>? = null,
    val mealTime: LocalDateTime? = null,
) {
    val editing: Boolean get() = mealLines != null
}

/**
 * Speech in, actions out, in two steps: the voice model writes down what was said, then the
 * Primary model (with its fallbacks) works out what to do. Every model can read text, so any
 * voice model works with any food model.
 */
class VoiceAssistant(
    private val settings: SettingsRepository,
    private val speech: Map<AiProvider, SpeechClient>,
    private val clients: Map<AiProvider, AiClient>,
) {

    suspend fun transcribe(wav: ByteArray): String {
        val config = settings.settings.first().voiceConfig ?: throw VoiceNotSetUp()
        val client = config.providerEnum?.let(speech::get) ?: throw VoiceNotSetUp()
        val key = settings.aiKey(config.id) ?: throw VoiceNotSetUp()
        return client.transcribe(key, config.model, wav, VOCABULARY)
    }

    /** Null when nothing actionable was said. */
    suspend fun understand(transcript: String, context: VoiceContext): VoiceCommand? {
        val hints = settings.settings.first().promptHints
        val prompt = VoicePrompt.command(transcript, context, hints)
        val chain = settings.aiChain()
        val (_, command) = AiChain.run(chain, clients, shouldFallBack = { it !is AiException.NoResult }) { client, key, config ->
            val reply = client.generateJson(key, config.model, prompt, VoicePrompt.SCHEMA)
            VoiceCommandParser.parse(reply.text, context.now, context.mealLines?.size ?: 0) ?: throw AiException.NoResult()
        } ?: throw VoiceNotSetUp()
        return command.takeUnless { it.isEmpty }
    }

    private companion object {
        /** Helps the voice model spell local foods and health words. */
        const val VOCABULARY =
            "rice, bhat, dal, roti, paratha, luchi, khichuri, biryani, tehari, polao, murgi, chicken curry, beef, mach, ilish, " +
                "dim, bhorta, shak, singara, fuchka, cha, water, glass, plate, bowl, glucose, sugar, mmol, mg/dL, fasting, " +
                "Metformin, insulin, units, tablet"
    }
}

object VoicePrompt {

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE)")

    fun command(transcript: String, context: VoiceContext, hints: PromptHints): String {
        // The transcript is user speech: keep it a quoted value, never instructions.
        val said = transcript.replace(Regex("[\"{}\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(600)
        val meal = context.mealLines?.let { lines ->
            buildString {
                append("\nThe user is looking at a meal they are logging. Its lines:\n")
                if (lines.isEmpty()) append("(no foods yet)\n")
                lines.forEachIndexed { i, line -> append(i + 1).append(". ").append(line.replace('"', ' ')).append('\n') }
                context.mealTime?.let { append("Meal time: ").append(it.format(stamp)).append('\n') }
            }
        }.orEmpty()
        return """
            You are the voice assistant of Neutrino, a food and health diary. Act on what the user said, like a helpful assistant would.
            The user said (speech written down; may be English, Bangla or a mix): "$said"
            Now: ${context.now.format(stamp)}.
            $meal
            Return ONLY a JSON object:
            {"reply": "string", "items": [{"action": "add|set|remove", "item": X, "name": "string", "quantity": X, "unit": "string", "grams": X, "calories": X, "protein_g": X, "carbs_g": X, "fat_g": X}], "meal_time": "string", "water_ml": X, "glucose_value": X, "glucose_unit": "string", "glucose_relation": "string", "glucose_time": "string", "medicines": [{"name": "string", "strength": "string", "amount": X, "time": "string"}]}

            items: foods and drinks with calories the user ate.
            - "add": a new food. Give its amount as a quantity and a household unit (piece, slice, cup, bowl, plate, glass, tbsp, tsp) or g/ml, its weight in grams, and calories, protein, carbs and fat for that whole amount. Use a short common English name; add the local name in parentheses for South Asian dishes. Typical portions: a plate of cooked rice is about 250 g, one roti 40 g, one paratha 80 g, a bowl of dal 200 g, a piece of curried chicken or fish 100 g.
            - "set": change a line of the meal above. "item" is its number. Give the new TOTAL amount, grams and nutrition for that total. Use it when the user says how much they had of a food already listed ("I had three burgers" when burgers are listed means 3 in total; "another burger" or "one more" adds to the listed amount), or corrects an amount.
            - "remove": remove line "item" when the user says they didn't have it. Other fields may be empty or 0.
            - Match foods the user names loosely to the listed lines (e.g. "the rice" or "700 g of protein" for a listed chicken). Leave "item" 0 for "add".
            - With no meal above, only use "add".
            meal_time: "YYYY-MM-DD HH:MM" or "".${if (context.editing) " Only when the user clearly asks to change this meal's time (\"change the time to 2 pm\"); a time said along with a food does not count." else " When the user says when they ate (work out times like \"two hours ago\" from Now); otherwise \"\"."}
            water_ml: plain water drunk, in ml (a glass is 250 ml, a bottle 500 ml); 0 if none. Tea, coffee, milk and juice go in items, not here.
            glucose_value: a blood sugar reading the user reports, as said; 0 if none. glucose_unit: "mmol" or "mgdl" if the user said it, else "". glucose_relation: fasting, before_meal, after_meal, bedtime or general. glucose_time: "YYYY-MM-DD HH:MM", or "" for now.
            medicines: medicines or insulin the user says they took. name exactly as said (brand or generic), strength if said (e.g. "500"), amount (tablets, or units of insulin; 0 if not said), time "YYYY-MM-DD HH:MM" or "" for now.
            reply: one short, friendly sentence saying what you did, to be read aloud, in the language the user spoke. Never put glucose numbers in it.
            If the words are not about food, water, blood sugar or medicine, or make no sense, return empty lists, 0s and "" everywhere.
        """.trimIndent() + hints.render()
    }

    val SCHEMA = Obj(
        "reply" to Str,
        "items" to Arr(
            Obj(
                "action" to Str, "item" to Num, "name" to Str, "quantity" to Num, "unit" to Str, "grams" to Num,
                "calories" to Num, "protein_g" to Num, "carbs_g" to Num, "fat_g" to Num,
            ),
        ),
        "meal_time" to Str,
        "water_ml" to Num,
        "glucose_value" to Num,
        "glucose_unit" to Str,
        "glucose_relation" to Str,
        "glucose_time" to Str,
        "medicines" to Arr(Obj("name" to Str, "strength" to Str, "amount" to Num, "time" to Str)),
    )
}
