package dev.ytosko.neutrino.data.glucose

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.health.connect.client.records.BloodGlucoseRecord
import dev.ytosko.neutrino.data.health.HealthConnectManager
import dev.ytosko.neutrino.data.meal.MealDatabase
import dev.ytosko.neutrino.glucose.MealFlag
import dev.ytosko.neutrino.glucose.MeterSync
import dev.ytosko.neutrino.glucose.MeterSyncException
import dev.ytosko.neutrino.glucose.RangeFlag
import dev.ytosko.neutrino.glucose.ReadingResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Context.meterStore: DataStore<Preferences> by preferencesDataStore(name = "glucose_meter")

/** The paired meter and what the last sync learned about it. */
data class PairedMeter(
    val address: String,
    val name: String?,
    val model: String?,
    val serial: String?,
    val associationId: Int?,
    val lastSequence: Int?,
    val lastSyncAt: Long?,
    /** Seconds to add to the meter's clock to get real time, at the last sync. */
    val clockOffsetSeconds: Long?,
    val clockWritable: Boolean?,
    /** When Neutrino last set the meter's clock. */
    val clockSetAt: Long?,
    val lastProblem: String?,
)

sealed interface MeterSyncOutcome {
    data class Synced(val newReadings: Int, val clockWasSet: Boolean) : MeterSyncOutcome
    data object NoMeter : MeterSyncOutcome
    data object BluetoothOff : MeterSyncOutcome
    data object NoPermission : MeterSyncOutcome
    data object NotPaired : MeterSyncOutcome
    data class Failed(val reason: String) : MeterSyncOutcome
}

/**
 * Blood glucose readings: the paired meter, syncing, storage, edits and Health Connect.
 *
 * Readings are sensitive health data. They're stored on the phone, included in the user's
 * encrypted backups and written to Health Connect (write-only). Values are never logged.
 */
