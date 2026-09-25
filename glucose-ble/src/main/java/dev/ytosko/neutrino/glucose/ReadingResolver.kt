package dev.ytosko.neutrino.glucose

import java.time.LocalDateTime

/** A reading outside the meter's range: Contour shows these as "HI" / "LO" instead of a number. */
enum class RangeFlag { None, High, Low }

/** Why a record from the meter was not turned into a reading. */
enum class SkipReason { ControlSolution, Error, NoValue }

/**
 * A meter record turned into a reading Neutrino can store: blood only, a usable value, and a time
 * on the phone's clock.
 *
 * @property time when the test was taken, in phone local time.
 * @property timeEstimated the meter's clock couldn't be trusted for this record (see [ReadingResolver]).
 */
data class ResolvedReading(
    val sequence: Int,
    val mmolPerL: Double,
    val time: LocalDateTime,
    val timeEstimated: Boolean,
    val meal: MealFlag,
    val range: RangeFlag,
)

data class Resolution(val readings: List<ResolvedReading>, val skipped: Map<Int, SkipReason>)

/**
 * Turns raw meter records into readings, correcting the meter's clock.
 *
 * Meter clocks are set by hand and drift or reset (for example after a battery change). Rules:
 * - **Offset correction:** every record's meter time is shifted by how far the meter clock was from
 *   the phone's at sync time. This keeps both fresh and days-old readings at their real time.
 * - **Clock resets:** records are numbered in order, so if a later record has an earlier meter
 *   time, the clock changed between them. The records before the change were stamped by a clock we
 *   can't measure any more: they keep their corrected time but are marked as estimated.
 * - **Never in the future:** a corrected time after the moment of sync becomes the sync time,
 *   marked as estimated. So does a meter-reported time fault.
 * - **Blood only:** control-solution tests and error results (bad strip, too little blood,
 *   temperature, faults) are skipped, never stored.
 */
object ReadingResolver {

    /** Values reported for results beyond the meter's range, matching CONTOUR meters (0.6–33.3 mmol/L). */
    const val HIGH_LIMIT_MMOL = 33.3
    const val LOW_LIMIT_MMOL = 0.6

    /** A backwards step bigger than this between consecutive records means the clock was changed. */
    private const val RESET_TOLERANCE_SECONDS = 60L

    /** Allowance for the seconds between the test and the sync, before a time counts as "future". */
    private const val FUTURE_TOLERANCE_SECONDS = 120L

    fun resolve(records: List<MeterRecord>, clock: MeterClock?, syncedAt: LocalDateTime): Resolution {
        val sorted = records.sortedBy { it.measurement.sequence }
        val skipped = LinkedHashMap<Int, SkipReason>()

        // Find the last clock change: records up to and including it were stamped by an older clock.
        var lastBeforeReset = -1
        for (i in 1 until sorted.size) {
            val previous = sorted[i - 1].measurement.meterTime
            val current = sorted[i].measurement.meterTime
            if (secondsBetween(current, previous) > RESET_TOLERANCE_SECONDS) lastBeforeReset = i - 1
        }

        val readings = sorted.mapIndexedNotNull { index, record ->
            val m = record.measurement
            val skip = skipReason(m)
            if (skip != null) {
                skipped[m.sequence] = skip
                return@mapIndexedNotNull null
            }
            val range = when {
                m.has(SensorStatus.RESULT_TOO_HIGH) -> RangeFlag.High
                m.has(SensorStatus.RESULT_TOO_LOW) -> RangeFlag.Low
                else -> RangeFlag.None
            }
            val value = m.mmolPerL ?: if (range == RangeFlag.High) HIGH_LIMIT_MMOL else LOW_LIMIT_MMOL

            var time = clock?.let { m.meterTime.plusSeconds(it.offsetSeconds) } ?: m.meterTime
            var estimated = index <= lastBeforeReset || m.has(SensorStatus.TIME_FAULT)
            if (secondsBetween(syncedAt, time) > FUTURE_TOLERANCE_SECONDS) {
                time = syncedAt
                estimated = true
            }
            ResolvedReading(
                sequence = m.sequence,
                mmolPerL = value,
                time = time,
                timeEstimated = estimated,
                meal = record.context?.meal ?: MealFlag.None,
                range = range,
            )
        }
        return Resolution(readings, skipped)
    }

    private fun skipReason(m: GlucoseMeasurement): SkipReason? = when {
        m.sampleType == SampleType.ControlSolution || m.sampleLocation == LOCATION_CONTROL_SOLUTION -> SkipReason.ControlSolution
        m.has(SensorStatus.INVALIDATING) -> SkipReason.Error
        m.mmolPerL == null && !m.has(SensorStatus.RESULT_TOO_HIGH) && !m.has(SensorStatus.RESULT_TOO_LOW) -> SkipReason.NoValue
        m.mmolPerL != null && (m.mmolPerL <= 0.0 || m.mmolPerL > 60.0) -> SkipReason.NoValue
        else -> null
    }

    private const val LOCATION_CONTROL_SOLUTION = 4
}
