package dev.ytosko.neutrino.data.meal

import dev.ytosko.neutrino.data.glucose.GlucoseEntity
import dev.ytosko.neutrino.data.glucose.GlucoseDao
import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A logged meal. Neutrino only writes to Health Connect (never reads), so this local copy
 * powers the Today screen and is what backups carry (entities serialize straight into backups, so
 * new columns need defaults to keep old backups readable). [id] doubles as the Health Connect
 * clientRecordId, which lets us delete the matching record later.
 */
@Serializable
@Entity(tableName = "meals")
data class MealEntity(
    @PrimaryKey val id: String,
    val name: String,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    /** [dev.ytosko.neutrino.domain.MealType] name. */
    val mealType: String,
    val eatenAtEpochMs: Long,
    /** Zone the meal was eaten in, e.g. "Asia/Dhaka". */
    val zoneId: String,
    val thumbnailPath: String?,
    val syncedToHealthConnect: Boolean,
    val provider: String?,
    val model: String?,
    val inputTokens: Int,
    val outputTokens: Int,
    val createdAtEpochMs: Long,
    /** Starred for "Log again". */
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
)

/** One food line of a meal: what, how much, and the nutrition it contributed. */
@Serializable
@Entity(
    tableName = "meal_items",
    foreignKeys = [ForeignKey(entity = MealEntity::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mealId")],
)
data class MealItemEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val mealId: String,
    val position: Int,
    val foodId: String,
    val name: String,
    val category: String,
    val quantity: Double,
    val unit: String,
    val grams: Double,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
)

/**
 * The user's personal food directory: every food they've logged (built-in, packaged, custom or
 * learned from a photo) with usage stats that rank search results.
 */
@Serializable
@Entity(tableName = "foods", indices = [Index("name")])
data class FoodEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val kcalPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    /** "plate=250;piece=90": grams per household unit. */
    val units: String,
    val density: Double?,
    val aliases: String,
    val source: String,
    val useCount: Int,
    val lastUsedEpochMs: Long,
    val breakfastCount: Int,
    val lunchCount: Int,
    val snackCount: Int,
    val dinnerCount: Int,
    val lastQuantity: Double?,
    val lastUnit: String?,
    val createdAtEpochMs: Long,
)

@Serializable
@Entity(tableName = "water")
data class WaterEntity(
    @PrimaryKey val id: String,
    val amountMl: Int,
    val loggedAtEpochMs: Long,
    val zoneId: String,
    val syncedToHealthConnect: Boolean,
)

data class TopFoodRow(val name: String, val category: String, val times: Int)

/** A meal plus its first food's category, which picks the icon for meals without a photo. */
data class MealWithCategory(
    @Embedded val meal: MealEntity,
    val firstCategory: String?,
)

@Dao
interface MealDao {
    @Query(
        """SELECT meals.*, (SELECT category FROM meal_items WHERE mealId = meals.id ORDER BY position LIMIT 1) AS firstCategory
        FROM meals WHERE eatenAtEpochMs >= :fromMs AND eatenAtEpochMs < :toMs ORDER BY eatenAtEpochMs""",
    )
    fun observeBetween(fromMs: Long, toMs: Long): Flow<List<MealWithCategory>>

    @Query(
        """SELECT i.name AS name, i.category AS category, COUNT(*) AS times FROM meal_items i
        JOIN meals m ON m.id = i.mealId
        WHERE m.eatenAtEpochMs >= :fromMs AND m.eatenAtEpochMs < :toMs
        GROUP BY i.foodId ORDER BY times DESC, MAX(m.eatenAtEpochMs) DESC LIMIT :limit""",
    )
    fun observeTopFoods(fromMs: Long, toMs: Long, limit: Int): Flow<List<TopFoodRow>>

    /** Latest meals, newest first, for "Log again" (duplicates by name are dropped by the caller). */
    @Query(
        """SELECT meals.*, (SELECT category FROM meal_items WHERE mealId = meals.id ORDER BY position LIMIT 1) AS firstCategory
        FROM meals ORDER BY eatenAtEpochMs DESC LIMIT :limit""",
    )
    fun observeRecent(limit: Int): Flow<List<MealWithCategory>>

    @Query(
        """SELECT meals.*, (SELECT category FROM meal_items WHERE mealId = meals.id ORDER BY position LIMIT 1) AS firstCategory
        FROM meals WHERE favorite = 1 ORDER BY eatenAtEpochMs DESC""",
    )
    fun observeFavorites(): Flow<List<MealWithCategory>>

