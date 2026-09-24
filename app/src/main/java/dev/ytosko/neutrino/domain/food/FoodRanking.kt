package dev.ytosko.neutrino.domain.food

import dev.ytosko.neutrino.domain.MealType
import kotlin.math.exp
import kotlin.math.ln

/** How a user has eaten a food so far, used to rank their directory. */
data class FoodUsage(
    val useCount: Int = 0,
    val lastUsedEpochMs: Long = 0,
    val mealCounts: Map<MealType, Int> = emptyMap(),
    val lastPortion: Portion? = null,
)

object FoodRanking {

    /**
     * Text relevance: 0 = no match. Name prefix beats word prefix beats alias beats substring,
     * and every query word must match somewhere.
     */
    fun matchScore(query: String, name: String, aliases: List<String> = emptyList()): Int {
        val q = query.normalizedForSearch()
        if (q.isEmpty()) return 1
        val n = name.normalizedForSearch()
        val a = aliases.map { it.normalizedForSearch() }
        val haystack = (listOf(n) + a).joinToString(" ")
        if (q.split(' ').any { word -> word !in haystack }) return 0
        return when {
            n == q -> 100
            n.startsWith(q) -> 90
            a.any { it == q } -> 85
            n.split(' ').any { it.startsWith(q) } -> 75
            a.any { it.startsWith(q) } -> 70
            a.any { alias -> alias.split(' ').any { it.startsWith(q) } } -> 60
            else -> 40
        }
    }

    /**
     * Personal relevance: how often, how recently, and whether it's usually eaten at this meal.
     * Roughly 0–100; a food eaten daily at this meal scores near the top.
     */
    fun usageScore(usage: FoodUsage, mealType: MealType, nowEpochMs: Long): Double {
        if (usage.useCount == 0) return 0.0
        val frequency = 20 * ln(1.0 + usage.useCount)                     // 1 → 14, 10 → 48
        val daysAgo = (nowEpochMs - usage.lastUsedEpochMs).coerceAtLeast(0) / 86_400_000.0
        val recency = 30 * exp(-daysAgo / 7.0)                              // halves roughly every 5 days
        val atThisMeal = usage.mealCounts[mealType] ?: 0
        val mealFit = 30.0 * atThisMeal / usage.useCount
        return frequency + recency + mealFit
    }
}
