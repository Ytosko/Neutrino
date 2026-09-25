package dev.ytosko.neutrino.data.glucose

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import dev.ytosko.neutrino.appContainer
import dev.ytosko.neutrino.glucose.GlucoseUuids

/**
 * Second way to notice the meter after a test, alongside the companion device wake-up.
 *
 * Android's companion wake-up watches the meter's exact Bluetooth address. Meters that advertise
 * with a changing (private) address are never recognised that way, so Neutrino also keeps a
 * low-power background scan that the phone's Bluetooth chip filters for the Glucose service. When
 * a glucose meter starts advertising, Android wakes Neutrino, which then connects to the paired
 * meter. Needs the "nearby devices" permission (Android 12+), never location.
 */
@SuppressLint("MissingPermission")
object MeterScan {

    private fun canScan(context: Context) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, MeterScanReceiver::class.java),
        // Mutable: Android adds the scan results to it.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Starts (or restarts) the background scan. Safe to call repeatedly. */
    fun start(context: Context) {
        if (!canScan(context)) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled } ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(GlucoseUuids.GLUCOSE_SERVICE)).build())
        val intent = pendingIntent(context)
        runCatching { scanner.stopScan(intent) }
        // Every matching advert wakes us (the filter runs in the Bluetooth chip, so this costs nothing
        // until a meter advertises); repeat wake-ups within one meter visit are ignored later.
        // "First match" would be leaner but isn't reliable on every chip, so it isn't used.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        runCatching { scanner.startScan(filters, settings, intent) }
    }

    fun stop(context: Context) {
        if (!canScan(context)) return
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(pendingIntent(context)) }
    }
}

/** Woken by the background scan when a glucose meter starts advertising nearby. */
class MeterScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.applicationContext.appContainer.syncMeterInBackground()
    }
}
