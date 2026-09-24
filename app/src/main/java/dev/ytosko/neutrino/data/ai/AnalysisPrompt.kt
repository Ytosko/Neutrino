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
        Analyze the provided food image carefully.
        Identify every distinct food item or dish visible. Give each a short, common English name; for South Asian or Bangladeshi dishes add the local name in parentheses, e.g. "Beef curry (gorur mangsho bhuna)". Treat a prepared dish (curry, biryani, dal) as one item rather than listing its ingredients.

        For each item:
        1. Estimate the portion as a quantity and a household unit people understand: piece, slice, cup, bowl, plate, glass, tbsp or tsp. Use g or ml only when nothing else fits.
        2. Estimate the portion's weight in grams from visual cues such as plate size, volume, thickness, count and typical serving sizes.
        3. Estimate the calories, protein, carbohydrates and fat for that portion.

        Do not ignore sauces, oils, dressings, toppings or other calorie-containing ingredients that are visibly present; count cooking oil as part of the dish it is in. Do not invent ingredients that cannot reasonably be inferred from the image. Because image-based portion estimation is approximate, use the most realistic estimate rather than claiming exact measurements. If there is no food in the image, return an empty items list.

        Return ONLY a valid JSON object, with no markdown or text before or after it, using exactly this schema:
        {"food_name": "string", "items": [{"name": "string", "quantity": X, "unit": "string", "grams": X, "calories": X, "protein_g": X, "carbs_g": X, "fat_g": X}]}
        "food_name" is a concise name for the whole meal. All numeric values must be numbers, not strings.
    """.trimIndent()

    val MEAL_SCHEMA = Obj(
        "food_name" to Str,
        "items" to Arr(
            Obj(
                "name" to Str, "quantity" to Num, "unit" to Str, "grams" to Num,
                "calories" to Num, "protein_g" to Num, "carbs_g" to Num, "fat_g" to Num,
            ),
        ),
    )

    fun customFood(name: String): String {
        // The name is user text: keep it short and quote-free so it stays a value, not an instruction.
        val clean = name.replace(Regex("[\"\\n\\r{}]"), " ").trim().take(60)
        return """
            Estimate typical nutrition for this food as it is usually prepared and eaten: "$clean".
            If it is a South Asian or Bangladeshi dish, assume a typical home or restaurant recipe.
            Return ONLY a valid JSON object, no markdown:
            {"name": "string", "category": "string", "kcal_100g": X, "protein_100g": X, "carbs_100g": X, "fat_100g": X, "units": [{"unit": "string", "grams": X}], "g_per_ml": X}
            - name: clean display name (fix spelling; keep a local name in parentheses if useful)
            - category: one of grain, ricedish, bread, poultry, meat, fish, egg, dairy, legume, vegetable, leafy, fruit, nuts, fat, sweet, dessert, snack, curry, fastfood, pizza, drink, hotdrink, other
            - units: 1 to 3 household units people use for this food (piece, slice, cup, bowl, plate, glass, tbsp, tsp, handful, scoop, serving), each with the grams in ONE unit
            - g_per_ml: density if it is a drink or liquid, otherwise 0
        """.trimIndent()
    }

    val FOOD_SCHEMA = Obj(
        "name" to Str, "category" to Str,
        "kcal_100g" to Num, "protein_100g" to Num, "carbs_100g" to Num, "fat_100g" to Num,
        "units" to Arr(Obj("unit" to Str, "grams" to Num)),
        "g_per_ml" to Num,
    )
}

/** Minimal JSON schema description, rendered in each provider's dialect. */
sealed interface JsonSchema {
    data object Str : JsonSchema
    data object Num : JsonSchema
    data class Arr(val items: JsonSchema) : JsonSchema
    class Obj(vararg val properties: Pair<String, JsonSchema>) : JsonSchema
}
