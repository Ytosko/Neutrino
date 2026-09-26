package dev.ytosko.neutrino.data.medicine

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The user's medicines and insulin, and the doses they log. Everything stays on the phone (and in
 * encrypted backups); only MedEx name suggestions go out, while the user types a name.
 */
class MedicineRepository(
    private val dao: MedicineDao,
    val medex: MedexClient,
    /** Doses or the list changed: back up, redraw widgets, re-book reminders. */
    private val onChanged: () -> Unit,
    private val onScheduleChanged: () -> Unit,
) {

    val medicines: Flow<List<MedicineEntity>> = dao.observeMedicines()

    suspend fun activeMedicines(): List<MedicineEntity> = dao.activeMedicines()

    suspend fun medicine(id: String): MedicineEntity? = dao.medicine(id)

    /** Adds or updates one; a new one gets an id here. */
    suspend fun save(medicine: MedicineEntity): MedicineEntity {
        val stored = if (medicine.id.isBlank()) {
            medicine.copy(id = UUID.randomUUID().toString(), createdAtEpochMs = System.currentTimeMillis())
        } else {
            medicine
        }
        dao.upsertMedicine(stored)
        onScheduleChanged()
        onChanged()
        return stored
    }

    /** Takes it off the list; past doses keep showing it. */
    suspend fun remove(id: String) {
        dao.archive(id)
        onScheduleChanged()
        onChanged()
    }

    fun observeDoses(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): Flow<List<DoseEntity>> =
        dao.observeDosesBetween(
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            toInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

    suspend fun dosesBetween(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): List<DoseEntity> =
        dao.dosesBetween(
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            toInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

    suspend fun logDose(medicine: MedicineEntity, amount: Double, at: Instant, zone: ZoneId): DoseEntity {
        val dose = DoseEntity(
            id = UUID.randomUUID().toString(),
            medicineId = medicine.id,
            medicineName = medicine.displayName,
            kind = medicine.kind,
            amount = amount,
            unit = medicine.doseUnit,
            takenAtEpochMs = at.toEpochMilli(),
            zoneId = zone.id,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        dao.upsertDose(dose)
        onChanged()
        return dose
    }

    suspend fun editDose(dose: DoseEntity) {
        dao.upsertDose(dose)
        onChanged()
    }

    /** Returns what was deleted, for undo. */
    suspend fun deleteDose(id: String): DoseEntity? {
        val stored = dao.dose(id) ?: return null
        dao.deleteDose(id)
        onChanged()
        return stored
    }

    suspend fun restoreDose(dose: DoseEntity) {
        dao.upsertDose(dose)
        onChanged()
    }

    /** Whether a dose of [medicineId] was logged in the [minutes] before [now], so a reminder can be skipped. */
    suspend fun takenRecently(medicineId: String, now: Instant, minutes: Long = 90): Boolean =
        dao.countDoses(medicineId, now.minusSeconds(minutes * 60).toEpochMilli(), now.plusSeconds(60).toEpochMilli()) > 0
}
