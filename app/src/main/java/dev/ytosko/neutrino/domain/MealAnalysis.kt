package dev.ytosko.neutrino.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/** Nutrition estimate for a whole meal, as returned by the analysis prompt. */
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

data class MealAnalysis(
    val foodName: String,
    val nutrition: Nutrition,
    val usage: TokenUsage? = null,
)

/**
 * Parses the model's reply into [MealAnalysis]. Tolerates markdown fences, surrounding text,
 * numbers sent as strings and negative/NaN values, because not every model obeys the schema.
 */
object MealAnalysisParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Returns null if no JSON object with at least one nutrition field can be found. */
    fun parse(text: String): MealAnalysis? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null

        val fields = listOf("calories", "protein_g", "carbs_g", "fat_g")
        if (fields.none { it in obj }) return null

        val name = (obj["food_name"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(80)
        return MealAnalysis(
            foodName = name.takeUnless { it.isNullOrBlank() } ?: "Meal",
            nutrition = Nutrition(
                calories = obj.number("calories"),
                proteinG = obj.number("protein_g"),
                carbsG = obj.number("carbs_g"),
                fatG = obj.number("fat_g"),
            ),
        )
    }

    private fun JsonObject.number(key: String): Double {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return 0.0
        val value = raw.filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull() ?: return 0.0
        return if (value.isFinite() && value > 0) value else 0.0
    }
}

/** Rounds for display and storage: whole kcal, grams to one decimal. */
fun Double.roundKcal(): Int = roundToInt()
fun Double.roundGrams(): Double = (this * 10).roundToInt() / 10.0
