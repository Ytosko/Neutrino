package dev.ytosko.neutrino.data.glucose

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.ytosko.neutrino.MainActivity
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.appContainer
import dev.ytosko.neutrino.data.reminders.MealReminders
import dev.ytosko.neutrino.glucose.GlucoseUuids
import java.util.concurrent.Executor

/** What the system meter picker returned. */
data class PickedMeter(val address: String, val name: String?, val associationId: Int?)

/**
 * Pairing through Android's companion device manager: the system shows only nearby Bluetooth
 * glucose meters, and once associated, Android wakes Neutrino when the meter appears (after a test)
 * even if the app hasn't been opened for days. No location permission is needed for this.
 */
@SuppressLint("MissingPermission")
object MeterCompanion {

    private fun manager(context: Context) = context.getSystemService(CompanionDeviceManager::class.java)

    private fun request(): AssociationRequest = AssociationRequest.Builder()
        .addDeviceFilter(
            BluetoothLeDeviceFilter.Builder()
                .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(GlucoseUuids.GLUCOSE_SERVICE)).build())
                .build(),
        )
        .setSingleDevice(false)
        .build()

    /** Opens the system picker. [onChooser] gets the IntentSender to launch; [onError] a message. */
    fun startPicker(context: Context, onChooser: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val cdm = manager(context) ?: return onError("This phone can't pair devices")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.associate(request(), Executor { it.run() }, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) = Unit
                override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "No meter found")
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request(), object : CompanionDeviceManager.Callback() {
                @Deprecated("Before Android 13")
                override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)
                override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "No meter found")
            }, null)
        }
    }

    /** Reads the picker's result. */
    fun result(data: Intent?): PickedMeter? {
        data ?: return null
        val scan: ScanResult? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, ScanResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE)
        }
        val association: AssociationInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
        } else {
            null
        }
        val address = scan?.device?.address
            ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) association?.deviceMacAddress?.toString()?.uppercase() else null)
            ?: return null
        val name = scan?.scanRecord?.deviceName ?: runCatching { scan?.device?.name }.getOrNull()
        val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) association?.id else null
        return PickedMeter(address, name, id)
    }

    /** Asks Android to wake Neutrino whenever the meter shows up. */
    fun observe(context: Context, meter: PairedMeter) {
        val cdm = manager(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 36 && meter.associationId != null) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(meter.associationId).build())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(meter.address)
            }
        }
    }

    fun forget(context: Context, meter: PairedMeter) {
        val cdm = manager(context) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 36 && meter.associationId != null) {
                cdm.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(meter.associationId).build())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                cdm.stopObservingDevicePresence(meter.address)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && meter.associationId != null) {
                cdm.disassociate(meter.associationId)
            } else {
                @Suppress("DEPRECATION")
                cdm.disassociate(meter.address)
            }
        }
    }
}

/**
 * Android binds this when the paired meter appears nearby (it turns Bluetooth on after a test) and
 * unbinds when it's gone. Neutrino syncs straight away.
 */
class MeterPresenceService : CompanionDeviceService() {

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) appeared()
    }

    @Deprecated("Android 13-15")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared()

    @Deprecated("Android 12")
    @Suppress("DEPRECATION")
    override fun onDeviceAppeared(address: String) = appeared()

    private fun appeared() = applicationContext.appContainer.syncMeterInBackground()
}

/** "New glucose reading" notifications. The value is hidden on the lock screen. */
object MeterNotifications {
    private const val CHANNEL = "glucose_readings"
    private const val ID = 300

    fun newReadings(context: Context, count: Int) {
        if (!MealReminders.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.meter_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.meter_channel_body)
            },
        )
        val open = PendingIntent.getActivity(
            context, ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.resources.getQuantityString(R.plurals.meter_new_readings, count, count)
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neutrino_mark)
            .setContentTitle(context.getString(R.string.meter_saved_title))
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neutrino_mark)
            .setColor(context.getColor(R.color.brand_coral))
            .setContentTitle(context.getString(R.string.meter_saved_title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }
}
