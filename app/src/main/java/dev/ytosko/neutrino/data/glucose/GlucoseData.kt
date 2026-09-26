package dev.ytosko.neutrino.data.glucose

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A blood glucose reading. Sensitive health data: it stays on the phone, in the user's encrypted
 * backups and in Health Connect, and is never logged.
 *
 * [id] doubles as the Health Connect clientRecordId. Meter readings use "meter-<serial>-<sequence>",
 * so the same reading can never be stored twice even if the meter sends it again.
 * New columns need defaults so older backups stay readable.
 */
@Serializable
@Entity(tableName = "glucose_readings", indices = [Index("measuredAtEpochMs")])
data class GlucoseEntity(
    @PrimaryKey val id: String,
    val mmolPerL: Double,
    val measuredAtEpochMs: Long,
    val zoneId: String,
    /** [GlucoseRelation] name. */
    val relation: String = GlucoseRelation.General.name,
    /** The meter's clock couldn't be trusted for this reading. */
    val timeEstimated: Boolean = false,
    /** "High"/"Low" when the meter showed HI/LO instead of a number. */
    val rangeFlag: String? = null,
    val meterSerial: String? = null,
    val meterSequence: Int? = null,
    /** The user changed the time or meal relation in Neutrino. */
    val edited: Boolean = false,
    val syncedToHealthConnect: Boolean = false,
    val createdAtEpochMs: Long = 0,
    /** Package of the app it was imported from through Health Connect (e.g. a CGM app); null for Neutrino's own. */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val importedFrom: String? = null,
)

/** How a reading relates to eating. "General" is what unmarked meter readings get. */
enum class GlucoseRelation { General, Fasting, BeforeMeal, AfterMeal, Bedtime }

@Dao
interface GlucoseDao {
    @Query("SELECT * FROM glucose_readings WHERE measuredAtEpochMs >= :fromMs AND measuredAtEpochMs < :toMs ORDER BY measuredAtEpochMs")
    fun observeBetween(fromMs: Long, toMs: Long): Flow<List<GlucoseEntity>>

    @Query("SELECT * FROM glucose_readings WHERE id = :id")
    suspend fun get(id: String): GlucoseEntity?

    /** Returns -1 for readings already stored (never overwrites a user's edits). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reading: GlucoseEntity): Long

    @Update
    suspend fun update(reading: GlucoseEntity)

    @Query("DELETE FROM glucose_readings WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM glucose_readings WHERE syncedToHealthConnect = 0")
    suspend fun unsynced(): List<GlucoseEntity>

    @Query("UPDATE glucose_readings SET syncedToHealthConnect = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("SELECT * FROM glucose_readings")
    suspend fun all(): List<GlucoseEntity>

    @Query("DELETE FROM glucose_readings")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<GlucoseEntity>)

    @Query("SELECT COUNT(*) FROM glucose_readings")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM glucose_readings ORDER BY measuredAtEpochMs DESC LIMIT 1")
    suspend fun latest(): GlucoseEntity?

    @Query("SELECT * FROM glucose_readings ORDER BY measuredAtEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<GlucoseEntity?>
}
