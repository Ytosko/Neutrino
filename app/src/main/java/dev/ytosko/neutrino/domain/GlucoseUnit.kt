package dev.ytosko.neutrino.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * How glucose is shown. Readings are always stored in mmol/L (meters send SI units over
 * Bluetooth); mg/dL is only a display choice.
 */
enum class GlucoseUnit(val label: String) {
    MmolL("mmol/L"),
    MgDl("mg/dL"),
    ;

    /** "7.2" in mmol/L, "130" in mg/dL. */
    fun format(mmol: Double): String = when (this) {
        MmolL -> String.format(Locale.US, "%.1f", mmol)
        MgDl -> toMgDl(mmol).roundToInt().toString()
    }

    /** The value in this unit, for charts and steppers. */
    fun fromMmol(mmol: Double): Double = when (this) {
        MmolL -> mmol
        MgDl -> toMgDl(mmol)
    }

    fun toMmol(value: Double): Double = when (this) {
        MmolL -> value
        MgDl -> value / MG_DL_PER_MMOL_L
    }

    /** One step of the target-range steppers, in mmol/L. */
    val stepMmol: Double get() = if (this == MmolL) 0.1 else 1.0 / MG_DL_PER_MMOL_L

    companion object {
        /** Glucose: 180.16 g/mol, so 1 mmol/L = 18.016 mg/dL. */
        const val MG_DL_PER_MMOL_L = 18.016

        fun toMgDl(mmol: Double): Double = mmol * MG_DL_PER_MMOL_L

        fun fromId(id: String?): GlucoseUnit? = entries.firstOrNull { it.name == id }

        /** Countries where meters and doctors usually use mg/dL; everywhere else defaults to mmol/L. */
        private val MG_DL_REGIONS = setOf(
            "US", "IN", "JP", "KR", "TW", "FR", "IT", "ES", "PT", "BE", "AT", "IL", "EG",
            "MX", "BR", "AR", "CO", "CL", "PE", "VE", "EC", "UY", "TR", "PH", "TH", "VN", "ID", "NP", "PK",
        )

        fun defaultFor(locale: Locale = Locale.getDefault()): GlucoseUnit =
            if (locale.country.uppercase() in MG_DL_REGIONS) MgDl else MmolL
    }
}
