package dev.ytosko.neutrino.data.health

import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.records.BloodGlucoseRecord
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType as HcMealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import dev.ytosko.neutrino.domain.MealType
import dev.ytosko.neutrino.domain.Nutrition
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class HealthConnectAvailability { Available, NotInstalled, UpdateRequired, NotSupported }

/** What Neutrino can write to Health Connect; each is allowed (or not) separately. */
enum class HealthKind { Nutrition, Hydration, Glucose }

/** A blood glucose record another app saved to Health Connect. */
data class ImportedGlucose(
    val id: String,
    val time: Instant,
    val zone: ZoneId?,
    val mmolPerL: Double,
    val relation: dev.ytosko.neutrino.data.glucose.GlucoseRelation,
    val sourcePackage: String,
)

/**
 * Health Connect access. Neutrino asks to WRITE nutrition, hydration and blood glucose. It reads
 * nothing, unless the user turns on glucose import: then it asks to READ blood glucose only, and
 * only ever keeps readings other apps made (see the privacy policy).
 */
class HealthConnectManager(private val context: Context) {

    fun permissionFor(kind: HealthKind): String = when (kind) {
        HealthKind.Nutrition -> HealthPermission.getWritePermission(NutritionRecord::class)
        HealthKind.Hydration -> HealthPermission.getWritePermission(HydrationRecord::class)
        HealthKind.Glucose -> HealthPermission.getWritePermission(BloodGlucoseRecord::class)
    }

    /** Everything Neutrino asks for when connecting: meals, water and glucose. */
    val permissions: Set<String> = HealthKind.entries.mapTo(LinkedHashSet(), ::permissionFor)

    /** What meals and water need. */
    private val mealPermissions: Set<String> = setOf(permissionFor(HealthKind.Nutrition), permissionFor(HealthKind.Hydration))

    val glucosePermission: String = permissionFor(HealthKind.Glucose)

    suspend fun hasGlucosePermission(): Boolean = glucosePermission in grantedPermissions()

    /** Only asked for when the user turns on "Import from other glucose apps". */
    val glucoseReadPermission: String = HealthPermission.getReadPermission(BloodGlucoseRecord::class)

    suspend fun canReadGlucose(): Boolean = glucoseReadPermission in grantedPermissions()

    /** Blood glucose other apps saved between [since] and [until]; empty without the read permission. */
    suspend fun readOtherAppsGlucose(since: Instant, until: Instant): List<ImportedGlucose> {
        val client = client ?: return emptyList()
        if (!canReadGlucose()) return emptyList()
        val out = mutableListOf<ImportedGlucose>()
        var page: String? = null
        do {
            val response = client.readRecords(
                androidx.health.connect.client.request.ReadRecordsRequest(
                    recordType = BloodGlucoseRecord::class,
                    timeRangeFilter = androidx.health.connect.client.time.TimeRangeFilter.between(since, until),
                    pageSize = 1000,
                    pageToken = page,
                ),
            )
            response.records
                .filter { it.metadata.dataOrigin.packageName != context.packageName }
                .mapTo(out) {
                    ImportedGlucose(
                        id = it.metadata.id,
                        time = it.time,
                        zone = it.zoneOffset,
                        mmolPerL = it.level.inMillimolesPerLiter,
                        relation = when (it.relationToMeal) {
                            BloodGlucoseRecord.RELATION_TO_MEAL_FASTING -> dev.ytosko.neutrino.data.glucose.GlucoseRelation.Fasting
                            BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL -> dev.ytosko.neutrino.data.glucose.GlucoseRelation.BeforeMeal
                            BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL -> dev.ytosko.neutrino.data.glucose.GlucoseRelation.AfterMeal
                            else -> dev.ytosko.neutrino.data.glucose.GlucoseRelation.General
                        },
                        sourcePackage = it.metadata.dataOrigin.packageName,
                    )
                }
            page = response.pageToken
        } while (!page.isNullOrEmpty())
        return out
    }

