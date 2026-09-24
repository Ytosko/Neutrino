package dev.ytosko.neutrino.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/** Nutrition totals: kcal and grams of protein, carbs and fat. */
data class Nutrition(
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
) {
    val isEmpty: Boolean get() = calories <= 0.0 && proteinG <= 0.0 && carbsG <= 0.0 && fatG <= 0.0

    operator fun times(factor: Double) = Nutrition(calories * factor, proteinG * factor, carbsG * factor, fatG * factor)
    operator fun plus(other: Nutrition) =
        Nutrition(calories + other.calories, proteinG + other.proteinG, carbsG + other.carbsG, fatG + other.fatG)

    companion object {
        val ZERO = Nutrition(0.0, 0.0, 0.0, 0.0)
    }
}

data class TokenUsage(val input: Int, val output: Int) {
    val total: Int get() = input + output
}

/** One food the model saw on the plate, with its estimated portion and nutrition. */
data class ScannedItem(
    val name: String,
    val quantity: Double,
    /** Unit as the model wrote it ("plate", "piece", "g"…); mapped to a FoodUnit later. */
    val unit: String,
    /** Estimated weight of the portion; 0 if the model didn't say. */
    val grams: Double,
    val nutrition: Nutrition,
)

data class MealAnalysis(
    val foodName: String,
    val items: List<ScannedItem>,
    val usage: TokenUsage? = null,
) {
    val nutrition: Nutrition get() = items.fold(Nutrition.ZERO) { acc, item -> acc + item.nutrition }
}

/** AI estimate for a custom food name, per 100 g. */
data class FoodEstimate(
    val name: String,
    val category: String,
    val per100g: Nutrition,
    /** Unit name → grams in one unit. */
    val unitGrams: Map<String, Double>,
    val gramsPerMl: Double?,
)

/**
 * Parses model replies. Tolerates markdown fences, surrounding text, numbers sent as strings
 * and negative/NaN values, because not every model obeys the schema.
 */
object MealAnalysisParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Returns null if no JSON object can be found. Accepts the item list format and, for
     * robustness, the older totals-only format (treated as a single item).
     */
    fun parse(text: String): MealAnalysis? {
        val obj = extractObject(text) ?: return null
        val mealName = obj.string("food_name")?.take(80)

        val items = (obj["items"] as? JsonArray)?.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.string("name")?.take(80)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ScannedItem(
                name = name,
                quantity = item.number("quantity").takeIf { it > 0 } ?: 1.0,
                unit = item.string("unit").orEmpty().ifBlank { "serving" },
                grams = item.number("grams"),
                nutrition = item.nutrition(),
            )
        }

        if (items != null) {
            return MealAnalysis(
                foodName = mealName?.takeIf { it.isNotBlank() } ?: items.joinToString(", ") { it.name }.ifBlank { "Meal" },
                items = items,
            )
        }

        // Totals-only reply.
        if (listOf("calories", "protein_g", "carbs_g", "fat_g").none { it in obj }) return null
        val name = mealName?.takeIf { it.isNotBlank() } ?: "Meal"
        val totals = obj.nutrition()
        return MealAnalysis(
            foodName = name,
            items = if (totals.isEmpty) emptyList() else listOf(ScannedItem(name, 1.0, "serving", 0.0, totals)),
        )
    }

    fun parseFoodEstimate(text: String): FoodEstimate? {
        val obj = extractObject(text) ?: return null
        val per100 = Nutrition(
            calories = obj.number("kcal_100g"),
            proteinG = obj.number("protein_100g"),
            carbsG = obj.number("carbs_100g"),
            fatG = obj.number("fat_100g"),
        )
        if (per100.isEmpty) return null
        val units = (obj["units"] as? JsonArray).orEmpty().mapNotNull { element ->
            val unit = (element as? JsonObject) ?: return@mapNotNull null
            val name = unit.string("unit")?.lowercase() ?: return@mapNotNull null
            unit.number("grams").takeIf { it > 0 }?.let { name to it }
        }.toMap()
        return FoodEstimate(
            name = obj.string("name")?.take(80).orEmpty(),
            category = obj.string("category").orEmpty(),
            per100g = per100,
            unitGrams = units,
            gramsPerMl = obj.number("g_per_ml").takeIf { it in 0.3..2.5 },
        )
    }

    private fun extractObject(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.nutrition() = Nutrition(
        calories = number("calories"),
        proteinG = number("protein_g"),
        carbsG = number("carbs_g"),
        fatG = number("fat_g"),
    )

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()

    private fun JsonObject.number(key: String): Double {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return 0.0
        val value = Regex("-?\\d+(?:\\.\\d+)?").find(raw)?.value?.toDoubleOrNull() ?: return 0.0
        return if (value.isFinite() && value > 0) value else 0.0
    }
}

/** Rounds for display and storage: whole kcal, grams to one decimal. */
fun Double.roundKcal(): Int = roundToInt()
fun Double.roundGrams(): Double = (this * 10).roundToInt() / 10.0
