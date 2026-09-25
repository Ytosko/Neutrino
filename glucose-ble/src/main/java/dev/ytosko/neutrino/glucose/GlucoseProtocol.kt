package dev.ytosko.neutrino.glucose

import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Bluetooth SIG Glucose Profile (GLP) messages, as used by standard blood glucose meters such as
 * Ascensia's CONTOUR range. Everything here is byte-level encoding and decoding, little-endian as
 * the specification requires. Encoders exist for the meter simulator and tests.
 */
object GlucoseUuids {
    private fun sig(short: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    val GLUCOSE_SERVICE: UUID = sig(0x1808)
    val MEASUREMENT: UUID = sig(0x2A18)
    val MEASUREMENT_CONTEXT: UUID = sig(0x2A34)
    val FEATURE: UUID = sig(0x2A51)
    val RACP: UUID = sig(0x2A52)

    val CURRENT_TIME_SERVICE: UUID = sig(0x1805)
    val CURRENT_TIME: UUID = sig(0x2A2B)

    val DEVICE_INFO_SERVICE: UUID = sig(0x180A)
    val MANUFACTURER_NAME: UUID = sig(0x2A29)
    val MODEL_NUMBER: UUID = sig(0x2A24)
    val SERIAL_NUMBER: UUID = sig(0x2A25)

    /** Client Characteristic Configuration descriptor, to turn on notifications/indications. */
    val CCCD: UUID = sig(0x2902)
}

class GlucoseFormatException(message: String) : Exception(message)

/** IEEE 11073 16-bit SFLOAT: 4-bit signed exponent, 12-bit signed mantissa. */
object SFloat {
    private const val NAN = 0x07FF
    private const val NRES = 0x0800
    private const val POS_INF = 0x07FE
    private const val NEG_INF = 0x0802
    private const val RESERVED = 0x0801

    /** Returns null for the special "not a number", "not at this resolution" and infinity values. */
    fun decode(raw: Int): Double? {
        val mantissaBits = raw and 0x0FFF
        if (mantissaBits in setOf(NAN, NRES, POS_INF, NEG_INF, RESERVED)) return null
        val mantissa = if (mantissaBits >= 0x0800) mantissaBits - 0x1000 else mantissaBits
        val exponentBits = (raw shr 12) and 0x0F
        val exponent = if (exponentBits >= 0x08) exponentBits - 0x10 else exponentBits
        return mantissa * 10.0.pow(exponent)
    }

    /** Encodes [value] with the given decimal [exponent] (e.g. -1 for one decimal, -5 for kg/L). */
    fun encode(value: Double, exponent: Int): Int {
        val mantissa = (value / 10.0.pow(exponent)).roundToInt()
        require(mantissa in -2046..2045) { "Value $value doesn't fit an SFLOAT with exponent $exponent" }
        return ((exponent and 0x0F) shl 12) or (mantissa and 0x0FFF)
    }
}

/** Sample types (low nibble of the type/location byte). */
enum class SampleType(val code: Int) {
    CapillaryWholeBlood(1), CapillaryPlasma(2), VenousWholeBlood(3), VenousPlasma(4),
    ArterialWholeBlood(5), ArterialPlasma(6), UndeterminedWholeBlood(7), UndeterminedPlasma(8),
    InterstitialFluid(9), ControlSolution(0xA), Unknown(0);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** Sensor Status Annunciation bits (Glucose Measurement characteristic). */
object SensorStatus {
    const val BATTERY_LOW = 1 shl 0
    const val SENSOR_MALFUNCTION = 1 shl 1
    const val SAMPLE_INSUFFICIENT = 1 shl 2
    const val STRIP_INSERTION_ERROR = 1 shl 3
    const val STRIP_TYPE_INCORRECT = 1 shl 4
    const val RESULT_TOO_HIGH = 1 shl 5
    const val RESULT_TOO_LOW = 1 shl 6
    const val TEMPERATURE_TOO_HIGH = 1 shl 7
    const val TEMPERATURE_TOO_LOW = 1 shl 8
    const val READ_INTERRUPTED = 1 shl 9
    const val GENERAL_FAULT = 1 shl 10
    const val TIME_FAULT = 1 shl 11

