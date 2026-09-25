package dev.ytosko.neutrino.glucose

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.abs

/** A value the meter pushed to us (notification or indication). */
class Incoming(val characteristic: UUID, val value: ByteArray)

/**
 * The few GATT operations a sync needs. Implemented over Android Bluetooth in the app, and by fakes
 * in tests. Every call either succeeds or throws.
 */
interface MeterLink {
    fun has(service: UUID, characteristic: UUID): Boolean
    fun canWrite(service: UUID, characteristic: UUID): Boolean
    suspend fun read(service: UUID, characteristic: UUID): ByteArray
    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray)
    /** Turns on notifications ([indicate] = false) or indications for a characteristic. */
    suspend fun subscribe(service: UUID, characteristic: UUID, indicate: Boolean)
    /** Everything the meter pushes, in arrival order. */
    val incoming: ReceiveChannel<Incoming>
}

data class MeterInfo(val manufacturer: String?, val model: String?, val serial: String?)

/** The meter's clock compared with the phone's, both read at the same moment. */
data class MeterClock(val meterTime: LocalDateTime, val phoneTime: LocalDateTime) {
    /** Add this to a meter timestamp to get phone time. Positive means the meter is behind. */
    val offsetSeconds: Long get() = secondsBetween(meterTime, phoneTime)
}

/** A stored record with its context, straight from the meter. */
data class MeterRecord(val measurement: GlucoseMeasurement, val context: GlucoseContext?)

data class MeterSyncResult(
    val info: MeterInfo,
    /** Null if the meter has no readable clock. */
    val clock: MeterClock?,
    /** New records, oldest first. */
    val records: List<MeterRecord>,
    /** Whether the meter lets us set its clock. */
    val clockWritable: Boolean,
    /** True if Neutrino corrected the meter's clock during this sync. */
    val clockWasSet: Boolean,
)

open class MeterSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One sync with a bonded meter, following the Glucose Profile:
 *
 * 1. read device info and the meter's clock (and note how far it is from the phone's),
 * 2. subscribe to measurements, context and the Record Access Control Point,
 * 3. ask for records newer than [MeterSync.run]'s `lastSequence` (or all of them the first time),
 * 4. collect them until the meter says it's done,
 * 5. then, if the clock is off and the meter allows it, set it to the phone's time.
 *
 * The clock is only set after the records are read: stored records keep the timestamps the old
 * clock gave them, and the measured offset is what corrects them.
 */
object MeterSync {

    /** How far the meter clock may be off before Neutrino sets it. */
    const val CLOCK_TOLERANCE_SECONDS = 60L

    suspend fun run(
        link: MeterLink,
        lastSequence: Int?,
        phoneNow: () -> LocalDateTime,
        setClock: Boolean = true,
        timeoutMs: Long = 60_000,
    ): MeterSyncResult = try {
        withTimeout(timeoutMs) { session(link, lastSequence, phoneNow, setClock) }
    } catch (e: TimeoutCancellationException) {
        throw MeterSyncException("The meter stopped responding", e)
    }

