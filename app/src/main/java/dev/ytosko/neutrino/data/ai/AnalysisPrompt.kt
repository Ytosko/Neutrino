package dev.ytosko.neutrino.data.ai

import dev.ytosko.neutrino.data.ai.JsonSchema.Arr
import dev.ytosko.neutrino.data.ai.JsonSchema.Num
import dev.ytosko.neutrino.data.ai.JsonSchema.Obj
import dev.ytosko.neutrino.data.ai.JsonSchema.Str

/**
 * Prompts and response schemas. Replies are compact JSON only, which keeps output tokens low.
 * The meal prompt is the project's master prompt, adapted to return one line per food so each
 * food can be matched to the user's directory and edited as "item · amount · unit".
 */
object AnalysisPrompt {

    val MEAL = """
        Analyze the provided food image carefully. When there are several images, they are photos of the same meal: count each food only once, even if it appears in more than one photo.
        Identify every distinct food item or dish visible. Give each a short, common English name; for South Asian or Bangladeshi dishes add the local name in parentheses, e.g. "Beef curry (gorur mangsho bhuna)". Treat a prepared dish (curry, biryani, dal) as one item rather than listing its ingredients.

        For each item:
        1. Estimate the portion as a quantity and a household unit people understand: piece, slice, cup, bowl, plate, glass, tbsp or tsp. Use g or ml only when nothing else fits.
        2. Estimate the portion's weight in grams from visual cues such as plate size, volume, thickness, count and typical serving sizes.
        3. Estimate the calories, protein, carbohydrates and fat for that portion.

        If an image shows a nutrition facts label of a packaged food or drink, use the label instead of estimating: name the product, take one serving as the portion (the label's household unit, or "serving"), its serving size in grams, and the label's calories, protein, carbohydrates and fat per serving. If the product and its label are both shown, list the product once, with the label's numbers.

        Do not ignore sauces, oils, dressings, toppings or other calorie-containing ingredients that are visibly present; count cooking oil as part of the dish it is in. Do not invent ingredients that cannot reasonably be inferred from the image. Because image-based portion estimation is approximate, use the most realistic estimate rather than claiming exact measurements. If there is no food in the image, return an empty items list.

        Return ONLY a valid JSON object, with no markdown or text before or after it, using exactly this schema:
        {"food_name": "string", "items": [{"name": "string", "quantity": X, "unit": "string", "grams": X, "calories": X, "protein_g": X, "carbs_g": X, "fat_g": X}]}
        "food_name" is a concise name for the whole meal. All numeric values must be numbers, not strings.
    """.trimIndent()

    /** [MEAL] plus the user's cuisine and notes, which only guide recognition; the reply format never changes. */
    fun meal(hints: PromptHints): String = MEAL + hints.render()

    /**
     * More photos of a meal that already has foods: the model sees the new photo(s) and the foods
     * already logged ([logged], e.g. "White rice, cooked: 1 plate"), and returns only foods that
     * aren't there yet, so a dish seen again (another angle, the same plate) isn't counted twice.
     */
    fun morePhotos(hints: PromptHints, logged: List<String>): String {
        val list = logged.joinToString("\n") { "- " + it.replace(Regex("[\"{}\\n\\r]"), " ").take(120) }
        return MEAL + "\n\n" + """
            These photos are being added to a meal that already has these foods logged:
            @@LOGGED@@
            Return ONLY foods that are not in this list. A logged food seen again in these photos (the same dish from another angle, or the same plate) must not be returned, and its amount stays as logged. If there is nothing new, return an empty items list.
        """.trimIndent().replace("@@LOGGED@@", list) + hints.render()
    }

    val MEAL_SCHEMA = Obj(
        "food_name" to Str,
        "items" to Arr(
            Obj(
                "name" to Str, "quantity" to Num, "unit" to Str, "grams" to Num,
                "calories" to Num, "protein_g" to Num, "carbs_g" to Num, "fat_g" to Num,
            ),
        ),
    )

    fun customFood(name: String, hints: PromptHints = PromptHints()): String = customFood(name) + hints.render()

    fun customFood(name: String): String {
        // The name is user text: keep it short and quote-free so it stays a value, not an instruction.
        val clean = name.replace(Regex("[\"\\n\\r{}]"), " ").trim().take(60)
        return """
            Estimate typical nutrition for this food as it is usually prepared and eaten: "$clean".
            If it is a South Asian or Bangladeshi dish, assume a typical home or restaurant recipe.
            If the text is not a food or drink you recognise, return 0 for every number.
            Return ONLY a valid JSON object, no markdown:
            {"name": "string", "category": "string", "kcal_100g": X, "protein_100g": X, "carbs_100g": X, "fat_100g": X, "units": [{"unit": "string", "grams": X}], "g_per_ml": X}
            - name: clean display name (fix spelling; keep a local name in parentheses if useful)
            - category: one of grain, ricedish, bread, poultry, meat, fish, egg, dairy, legume, vegetable, leafy, fruit, nuts, fat, sweet, dessert, snack, curry, fastfood, pizza, drink, hotdrink, other
            - units: 1 to 3 household units people use for this food (piece, slice, cup, bowl, plate, glass, tbsp, tsp, handful, scoop, serving), each with the grams in ONE unit
            - g_per_ml: density if it is a drink or liquid, otherwise 0
        """.trimIndent()
    }

