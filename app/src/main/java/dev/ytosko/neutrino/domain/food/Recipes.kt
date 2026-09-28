package dev.ytosko.neutrino.domain.food

import dev.ytosko.neutrino.domain.Nutrition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** One ingredient of a home recipe, as the AI estimated it (raw weight and what it adds). */
data class RecipeIngredient(val name: String, val grams: Double, val nutrition: Nutrition)

/**
 * A home recipe worked out: its ingredients, and the dish's cooked weight (cooking loses water, so
 * it's usually less than the ingredients together). Split into [servings] of [unit].
 */
data class RecipeResult(
    val name: String,
    val ingredients: List<RecipeIngredient>,
    /** Whole dish once cooked, in grams; 0 when unknown (then the ingredients' weight is used). */
    val cookedGrams: Double,
    val servings: Int,
    val unit: FoodUnit,
) {
    val total: Nutrition get() = ingredients.fold(Nutrition.ZERO) { acc, it -> acc + it.nutrition }
    val totalGrams: Double get() = cookedGrams.takeIf { it > 0 } ?: ingredients.sumOf { it.grams }
    val perServing: Nutrition get() = total * (1.0 / servings.coerceAtLeast(1))
    val gramsPerServing: Double get() = totalGrams / servings.coerceAtLeast(1)

    /**
     * The dish as a food of your own: nutrition per 100 g of the cooked dish, and one [unit] (a
     * plate, a bowl…) weighing a serving.
     */
    fun toFood(id: String): Food {
        val grams = totalGrams.takeIf { it > 0 } ?: 100.0
        return Food(
            id = id,
            name = name,
            category = ScanFoods.guessCategory(name),
            per100g = total * (100.0 / grams),
            unitGrams = if (unit.isMass || unit.isVolume) emptyMap() else mapOf(unit to gramsPerServing),
            defaultPortion = Portion(1.0, unit),
            source = FoodSource.Custom,
        )
    }
}

/** Reads the AI's recipe reply; lenient like the other parsers. Null if there's nothing usable. */
object RecipeParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String, fallbackName: String, servings: Int, unit: FoodUnit): RecipeResult? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        val ingredients = (obj["ingredients"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val name = item.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RecipeIngredient(
                name = name.take(60),
                grams = item.number("grams"),
                nutrition = Nutrition(item.number("calories"), item.number("protein_g"), item.number("carbs_g"), item.number("fat_g")),
            )
        }.filterNot { it.nutrition.isEmpty && it.grams <= 0 }
        if (ingredients.isEmpty()) return null
        return RecipeResult(
            name = obj.string("name")?.takeIf { it.isNotBlank() }?.take(80) ?: fallbackName,
            ingredients = ingredients,
            cookedGrams = obj.number("cooked_grams"),
            servings = servings,
            unit = unit,
        )
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()

    private fun JsonObject.number(key: String): Double {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull ?: return 0.0
        val value = Regex("-?\\d+(?:\\.\\d+)?").find(raw)?.value?.toDoubleOrNull() ?: return 0.0
        return if (value.isFinite() && value > 0) value else 0.0
    }
}

/** A meal you eat often: the latest time it was logged (to log again) and how often in the window. */
data class UsualMeal(val mealId: String, val times: Int)

/**
 * "My usual meals": meals with the same foods, logged at least [minTimes] in the window, most often
 * first, those of the current meal type ahead of the rest. The same foods under a different name
 * (the AI names meals freely) count as the same meal.
 */
object UsualMeals {

    data class Logged(val id: String, val eatenAtEpochMs: Long, val mealType: String, val foodIds: Set<String>)

    fun pick(meals: List<Logged>, currentType: String, limit: Int = 3, minTimes: Int = 2): List<UsualMeal> =
        meals.filter { it.foodIds.isNotEmpty() }
            .groupBy { it.foodIds }
            .values
            .filter { it.size >= minTimes }
            .map { group ->
                val latest = group.maxBy { it.eatenAtEpochMs }
                val sameType = group.count { it.mealType == currentType }
                Triple(UsualMeal(latest.id, group.size), sameType, latest.eatenAtEpochMs)
            }
            .sortedWith(compareByDescending<Triple<UsualMeal, Int, Long>> { it.second > 0 }.thenByDescending { it.first.times }.thenByDescending { it.third })
            .take(limit)
            .map { it.first }
}