    private suspend fun session(link: MeterLink, lastSequence: Int?, phoneNow: () -> LocalDateTime, setClock: Boolean): MeterSyncResult {
        if (!link.has(GlucoseUuids.GLUCOSE_SERVICE, GlucoseUuids.MEASUREMENT) || !link.has(GlucoseUuids.GLUCOSE_SERVICE, GlucoseUuids.RACP)) {
            throw MeterSyncException("This device isn't a Bluetooth glucose meter")
        }
        val info = MeterInfo(
            manufacturer = readText(link, GlucoseUuids.MANUFACTURER_NAME),
            model = readText(link, GlucoseUuids.MODEL_NUMBER),
            serial = readText(link, GlucoseUuids.SERIAL_NUMBER),
        )
        val clock = readClock(link, phoneNow)
        val hasContext = link.has(GlucoseUuids.GLUCOSE_SERVICE, GlucoseUuids.MEASUREMENT_CONTEXT)

        link.subscribe(GlucoseUuids.GLUCOSE_SERVICE, GlucoseUuids.MEASUREMENT, indicate = false)
        if (hasContext) link.subscribe(GlucoseUuids.GLUCOSE_SERVICE, GlucoseUuids.MEASUREMENT_CONTEXT, indicate = false)
        link.subscribe(GlucoseUuids.GLUCOSE_SERVICE, GlucoseUuids.RACP, indicate = true)

        val from = lastSequence?.let { it + 1 }
        var collected = request(link, if (from != null && from <= 0xFFFF) Racp.reportFrom(from) else Racp.reportAll())
        if (collected == null) {
            // Some meters don't support the ">= sequence" filter: fetch all and keep the new ones.
            collected = request(link, Racp.reportAll()) ?: throw MeterSyncException("The meter refused to send its readings")
        }
        var records = collected
            .filter { lastSequence == null || it.measurement.sequence > lastSequence }
            .sortedBy { it.measurement.sequence }
        if (records.isEmpty() && lastSequence != null) {
            // Nothing newer: check the meter didn't restart its numbering (memory cleared or reset).
            val newest = request(link, Racp.reportLast())?.maxOfOrNull { it.measurement.sequence }
            if (newest != null && newest < lastSequence) {
                records = (request(link, Racp.reportAll()) ?: emptyList()).sortedBy { it.measurement.sequence }
            }
        }

        val writable = link.canWrite(GlucoseUuids.CURRENT_TIME_SERVICE, GlucoseUuids.CURRENT_TIME)
        var clockWasSet = false
        if (setClock && clock != null && writable && abs(clock.offsetSeconds) > CLOCK_TOLERANCE_SECONDS) {
            clockWasSet = runCatching {
                link.write(GlucoseUuids.CURRENT_TIME_SERVICE, GlucoseUuids.CURRENT_TIME, CurrentTime.encode(phoneNow()))
                // Read back: only trust that the clock was set if the meter now agrees.
                readClock(link, phoneNow)?.let { abs(it.offsetSeconds) <= CLOCK_TOLERANCE_SECONDS } ?: false
            }.getOrDefault(false)
        }
        return MeterSyncResult(info, clock, records, writable, clockWasSet)
    }

    /**
     * Sends one RACP request and gathers the records it produces. Returns null if the meter says
     * the request (or its filter) isn't supported, so the caller can fall back.
     */
    private suspend fun request(link: MeterLink, command: ByteArray): List<MeterRecord>? {
        link.write(GlucoseUuids.GLUCOSE_SERVICE, GlucoseUuids.RACP, command)
        val measurements = LinkedHashMap<Int, GlucoseMeasurement>()
        val contexts = HashMap<Int, GlucoseContext>()
        while (true) {
            // A meter streams records back to back; a long silence means it's gone.
            val next = withTimeoutOrNull(IDLE_TIMEOUT_MS) { link.incoming.receive() }
                ?: throw MeterSyncException("The meter stopped sending readings")
            when (next.characteristic) {
                GlucoseUuids.MEASUREMENT -> runCatching { GlucoseMeasurement.decode(next.value) }
                    .onSuccess { measurements[it.sequence] = it }
                GlucoseUuids.MEASUREMENT_CONTEXT -> runCatching { GlucoseContext.decode(next.value) }
                    .onSuccess { contexts[it.sequence] = it }
                GlucoseUuids.RACP -> {
                    val reply = runCatching { Racp.decodeReply(next.value) }.getOrNull()
                    if (reply is Racp.Reply.Response && reply.requestOpCode == Racp.OP_REPORT_RECORDS) {
                        return when (reply.code) {
                            Racp.SUCCESS, Racp.NO_RECORDS -> measurements.values.map { MeterRecord(it, contexts[it.sequence]) }
                            Racp.OPERATOR_NOT_SUPPORTED, Racp.INVALID_OPERATOR, Racp.INVALID_OPERAND -> null
                            else -> throw MeterSyncException("The meter reported error ${reply.code}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun readClock(link: MeterLink, phoneNow: () -> LocalDateTime): MeterClock? {
        if (!link.has(GlucoseUuids.CURRENT_TIME_SERVICE, GlucoseUuids.CURRENT_TIME)) return null
        return runCatching {
            val meter = CurrentTime.decode(link.read(GlucoseUuids.CURRENT_TIME_SERVICE, GlucoseUuids.CURRENT_TIME))
            MeterClock(meter, phoneNow())
        }.getOrNull()
    }

    private suspend fun readText(link: MeterLink, characteristic: UUID): String? {
        if (!link.has(GlucoseUuids.DEVICE_INFO_SERVICE, characteristic)) return null
        return runCatching { link.read(GlucoseUuids.DEVICE_INFO_SERVICE, characteristic).toString(Charsets.UTF_8).trim { it <= ' ' } }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private const val IDLE_TIMEOUT_MS = 15_000L
}
