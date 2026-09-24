package dev.ytosko.neutrino.domain.food

import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.ScannedItem

/** A scanned item resolved to a food plus the amount seen on the plate. */
data class ResolvedItem(val food: Food, val portion: Portion)

object ScanFoods {

    /**
     * Resolves a scanned item. If the user already has a food with this name (their directory or
     * the built-in list), that food's nutrition is reused so the same dish always gets the same
     * numbers; otherwise a new food is created from the model's estimate (the directory learns it).
     */
    fun resolve(item: ScannedItem, known: Food?): ResolvedItem {
        val unit = FoodUnit.fromKey(item.unit) ?: FoodUnit.Serving
        if (known != null) {
            if (known.grams(item.quantity, unit) != null) return ResolvedItem(known, Portion(item.quantity, unit))
            if (item.grams > 0) return ResolvedItem(known, Portion(item.grams, FoodUnit.Gram))
        }
        return ResolvedItem(learn(item, unit), Portion(item.quantity, unit))
    }

    private fun learn(item: ScannedItem, unit: FoodUnit): Food {
        // Without a weight, treat the portion as 100 g so the model's numbers are kept as-is.
        val portionGrams = item.grams.takeIf { it > 0 }
            ?: if (unit.isVolume) item.quantity * (if (unit == FoodUnit.Liter) 1000 else 1) else 100.0
        val perUnit = if (unit.isMass || unit.isVolume) null else portionGrams / item.quantity
        val per100 = item.nutrition * (100.0 / portionGrams)
        return Food(
            id = "scan-" + item.name.normalizedForSearch().replace(' ', '-'),
            name = item.name,
            category = guessCategory(item.name),
            per100g = Nutrition(per100.calories, per100.proteinG, per100.carbsG, per100.fatG),
            unitGrams = perUnit?.let { mapOf(unit to it) }.orEmpty(),
            density = if (unit.isVolume) 1.0 else null,
            defaultPortion = Portion(item.quantity, unit),
            source = FoodSource.Scan,
        )
    }

    private val categoryHints = listOf(
        FoodCategory.RiceDish to listOf("biryani", "biriyani", "tehari", "polao", "pulao", "khichuri", "fried rice", "kacchi"),
        FoodCategory.Curry to listOf("curry", "bhuna", "jhol", "korma", "dal", "daal", "bhorta", "masala", "stew", "haleem", "nihari"),
        FoodCategory.Grain to listOf("rice", "bhaat", "bhat", "oats", "noodle", "pasta"),
        FoodCategory.Bread to listOf("bread", "roti", "ruti", "paratha", "porota", "naan", "puri", "luchi"),
        FoodCategory.Poultry to listOf("chicken", "murgi", "duck"),
        FoodCategory.Meat to listOf("beef", "mutton", "goat", "lamb", "kabab", "kebab"),
        FoodCategory.Fish to listOf("fish", "mach", "hilsa", "ilish", "shrimp", "prawn", "chingri"),
        FoodCategory.Egg to listOf("egg", "dim", "omelet"),
        FoodCategory.Dairy to listOf("milk", "yogurt", "doi", "cheese"),
        FoodCategory.FastFood to listOf("burger", "fries", "sandwich", "nugget"),
        FoodCategory.Pizza to listOf("pizza"),
        FoodCategory.Dessert to listOf("cake", "mishti", "sweet", "payesh", "kheer", "rasgulla", "ice cream", "pitha"),
        FoodCategory.Snack to listOf("singara", "samosa", "chips", "fuchka", "chotpoti", "pakora", "biscuit"),
        FoodCategory.Leafy to listOf("salad", "shak", "spinach"),
        FoodCategory.Vegetable to listOf("vegetable", "sobji", "potato", "alu", "aloo"),
        FoodCategory.Fruit to listOf("mango", "banana", "apple", "fruit", "papaya", "guava"),
        FoodCategory.HotDrink to listOf("tea", "cha", "coffee"),
        FoodCategory.Drink to listOf("juice", "soda", "cola", "lassi", "water"),
    )

    fun guessCategory(name: String): FoodCategory {
        val text = name.normalizedForSearch()
        return categoryHints.firstOrNull { (_, words) -> words.any { word -> text.contains(word) } }?.first ?: FoodCategory.Other
    }
}
