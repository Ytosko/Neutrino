package dev.ytosko.neutrino.ui.glucose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.glucose.GlucoseEntity
import dev.ytosko.neutrino.data.glucose.GlucoseRelation
import dev.ytosko.neutrino.ui.theme.NeutrinoTheme
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ytosko.neutrino.domain.GlucoseUnit

/** Where a value sits against the user's target range. */
enum class GlucoseBand { Low, InRange, High }

fun band(mmol: Double, low: Double, high: Double): GlucoseBand = when {
    mmol < low -> GlucoseBand.Low
    mmol > high -> GlucoseBand.High
    else -> GlucoseBand.InRange
}

/** The unit glucose is shown in; provided at the app root from settings. */
val LocalGlucoseUnit = staticCompositionLocalOf { GlucoseUnit.MmolL }

/** "7.2" (or "130" in mg/dL), or "HI" / "LO" for results beyond the meter's range. */
@Composable
fun GlucoseEntity.valueText(): String = when (rangeFlag) {
    "High" -> "HI"
    "Low" -> "LO"
    else -> glucoseText(mmolPerL)
}

/** A value stored in mmol/L, in the user's unit, without the unit. */
@Composable
fun glucoseText(mmol: Double): String = LocalGlucoseUnit.current.format(mmol)

/** "mmol/L" or "mg/dL". */
@Composable
fun glucoseUnitLabel(): String = LocalGlucoseUnit.current.label

/** Colours are never the only signal: every value is also labelled low / in range / high. */
@Composable
fun bandColor(band: GlucoseBand): Color = when (band) {
    GlucoseBand.Low -> MaterialTheme.colorScheme.error
    GlucoseBand.InRange -> NeutrinoTheme.colors.protein
    GlucoseBand.High -> NeutrinoTheme.colors.carbs
}

@Composable
fun bandLabel(band: GlucoseBand): String = stringResource(
    when (band) {
        GlucoseBand.Low -> R.string.glucose_low
        GlucoseBand.InRange -> R.string.glucose_in_range
        GlucoseBand.High -> R.string.glucose_high
    },
)

@Composable
fun relationLabel(relation: GlucoseRelation): String = stringResource(
    when (relation) {
        GlucoseRelation.General -> R.string.glucose_general
        GlucoseRelation.Fasting -> R.string.glucose_fasting
        GlucoseRelation.BeforeMeal -> R.string.glucose_before_meal
        GlucoseRelation.AfterMeal -> R.string.glucose_after_meal
        GlucoseRelation.Bedtime -> R.string.glucose_bedtime
    },
)
