package dev.ytosko.neutrino.glucose

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class GlucoseProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `sfloat decodes values and special codes`() {
        assertEquals(0.0055, SFloat.decode(0xB226)!!, 1e-12) // 550 x 10^-5
        assertEquals(-2.0, SFloat.decode(0x0FFE)!!, 1e-12)
        assertNull(SFloat.decode(0x07FF)) // NaN
        assertNull(SFloat.decode(0x0800)) // not at this resolution
        assertEquals(0xB226, SFloat.encode(0.0055, -5))
    }

    @Test
    fun `measurement in mol per litre with time offset and context flag`() {
        // flags: time offset | concentration | mol/L | context follows = 0x17
        val payload = bytes(
            0x17, 0x2A, 0x00, // sequence 42
            0xEA, 0x07, 9, 25, 14, 5, 30, // 2026-09-25 14:05:30
            0x0F, 0x00, // +15 minutes
            0x26, 0xB2, // 0.0055 mol/L
            0x11, // capillary whole blood, finger
        )
        val m = GlucoseMeasurement.decode(payload)
        assertEquals(42, m.sequence)
        assertEquals(LocalDateTime.of(2026, 9, 25, 14, 5, 30), m.baseTime)
        assertEquals(LocalDateTime.of(2026, 9, 25, 14, 20, 30), m.meterTime)
        assertEquals(5.5, m.mmolPerL!!, 1e-9)
        assertEquals(SampleType.CapillaryWholeBlood, m.sampleType)
        assertTrue(m.contextFollows)
    }

    @Test
    fun `measurement in kg per litre converts from mg per dl`() {
        // flags: concentration only (kg/L) + sensor status
        val payload = bytes(0x0A, 0x01, 0x00, 0xEA, 0x07, 9, 25, 8, 0, 0, 0x63, 0xB0, 0x11, 0x20, 0x00)
        val m = GlucoseMeasurement.decode(payload)
        assertEquals(99 / GlucoseMeasurement.MGDL_PER_MMOL, m.mmolPerL!!, 1e-9) // 99 mg/dL = 5.49 mmol/L
        assertTrue(m.has(SensorStatus.RESULT_TOO_HIGH))
    }

    @Test
    fun `encode and decode round trip`() {
        val original = GlucoseMeasurement(7, LocalDateTime.of(2026, 1, 2, 3, 4, 5), -30, 12.3, contextFollows = true, sensorStatus = SensorStatus.BATTERY_LOW)
        val decoded = GlucoseMeasurement.decode(GlucoseMeasurement.encode(original))
        assertEquals(original.sequence, decoded.sequence)
        assertEquals(original.meterTime, decoded.meterTime)
        assertEquals(12.3, decoded.mmolPerL!!, 0.001)
        assertEquals(original.sensorStatus, decoded.sensorStatus)
        assertEquals(12.3, GlucoseMeasurement.decode(GlucoseMeasurement.encode(original, inMolPerL = false)).mmolPerL!!, 0.05)
    }

    @Test
    fun `context meal field with other fields before it`() {
        // flags: carbs present | meal present; carbs id + SFLOAT, then meal = after meal
        val payload = bytes(0x03, 0x05, 0x00, 0x01, 0x32, 0x00, 0x02)
        val c = GlucoseContext.decode(payload)
        assertEquals(5, c.sequence)
        assertEquals(MealFlag.AfterMeal, c.meal)
        assertEquals(MealFlag.Fasting, GlucoseContext.decode(GlucoseContext.encode(GlucoseContext(9, MealFlag.Fasting))).meal)
    }

    @Test
    fun `racp commands and replies`() {
        assertArrayEquals(bytes(0x01, 0x01), Racp.reportAll())
        assertArrayEquals(bytes(0x01, 0x03, 0x01, 0x2C, 0x01), Racp.reportFrom(300))
        assertEquals(Racp.Reply.Response(Racp.OP_REPORT_RECORDS, Racp.NO_RECORDS), Racp.decodeReply(bytes(0x06, 0x00, 0x01, 0x06)))
        assertEquals(Racp.Reply.Count(12), Racp.decodeReply(bytes(0x05, 0x00, 0x0C, 0x00)))
    }

    @Test
    fun `current time round trip`() {
        val t = LocalDateTime.of(2026, 9, 25, 23, 59, 58)
        assertEquals(t, CurrentTime.decode(CurrentTime.encode(t)))
        assertEquals(10, CurrentTime.encode(t).size)
    }

    @Test(expected = GlucoseFormatException::class)
    fun `truncated messages are rejected`() {
        GlucoseMeasurement.decode(bytes(0x02, 0x01, 0x00, 0xEA))
    }
}
