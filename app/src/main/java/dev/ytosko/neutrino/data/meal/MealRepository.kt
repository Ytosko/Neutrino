package dev.ytosko.neutrino.data.meal

import dev.ytosko.neutrino.data.health.HealthConnectManager
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import dev.ytosko.neutrino.domain.TokenUsage
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
)

data class DaySummary(
    val meals: List<LoggedMeal>,
    val waterMl: Int,
    val waterEntries: List<String>,
) {
    val totals: Nutrition get() = meals.fold(Nutrition.ZERO) { acc, meal -> acc + meal.nutrition }
}

/** A meal the user approved on the review screen, ready to save. */
data class MealDraft(
    val name: String,
    val nutrition: Nutrition,
    val mealType: MealType,
    val eatenAt: Instant,
    val zone: ZoneId,
    val photoJpeg: ByteArray?,
    val provider: String?,
    val model: String?,
    val usage: TokenUsage?,
)

/** Result of saving: whether Health Connect received it too. */
data class SaveResult(val id: String, val syncedToHealthConnect: Boolean)

class MealRepository(
    private val db: MealDatabase,
    private val healthConnect: HealthConnectManager,
    private val photos: PhotoProcessor,
) {

    fun observeDay(date: LocalDate, zone: ZoneId): Flow<DaySummary> {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(db.meals().observeBetween(from, to), db.water().observeBetween(from, to)) { meals, water ->
            DaySummary(
                meals = meals.map { it.toLoggedMeal() },
                waterMl = water.sumOf { it.amountMl },
                waterEntries = water.map { it.id },
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
        db.meals().insert(
            MealEntity(
                id = id,
                name = draft.name,
                calories = draft.nutrition.calories,
                proteinG = draft.nutrition.proteinG,
                carbsG = draft.nutrition.carbsG,
                fatG = draft.nutrition.fatG,
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
        )
        return SaveResult(id, synced)
    }

    suspend fun deleteMeal(id: String) {
        val meal = db.meals().get(id) ?: return
        healthConnect.deleteMeal(id)
        db.meals().delete(id)
        photos.deleteThumbnail(meal.thumbnailPath)
    }

    suspend fun addWater(amountMl: Int, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        val synced = runCatching { healthConnect.writeWater(id, amountMl, now, zone) }.getOrDefault(false)
        db.water().insert(WaterEntity(id, amountMl, now.toEpochMilli(), zone.id, synced))
        return synced
    }

    suspend fun deleteWater(id: String) {
        healthConnect.deleteWater(id)
        db.water().delete(id)
    }

    private fun MealEntity.toLoggedMeal() = LoggedMeal(
        id = id,
        name = name,
        nutrition = Nutrition(calories, proteinG, carbsG, fatG),
        mealType = runCatching { MealType.valueOf(mealType) }.getOrDefault(MealType.Snack),
        eatenAt = Instant.ofEpochMilli(eatenAtEpochMs),
        thumbnailPath = thumbnailPath,
        syncedToHealthConnect = syncedToHealthConnect,
    )
}
