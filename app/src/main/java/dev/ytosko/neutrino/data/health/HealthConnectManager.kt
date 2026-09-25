package dev.ytosko.neutrino.data.health

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType as HcMealType
import androidx.health.connect.client.records.NutritionRecord
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

/**
 * Health Connect access. Neutrino only ever asks to WRITE nutrition and hydration;
 * it reads no health data (see the privacy policy).
 */
class HealthConnectManager(private val context: Context) {

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
    )

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectAvailability.UpdateRequired
            else -> if (isPlayStoreInstalled()) HealthConnectAvailability.NotInstalled else HealthConnectAvailability.NotSupported
        }

    private val client: HealthConnectClient?
        get() = if (availability() == HealthConnectAvailability.Available) HealthConnectClient.getOrCreate(context) else null

    suspend fun hasAllPermissions(): Boolean = grantedPermissions().containsAll(permissions)

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
    }

    /** Retries deletes that failed while Health Connect was unavailable. Returns how many went through. */
    suspend fun retryPendingDeletes(): Int {
        if (!hasAllPermissions()) return 0
        var done = 0
        pending.all().forEach { key ->
            val ok = when {
                key.startsWith(MEAL_PREFIX) -> delete(NutritionRecord::class, key.removePrefix(MEAL_PREFIX))
                key.startsWith(WATER_PREFIX) -> delete(HydrationRecord::class, key.removePrefix(WATER_PREFIX))
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
