package dev.ytosko.neutrino.glucose

import java.time.LocalDateTime
import java.util.UUID

/**
 * A glucose meter's behaviour without the radio: stored records, a clock that can be wrong, and
 * the Record Access Control Point. Modelled on the Glucose Profile and on how CONTOUR meters are
 * known to behave. The meter simulator app puts this behind a real Bluetooth GATT server; tests use
 * it directly.
 *
 * @param now the "real" time source (the device's clock); the meter's clock is this plus [clockSkewSeconds].
 */
class SimulatedMeter(
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    val manufacturer: String = "Ascensia Diabetes Care",
    val model: String = "CONTOUR PLUS ELITE",
    val serial: String = "SIM0001234",
) {
    data class Stored(val measurement: GlucoseMeasurement, val context: GlucoseContext?)

    /** Seconds the meter clock is ahead (positive) or behind (negative) real time. */
    var clockSkewSeconds: Long = 0

    /** Whether a phone may set the meter clock through the Current Time Service. */
    var clockWritable: Boolean = true

    /** Whether the meter understands "records with sequence >= N" (some meters only do "all"). */
    var supportsSequenceFilter: Boolean = true

    /** Send measurements in kg/L (mg/dL based) instead of mol/L, as some meters do. */
    var reportsInKgPerL: Boolean = false

    private val records = mutableListOf<Stored>()

    /** Clears memory and restarts numbering, like a meter reset. */
    fun clearMemory() {
        records.clear()
        nextSequence = 1
    }
    private var nextSequence = 1

    val stored: List<Stored> get() = records.toList()

    fun meterNow(): LocalDateTime = now().plusSeconds(clockSkewSeconds).withNano(0)

    /** Sets the meter clock to an absolute time, like changing it by hand or after a battery swap. */
    fun setMeterClock(time: LocalDateTime) {
        clockSkewSeconds = secondsBetween(now(), time)
    }

    /** A finger-prick test. Returns the new record. */
    fun takeTest(
        mmolPerL: Double?,
        meal: MealFlag = MealFlag.None,
        sensorStatus: Int = 0,
        sampleType: SampleType = SampleType.CapillaryWholeBlood,
    ): Stored {
        val sequence = nextSequence
        nextSequence = if (nextSequence == 0xFFFF) 0 else nextSequence + 1
        val measurement = GlucoseMeasurement(
            sequence = sequence,
            baseTime = meterNow(),
            mmolPerL = mmolPerL,
            sampleType = sampleType,
            sensorStatus = sensorStatus,
            contextFollows = meal != MealFlag.None,
        )
        val stored = Stored(measurement, if (meal != MealFlag.None) GlucoseContext(sequence, meal) else null)
        records += stored
        return stored
    }

    // ---- GATT behaviour -------------------------------------------------------------------------

    fun read(service: UUID, characteristic: UUID): ByteArray = when (characteristic) {
        GlucoseUuids.MANUFACTURER_NAME -> manufacturer.toByteArray()
        GlucoseUuids.MODEL_NUMBER -> model.toByteArray()
        GlucoseUuids.SERIAL_NUMBER -> serial.toByteArray()
        GlucoseUuids.CURRENT_TIME -> CurrentTime.encode(meterNow())
        GlucoseUuids.FEATURE -> byteArrayOf(0x00, 0x04) // multiple bond support
        else -> throw IllegalArgumentException("Can't read $characteristic")
    }

    /** Handles a write; returns what the meter sends back, in order (notifications/indications). */
    fun write(characteristic: UUID, value: ByteArray): List<Incoming> = when (characteristic) {
        GlucoseUuids.CURRENT_TIME -> {
            require(clockWritable) { "Current Time is read-only on this meter" }
            setMeterClock(CurrentTime.decode(value))
            emptyList()
        }
        GlucoseUuids.RACP -> handleRacp(value)
        else -> throw IllegalArgumentException("Can't write $characteristic")
    }

    private fun handleRacp(command: ByteArray): List<Incoming> {
        val op = command.getOrNull(0)?.toInt()?.and(0xFF) ?: return listOf(response(0, Racp.INVALID_OPERAND))
        val operator = command.getOrNull(1)?.toInt()?.and(0xFF) ?: Racp.OPERATOR_NULL
        return when (op) {
            Racp.OP_REPORT_RECORDS -> {
                val selected = when (operator) {
                    Racp.OPERATOR_ALL -> records
                    Racp.OPERATOR_LAST -> records.takeLast(1)
                    Racp.OPERATOR_GREATER_OR_EQUAL -> {
                        if (!supportsSequenceFilter) return listOf(response(op, Racp.OPERATOR_NOT_SUPPORTED))
                        if (command.size < 5 || command[2].toInt() != Racp.FILTER_SEQUENCE) return listOf(response(op, Racp.INVALID_OPERAND))
                        val from = (command[3].toInt() and 0xFF) or ((command[4].toInt() and 0xFF) shl 8)
                        records.filter { it.measurement.sequence >= from }
                    }
                    else -> return listOf(response(op, Racp.OPERATOR_NOT_SUPPORTED))
                }
                if (selected.isEmpty()) return listOf(response(op, Racp.NO_RECORDS))
                selected.flatMap { stored ->
                    listOfNotNull(
                        Incoming(GlucoseUuids.MEASUREMENT, GlucoseMeasurement.encode(stored.measurement, inMolPerL = !reportsInKgPerL)),
                        stored.context?.let { Incoming(GlucoseUuids.MEASUREMENT_CONTEXT, GlucoseContext.encode(it)) },
                    )
                } + response(op, Racp.SUCCESS)
            }
            Racp.OP_REPORT_COUNT -> listOf(Incoming(GlucoseUuids.RACP, Racp.encodeCount(records.size)))
            else -> listOf(response(op, Racp.OP_NOT_SUPPORTED))
        }
    }

    private fun response(op: Int, code: Int) = Incoming(GlucoseUuids.RACP, Racp.encodeResponse(op, code))
}
