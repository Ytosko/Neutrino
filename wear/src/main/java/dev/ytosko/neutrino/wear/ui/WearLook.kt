package dev.ytosko.neutrino.wear.ui

import android.content.Context
import dev.ytosko.neutrino.wear.R
import dev.ytosko.neutrino.wear.protocol.GlucoseBand
import dev.ytosko.neutrino.wear.protocol.WearGlucose
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The phone widget's night colours (watches are always dark), as ARGB. */
object WearColors {
    const val CARBS = 0xFFFBBF24.toInt()
    const val PROTEIN = 0xFF2DD4BF.toInt()
    const val FAT = 0xFFC4B5FD.toInt()
    const val KCAL = 0xFFECEEF2.toInt()
    const val WATER = 0xFF7DD3FC.toInt()
    const val WATER_CONTAINER = 0xFF1B3A4D.toInt()
    const val GLUCOSE = 0xFFF9A8D4.toInt()
    const val LOW = 0xFFFF8A80.toInt()
    const val IN_RANGE = 0xFF4ADE80.toInt()
    const val HIGH = 0xFFFBBF24.toInt()
    const val MUTED = 0xFFB8ABA6.toInt()
    const val ON_SURFACE = 0xFFECEEF2.toInt()

    fun band(band: GlucoseBand): Int = when (band) {
        GlucoseBand.Low -> LOW
        GlucoseBand.InRange -> IN_RANGE
        GlucoseBand.High -> HIGH
    }

    /** [color] at [alpha] (0..1), for a ring's faint track. */
    fun faint(color: Int, alpha: Float = 0.22f): Int = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
}

/** "2:57 PM" today, or a short date and time for an older reading, in the watch's own locale. */
fun WearGlucose.timeText(zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(measuredAtEpochMs).atZone(zone)
    return if (at.toLocalDate() == LocalDate.now(zone)) {
        at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    } else {
        at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
    }
}

fun WearGlucose.bandText(context: Context): String = context.getString(
    when (band) {
        GlucoseBand.Low -> R.string.glucose_low
        GlucoseBand.InRange -> R.string.glucose_in_range
        GlucoseBand.High -> R.string.glucose_high
    },
)

/** For screen readers: "Blood glucose 6.4 mmol/L, In range, 2:57 PM". */
fun WearGlucose.spoken(context: Context): String =
    "${context.getString(R.string.glucose_title)} $value $unit, ${bandText(context)}, ${timeText()}"
