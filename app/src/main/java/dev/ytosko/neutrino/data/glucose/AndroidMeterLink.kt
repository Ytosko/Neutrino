package dev.ytosko.neutrino.data.glucose

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import dev.ytosko.neutrino.glucose.GlucoseUuids
import dev.ytosko.neutrino.glucose.Incoming
import dev.ytosko.neutrino.glucose.MeterLink
import dev.ytosko.neutrino.glucose.MeterSyncException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** The meter asked for pairing we don't have (bond removed on the phone or the meter). */
class MeterNotPairedException : MeterSyncException("The meter isn't paired with this phone")

/**
 * [MeterLink] over Android's GATT client. Android allows one GATT operation at a time, so every
 * read/write/subscribe waits for its callback before the next starts. Values are never logged.
 */
@SuppressLint("MissingPermission")
class AndroidMeterLink private constructor(private val device: BluetoothDevice) : MeterLink {

    override val incoming = Channel<Incoming>(Channel.UNLIMITED)

    private var gatt: BluetoothGatt? = null
    private val ops = Mutex()
    private var pending: CompletableDeferred<ByteArray?>? = null
    private val connected = CompletableDeferred<Unit>()
    private var disconnected = false

    override fun has(service: UUID, characteristic: UUID): Boolean = find(service, characteristic) != null

    override fun canWrite(service: UUID, characteristic: UUID): Boolean {
        val c = find(service, characteristic) ?: return false
        return c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray = op {
        val c = find(service, characteristic) ?: throw MeterSyncException("Missing $characteristic")
        gatt!!.readCharacteristic(c)
    } ?: ByteArray(0)

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        op {
            val c = find(service, characteristic) ?: throw MeterSyncException("Missing $characteristic")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt!!.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = value
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt!!.writeCharacteristic(c)
            }
        }
    }

    override suspend fun subscribe(service: UUID, characteristic: UUID, indicate: Boolean) {
        op {
            val c = find(service, characteristic) ?: throw MeterSyncException("Missing $characteristic")
            val g = gatt!!
            g.setCharacteristicNotification(c, true)
            val descriptor = c.getDescriptor(GlucoseUuids.CCCD) ?: throw MeterSyncException("Missing CCCD")
            val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
        }
    }

    fun close() {
        disconnected = true
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        pending?.completeExceptionally(MeterSyncException("Disconnected"))
        incoming.close()
    }

    private fun find(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(service)?.getCharacteristic(characteristic)

    /** Starts one GATT operation and waits for its callback. [start] returns false if Android refused it. */
    private suspend fun op(start: () -> Boolean): ByteArray? = ops.withLock {
        if (disconnected) throw MeterSyncException("The meter disconnected")
        val result = CompletableDeferred<ByteArray?>()
        pending = result
        if (!start()) throw MeterSyncException("Bluetooth refused the request")
        withTimeout(OP_TIMEOUT_MS) { result.await() }
    }

    private fun finish(status: Int, value: ByteArray? = null) {
        val p = pending ?: return
        pending = null
        when (status) {
            BluetoothGatt.GATT_SUCCESS -> p.complete(value)
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ->
                p.completeExceptionally(MeterNotPairedException())
            else -> p.completeExceptionally(MeterSyncException("Bluetooth error $status"))
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnected = true
                connected.completeExceptionally(MeterSyncException("Couldn't connect to the meter ($status)"))
                pending?.completeExceptionally(MeterSyncException("The meter disconnected"))
                pending = null
                incoming.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) connected.complete(Unit)
            else connected.completeExceptionally(MeterSyncException("Couldn't read the meter's services ($status)"))
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            finish(status, value)

        @Deprecated("Before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) finish(status, c.value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish(status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(Incoming(c.uuid, value))
        }

        @Deprecated("Before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) incoming.trySend(Incoming(c.uuid, c.value.copyOf()))
        }
    }

    companion object {
        private const val OP_TIMEOUT_MS = 10_000L

        /** Connects and discovers services. The caller must [close] the link. */
        suspend fun connect(context: Context, device: BluetoothDevice, timeoutMs: Long = 20_000): AndroidMeterLink {
            val link = AndroidMeterLink(device)
            link.gatt = device.connectGatt(context, false, link.callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeout(timeoutMs) { link.connected.await() }
            } catch (e: Exception) {
                link.close()
                throw if (e is MeterSyncException) e else MeterSyncException("The meter didn't answer", e)
            }
            return link
        }
    }
}
