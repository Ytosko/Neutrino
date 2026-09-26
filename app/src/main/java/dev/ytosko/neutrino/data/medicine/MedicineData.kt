package dev.ytosko.neutrino.data.medicine

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Tablets and the like, or insulin. Each switch in Settings turns one of them on. */
enum class MedicineKind { Medicine, Insulin }

enum class InsulinType { Rapid, Short, Intermediate, Long, Mixed }

/** What a dose is counted in. */
enum class DoseUnit { Tablet, Capsule, Ml, Mg, Units, Puff, Drop }

/**
 * A medicine or insulin the user takes, as they set it up. Health data: it stays on the phone and
 * in the user's encrypted backups (Health Connect has no place for it), and is never logged.
 * New columns need defaults so older backups stay readable.
 */
@Serializable
@Entity(tableName = "medicines")
data class MedicineEntity(
    @PrimaryKey val id: String,
    /** Brand or everyday name, e.g. "Napa". */
    val name: String,
    /** Group (generic), e.g. "Paracetamol". */
    val generic: String? = null,
    /** e.g. "500 mg". */
    val strength: String? = null,
    /** e.g. "Tablet". */
    val form: String? = null,
    /** [MedicineKind] name. */
    val kind: String = MedicineKind.Medicine.name,
    /** [InsulinType] name, for insulin. */
    val insulinType: String? = null,
    /** What a usual dose is, used to fill in the log; null means ask each time. */
    val usualDose: Double? = null,
    /** [DoseUnit] name. */
    val doseUnit: String = DoseUnit.Tablet.name,
    /** Daily reminder times as "HH:mm", comma separated; empty for none. */
    val reminderTimes: String = "",
    /** Removed from the list; kept so past doses still show their medicine. */
    val archived: Boolean = false,
    val createdAtEpochMs: Long = 0,
) {
    val kindEnum: MedicineKind get() = runCatching { MedicineKind.valueOf(kind) }.getOrDefault(MedicineKind.Medicine)
    val unitEnum: DoseUnit get() = runCatching { DoseUnit.valueOf(doseUnit) }.getOrDefault(DoseUnit.Tablet)
    val insulinTypeEnum: InsulinType? get() = insulinType?.let { runCatching { InsulinType.valueOf(it) }.getOrNull() }

    /** "Napa 500 mg". */
    val displayName: String get() = listOfNotNull(name, strength?.takeIf { it.isNotBlank() }).joinToString(" ")

    val reminders: List<java.time.LocalTime>
        get() = reminderTimes.split(',').mapNotNull { runCatching { java.time.LocalTime.parse(it.trim()) }.getOrNull() }.sorted()
}

/** One dose taken. Same privacy as [MedicineEntity]. */
@Serializable
@Entity(tableName = "doses", indices = [Index("takenAtEpochMs"), Index("medicineId")])
data class DoseEntity(
    @PrimaryKey val id: String,
    val medicineId: String,
    /** The medicine's name when logged, so history reads right even if it's removed later. */
    val medicineName: String,
    /** [MedicineKind] name. */
    val kind: String = MedicineKind.Medicine.name,
    val amount: Double,
    /** [DoseUnit] name. */
    val unit: String,
    val takenAtEpochMs: Long,
    val zoneId: String,
    val createdAtEpochMs: Long = 0,
) {
    val kindEnum: MedicineKind get() = runCatching { MedicineKind.valueOf(kind) }.getOrDefault(MedicineKind.Medicine)
    val unitEnum: DoseUnit get() = runCatching { DoseUnit.valueOf(unit) }.getOrDefault(DoseUnit.Tablet)
}

@Dao
interface MedicineDao {
    @Query("SELECT * FROM medicines WHERE archived = 0 ORDER BY kind DESC, name COLLATE NOCASE")
    fun observeMedicines(): Flow<List<MedicineEntity>>

    @Query("SELECT * FROM medicines WHERE archived = 0")
    suspend fun activeMedicines(): List<MedicineEntity>

    @Query("SELECT * FROM medicines WHERE id = :id")
    suspend fun medicine(id: String): MedicineEntity?

    @Upsert
    suspend fun upsertMedicine(medicine: MedicineEntity)

    @Query("UPDATE medicines SET archived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("SELECT * FROM doses WHERE takenAtEpochMs >= :fromMs AND takenAtEpochMs < :toMs ORDER BY takenAtEpochMs")
    fun observeDosesBetween(fromMs: Long, toMs: Long): Flow<List<DoseEntity>>

    @Query("SELECT * FROM doses WHERE takenAtEpochMs >= :fromMs AND takenAtEpochMs < :toMs ORDER BY takenAtEpochMs")
    suspend fun dosesBetween(fromMs: Long, toMs: Long): List<DoseEntity>

    @Query("SELECT COUNT(*) FROM doses WHERE medicineId = :medicineId AND takenAtEpochMs >= :fromMs AND takenAtEpochMs < :toMs")
    suspend fun countDoses(medicineId: String, fromMs: Long, toMs: Long): Int

    @Query("SELECT * FROM doses WHERE id = :id")
    suspend fun dose(id: String): DoseEntity?

    @Upsert
    suspend fun upsertDose(dose: DoseEntity)

    @Query("DELETE FROM doses WHERE id = :id")
    suspend fun deleteDose(id: String)

    @Query("SELECT * FROM medicines")
    suspend fun allMedicines(): List<MedicineEntity>

    @Query("SELECT * FROM doses")
    suspend fun allDoses(): List<DoseEntity>

    @Query("DELETE FROM medicines")
    suspend fun clearMedicines()

    @Query("DELETE FROM doses")
    suspend fun clearDoses()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedicines(medicines: List<MedicineEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDoses(doses: List<DoseEntity>)
}
