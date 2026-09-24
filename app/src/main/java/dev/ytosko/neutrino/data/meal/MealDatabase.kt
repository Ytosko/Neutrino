package dev.ytosko.neutrino.data.meal

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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

    @Insert
    suspend fun insert(meal: MealEntity)

    @Query("DELETE FROM meals WHERE id = :id")
    suspend fun delete(id: String)
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

@Database(entities = [MealEntity::class, WaterEntity::class], version = 1, exportSchema = true)
abstract class MealDatabase : RoomDatabase() {
    abstract fun meals(): MealDao
    abstract fun water(): WaterDao

    companion object {
        fun create(context: Context): MealDatabase =
            Room.databaseBuilder(context.applicationContext, MealDatabase::class.java, "neutrino.db").build()
    }
}
