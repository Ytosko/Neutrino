package dev.ytosko.neutrino.domain.food

import dev.ytosko.neutrino.domain.Nutrition
import java.text.Normalizer

/** Units a user can pick. Mass/volume units always work; household units need a per-food weight. */
enum class FoodUnit(val key: String) {
    Gram("g"), Kilogram("kg"), Milliliter("ml"), Liter("L"),
    Plate("plate"), Bowl("bowl"), Cup("cup"), Glass("glass"), Piece("piece"), Slice("slice"),
    Tablespoon("tbsp"), Teaspoon("tsp"), Handful("handful"), Scoop("scoop"), Serving("serving"), Can("can"),
    ;

    val isMass: Boolean get() = this == Gram || this == Kilogram
    val isVolume: Boolean get() = this == Milliliter || this == Liter

    companion object {
        fun fromKey(key: String?): FoodUnit? {
            val k = key?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.key.lowercase() == k } ?: when (k) {
                "gram", "grams", "gm", "gms" -> Gram
                "kilogram", "kilograms", "kgs" -> Kilogram
                "milliliter", "milliliters", "millilitre", "millilitres", "mls" -> Milliliter
                "liter", "liters", "litre", "litres", "l" -> Liter
                "plates" -> Plate
                "bowls" -> Bowl
                "cups" -> Cup
                "glasses" -> Glass
                "pieces", "pcs", "pc", "whole", "item", "items" -> Piece
                "slices" -> Slice
                "tablespoon", "tablespoons" -> Tablespoon
                "teaspoon", "teaspoons" -> Teaspoon
                "handfuls" -> Handful
                "scoops" -> Scoop
                "servings", "portion", "portions" -> Serving
                "cans" -> Can
                else -> null
            }
        }
    }
}

/** Drives the icon shown next to a food. */
enum class FoodCategory(val key: String) {
    Grain("grain"), RiceDish("ricedish"), Bread("bread"), Poultry("poultry"), Meat("meat"), Fish("fish"),
    Egg("egg"), Dairy("dairy"), Legume("legume"), Vegetable("vegetable"), Leafy("leafy"), Fruit("fruit"),
    Citrus("citrus"), Grape("grape"), Nuts("nuts"), Fat("fat"), Sweet("sweet"), Dessert("dessert"),
    Snack("snack"), Curry("curry"), FastFood("fastfood"), Pizza("pizza"), Drink("drink"), HotDrink("hotdrink"),
    Packaged("packaged"), Other("other"),
    ;

    companion object {
        fun fromKey(key: String?): FoodCategory = entries.firstOrNull { it.key == key?.lowercase() } ?: Other
    }
}

/** Where a food came from. User-added foods (custom, scan) live only in the user's directory. */
enum class FoodSource(val key: String) {
    Builtin("builtin"), OpenFoodFacts("off"), Custom("custom"), Scan("scan");

    companion object {
        fun fromKey(key: String?): FoodSource = entries.firstOrNull { it.key == key } ?: Custom
    }
}

data class Portion(val quantity: Double, val unit: FoodUnit)

data class Food(
    val id: String,
    val name: String,
    val category: FoodCategory,
    /** Nutrition per 100 g. */
    val per100g: Nutrition,
    /** Grams in ONE of each household unit (plate, piece, …). */
    val unitGrams: Map<FoodUnit, Double> = emptyMap(),
    /** g/ml; enables ml and L when known. */
    val density: Double? = null,
    val aliases: List<String> = emptyList(),
    val defaultPortion: Portion? = null,
    val source: FoodSource = FoodSource.Builtin,
) {
    /** Units that make sense for this food: household units first, then mass, then volume. */
    val availableUnits: List<FoodUnit>
        get() = buildList {
            addAll(unitGrams.keys.filterNot { it.isMass || it.isVolume }.sortedBy { it.ordinal })
            add(FoodUnit.Gram)
            add(FoodUnit.Kilogram)
            if (density != null) {
                add(FoodUnit.Milliliter)
                add(FoodUnit.Liter)
            }
        }

    /** Weight of [quantity] × [unit], or null if the unit doesn't apply to this food. */
    fun grams(quantity: Double, unit: FoodUnit): Double? = when (unit) {
        FoodUnit.Gram -> quantity
        FoodUnit.Kilogram -> quantity * 1000
        FoodUnit.Milliliter -> density?.let { quantity * it }
        FoodUnit.Liter -> density?.let { quantity * 1000 * it }
        else -> unitGrams[unit]?.let { quantity * it }
    }

    fun nutrition(quantity: Double, unit: FoodUnit): Nutrition? = grams(quantity, unit)?.let { per100g * (it / 100.0) }

    /** Sensible starting amount when the food is first picked. */
    val suggestedPortion: Portion
        get() = defaultPortion?.takeIf { grams(it.quantity, it.unit) != null }
            ?: unitGrams.keys.firstOrNull { !it.isMass && !it.isVolume }?.let { Portion(1.0, it) }
            ?: Portion(100.0, FoodUnit.Gram)
}

/** Lowercase, accent-free, single-spaced text for matching ("Rôti / Chapati" → "roti chapati"). */
fun String.normalizedForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
