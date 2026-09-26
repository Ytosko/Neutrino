package dev.ytosko.neutrino.domain.insights

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Glucose management indicator: an estimated A1c from average glucose,
 * GMI (%) = 3.31 + 0.02392 × mean glucose in mg/dL (Bergenstal et al., Diabetes Care 2018).
 */
data class Gmi(
    /** NGSP / DCCT percent, e.g. 6.8. */
    val percent: Double,
    /** The same as IFCC mmol/mol, e.g. 51. */
    val mmolPerMol: Int,
    val readings: Int,
    val days: Int,
)

object GmiCalculator {
    /** The Health page looks back this far. */
    const val WINDOW_DAYS = 90L

    /** GMI is meant for at least two weeks of data; with fewer days it would mislead. */
    const val MIN_DAYS = 14

    /** [readings] are (day, mmol/L). Null until they cover at least [MIN_DAYS] different days. */
    fun from(readings: List<Pair<LocalDate, Double>>): Gmi? {
        val days = readings.mapTo(HashSet()) { it.first }.size
        if (days < MIN_DAYS) return null
        val meanMgDl = readings.map { it.second }.average() * MG_DL_PER_MMOL
        val percent = 3.31 + 0.02392 * meanMgDl
        return Gmi(
            percent = percent,
            mmolPerMol = ((percent - 2.152) * 10.929).roundToInt(),
            readings = readings.size,
            days = days,
        )
    }

    private const val MG_DL_PER_MMOL = 18.018
}