    /** Which kinds are allowed right now; empty when Health Connect isn't available. */
    suspend fun grantedKinds(): Set<HealthKind> {
        val granted = grantedPermissions()
        return HealthKind.entries.filterTo(LinkedHashSet()) { permissionFor(it) in granted }
    }

    /** Meals and water are going to Health Connect but glucose readings aren't allowed to. */
    suspend fun glucoseLeftOut(): Boolean {
        val kinds = grantedKinds()
        return HealthKind.Glucose !in kinds && (HealthKind.Nutrition in kinds || HealthKind.Hydration in kinds)
    }

    /**
     * Writes one blood glucose reading (capillary blood, from a finger-prick meter). Writing the same
     * [id] again with a higher [version] replaces it, e.g. after the user changes its meal relation.
     */
    suspend fun writeGlucose(
        id: String,
        mmolPerL: Double,
        time: Instant,
        zone: ZoneId,
        relationToMeal: Int,
        fromMeter: Boolean,
        meterModel: String? = null,
        version: Long = System.currentTimeMillis(),
    ): Boolean {
        val client = client ?: return false
        if (!hasGlucosePermission()) return false
        // Meter readings are recorded by a device; readings typed in by hand would be manual entries.
        val metadata = if (fromMeter) {
            Metadata.autoRecorded(Device(model = meterModel, type = Device.TYPE_UNKNOWN), clientRecordId = id, clientRecordVersion = version)
        } else {
            Metadata.manualEntry(clientRecordId = id, clientRecordVersion = version)
        }
        client.insertRecords(
            listOf(
                BloodGlucoseRecord(
                    time = time,
                    zoneOffset = zone.rules.getOffset(time),
                    metadata = metadata,
                    level = BloodGlucose.millimolesPerLiter(mmolPerL),
                    specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD,
                    relationToMeal = relationToMeal,
                ),
            ),
        )
        return true
    }

