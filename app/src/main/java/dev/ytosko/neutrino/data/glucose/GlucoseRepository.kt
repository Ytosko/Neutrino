package dev.ytosko.neutrino.data.glucose

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private val Context.meterStore: DataStore<Preferences> by preferencesDataStore(name = "glucose_meter")

/** A paired meter and what the last sync learned about it. [id] is its Bluetooth address. */
@Serializable
data class PairedMeter(
    val id: String,
    val address: String,
    /** [MeterModel] name the user chose when adding it. */
    val modelKey: String = MeterModel.ContourPlusElite.name,
    /** Bluetooth name, as advertised. */
    val name: String? = null,
    /** Model string the meter reports. */
    val model: String? = null,
    val serial: String? = null,
    val associationId: Int? = null,
    val lastSequence: Int? = null,
    val lastSyncAt: Long? = null,
    /** Seconds to add to the meter's clock to get real time, at the last sync. */
    val clockOffsetSeconds: Long? = null,
    val clockWritable: Boolean? = null,
    /** When Neutrino last set the meter's clock. */
    val clockSetAt: Long? = null,
    val lastProblem: String? = null,
    val addedAt: Long = 0,
) {
    val meterModel: MeterModel get() = MeterModel.fromKey(modelKey)
}

sealed interface MeterSyncOutcome {
    data class Synced(val newReadings: Int, val clockWasSet: Boolean) : MeterSyncOutcome
    data object NoMeter : MeterSyncOutcome
    data object BluetoothOff : MeterSyncOutcome
    data object NoPermission : MeterSyncOutcome
    data object NotPaired : MeterSyncOutcome
    data class Failed(val reason: String) : MeterSyncOutcome
}

sealed interface AddMeterResult {
    data class Added(val meter: PairedMeter) : AddMeterResult
    data object AlreadyAdded : AddMeterResult
    data object LimitReached : AddMeterResult
}

