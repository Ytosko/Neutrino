package dev.ytosko.neutrino.data.health

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord

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

    suspend fun hasAllPermissions(): Boolean =
        client?.permissionController?.getGrantedPermissions()?.containsAll(permissions) ?: false

    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

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
    }
}