    /** Faults that make the value itself unusable. */
    const val INVALIDATING = SENSOR_MALFUNCTION or SAMPLE_INSUFFICIENT or STRIP_INSERTION_ERROR or
        STRIP_TYPE_INCORRECT or TEMPERATURE_TOO_HIGH or TEMPERATURE_TOO_LOW or READ_INTERRUPTED or GENERAL_FAULT
}

/**
 * One Glucose Measurement (0x2A18) record.
 *
 * @property baseTime the meter's clock when the test was taken (local wall-clock, no zone).
 * @property mmolPerL the concentration converted to mmol/L, or null if the record has none.
 */
data class GlucoseMeasurement(
    val sequence: Int,
    val baseTime: LocalDateTime,
    val timeOffsetMinutes: Int = 0,
    val mmolPerL: Double?,
    val sampleType: SampleType = SampleType.CapillaryWholeBlood,
    val sampleLocation: Int = LOCATION_FINGER,
    val sensorStatus: Int = 0,
    val contextFollows: Boolean = false,
) {
    /** The meter's own timestamp for the test (base time plus its time offset). */
    val meterTime: LocalDateTime get() = baseTime.plusMinutes(timeOffsetMinutes.toLong())

    fun has(status: Int) = sensorStatus and status != 0

    companion object {
        const val LOCATION_FINGER = 1

        /** mg/dL per mmol/L for glucose (molar mass 180.16 g/mol). */
        const val MGDL_PER_MMOL = 18.0182

        private const val FLAG_TIME_OFFSET = 0x01
        private const val FLAG_CONCENTRATION = 0x02
        private const val FLAG_UNITS_MOL = 0x04
        private const val FLAG_STATUS = 0x08
        private const val FLAG_CONTEXT = 0x10

        fun decode(bytes: ByteArray): GlucoseMeasurement {
            val r = Reader(bytes)
            val flags = r.u8()
            val sequence = r.u16()
            val base = r.dateTime()
            val offset = if (flags and FLAG_TIME_OFFSET != 0) r.s16() else 0
            var mmol: Double? = null
            var type = SampleType.Unknown
            var location = 0x0F
            if (flags and FLAG_CONCENTRATION != 0) {
                val value = SFloat.decode(r.u16())
                mmol = value?.let {
                    // mol/L -> mmol/L, or kg/L -> mg/dL -> mmol/L.
                    if (flags and FLAG_UNITS_MOL != 0) it * 1_000 else it * 100_000 / MGDL_PER_MMOL
                }
                val typeLocation = r.u8()
                type = SampleType.of(typeLocation and 0x0F)
                location = (typeLocation shr 4) and 0x0F
            }
            val status = if (flags and FLAG_STATUS != 0) r.u16() else 0
            return GlucoseMeasurement(
                sequence = sequence,
                baseTime = base,
                timeOffsetMinutes = offset,
                mmolPerL = mmol,
                sampleType = type,
                sampleLocation = location,
                sensorStatus = status,
                contextFollows = flags and FLAG_CONTEXT != 0,
            )
        }

        /** Encodes like a meter would. [inMolPerL] picks mol/L (true) or kg/L units. */
        fun encode(m: GlucoseMeasurement, inMolPerL: Boolean = true): ByteArray {
            val w = Writer()
            var flags = 0
            if (m.timeOffsetMinutes != 0) flags = flags or FLAG_TIME_OFFSET
            if (m.mmolPerL != null) flags = flags or FLAG_CONCENTRATION or (if (inMolPerL) FLAG_UNITS_MOL else 0)
            if (m.sensorStatus != 0) flags = flags or FLAG_STATUS
            if (m.contextFollows) flags = flags or FLAG_CONTEXT
            w.u8(flags)
            w.u16(m.sequence)
            w.dateTime(m.baseTime)
            if (m.timeOffsetMinutes != 0) w.u16(m.timeOffsetMinutes and 0xFFFF)
            if (m.mmolPerL != null) {
                val raw = if (inMolPerL) SFloat.encode(m.mmolPerL / 1_000, -5) else SFloat.encode(m.mmolPerL * MGDL_PER_MMOL / 100_000, -5)
                w.u16(raw)
                w.u8(((m.sampleLocation and 0x0F) shl 4) or (m.sampleType.code and 0x0F))
            }
            if (m.sensorStatus != 0) w.u16(m.sensorStatus)
            return w.bytes()
        }
    }
}

/** Meal marking from the Glucose Measurement Context (0x2A34) "Meal" field. */
enum class MealFlag(val code: Int) {
    None(0), BeforeMeal(1), AfterMeal(2), Fasting(3), Casual(4), Bedtime(5);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: None
    }
}

/** Glucose Measurement Context (0x2A34): the extra details a meter can attach to a reading. */
data class GlucoseContext(val sequence: Int, val meal: MealFlag = MealFlag.None) {
    companion object {
        private const val FLAG_CARBS = 0x01
        private const val FLAG_MEAL = 0x02
        private const val FLAG_EXTENDED = 0x80

        fun decode(bytes: ByteArray): GlucoseContext {
            val r = Reader(bytes)
            val flags = r.u8()
            val sequence = r.u16()
            if (flags and FLAG_EXTENDED != 0) r.u8()
            if (flags and FLAG_CARBS != 0) { r.u8(); r.u16() }
            val meal = if (flags and FLAG_MEAL != 0) MealFlag.of(r.u8()) else MealFlag.None
            // Tester/health, exercise, medication and HbA1c follow; Neutrino doesn't use them.
            return GlucoseContext(sequence, meal)
        }

        fun encode(c: GlucoseContext): ByteArray {
            val w = Writer()
            val hasMeal = c.meal != MealFlag.None
            w.u8(if (hasMeal) FLAG_MEAL else 0)
            w.u16(c.sequence)
            if (hasMeal) w.u8(c.meal.code)
            return w.bytes()
        }
    }
}

/** Record Access Control Point (0x2A52): how an app asks a meter for its stored readings. */
object Racp {
    const val OP_REPORT_RECORDS = 0x01
    const val OP_ABORT = 0x03
    const val OP_REPORT_COUNT = 0x04
    const val OP_COUNT_RESPONSE = 0x05
    const val OP_RESPONSE = 0x06