/**
 * Blood glucose readings: the paired meters, syncing, storage, edits and Health Connect.
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
    /** One sync at a time per meter; different meters may sync side by side. */
    private val syncLocks = ConcurrentHashMap<String, Mutex>()
    private val hcLock = Mutex()
    private val bluetooth get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val meters = stringPreferencesKey("meters")

        // The single meter stored before several meters were supported; moved into [meters] on first read.
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

    private fun decode(p: Preferences): List<PairedMeter> {
        p[Keys.meters]?.let { text -> return runCatching { json.decodeFromString<List<PairedMeter>>(text) }.getOrDefault(emptyList()) }
        val address = p[Keys.address] ?: return emptyList()
        return listOf(
            PairedMeter(
                id = address,
                address = address,
                modelKey = MeterModel.guess(p[Keys.model], p[Keys.name]).name,
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
            ),
        )
    }

    private fun MutablePreferences.write(meters: List<PairedMeter>) {
        this[Keys.meters] = json.encodeToString(meters)
        listOf(
            Keys.address, Keys.name, Keys.model, Keys.serial, Keys.lastProblem,
        ).forEach { remove(it) }
        remove(Keys.associationId)
        remove(Keys.lastSequence)
        remove(Keys.lastSyncAt)
        remove(Keys.clockOffset)
        remove(Keys.clockWritable)
        remove(Keys.clockSetAt)
    }

    private suspend fun editMeters(change: (List<PairedMeter>) -> List<PairedMeter>) {
        store.edit { p -> p.write(change(decode(p))) }
    }

    private suspend fun updateMeter(id: String, change: (PairedMeter) -> PairedMeter) =
        editMeters { list -> list.map { if (it.id == id) change(it) else it } }

    /** Paired meters, oldest first. */
    val meters: Flow<List<PairedMeter>> = prefs.map(::decode).distinctUntilChanged()

    fun meter(id: String): Flow<PairedMeter?> = meters.map { list -> list.firstOrNull { it.id == id } }.distinctUntilChanged()

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
        val source = reading.meterSerial?.let { serial -> meters.first().firstOrNull { it.serial == serial } }
        val ok = runCatching {
            healthConnect.writeGlucose(
                id = reading.id,
                mmolPerL = reading.mmolPerL,
                time = Instant.ofEpochMilli(reading.measuredAtEpochMs),
                zone = runCatching { ZoneId.of(reading.zoneId) }.getOrDefault(ZoneId.systemDefault()),
                relationToMeal = reading.relationEnum.toHealthConnect(),
                fromMeter = reading.meterSerial != null || reading.meterSequence != null,
                meterModel = source?.meterModel?.displayName,
            )
        }.getOrDefault(false)
        if (ok) db.glucose().markSynced(reading.id)
        return ok
    }

    // ---- Meters ---------------------------------------------------------------------------------

    fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothOn(): Boolean = runCatching { bluetooth?.isEnabled == true }.getOrDefault(false)

    fun device(address: String): BluetoothDevice? = runCatching { bluetooth?.getRemoteDevice(address) }.getOrNull()

    suspend fun canAddMeter(): Boolean = meters.first().size < MAX_METERS

    suspend fun isAdded(address: String): Boolean = meters.first().any { it.address.equals(address, ignoreCase = true) }

    /** Remembers a newly paired meter, up to [MAX_METERS]. */
    suspend fun addMeter(address: String, name: String?, associationId: Int?, model: MeterModel): AddMeterResult {
        var result: AddMeterResult = AddMeterResult.LimitReached
        editMeters { list ->
            when {
                list.any { it.address.equals(address, ignoreCase = true) } -> {
                    result = AddMeterResult.AlreadyAdded
                    list
                }
                list.size >= MAX_METERS -> list
                else -> {
                    val meter = PairedMeter(
                        id = address, address = address, modelKey = model.name, name = name,
                        associationId = associationId, addedAt = System.currentTimeMillis(),
                    )
                    result = AddMeterResult.Added(meter)
                    list + meter
                }
            }
        }
        return result
    }

    /** After pairing again, the old "not paired" warning no longer applies. */
    suspend fun clearProblem(id: String) = updateMeter(id) { it.copy(lastProblem = null) }

    /** Forgets a meter. Readings already saved stay. */
    suspend fun forgetMeter(id: String) {
        editMeters { list -> list.filterNot { it.id == id } }
    }

    /**
     * Downloads new readings from one meter. [zone] is the phone's time zone, used to place
     * readings on the right day.
     */
    suspend fun sync(meterId: String, zone: ZoneId = ZoneId.systemDefault()): MeterSyncOutcome =
        syncLocks.getOrPut(meterId) { Mutex() }.withLock {
            val meter = meters.first().firstOrNull { it.id == meterId } ?: return MeterSyncOutcome.NoMeter
            if (!hasBluetoothPermission()) return MeterSyncOutcome.NoPermission
            if (!isBluetoothOn()) return MeterSyncOutcome.BluetoothOff
            val device = device(meter.address) ?: return MeterSyncOutcome.Failed("Unknown meter address")
            if (device.bondState != BluetoothDevice.BOND_BONDED) return recordProblem(meterId, MeterSyncOutcome.NotPaired)

            val link = try {
                AndroidMeterLink.connect(context, device)
            } catch (e: MeterSyncException) {
                return recordProblem(meterId, MeterSyncOutcome.Failed(e.message ?: "Couldn't connect"))
            }
            val result = try {
                MeterSync.run(link, meter.lastSequence, phoneNow = { LocalDateTime.now(zone) })
            } catch (_: MeterNotPairedException) {
                return recordProblem(meterId, MeterSyncOutcome.NotPaired)
            } catch (e: MeterSyncException) {
                return recordProblem(meterId, MeterSyncOutcome.Failed(e.message ?: "Sync failed"))
            } finally {
                link.close()
            }

            val resolution = ReadingResolver.resolve(result.records, result.clock, LocalDateTime.now(zone))
            val key = (result.info.serial ?: meter.serial ?: meter.address).filter { it.isLetterOrDigit() }
            val now = System.currentTimeMillis()
            // Save the meter's details first, so the readings' Health Connect entries can name it.
            updateMeter(meterId) { m ->
                m.copy(
                    lastSequence = result.records.maxOfOrNull { it.measurement.sequence } ?: m.lastSequence,
                    lastSyncAt = now,
                    clockOffsetSeconds = result.clock?.let { if (result.clockWasSet) 0 else it.offsetSeconds } ?: m.clockOffsetSeconds,
                    clockWritable = result.clockWritable,
                    clockSetAt = if (result.clockWasSet) now else m.clockSetAt,
                    model = result.info.model ?: m.model,
                    serial = result.info.serial ?: m.serial,
                    lastProblem = null,
                )
            }
            // The same physical meter added twice (e.g. paired again under a new address): keep this one.
            result.info.serial?.let { serial -> editMeters { list -> list.filterNot { it.id != meterId && it.serial == serial } } }

            var added = 0
            resolution.readings.forEach { reading ->
                val stamp = result.records.first { it.measurement.sequence == reading.sequence }.measurement.meterTime
                val entity = GlucoseEntity(
                    // Serial + sequence + the meter's own timestamp: stable across re-downloads, unique
                    // across meters, and unique even if a meter's numbering restarts.
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
            if (added > 0) onChanged()
            MeterSyncOutcome.Synced(added, result.clockWasSet)
        }

    private suspend fun recordProblem(meterId: String, outcome: MeterSyncOutcome): MeterSyncOutcome {
        val text = when (outcome) {
            MeterSyncOutcome.NotPaired -> PROBLEM_NOT_PAIRED
            is MeterSyncOutcome.Failed -> "failed"
            else -> null
        }
        if (text != null) updateMeter(meterId) { it.copy(lastProblem = text) }
        return outcome
    }

    companion object {
        const val MAX_METERS = 5
        const val PROBLEM_NOT_PAIRED = "not_paired"
        private val ID_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
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
