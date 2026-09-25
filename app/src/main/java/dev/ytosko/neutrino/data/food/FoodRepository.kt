package dev.ytosko.neutrino.data.food

import dev.ytosko.neutrino.data.meal.FoodDao
import dev.ytosko.neutrino.data.meal.FoodEntity
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.food.Food
import dev.ytosko.neutrino.domain.food.FoodCategory
import dev.ytosko.neutrino.domain.food.FoodRanking
import dev.ytosko.neutrino.domain.food.FoodSource
import dev.ytosko.neutrino.domain.food.FoodUnit
import dev.ytosko.neutrino.domain.food.FoodUsage
import dev.ytosko.neutrino.domain.food.Portion
import dev.ytosko.neutrino.domain.food.normalizedForSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A food plus how the user has used it (empty usage for foods they've never logged). */
data class RankedFood(val food: Food, val usage: FoodUsage)

/**
 * Combines the user's personal directory (ranked by their habits) with the built-in catalog.
 * Online sources (Open Food Facts, AI) are called by the search screen separately.
 */
class FoodRepository(
    private val dao: FoodDao,
    private val catalog: FoodCatalog,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Local results for [query] at [mealType]: the user's own foods first (by relevance and
     * habit), then built-in foods they haven't used. An empty query returns their usual foods.
     */
    suspend fun search(query: String, mealType: MealType, limit: Int = 40): List<RankedFood> = withContext(Dispatchers.Default) {
        val time = now()
        val mine = dao.all().map { it.toFood() to it.toUsage() }
        val mineIds = mine.map { it.first.id }.toSet()

        val personal = mine
            .map { (food, usage) -> Triple(food, usage, FoodRanking.matchScore(query, food.name, food.aliases)) }
            .filter { it.third > 0 }
            .sortedByDescending { (_, usage, match) ->
                (if (query.isBlank()) 0.0 else match.toDouble()) + FoodRanking.usageScore(usage, mealType, time)
            }
            .map { RankedFood(it.first, it.second) }

        val builtin = if (query.isBlank()) emptyList()
        else catalog.search(query, limit).filter { it.id !in mineIds }.map { RankedFood(it, FoodUsage()) }

        (personal + builtin).take(limit)
    }

    /** Finds a food the user already has with this name (so a re-scanned "biryani" reuses its values). */
    suspend fun findByName(name: String): Food? {
        dao.byName(name.trim())?.let { return it.toFood() }
        val key = name.normalizedForSearch()
        return catalog.foods.firstOrNull { food -> food.name.normalizedForSearch() == key || food.aliases.any { it.normalizedForSearch() == key } }
    }

    /** Adds [food] to the directory (if new) and records that it was eaten at [mealType]. */
    suspend fun recordUse(food: Food, mealType: MealType, portion: Portion) {
        val existing = dao.get(food.id)
        val base = existing ?: food.toEntity(now())
        dao.upsert(
            base.copy(
                useCount = base.useCount + 1,
                lastUsedEpochMs = now(),
                breakfastCount = base.breakfastCount + if (mealType == MealType.Breakfast) 1 else 0,
                lunchCount = base.lunchCount + if (mealType == MealType.Lunch) 1 else 0,
                snackCount = base.snackCount + if (mealType == MealType.Snack) 1 else 0,
                dinnerCount = base.dinnerCount + if (mealType == MealType.Dinner) 1 else 0,
                lastQuantity = portion.quantity,
                lastUnit = portion.unit.key,
            ),
        )
    }

    /** The portion the user last logged for this food, if any. */
    /** A food by id: the user's directory first, then the built-in list. */
    suspend fun byId(id: String): Food? = dao.get(id)?.toFood() ?: catalog.byId(id)

    suspend fun lastPortion(foodId: String): Portion? = dao.get(foodId)?.toUsage()?.lastPortion

    private fun Food.toEntity(time: Long) = FoodEntity(
        id = id,
        name = name,
        category = category.key,
        kcalPer100g = per100g.calories,
        proteinPer100g = per100g.proteinG,
        carbsPer100g = per100g.carbsG,
        fatPer100g = per100g.fatG,
        units = unitGrams.entries.joinToString(";") { "${it.key.key}=${it.value}" },
        density = density,
        aliases = aliases.joinToString("|"),
        source = source.key,
        useCount = 0,
        lastUsedEpochMs = 0,
        breakfastCount = 0,
        lunchCount = 0,
        snackCount = 0,
        dinnerCount = 0,
        lastQuantity = null,
        lastUnit = null,
        createdAtEpochMs = time,
    )
}

internal fun FoodEntity.toFood() = Food(
    id = id,
    name = name,
    category = FoodCategory.fromKey(category),
    per100g = Nutrition(kcalPer100g, proteinPer100g, carbsPer100g, fatPer100g),
    unitGrams = units.split(';').mapNotNull { part ->
        val (key, grams) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
        val unit = FoodUnit.fromKey(key) ?: return@mapNotNull null
        grams.toDoubleOrNull()?.let { unit to it }
    }.toMap(),
    density = density,
    aliases = aliases.split('|').filter { it.isNotBlank() },
    defaultPortion = toUsage().lastPortion,
    source = FoodSource.fromKey(source),
)

internal fun FoodEntity.toUsage() = FoodUsage(
    useCount = useCount,
    lastUsedEpochMs = lastUsedEpochMs,
    mealCounts = mapOf(
        MealType.Breakfast to breakfastCount,
        MealType.Lunch to lunchCount,
        MealType.Snack to snackCount,
        MealType.Dinner to dinnerCount,
    ),
    lastPortion = lastQuantity?.let { q -> FoodUnit.fromKey(lastUnit)?.let { Portion(q, it) } },
)