    const val OPERATOR_NULL = 0x00
    const val OPERATOR_ALL = 0x01
    const val OPERATOR_GREATER_OR_EQUAL = 0x03
    const val OPERATOR_LAST = 0x06

    const val FILTER_SEQUENCE = 0x01

    const val SUCCESS = 0x01
    const val OP_NOT_SUPPORTED = 0x02
    const val INVALID_OPERATOR = 0x03
    const val OPERATOR_NOT_SUPPORTED = 0x04
    const val INVALID_OPERAND = 0x05
    const val NO_RECORDS = 0x06

    fun reportAll(): ByteArray = byteArrayOf(OP_REPORT_RECORDS.toByte(), OPERATOR_ALL.toByte())

    /** Records with sequence >= [from]. */
    fun reportFrom(from: Int): ByteArray = Writer().apply {
        u8(OP_REPORT_RECORDS); u8(OPERATOR_GREATER_OR_EQUAL); u8(FILTER_SEQUENCE); u16(from)
    }.bytes()

    /** Just the newest record: used to notice a meter whose numbering restarted. */
    fun reportLast(): ByteArray = byteArrayOf(OP_REPORT_RECORDS.toByte(), OPERATOR_LAST.toByte())

    fun reportCount(): ByteArray = byteArrayOf(OP_REPORT_COUNT.toByte(), OPERATOR_ALL.toByte())

    sealed interface Reply {
        data class Response(val requestOpCode: Int, val code: Int) : Reply
        data class Count(val count: Int) : Reply
    }

    fun decodeReply(bytes: ByteArray): Reply {
        val r = Reader(bytes)
        return when (val op = r.u8()) {
            OP_RESPONSE -> { r.u8(); Reply.Response(r.u8(), r.u8()) }
            OP_COUNT_RESPONSE -> { r.u8(); Reply.Count(r.u16()) }
            else -> throw GlucoseFormatException("Unexpected RACP op code $op")
        }
    }

    fun encodeResponse(requestOpCode: Int, code: Int): ByteArray =
        byteArrayOf(OP_RESPONSE.toByte(), OPERATOR_NULL.toByte(), requestOpCode.toByte(), code.toByte())

    fun encodeCount(count: Int): ByteArray = Writer().apply { u8(OP_COUNT_RESPONSE); u8(OPERATOR_NULL); u16(count) }.bytes()
}

/** Current Time characteristic (0x2A2B) of the Current Time Service. */
object CurrentTime {
    private const val ADJUST_MANUAL = 0x01

    fun decode(bytes: ByteArray): LocalDateTime = Reader(bytes).dateTime()

    fun encode(time: LocalDateTime): ByteArray = Writer().apply {
        dateTime(time)
        u8(time.dayOfWeek.value) // 1 = Monday ... 7 = Sunday
        u8((time.nano / 1_000_000_000.0 * 256).toInt().coerceIn(0, 255))
        u8(ADJUST_MANUAL)
    }.bytes()
}

/** Seconds between two wall-clock times, e.g. how far the meter's clock is from the phone's. */
fun secondsBetween(from: LocalDateTime, to: LocalDateTime): Long =
    java.time.Duration.between(from, to).seconds

internal class Reader(private val bytes: ByteArray) {
    private var pos = 0

    private fun need(n: Int) {
        if (pos + n > bytes.size) throw GlucoseFormatException("Message too short (${bytes.size} bytes)")
    }

    fun u8(): Int { need(1); return bytes[pos++].toInt() and 0xFF }
    fun u16(): Int { need(2); return (bytes[pos++].toInt() and 0xFF) or ((bytes[pos++].toInt() and 0xFF) shl 8) }
    fun s16(): Int = u16().toShort().toInt()

    fun dateTime(): LocalDateTime {
        val year = u16(); val month = u8(); val day = u8(); val hour = u8(); val minute = u8(); val second = u8()
        return try {
            LocalDateTime.of(year, month, day, hour, minute, second)
        } catch (e: java.time.DateTimeException) {
            throw GlucoseFormatException("Invalid date $year-$month-$day $hour:$minute:$second")
        }
    }
}

internal class Writer {
    private val out = java.io.ByteArrayOutputStream()

    fun u8(v: Int) = out.write(v and 0xFF)
    fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    fun dateTime(t: LocalDateTime) {
        u16(t.year); u8(t.monthValue); u8(t.dayOfMonth); u8(t.hour); u8(t.minute); u8(t.second)
    }

    fun bytes(): ByteArray = out.toByteArray()
}
