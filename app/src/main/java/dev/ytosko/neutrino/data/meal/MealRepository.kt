package dev.ytosko.neutrino.data.meal

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
    val date: LocalDate,
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

/** A saved meal loaded back for editing. */
data class StoredMeal(
    val meal: MealEntity,
    val items: List<MealItemEntity>,
    val thumbnail: ByteArray?,
)

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
                date = date,
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
            draft.itemEntities(id),
        )
        // Teach the directory: every food eaten moves up the user's search results.
        draft.items.forEach { foods.recordUse(it.food, draft.mealType, it.portion) }
        onChanged()
        return SaveResult(id, synced)
    }

    suspend fun loadMeal(id: String): StoredMeal? {
        val meal = db.meals().get(id) ?: return null
        val thumbnail = meal.thumbnailPath?.let { path ->
            withContext(Dispatchers.IO) { runCatching { java.io.File(path).readBytes() }.getOrNull() }
        }
        return StoredMeal(meal, db.meals().items(id), thumbnail)
    }

    /** Saves changes to an existing meal, replacing its Health Connect record too. */
    suspend fun updateMeal(id: String, draft: MealDraft): SaveResult {
        val existing = db.meals().get(id) ?: return saveMeal(draft)
        val nutrition = draft.nutrition
        val synced = runCatching {
            healthConnect.writeMeal(id, draft.name, nutrition, draft.mealType, draft.eatenAt, draft.zone)
        }.getOrDefault(false)
        db.meals().updateWithItems(
            existing.copy(
                name = draft.name,
                calories = nutrition.calories,
                proteinG = nutrition.proteinG,
                carbsG = nutrition.carbsG,
                fatG = nutrition.fatG,
                mealType = draft.mealType.name,
                eatenAtEpochMs = draft.eatenAt.toEpochMilli(),
                zoneId = draft.zone.id,
                syncedToHealthConnect = synced,
            ),
            draft.itemEntities(id),
        )
        onChanged()
        return SaveResult(id, synced)
    }

    /**
     * Deletes a meal (here and in Health Connect) and returns what's needed to [restoreMeal] it,
     * so the Today screen can offer Undo.
     */
    suspend fun deleteMeal(id: String): StoredMeal? {
        val stored = loadMeal(id) ?: return null
        healthConnect.deleteMeal(id)
        db.meals().delete(id)
        photos.deleteThumbnail(stored.meal.thumbnailPath)
        onChanged()
        return stored
    }

    /** Puts back a meal removed by [deleteMeal]. */
    suspend fun restoreMeal(stored: StoredMeal) {
        val meal = stored.meal
        healthConnect.cancelPendingDelete(meal.id)
        val thumbnail = stored.thumbnail?.let { photos.saveThumbnail(it, meal.id) }
        val synced = runCatching {
            healthConnect.writeMeal(
                meal.id,
                meal.name,
                Nutrition(meal.calories, meal.proteinG, meal.carbsG, meal.fatG),
                runCatching { MealType.valueOf(meal.mealType) }.getOrDefault(MealType.Snack),
                Instant.ofEpochMilli(meal.eatenAtEpochMs),
                ZoneId.of(meal.zoneId),
            )
        }.getOrDefault(false)
        db.meals().insertWithItems(meal.copy(thumbnailPath = thumbnail, syncedToHealthConnect = synced), stored.items)
        onChanged()
    }

    /**
     * Makes Health Connect match Neutrino: sends meals and water saved while it wasn't connected,
     * and finishes deletes that couldn't reach it. Safe to call often; does nothing without permission.
     * Returns how many changes were sent.
     */
    suspend fun syncWithHealthConnect(): Int = syncMutex.withLock {
        if (!runCatching { healthConnect.hasAllPermissions() }.getOrDefault(false)) return 0
        var sent = runCatching { healthConnect.retryPendingDeletes() }.getOrDefault(0)
        db.meals().unsynced().forEach { meal ->
            val ok = runCatching {
                healthConnect.writeMeal(
                    meal.id,
                    meal.name,
                    Nutrition(meal.calories, meal.proteinG, meal.carbsG, meal.fatG),
                    runCatching { MealType.valueOf(meal.mealType) }.getOrDefault(MealType.Snack),
                    Instant.ofEpochMilli(meal.eatenAtEpochMs),
                    runCatching { ZoneId.of(meal.zoneId) }.getOrDefault(ZoneId.systemDefault()),
                )
            }.getOrDefault(false)
            if (ok) {
                db.meals().markSynced(meal.id)
                sent++
            }
        }
        db.water().unsynced().forEach { water ->
            val ok = runCatching {
                healthConnect.writeWater(
                    water.id,
                    water.amountMl,
                    Instant.ofEpochMilli(water.loggedAtEpochMs),
                    runCatching { ZoneId.of(water.zoneId) }.getOrDefault(ZoneId.systemDefault()),
                )
            }.getOrDefault(false)
            if (ok) {
                db.water().markSynced(water.id)
                sent++
            }
        }
        sent
    }

    private val syncMutex = Mutex()

    /** Logs water at [at] (now by default; a past day passes a time on that day). */
    suspend fun addWater(amountMl: Int, zone: ZoneId = ZoneId.systemDefault(), at: Instant = Instant.now()): Boolean {
        val id = UUID.randomUUID().toString()
        val now = at
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

private fun MealDraft.itemEntities(mealId: String) = items.mapIndexed { index, item ->
    MealItemEntity(
        mealId = mealId,
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
}
