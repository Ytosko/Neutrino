package dev.ytosko.neutrino.data.food

import dev.ytosko.neutrino.data.ai.AiException
import dev.ytosko.neutrino.data.ai.call
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.food.Food
import dev.ytosko.neutrino.domain.food.FoodCategory
import dev.ytosko.neutrino.domain.food.FoodSource
import dev.ytosko.neutrino.domain.food.FoodUnit
import dev.ytosko.neutrino.domain.food.Portion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Packaged products from Open Food Facts (openfoodfacts.org, ODbL). No key needed; only the
 * search text is sent. Values are per 100 g (or per 100 ml, treated as ~1 g/ml).
 */
class OpenFoodFactsClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val userAgent: String,
    private val searchUrl: String = "https://search.openfoodfacts.org",
    private val classicUrl: String = "https://world.openfoodfacts.org",
) {

    /** Uses the fast search service, falling back to the classic endpoint if it's unavailable. */
    suspend fun search(query: String, limit: Int = 15): List<Food> =
        try {
            fetch(
                "$searchUrl/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", query)
                    .addQueryParameter("page_size", limit.toString())
                    .addQueryParameter("fields", FIELDS)
                    .build(),
                listKey = "hits",
            )
        } catch (_: AiException) {
            fetch(
                "$classicUrl/cgi/search.pl".toHttpUrl().newBuilder()
                    .addQueryParameter("search_terms", query)
                    .addQueryParameter("search_simple", "1")
                    .addQueryParameter("action", "process")
                    .addQueryParameter("json", "1")
                    .addQueryParameter("page_size", limit.toString())
                    .addQueryParameter("fields", FIELDS)
                    .build(),
                listKey = "products",
            )
        }

    private suspend fun fetch(url: HttpUrl, listKey: String): List<Food> {
        val (code, body) = http.call(Request.Builder().url(url).header("User-Agent", userAgent).build())
        if (code !in 200..299) throw AiException.Unexpected(code)
        val products = runCatching { json.parseToJsonElement(body).jsonObject[listKey] }.getOrNull() as? JsonArray
            ?: throw AiException.NoResult()
        return products.mapNotNull { (it as? JsonObject)?.toFood() }.distinctBy { it.name.lowercase() }
    }

    private fun JsonObject.toFood(): Food? {
        val code = string("code") ?: return null
        val name = string("product_name")?.takeIf { it.isNotBlank() } ?: return null
        // "brands" is a comma-separated string on the classic API and an array on the search service.
        val brand = when (val b = this["brands"]) {
            is JsonArray -> (b.firstOrNull() as? JsonPrimitive)?.contentOrNull
            else -> string("brands")?.split(',')?.firstOrNull()
        }?.trim()?.takeIf { it.isNotBlank() }
        val n = this["nutriments"] as? JsonObject ?: return null
        val kcal = n.number("energy-kcal_100g") ?: n.number("energy_100g")?.let { it / 4.184 } ?: return null
        val per100 = Nutrition(
            calories = kcal,
            proteinG = n.number("proteins_100g") ?: 0.0,
            carbsG = n.number("carbohydrates_100g") ?: 0.0,
            fatG = n.number("fat_100g") ?: 0.0,
        )
        val serving = (this["serving_quantity"] as? JsonPrimitive)?.number()?.takeIf { it > 0 }
        val liquid = string("quantity")?.lowercase()?.let { Regex("\\d\\s*(ml|cl|l)\\b").containsMatchIn(it) } == true
        return Food(
            id = "off-$code",
            name = if (brand != null && !name.contains(brand, ignoreCase = true)) "$name ($brand)" else name,
            category = FoodCategory.Packaged,
            per100g = per100,
            unitGrams = serving?.let { mapOf(FoodUnit.Serving to it) }.orEmpty(),
            density = if (liquid) 1.0 else null,
            defaultPortion = serving?.let { Portion(1.0, FoodUnit.Serving) },
            source = FoodSource.OpenFoodFacts,
        )
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.number()?.takeIf { it >= 0 }
    private fun JsonPrimitive.number(): Double? = doubleOrNull ?: contentOrNull?.toDoubleOrNull()

    private companion object {
        const val FIELDS = "code,product_name,brands,nutriments,serving_quantity,quantity"
    }
}
