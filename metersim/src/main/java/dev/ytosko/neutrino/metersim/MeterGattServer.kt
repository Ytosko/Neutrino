package dev.ytosko.neutrino.metersim

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import dev.ytosko.neutrino.glucose.GlucoseUuids
import dev.ytosko.neutrino.glucose.Incoming
import dev.ytosko.neutrino.glucose.SimulatedMeter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Puts a [SimulatedMeter] on the air as a Bluetooth LE glucose meter, like a CONTOUR meter:
 * Glucose, Current Time and Device Information services; encrypted glucose characteristics (so the
 * phone must pair); advertising only for a short window after a test or in pairing mode.
 */
@SuppressLint("MissingPermission")
class MeterGattServer(private val context: Context, val meter: SimulatedMeter) {

    data class Status(
        val advertisingUntil: Long = 0,
        val connected: List<String> = emptyList(),
        val log: List<String> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var advertiseJob: Job? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Which characteristics each connected phone subscribed to. */
    private val subscriptions = mutableMapOf<String, MutableSet<UUID>>()

    /** Notifications go out one at a time; Android drops them if sent before the previous one is done. */
    private val outbox = Channel<Pair<BluetoothDevice, Incoming>>(Channel.UNLIMITED)
    private var sent: CompletableDeferred<Unit>? = null

    fun start() {
        if (server != null) return
        server = manager.openGattServer(context, callback)
        addServices()
        scope.launch { for ((device, item) in outbox) send(device, item) }
        log("GATT server started as ${meter.model}")
    }

    fun stop() {
        stopAdvertising()
        server?.close()
        server = null
    }

    /** Rebuilds the Current Time service, e.g. after "clock writable" changes. */
    fun refreshClockService() {
        val s = server ?: return
        s.getService(GlucoseUuids.CURRENT_TIME_SERVICE)?.let { s.removeService(it) }
        scope.launch {
            delay(300)
            s.addService(currentTimeService())
            log("Clock is now ${if (meter.clockWritable) "writable" else "read-only"}")
        }
    }

    /** Advertise for [seconds], like a meter after a test (or in pairing mode). */
    fun advertise(seconds: Int, reason: String) {
        stopAdvertising()
        val advertiser = manager.adapter.bluetoothLeAdvertiser ?: return log("No LE advertiser")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(GlucoseUuids.GLUCOSE_SERVICE)).build()
        val response = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, response, advertiseCallback)
        val until = System.currentTimeMillis() + seconds * 1_000L
        _status.update { it.copy(advertisingUntil = until) }
        log("Advertising for $seconds s ($reason)")
        advertiseJob = scope.launch {
            delay(seconds * 1_000L)
            stopAdvertising()
            log("Advertising stopped")
        }
    }

    private fun stopAdvertising() {
        advertiseJob?.cancel()
        manager.adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        _status.update { it.copy(advertisingUntil = 0) }
    }

    // ---- Services -------------------------------------------------------------------------------

    private fun addServices() {
        val s = server ?: return
        // Services must be added one at a time; onServiceAdded tells us when to add the next.
        pendingServices.clear()
        pendingServices += listOf(deviceInfoService(), currentTimeService(), glucoseService())
        s.addService(pendingServices.removeAt(0))
    }

    private val pendingServices = mutableListOf<BluetoothGattService>()

    private fun glucoseService() = BluetoothGattService(GlucoseUuids.GLUCOSE_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(notifying(GlucoseUuids.MEASUREMENT, BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        addCharacteristic(notifying(GlucoseUuids.MEASUREMENT_CONTEXT, BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        addCharacteristic(
            BluetoothGattCharacteristic(
                GlucoseUuids.FEATURE,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            ),
        )
        addCharacteristic(
            BluetoothGattCharacteristic(
                GlucoseUuids.RACP,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            ).apply { addDescriptor(cccd()) },
        )
    }

    private fun currentTimeService() = BluetoothGattService(GlucoseUuids.CURRENT_TIME_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        val writable = meter.clockWritable
        addCharacteristic(
            BluetoothGattCharacteristic(
                GlucoseUuids.CURRENT_TIME,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    (if (writable) BluetoothGattCharacteristic.PROPERTY_WRITE else 0),
                BluetoothGattCharacteristic.PERMISSION_READ or (if (writable) BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED else 0),
            ).apply { addDescriptor(cccd()) },
        )
    }

    private fun deviceInfoService() = BluetoothGattService(GlucoseUuids.DEVICE_INFO_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        listOf(GlucoseUuids.MANUFACTURER_NAME, GlucoseUuids.MODEL_NUMBER, GlucoseUuids.SERIAL_NUMBER).forEach {
            addCharacteristic(BluetoothGattCharacteristic(it, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        }
    }

    private fun notifying(uuid: UUID, property: Int) =
        BluetoothGattCharacteristic(uuid, property, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED).apply { addDescriptor(cccd()) }

    /** Subscribing requires an encrypted (paired) link, which is what makes the phone pair. */
    private fun cccd() = BluetoothGattDescriptor(
        GlucoseUuids.CCCD,
        BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
    )

    // ---- Callbacks ------------------------------------------------------------------------------

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (pendingServices.isNotEmpty()) server?.addService(pendingServices.removeAt(0))
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Phone connected ($address, bond ${bondName(device.bondState)})")
                _status.update { it.copy(connected = (it.connected + address).distinct()) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Phone disconnected ($address)")
                subscriptions.remove(address)
                _status.update { it.copy(connected = it.connected - address) }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = runCatching { meter.read(characteristic.service.uuid, characteristic.uuid) }.getOrNull()
            if (value == null) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                return
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset.coerceAtMost(value.size), value.size))
            log("Read ${name(characteristic.uuid)}")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val replies = runCatching { meter.write(characteristic.uuid, value) }
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (replies.isSuccess) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    offset, null,
                )
            }
            log("Write ${name(characteristic.uuid)}: ${value.hex()}${if (replies.isFailure) " (refused)" else ""}")
            replies.getOrNull()?.forEach { reply ->
                if (reply.characteristic in subscriptions[device.address].orEmpty()) outbox.trySend(device to reply)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val uuid = descriptor.characteristic.uuid
            val on = value.isNotEmpty() && value[0].toInt() != 0
            val set = subscriptions.getOrPut(device.address) { mutableSetOf() }
            if (on) set += uuid else set -= uuid
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            log("${if (on) "Subscribed to" else "Unsubscribed from"} ${name(uuid)}")
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val on = descriptor.characteristic.uuid in subscriptions[device.address].orEmpty()
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(if (on) 1 else 0, 0))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            sent?.complete(Unit)
        }
    }

    private suspend fun send(device: BluetoothDevice, item: Incoming) {
        val s = server ?: return
        val characteristic = s.getService(GlucoseUuids.GLUCOSE_SERVICE)?.getCharacteristic(item.characteristic) ?: return
        val indicate = item.characteristic == GlucoseUuids.RACP
        val done = CompletableDeferred<Unit>().also { sent = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.notifyCharacteristicChanged(device, characteristic, indicate, item.value)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = item.value
            @Suppress("DEPRECATION")
            s.notifyCharacteristicChanged(device, characteristic, indicate)
        }
        withTimeoutOrNull(2_000) { done.await() }
        log("${if (indicate) "Indicated" else "Notified"} ${name(item.characteristic)}: ${item.value.hex()}")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            log("Advertising failed ($errorCode)")
        }
    }

    fun log(message: String) {
        val line = "${LocalTime.now().format(TIME)}  $message"
        _status.update { it.copy(log = (listOf(line) + it.log).take(80)) }
    }

    private fun name(uuid: UUID) = when (uuid) {
        GlucoseUuids.MEASUREMENT -> "Glucose Measurement"
        GlucoseUuids.MEASUREMENT_CONTEXT -> "Measurement Context"
        GlucoseUuids.RACP -> "Record Access Control Point"
        GlucoseUuids.FEATURE -> "Glucose Feature"
        GlucoseUuids.CURRENT_TIME -> "Current Time"
        GlucoseUuids.MANUFACTURER_NAME -> "Manufacturer"
        GlucoseUuids.MODEL_NUMBER -> "Model"
        GlucoseUuids.SERIAL_NUMBER -> "Serial"
        else -> uuid.toString().take(8)
    }

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "paired"
        BluetoothDevice.BOND_BONDING -> "pairing"
        else -> "not paired"
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