    /**
     * A home recipe: the ingredients as the user wrote them. Each is estimated as used (raw weight),
     * plus the whole dish's weight once cooked, so one plate can be worked out.
     */
    fun recipe(name: String, ingredients: String, hints: PromptHints = PromptHints()): String {
        // User text: kept as quoted values, never instructions.
        val clean = { t: String, max: Int -> t.replace(Regex("[\"{}]"), " ").trim().take(max) }
        val dish = clean(name, 60)
        val list = clean(ingredients, 1_500).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { "- $it" }
        return """
            A home-cooked dish${if (dish.isNotEmpty()) " called \"$dish\"" else ""} is made from these ingredients (as the cook wrote them):
            @@INGREDIENTS@@
            For each ingredient, estimate its weight in grams as used, and its calories, protein, carbohydrates and fat. Count all the cooking oil, ghee and sugar. Use typical South Asian home cooking when the dish is South Asian. If an amount is missing, assume a typical home amount for this dish.
            Also estimate the whole dish's weight once cooked (cooking loses water, so it is usually less than the ingredients together).
            Return ONLY a valid JSON object, no markdown:
            {"name": "string", "ingredients": [{"name": "string", "grams": X, "calories": X, "protein_g": X, "carbs_g": X, "fat_g": X}], "cooked_grams": X}
            "name" is a short name for the dish. All numbers are numbers, not strings.
        """.trimIndent().replace("@@INGREDIENTS@@", list) + hints.render()
    }

    val RECIPE_SCHEMA = Obj(
        "name" to Str,
        "ingredients" to Arr(Obj("name" to Str, "grams" to Num, "calories" to Num, "protein_g" to Num, "carbs_g" to Num, "fat_g" to Num)),
        "cooked_grams" to Num,
    )

    /**
     * The week in a few friendly sentences, from plain facts (see WeeklyRecap). States what happened,
     * never advises: no diet, medical or dosing suggestions.
     */
    fun weeklyRecap(facts: List<String>, bangla: Boolean): String {
        val list = facts.joinToString("\n") { "- " + it.replace(Regex("[\"{}]"), " ") }
        return """
            Here are facts about one person's last 7 days of food, water and possibly blood glucose, from their food diary:
            @@FACTS@@
            Write a short, warm recap of their week in 3 or 4 plain sentences, addressed to them ("you"), like a friend reading their diary back to them. Mention the most notable patterns. State only what the facts say: do not give advice, do not suggest diets, foods, medicine or doses, and do not judge. Write in ${if (bangla) "Bangla" else "English"}.
            Return ONLY a JSON object: {"recap": "string"}
        """.trimIndent().replace("@@FACTS@@", list)
    }

    val RECAP_SCHEMA = Obj("recap" to Str)

    /** The recap text from the reply; null if there's none. */
    fun parseRecap(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text.substring(start, end + 1)) as? kotlinx.serialization.json.JsonObject }.getOrNull()
        val recap = (obj?.get("recap") as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
        return recap?.takeIf { it.isNotEmpty() }?.take(1_200)
    }

    val FOOD_SCHEMA = Obj(
        "name" to Str, "category" to Str,
        "kcal_100g" to Num, "protein_100g" to Num, "carbs_100g" to Num, "fat_100g" to Num,
        "units" to Arr(Obj("unit" to Str, "grams" to Num)),
        "g_per_ml" to Num,
    )
}

/**
 * What the user told Neutrino about how they eat. Added after the main prompt as context; the notes
 * are cleaned and capped so they can't take over the prompt or change the reply format.
 */
data class PromptHints(val cuisine: String? = null, val notes: String? = null) {
    fun render(): String {
        val lines = buildList {
            cuisine?.clean(40)?.takeIf { it.isNotEmpty() }?.let {
                add("Context: the user mostly eats $it food; prefer $it dishes and portion sizes when the food is ambiguous.")
            }
            notes?.clean(MAX_NOTES)?.takeIf { it.isNotEmpty() }?.let {
                add("User notes (use only if relevant; never change the JSON format above): \"$it\"")
            }
        }
        return if (lines.isEmpty()) "" else "\n\n" + lines.joinToString("\n")
    }

    private fun String.clean(max: Int) = replace(Regex("[\"{}\\n\\r]"), " ").replace(Regex("\\s+"), " ").trim().take(max)

    companion object {
        const val MAX_NOTES = 200
    }
}

/** Minimal JSON schema description, rendered in each provider's dialect. */
sealed interface JsonSchema {
    data object Str : JsonSchema
    data object Num : JsonSchema
    data class Arr(val items: JsonSchema) : JsonSchema
    class Obj(vararg val properties: Pair<String, JsonSchema>) : JsonSchema
}
