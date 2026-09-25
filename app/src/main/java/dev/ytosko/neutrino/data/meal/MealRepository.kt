package dev.ytosko.neutrino.data.meal

import dev.ytosko.neutrino.data.food.FoodRepository
import dev.ytosko.neutrino.data.health.HealthConnectManager
import dev.ytosko.neutrino.domain.food.Food
import dev.ytosko.neutrino.domain.food.FoodCategory
import dev.ytosko.neutrino.domain.food.Portion
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.TokenUsage
import dev.ytosko.neutrino.domain.insights.FoodCount
import dev.ytosko.neutrino.domain.insights.MealPoint
import dev.ytosko.neutrino.domain.insights.WaterPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class LoggedMeal(
    val id: String,
    val name: String,
    val nutrition: Nutrition,
    val mealType: MealType,
    val eatenAt: Instant,
    val thumbnailPath: String?,
    val syncedToHealthConnect: Boolean,
    /** First food's category, for the icon when there's no photo. */
    val category: FoodCategory? = null,
)

data class DaySummary(
    val meals: List<LoggedMeal>,
    val waterMl: Int,
    val waterEntries: List<String>,
) {
    val totals: Nutrition get() = meals.fold(Nutrition.ZERO) { acc, meal -> acc + meal.nutrition }
}

/** One reviewed line: a food, how much of it, and what that amount contains. */
data class DraftItem(
    val food: Food,
    val portion: Portion,
    val grams: Double,
    val nutrition: Nutrition,
)

/** A meal the user approved on the review screen, ready to save. */
data class MealDraft(
    val name: String,
    val items: List<DraftItem>,
    val mealType: MealType,
    val eatenAt: Instant,
    val zone: ZoneId,
    val photoJpeg: ByteArray?,
    val provider: String?,
    val model: String?,
    val usage: TokenUsage?,
) {
    val nutrition: Nutrition get() = items.fold(Nutrition.ZERO) { acc, item -> acc + item.nutrition }
}

/** Result of saving: whether Health Connect received it too. */
data class SaveResult(val id: String, val syncedToHealthConnect: Boolean)

data class RangeData(val meals: List<MealPoint>, val water: List<WaterPoint>, val topFoods: List<FoodCount>)

private const val TOP_FOODS = 5

class MealRepository(
    private val db: MealDatabase,
    private val healthConnect: HealthConnectManager,
    private val photos: PhotoProcessor,
    private val foods: FoodRepository,
    /** Called after meals or water change, e.g. to schedule a backup. */
    private val onChanged: () -> Unit = {},
) {

    fun observeDay(date: LocalDate, zone: ZoneId): Flow<DaySummary> {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(db.meals().observeBetween(from, to), db.water().observeBetween(from, to)) { meals, water ->
            DaySummary(
                meals = meals.map { it.meal.toLoggedMeal(it.firstCategory?.let(FoodCategory::fromKey)) },
                waterMl = water.sumOf { it.amountMl },
                waterEntries = water.map { it.id },
            )
        }
    }

    /** Whether a meal of [type] is already logged on [date], so its reminder can be skipped. */
    suspend fun hasMeal(type: MealType, date: LocalDate, zone: ZoneId): Boolean {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return db.meals().countOfType(type.name, from, to) > 0
    }

    /** Everything logged from [from] to [toInclusive], for the Health page charts. */
    fun observeRange(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): Flow<RangeData> {
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = toInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(
            db.meals().observeBetween(fromMs, toMs),
            db.water().observeBetween(fromMs, toMs),
            db.meals().observeTopFoods(fromMs, toMs, TOP_FOODS),
        ) { meals, water, top ->
            RangeData(
                meals = meals.map { row ->
                    val meal = row.meal
                    MealPoint(
                        date = Instant.ofEpochMilli(meal.eatenAtEpochMs).atZone(zone).toLocalDate(),
                        type = runCatching { MealType.valueOf(meal.mealType) }.getOrDefault(MealType.Snack),
                        nutrition = Nutrition(meal.calories, meal.proteinG, meal.carbsG, meal.fatG),
                    )
                },
                water = water.map { WaterPoint(Instant.ofEpochMilli(it.loggedAtEpochMs).atZone(zone).toLocalDate(), it.amountMl) },
                topFoods = top.map { FoodCount(it.name, it.category, it.times) },
            )
        }
    }

    /** Saves to Health Connect (if connected) and to local history. Health Connect failures don't lose the meal. */
    suspend fun saveMeal(draft: MealDraft): SaveResult {
        val id = UUID.randomUUID().toString()
        val synced = runCatching {
            healthConnect.writeMeal(id, draft.name, draft.nutrition, draft.mealType, draft.eatenAt, draft.zone)
        }.getOrDefault(false)
        val thumbnail = draft.photoJpeg?.let { photos.saveThumbnail(it, id) }
        val nutrition = draft.nutrition
        db.meals().insertWithItems(
            MealEntity(
                id = id,
                name = draft.name,
                calories = nutrition.calories,
                proteinG = nutrition.proteinG,
                carbsG = nutrition.carbsG,
                fatG = nutrition.fatG,
                mealType = draft.mealType.name,
                eatenAtEpochMs = draft.eatenAt.toEpochMilli(),
                zoneId = draft.zone.id,
                thumbnailPath = thumbnail,
                syncedToHealthConnect = synced,
                provider = draft.provider,
                model = draft.model,
                inputTokens = draft.usage?.input ?: 0,
                outputTokens = draft.usage?.output ?: 0,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
            draft.items.mapIndexed { index, item ->
                MealItemEntity(
                    mealId = id,
                    position = index,
                    foodId = item.food.id,
                    name = item.food.name,
                    category = item.food.category.key,
                    quantity = item.portion.quantity,
                    unit = item.portion.unit.key,
                    grams = item.grams,
                    calories = item.nutrition.calories,
                    proteinG = item.nutrition.proteinG,
                    carbsG = item.nutrition.carbsG,
                    fatG = item.nutrition.fatG,
                )
            },
        )
        // Teach the directory: every food eaten moves up the user's search results.
        draft.items.forEach { foods.recordUse(it.food, draft.mealType, it.portion) }
        onChanged()
        return SaveResult(id, synced)
    }

    suspend fun deleteMeal(id: String) {
        val meal = db.meals().get(id) ?: return
        healthConnect.deleteMeal(id)
        db.meals().delete(id)
        photos.deleteThumbnail(meal.thumbnailPath)
        onChanged()
    }

    suspend fun addWater(amountMl: Int, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        val synced = runCatching { healthConnect.writeWater(id, amountMl, now, zone) }.getOrDefault(false)
        db.water().insert(WaterEntity(id, amountMl, now.toEpochMilli(), zone.id, synced))
        onChanged()
        return synced
    }

    suspend fun deleteWater(id: String) {
        healthConnect.deleteWater(id)
        db.water().delete(id)
        onChanged()
    }

    private fun MealEntity.toLoggedMeal(category: FoodCategory?) = LoggedMeal(
        id = id,
        name = name,
        nutrition = Nutrition(calories, proteinG, carbsG, fatG),
        mealType = runCatching { MealType.valueOf(mealType) }.getOrDefault(MealType.Snack),
        eatenAt = Instant.ofEpochMilli(eatenAtEpochMs),
        thumbnailPath = thumbnailPath,
        syncedToHealthConnect = syncedToHealthConnect,
        category = category,
    )
}