    @Query("UPDATE meals SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    /** Un-stars every meal with this name, so a favourite can be removed from any copy of it. */
    @Query("UPDATE meals SET favorite = 0 WHERE name = :name")
    suspend fun clearFavoriteByName(name: String)

    @Query("SELECT COUNT(*) FROM meals WHERE mealType = :mealType AND eatenAtEpochMs >= :fromMs AND eatenAtEpochMs < :toMs")
    suspend fun countOfType(mealType: String, fromMs: Long, toMs: Long): Int

    @Query("SELECT * FROM meals WHERE id = :id")
    suspend fun get(id: String): MealEntity?

    @Query("SELECT * FROM meal_items WHERE mealId = :mealId ORDER BY position")
    suspend fun items(mealId: String): List<MealItemEntity>

    @Insert
    suspend fun insert(meal: MealEntity)

    @Insert
    suspend fun insertItems(items: List<MealItemEntity>)

    @Transaction
    suspend fun insertWithItems(meal: MealEntity, items: List<MealItemEntity>) {
        insert(meal)
        insertItems(items)
    }

    @Query("DELETE FROM meals WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM meals WHERE syncedToHealthConnect = 0")
    suspend fun unsynced(): List<MealEntity>

    @Query("UPDATE meals SET syncedToHealthConnect = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @androidx.room.Update
    suspend fun update(meal: MealEntity)

    @Query("DELETE FROM meal_items WHERE mealId = :mealId")
    suspend fun deleteItems(mealId: String)

    @Transaction
    suspend fun updateWithItems(meal: MealEntity, items: List<MealItemEntity>) {
        update(meal)
        deleteItems(meal.id)
        insertItems(items)
    }
}

@Dao
interface FoodDao {
    @Query("SELECT * FROM foods")
    suspend fun all(): List<FoodEntity>

    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun get(id: String): FoodEntity?

    @Query("SELECT * FROM foods WHERE lower(name) = lower(:name) LIMIT 1")
    suspend fun byName(name: String): FoodEntity?

    @Upsert
    suspend fun upsert(food: FoodEntity)
}

@Dao
interface WaterDao {
    @Query("SELECT * FROM water WHERE loggedAtEpochMs >= :fromMs AND loggedAtEpochMs < :toMs ORDER BY loggedAtEpochMs")
    fun observeBetween(fromMs: Long, toMs: Long): Flow<List<WaterEntity>>

    @Insert
    suspend fun insert(water: WaterEntity)

    @Query("DELETE FROM water WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM water WHERE syncedToHealthConnect = 0")
    suspend fun unsynced(): List<WaterEntity>

    @Query("UPDATE water SET syncedToHealthConnect = 1 WHERE id = :id")
    suspend fun markSynced(id: String)
}

/** Whole-database reads and a replace-everything write, for backup and restore. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM meals")
    suspend fun meals(): List<MealEntity>

    @Query("SELECT * FROM meal_items")
    suspend fun items(): List<MealItemEntity>

    @Query("SELECT * FROM foods")
    suspend fun foods(): List<FoodEntity>

    @Query("SELECT * FROM water")
    suspend fun water(): List<WaterEntity>

    @Query("SELECT COUNT(*) FROM meals")
    suspend fun mealCount(): Int

    @Query("SELECT COUNT(*) FROM foods")
    suspend fun foodCount(): Int

    @Query("DELETE FROM meals")
    suspend fun clearMeals()

    @Query("DELETE FROM meal_items")
    suspend fun clearItems()

    @Query("DELETE FROM foods")
    suspend fun clearFoods()

    @Query("DELETE FROM water")
    suspend fun clearWater()

    @Insert
    suspend fun insertMeals(meals: List<MealEntity>)

    @Insert
    suspend fun insertItems(items: List<MealItemEntity>)

    @Insert
    suspend fun insertFoods(foods: List<FoodEntity>)

    @Insert
    suspend fun insertWater(water: List<WaterEntity>)

    @Transaction
    suspend fun replaceAll(meals: List<MealEntity>, items: List<MealItemEntity>, foods: List<FoodEntity>, water: List<WaterEntity>) {
        clearItems()
        clearMeals()
        clearFoods()
        clearWater()
        insertMeals(meals)
        insertItems(items)
        insertFoods(foods)
        insertWater(water)
    }
}

@Database(
    entities = [MealEntity::class, MealItemEntity::class, FoodEntity::class, WaterEntity::class, GlucoseEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class MealDatabase : RoomDatabase() {
    abstract fun meals(): MealDao
    abstract fun foods(): FoodDao
    abstract fun water(): WaterDao
    abstract fun backup(): BackupDao
    abstract fun glucose(): GlucoseDao

    companion object {
        const val NAME = "neutrino.db"

        fun create(context: Context): MealDatabase =
            Room.databaseBuilder(context.applicationContext, MealDatabase::class.java, NAME).build()
    }
}