@SuppressLint("MissingPermission")
class GlucoseRepository(
    private val context: Context,
    private val db: MealDatabase,
    private val healthConnect: HealthConnectManager,
    private val onChanged: () -> Unit = {},
) {
    private val store = context.applicationContext.meterStore
    private val syncLock = Mutex()
    private val hcLock = Mutex()
    private val bluetooth get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private object Keys {
        val address = stringPreferencesKey("address")
        val name = stringPreferencesKey("name")
        val model = stringPreferencesKey("model")
        val serial = stringPreferencesKey("serial")
        val associationId = intPreferencesKey("association_id")
        val lastSequence = intPreferencesKey("last_sequence")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val clockOffset = longPreferencesKey("clock_offset_s")
        val clockWritable = booleanPreferencesKey("clock_writable")
        val clockSetAt = longPreferencesKey("clock_set_at")
        val lastProblem = stringPreferencesKey("last_problem")
    }

    private val prefs: Flow<Preferences> = store.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    val meter: Flow<PairedMeter?> = prefs.map { p ->
        val address = p[Keys.address] ?: return@map null
        PairedMeter(
            address = address,
            name = p[Keys.name],
            model = p[Keys.model],
            serial = p[Keys.serial],
            associationId = p[Keys.associationId],
            lastSequence = p[Keys.lastSequence],
            lastSyncAt = p[Keys.lastSyncAt],
            clockOffsetSeconds = p[Keys.clockOffset],
            clockWritable = p[Keys.clockWritable],
            clockSetAt = p[Keys.clockSetAt],
            lastProblem = p[Keys.lastProblem],
        )
    }

    // ---- Readings -------------------------------------------------------------------------------

    fun observeBetween(from: LocalDate, toInclusive: LocalDate, zone: ZoneId): Flow<List<GlucoseEntity>> =
        db.glucose().observeBetween(
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            toInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        )

    /** Changes a reading's meal relation and/or time; Health Connect gets the new version. */
    suspend fun edit(id: String, relation: GlucoseRelation, time: Instant) {
        val current = db.glucose().get(id) ?: return
        val updated = current.copy(
            relation = relation.name,
            measuredAtEpochMs = time.toEpochMilli(),
            timeEstimated = current.timeEstimated && time.toEpochMilli() == current.measuredAtEpochMs,
            edited = true,
            syncedToHealthConnect = false,
        )
        db.glucose().update(updated)
        pushToHealthConnect(updated)
        onChanged()
    }

    suspend fun delete(id: String): GlucoseEntity? {
        val current = db.glucose().get(id) ?: return null
        db.glucose().delete(id)
        healthConnect.deleteGlucose(id)
        onChanged()
        return current
    }

    suspend fun restore(reading: GlucoseEntity) {
        healthConnect.cancelPendingDelete(reading.id)
        db.glucose().insertAll(listOf(reading.copy(syncedToHealthConnect = false)))
        pushToHealthConnect(reading)
        onChanged()
    }

    /** Sends readings that haven't reached Health Connect yet. Returns how many were sent. */
    suspend fun syncWithHealthConnect(): Int = hcLock.withLock {
        if (!runCatching { healthConnect.hasGlucosePermission() }.getOrDefault(false)) return 0
        db.glucose().unsynced().count { pushToHealthConnect(it) }
    }

    private suspend fun pushToHealthConnect(reading: GlucoseEntity): Boolean {
        val ok = runCatching {
            healthConnect.writeGlucose(
                id = reading.id,
                mmolPerL = reading.mmolPerL,
                time = Instant.ofEpochMilli(reading.measuredAtEpochMs),
                zone = runCatching { ZoneId.of(reading.zoneId) }.getOrDefault(ZoneId.systemDefault()),
                relationToMeal = reading.relationEnum.toHealthConnect(),
                fromMeter = reading.meterSerial != null || reading.meterSequence != null,
                meterModel = meter.first()?.model,
            )
        }.getOrDefault(false)
        if (ok) db.glucose().markSynced(reading.id)
        return ok
    }

    // ---- The meter ------------------------------------------------------------------------------

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothOn(): Boolean = runCatching { bluetooth?.isEnabled == true }.getOrDefault(false)

    fun device(address: String): BluetoothDevice? = runCatching { bluetooth?.getRemoteDevice(address) }.getOrNull()

    suspend fun savePairing(address: String, name: String?, associationId: Int?) {
        store.edit {
            it.clear()
            it[Keys.address] = address
            if (name != null) it[Keys.name] = name
            if (associationId != null) it[Keys.associationId] = associationId
        }
    }

    /** Forgets the meter. Readings already saved stay. */
    suspend fun forgetMeter() {
        store.edit { it.clear() }
    }

    /**
     * Downloads new readings from the paired meter. Only one sync runs at a time. [zone] is the
     * phone's time zone, used to place readings on the right day.
     */
    suspend fun sync(zone: ZoneId = ZoneId.systemDefault()): MeterSyncOutcome = syncLock.withLock {
        val meter = meter.first() ?: return MeterSyncOutcome.NoMeter
        if (!hasBluetoothPermission()) return MeterSyncOutcome.NoPermission
        if (!isBluetoothOn()) return MeterSyncOutcome.BluetoothOff
        val device = device(meter.address) ?: return MeterSyncOutcome.Failed("Unknown meter address")
        if (device.bondState != BluetoothDevice.BOND_BONDED) return recordProblem(MeterSyncOutcome.NotPaired)

        val link = try {
            AndroidMeterLink.connect(context, device)
        } catch (e: MeterSyncException) {
            return recordProblem(MeterSyncOutcome.Failed(e.message ?: "Couldn't connect"))
        }
        val result = try {
            MeterSync.run(link, meter.lastSequence, phoneNow = { LocalDateTime.now(zone) })
        } catch (_: MeterNotPairedException) {
            return recordProblem(MeterSyncOutcome.NotPaired)
        } catch (e: MeterSyncException) {
            return recordProblem(MeterSyncOutcome.Failed(e.message ?: "Sync failed"))
        } finally {
            link.close()
        }

        val resolution = ReadingResolver.resolve(result.records, result.clock, LocalDateTime.now(zone))
        val key = (result.info.serial ?: meter.serial ?: meter.address).filter { it.isLetterOrDigit() }
        val now = System.currentTimeMillis()
        var added = 0
        resolution.readings.forEach { reading ->
            val stamp = result.records.first { it.measurement.sequence == reading.sequence }.measurement.meterTime
            val entity = GlucoseEntity(
                // Sequence + the meter's own timestamp: stable across re-downloads, unique even if the
                // meter's numbering restarts.
                id = "meter-$key-${reading.sequence}-${stamp.format(ID_STAMP)}",
                mmolPerL = reading.mmolPerL,
                measuredAtEpochMs = reading.time.atZone(zone).toInstant().toEpochMilli(),
                zoneId = zone.id,
                relation = reading.meal.toRelation().name,
                timeEstimated = reading.timeEstimated,
                rangeFlag = reading.range.takeIf { it != RangeFlag.None }?.name,
                meterSerial = result.info.serial,
                meterSequence = reading.sequence,
                createdAtEpochMs = now,
            )
            if (db.glucose().insert(entity) != -1L) {
                added++
                pushToHealthConnect(entity)
            }
        }

        store.edit { p ->
            result.records.maxOfOrNull { it.measurement.sequence }?.let { p[Keys.lastSequence] = it }
            p[Keys.lastSyncAt] = now
            result.clock?.let { p[Keys.clockOffset] = if (result.clockWasSet) 0 else it.offsetSeconds }
            p[Keys.clockWritable] = result.clockWritable
            if (result.clockWasSet) p[Keys.clockSetAt] = now
            result.info.model?.let { p[Keys.model] = it }
            result.info.serial?.let { p[Keys.serial] = it }
            p.remove(Keys.lastProblem)
        }
        if (added > 0) onChanged()
        MeterSyncOutcome.Synced(added, result.clockWasSet)
    }

    private suspend fun recordProblem(outcome: MeterSyncOutcome): MeterSyncOutcome {
        val text = when (outcome) {
            MeterSyncOutcome.NotPaired -> "not_paired"
            is MeterSyncOutcome.Failed -> "failed"
            else -> null
        }
        store.edit { if (text != null) it[Keys.lastProblem] = text }
        return outcome
    }

    private companion object {
        val ID_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    }
}

val GlucoseEntity.relationEnum: GlucoseRelation
    get() = runCatching { GlucoseRelation.valueOf(relation) }.getOrDefault(GlucoseRelation.General)

fun MealFlag.toRelation(): GlucoseRelation = when (this) {
    MealFlag.BeforeMeal -> GlucoseRelation.BeforeMeal
    MealFlag.AfterMeal -> GlucoseRelation.AfterMeal
    MealFlag.Fasting -> GlucoseRelation.Fasting
    MealFlag.Bedtime -> GlucoseRelation.Bedtime
    MealFlag.None, MealFlag.Casual -> GlucoseRelation.General
}

/** Health Connect has no "bedtime"; it becomes "general" there and stays "bedtime" in Neutrino. */
fun GlucoseRelation.toHealthConnect(): Int = when (this) {
    GlucoseRelation.General, GlucoseRelation.Bedtime -> BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL
    GlucoseRelation.Fasting -> BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
    GlucoseRelation.BeforeMeal -> BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL
    GlucoseRelation.AfterMeal -> BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL
}
