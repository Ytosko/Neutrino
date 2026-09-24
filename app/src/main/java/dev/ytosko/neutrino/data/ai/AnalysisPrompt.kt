package dev.ytosko.neutrino.data.ai

/**
 * Default meal-analysis prompt. The model must answer with a single compact JSON object,
 * which keeps output tokens (and cost) minimal.
 */
object AnalysisPrompt {

    val DEFAULT = """
        Analyze the provided food image carefully.
        First, identify every distinct food item or ingredient visible in the image. For each item:

        1. Estimate its portion size and weight in grams based on visual cues such as plate size, volume, thickness, count, and typical serving sizes.
        2. Estimate the calories, protein, carbohydrates, and fat for that estimated weight.
        3. Add the nutritional values of all items together to calculate the total meal macros.

        Do not ignore sauces, oils, dressings, toppings, or other calorie-containing ingredients that are visibly present. Do not invent ingredients that cannot reasonably be inferred from the image. Because image-based portion estimation is approximate, use the most realistic estimate rather than claiming exact measurements.
        Return ONLY a valid JSON object. Do not include markdown, explanations, ingredient breakdowns, comments, units outside the field names, or any text before or after the JSON.
        Use exactly this schema:
        {"calories": X, "protein_g": X, "carbs_g": X, "fat_g": X, "food_name": "string"}
        Requirements:

        * "calories" = estimated total calories for the entire visible meal.
        * "protein_g" = estimated total protein in grams.
        * "carbs_g" = estimated total carbohydrates in grams.
        * "fat_g" = estimated total fat in grams.
        * "food_name" = a concise name describing the complete meal.
        * All numeric values must be numbers, not strings.
        * Calculate the totals from the individually estimated food portions before producing the final JSON.
        * Output must be syntactically valid JSON and contain exactly these five fields.
    """.trimIndent()
}
