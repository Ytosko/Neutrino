package dev.ytosko.neutrino.domain.goals

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.Locale

/**
 * One liver result on one day: a FibroScan (CAP, stiffness, or just the grades from the report),
 * a blood test (ALT, AST), or both. The log of these shows how fatty liver is progressing.
 */
@Serializable
data class LiverTest(
    val id: String,
    val epochDay: Long,
    val cap: Int? = null,
    val kpa: Double? = null,
    val steatosisGrade: Int? = null,
    val fibrosisGrade: Int? = null,
    val alt: Int? = null,
    val ast: Int? = null,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)

    /** Fat grade: from CAP when there is one, else the one picked. */
    val steatosis: Int? get() = cap?.let(LiverGrades::steatosis) ?: steatosisGrade

    /** Scarring grade: from kPa when there is one, else the one picked. */
    val fibrosis: Int? get() = kpa?.let(LiverGrades::fibrosis) ?: fibrosisGrade

    val hasScan: Boolean get() = cap != null || kpa != null || steatosisGrade != null || fibrosisGrade != null
    val hasBlood: Boolean get() = alt != null || ast != null
    val isEmpty: Boolean get() = !hasScan && !hasBlood

    /** "CAP 286 dB/m (S2), 8.5 kPa (F2), ALT 39 U/L" — for the AI. */
    fun describe(): String = listOfNotNull(
        cap?.let { "CAP $it dB/m (S${steatosis})" } ?: steatosisGrade?.let { "steatosis S$it" },
        kpa?.let { "${String.format(Locale.US, "%.1f", it)} kPa (${fibrosisName(fibrosis!!)})" } ?: fibrosisGrade?.let { "fibrosis ${fibrosisName(it)}" },
        alt?.let { "ALT $it U/L" },
        ast?.let { "AST $it U/L" },
    ).joinToString(", ")

    companion object {
        fun fibrosisName(grade: Int): String = if (grade <= 1) "F0-F1" else "F$grade"
    }
}

object LiverLog {
    /** The newest result with FibroScan values. */
    fun latestScan(tests: List<LiverTest>): LiverTest? = tests.filter { it.hasScan }.maxByOrNull { it.epochDay }

    /** The newest result with ALT or AST. */
    fun latestBlood(tests: List<LiverTest>): LiverTest? = tests.filter { it.hasBlood }.maxByOrNull { it.epochDay }

    /**
     * The single liver values saved before the log existed, as the log's first entry (dated with
     * their test date, or [today]); null when there were none.
     */
    fun fromOld(c: Conditions, today: LocalDate): LiverTest? {
        val t = LiverTest(
            id = "liver-old",
            epochDay = c.liverTestDay ?: today.toEpochDay(),
            cap = c.cap, kpa = c.kpa, steatosisGrade = c.steatosisGrade, fibrosisGrade = c.fibrosisGrade,
            alt = c.alt, ast = c.ast,
        )
        return t.takeUnless { it.isEmpty }
    }
}
