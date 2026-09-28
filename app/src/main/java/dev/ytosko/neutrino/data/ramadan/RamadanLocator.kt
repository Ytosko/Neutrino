package dev.ytosko.neutrino.data.ramadan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import dev.ytosko.neutrino.data.reminders.DoseReminders
import dev.ytosko.neutrino.data.reminders.MealReminders
import dev.ytosko.neutrino.data.settings.SettingsRepository
import dev.ytosko.neutrino.domain.RamadanPlaces
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import kotlin.coroutines.resume

/**
 * Ramadan mode's "Use my location": finds roughly where the phone is (approximate location, from the
 * phone itself, so a VPN makes no difference) and, when that's a new place, fetches its Ramadan
 * times and re-books the reminders. The location never leaves the phone except as the rounded
 * coordinates sent to Ummah API for the times.
 */
class RamadanLocator(
    private val context: Context,
    private val settings: SettingsRepository,
    private val sync: RamadanSync,
) {
    fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** True when the place changed (and the new schedule was fetched). */
    suspend fun refresh(): Boolean {
        val s = settings.settings.first()
        if (!s.ramadan || !s.ramadanUsesLocation || !allowed()) return false
        val location = locate() ?: return false
        val place = RamadanPlaces.here(location.latitude, location.longitude, ZoneId.systemDefault().id)
        if (place.id == s.ramadanCity?.id && place.zone == s.ramadanCity.zone) return false
        settings.setRamadanCity(place)
        runCatching { sync.ensure() }
        if (settings.settings.first().remindersEnabled) MealReminders.scheduleAll(context)
        DoseReminders.sync(context)
        return true
    }

    /** A recent known position if there is one (under an hour old), else a fresh one (up to 15 s). */
    @Suppress("MissingPermission")
    private suspend fun locate(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = manager.getProviders(true)
        val known = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (known != null && System.currentTimeMillis() - known.time < 60 * 60 * 1000L) return known
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return known
        val provider = listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.GPS_PROVIDER)
            .firstOrNull { it in providers } ?: return known
        val fresh = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                runCatching {
                    manager.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
                }.onFailure { cont.resume(null) }
            }
        }
        return fresh ?: known
    }
}
