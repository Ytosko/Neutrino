package dev.ytosko.neutrino.data.meal

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
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

/**
 * A logged meal. Neutrino only writes to Health Connect (never reads), so this local copy
 * powers the Today screen and is what backups carry. [id] doubles as the Health Connect
 * clientRecordId, which lets us delete the matching record later.
 */
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
)

/** One food line of a meal: what, how much, and the nutrition it contributed. */
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

@Entity(tableName = "water")
data class WaterEntity(
    @PrimaryKey val id: String,
    val amountMl: Int,
    val loggedAtEpochMs: Long,
    val zoneId: String,
    val syncedToHealthConnect: Boolean,
)

@Dao
interface MealDao {
    @Query("SELECT * FROM meals WHERE eatenAtEpochMs >= :fromMs AND eatenAtEpochMs < :toMs ORDER BY eatenAtEpochMs")
    fun observeBetween(fromMs: Long, toMs: Long): Flow<List<MealEntity>>

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
}

@Database(
    entities = [MealEntity::class, MealItemEntity::class, FoodEntity::class, WaterEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class MealDatabase : RoomDatabase() {
    abstract fun meals(): MealDao
    abstract fun foods(): FoodDao
    abstract fun water(): WaterDao

    companion object {
        fun create(context: Context): MealDatabase =
            Room.databaseBuilder(context.applicationContext, MealDatabase::class.java, "neutrino.db").build()
    }
}
