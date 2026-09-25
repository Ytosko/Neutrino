package dev.ytosko.neutrino.glucose

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.UUID

/** Runs the real sync session against the simulated meter, scenario by scenario. */
class MeterSyncTest {

    /** "Real" time for both the phone and the meter's underlying clock; tests move it forward. */
    private var realNow = LocalDateTime.of(2026, 9, 25, 16, 25, 0)
    private val meter = SimulatedMeter(now = { realNow })

    private class FakeLink(private val meter: SimulatedMeter, private val hasClock: Boolean = true) : MeterLink {
        override val incoming = Channel<Incoming>(Channel.UNLIMITED)
        val subscribed = mutableSetOf<UUID>()

        override fun has(service: UUID, characteristic: UUID) =
            hasClock || characteristic != GlucoseUuids.CURRENT_TIME

        override fun canWrite(service: UUID, characteristic: UUID) =
            characteristic == GlucoseUuids.RACP || (characteristic == GlucoseUuids.CURRENT_TIME && meter.clockWritable)

        override suspend fun read(service: UUID, characteristic: UUID) = meter.read(service, characteristic)

        override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
            meter.write(characteristic, value).forEach { if (it.characteristic in subscribed) incoming.send(it) }
        }

        override suspend fun subscribe(service: UUID, characteristic: UUID, indicate: Boolean) {
            subscribed += characteristic
        }
    }

    private suspend fun sync(last: Int? = null, link: FakeLink = FakeLink(meter)) =
        MeterSync.run(link, last, phoneNow = { realNow })

    private fun resolve(result: MeterSyncResult) = ReadingResolver.resolve(result.records, result.clock, realNow)

    @Test
    fun `first sync downloads everything with meal marks and device info`() = runTest {
        meter.takeTest(5.4, MealFlag.Fasting)
        realNow = realNow.plusHours(3)
        meter.takeTest(9.8, MealFlag.AfterMeal)
        meter.takeTest(7.1)
        val result = sync()
        assertEquals(listOf(1, 2, 3), result.records.map { it.measurement.sequence })
        assertEquals(MealFlag.Fasting, result.records[0].context?.meal)
        assertEquals(null, result.records[2].context)
        assertEquals("CONTOUR PLUS ELITE", result.info.model)
        val readings = resolve(result).readings
        assertEquals(listOf(5.4, 9.8, 7.1), readings.map { Math.round(it.mmolPerL * 10) / 10.0 })
        assertEquals(MealFlag.None, readings[2].meal) // unmarked = General
    }

    @Test
    fun `later syncs only fetch new readings`() = runTest {
        repeat(3) { meter.takeTest(6.0 + it) }
        val first = sync()
        meter.takeTest(8.8)
        val second = sync(last = first.records.last().measurement.sequence)
        assertEquals(listOf(4), second.records.map { it.measurement.sequence })
        assertTrue(sync(last = 4).records.isEmpty())
    }

    @Test
    fun `meters without the sequence filter fall back to all and keep only new ones`() = runTest {
        meter.supportsSequenceFilter = false
        repeat(4) { meter.takeTest(5.0) }
        assertEquals(listOf(3, 4), sync(last = 2).records.map { it.measurement.sequence })
    }

    @Test
    fun `a clock 2h15m behind is set, and readings get their real time`() = runTest {
        meter.clockSkewSeconds = -(2 * 3600 + 15 * 60).toLong()
        meter.takeTest(7.2) // real time 16:25, meter says 14:10
        realNow = realNow.plusSeconds(20)
        val result = sync()
        assertEquals(8100L, result.clock!!.offsetSeconds)
        assertTrue(result.clockWasSet)
        assertEquals(0L, meter.clockSkewSeconds)
        val reading = resolve(result).readings.single()
        assertEquals(LocalDateTime.of(2026, 9, 25, 16, 25, 0), reading.time)
        assertFalse(reading.timeEstimated)
    }

    @Test
    fun `a read-only clock is left alone but every reading is still corrected`() = runTest {
        meter.clockWritable = false
        meter.clockSkewSeconds = 3600 // an hour fast
        meter.takeTest(6.6)
        realNow = realNow.plusDays(2) // phone away for two days
        meter.takeTest(8.1)
        val result = sync()
        assertFalse(result.clockWasSet)
        assertEquals(3600L, meter.clockSkewSeconds)
        val readings = resolve(result).readings
        assertEquals(LocalDateTime.of(2026, 9, 25, 16, 25, 0), readings[0].time)
        assertEquals(LocalDateTime.of(2026, 9, 27, 16, 25, 0), readings[1].time)
        assertTrue(readings.none { it.timeEstimated })
    }

    @Test
    fun `readings before a clock reset are marked estimated`() = runTest {
        meter.takeTest(5.5) // stamped by the old clock
        realNow = realNow.plusHours(1)
        meter.setMeterClock(LocalDateTime.of(2020, 1, 1, 0, 0)) // battery swap resets the clock
        meter.takeTest(6.5)
        val result = sync()
        val readings = resolve(result).readings
        assertTrue("stamped by the lost clock", readings[0].timeEstimated)
        assertFalse(readings[1].timeEstimated)
        assertEquals(realNow, readings[1].time) // current clock is measured, so this one is exact
    }

    @Test
    fun `without a readable clock a future time becomes the sync time`() = runTest {
        meter.clockSkewSeconds = 5 * 3600
        meter.takeTest(7.0)
        val result = sync(link = FakeLink(meter, hasClock = false))
        val reading = resolve(result).readings.single()
        assertEquals(realNow, reading.time)
        assertTrue(reading.timeEstimated)
    }

    @Test
    fun `control solution and failed tests are never stored, HI and LO are`() = runTest {
        meter.takeTest(6.0, sampleType = SampleType.ControlSolution)
        meter.takeTest(4.0, sensorStatus = SensorStatus.SAMPLE_INSUFFICIENT)
        meter.takeTest(null, sensorStatus = SensorStatus.RESULT_TOO_HIGH)
        meter.takeTest(5.2)
        val resolution = resolve(sync())
        assertEquals(mapOf(1 to SkipReason.ControlSolution, 2 to SkipReason.Error), resolution.skipped)
        assertEquals(listOf(3, 4), resolution.readings.map { it.sequence })
        assertEquals(RangeFlag.High, resolution.readings[0].range)
        assertEquals(ReadingResolver.HIGH_LIMIT_MMOL, resolution.readings[0].mmolPerL, 0.0)
    }

    @Test
    fun `meters that report in mg per dl give the same mmol per litre`() = runTest {
        meter.reportsInKgPerL = true
        meter.takeTest(10.0)
        assertEquals(10.0, resolve(sync()).readings.single().mmolPerL, 0.06)
    }

    @Test
    fun `an empty meter is fine`() = runTest {
        assertTrue(sync().records.isEmpty())
    }

    @Test(expected = MeterSyncException::class)
    fun `devices without the glucose service are refused`() = runTest {
        val notAMeter = object : MeterLink {
            override val incoming = Channel<Incoming>()
            override fun has(service: UUID, characteristic: UUID) = false
            override fun canWrite(service: UUID, characteristic: UUID) = false
            override suspend fun read(service: UUID, characteristic: UUID) = ByteArray(0)
            override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) = Unit
            override suspend fun subscribe(service: UUID, characteristic: UUID, indicate: Boolean) = Unit
        }
        MeterSync.run(notAMeter, null, { realNow })
    }

    @Test
    fun `a meter whose numbering restarted is read again from the start`() = runTest {
        repeat(5) { meter.takeTest(6.0) }
        val first = sync()
        meter.clearMemory()
        realNow = realNow.plusHours(1)
        meter.takeTest(9.1)
        val second = sync(last = first.records.last().measurement.sequence)
        assertEquals(listOf(1), second.records.map { it.measurement.sequence })
    }
}