    suspend fun deleteGlucose(id: String) {
        if (!delete(BloodGlucoseRecord::class, id)) pending.add(GLUCOSE_PREFIX + id)
    }

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
            else -> if (isPlayStoreInstalled()) HealthConnectAvailability.NotInstalled else HealthConnectAvailability.NotSupported
        }

    private val client: HealthConnectClient?
        get() = if (availability() == HealthConnectAvailability.Available) HealthConnectClient.getOrCreate(context) else null

    suspend fun hasMealPermissions(): Boolean = grantedPermissions().containsAll(mealPermissions)

    private suspend fun grantedPermissions(): Set<String> =
        client?.permissionController?.getGrantedPermissions() ?: emptySet()

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    /**
     * Writes one meal. [id] becomes the clientRecordId so the record can be found and deleted later;
     * writing the same [id] again with a higher [version] replaces it (used when a meal is edited).
     * Returns false (without throwing) if Health Connect is unavailable or permission is missing.
     */
    suspend fun writeMeal(
        id: String,
        name: String,
        nutrition: Nutrition,
        mealType: MealType,
        eatenAt: Instant,
        zone: ZoneId,
        version: Long = System.currentTimeMillis(),
    ): Boolean {
        val client = client ?: return false
        if (HealthPermission.getWritePermission(NutritionRecord::class) !in grantedPermissions()) return false
        val offset = zone.rules.getOffset(eatenAt)
        val record = NutritionRecord(
            startTime = eatenAt,
            startZoneOffset = offset,
            endTime = eatenAt.plus(MEAL_DURATION),
            endZoneOffset = zone.rules.getOffset(eatenAt.plus(MEAL_DURATION)),
            metadata = Metadata.manualEntry(clientRecordId = id, clientRecordVersion = version),
            name = name,
            mealType = mealType.toHealthConnect(),
            energy = Energy.kilocalories(nutrition.calories),
            protein = Mass.grams(nutrition.proteinG),
            totalCarbohydrate = Mass.grams(nutrition.carbsG),
            totalFat = Mass.grams(nutrition.fatG),
        )
        client.insertRecords(listOf(record))
        return true
    }

    suspend fun writeWater(id: String, amountMl: Int, loggedAt: Instant, zone: ZoneId): Boolean {
        val client = client ?: return false
        if (HealthPermission.getWritePermission(HydrationRecord::class) !in grantedPermissions()) return false
        val end = loggedAt.plusSeconds(1)
        client.insertRecords(
            listOf(
                HydrationRecord(
                    startTime = loggedAt,
                    startZoneOffset = zone.rules.getOffset(loggedAt),
                    endTime = end,
                    endZoneOffset = zone.rules.getOffset(end),
                    metadata = Metadata.manualEntry(clientRecordId = id),
                    volume = Volume.milliliters(amountMl.toDouble()),
                ),
            ),
        )
        return true
    }

    /**
     * Deletes a record Neutrino wrote. If Health Connect can't be reached (not connected, no
     * permission), the delete is remembered and [retryPendingDeletes] finishes it later, so a meal
     * removed in Neutrino never lingers in Health Connect.
     */
    suspend fun deleteMeal(id: String) {
        if (!delete(NutritionRecord::class, id)) pending.add(MEAL_PREFIX + id)
    }

    suspend fun deleteWater(id: String) {
        if (!delete(HydrationRecord::class, id)) pending.add(WATER_PREFIX + id)
    }

    /** Takes a delete off the retry list, e.g. when Undo puts the meal back before it ran. */
    fun cancelPendingDelete(id: String) {
        pending.remove(MEAL_PREFIX + id)
        pending.remove(WATER_PREFIX + id)
        pending.remove(GLUCOSE_PREFIX + id)
    }

    /** Retries deletes that failed while Health Connect was unavailable. Returns how many went through. */
    suspend fun retryPendingDeletes(): Int {
        if (!hasMealPermissions()) return 0
        var done = 0
        pending.all().forEach { key ->
            val ok = when {
                key.startsWith(MEAL_PREFIX) -> delete(NutritionRecord::class, key.removePrefix(MEAL_PREFIX))
                key.startsWith(WATER_PREFIX) -> delete(HydrationRecord::class, key.removePrefix(WATER_PREFIX))
                key.startsWith(GLUCOSE_PREFIX) -> delete(BloodGlucoseRecord::class, key.removePrefix(GLUCOSE_PREFIX))
                else -> true
            }
            if (ok) {
                pending.remove(key)
                done++
            }
        }
        return done
    }

    private suspend fun delete(type: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>, id: String): Boolean {
        val client = client ?: return false
        return runCatching { client.deleteRecords(type, recordIdsList = emptyList(), clientRecordIdsList = listOf(id)) }.isSuccess
    }

    private val pending = PendingDeletes(context)

    /** Opens Health Connect in the Play Store (install or update). */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, "market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding".toUri())
            .setPackage("com.android.vending")
            .putExtra("overlay", true)
            .putExtra("callerId", context.packageName)

    /** Opens Health Connect's own settings, where users can manage Neutrino's permissions. */
    fun settingsIntent(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    private fun isPlayStoreInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.android.vending", 0)
    }.isSuccess

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        private val MEAL_DURATION: Duration = Duration.ofMinutes(1)
    }
}

internal fun MealType.toHealthConnect(): Int = when (this) {
    MealType.Breakfast -> HcMealType.MEAL_TYPE_BREAKFAST
    MealType.Lunch -> HcMealType.MEAL_TYPE_LUNCH
    MealType.Dinner -> HcMealType.MEAL_TYPE_DINNER
    MealType.Snack -> HcMealType.MEAL_TYPE_SNACK
}

private const val MEAL_PREFIX = "meal:"
private const val WATER_PREFIX = "water:"
private const val GLUCOSE_PREFIX = "glucose:"

/** Health Connect deletes still to do, kept across restarts. Only record ids, nothing else. */
private class PendingDeletes(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hc_pending_deletes", Context.MODE_PRIVATE)

    @Synchronized fun all(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    @Synchronized fun add(key: String) = prefs.edit().putStringSet(KEY, all() + key).apply()

    @Synchronized fun remove(key: String) = prefs.edit().putStringSet(KEY, all() - key).apply()

    private companion object {
        const val KEY = "keys"
    }
}
