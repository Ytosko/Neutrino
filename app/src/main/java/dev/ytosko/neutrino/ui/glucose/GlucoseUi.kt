package dev.ytosko.neutrino.ui.glucose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
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

/** Each food's usual glucose change (food id → rise), for foods with enough before/after readings. */
val LocalFoodRises = androidx.compose.runtime.compositionLocalOf<Map<String, dev.ytosko.neutrino.domain.insights.FoodRise>> { emptyMap() }

/** "Your glucose: +2.8 mmol/L after, on average (5×)", or nothing without enough readings. */
@androidx.compose.runtime.Composable
fun FoodRiseLine(foodId: String, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val rise = LocalFoodRises.current[foodId] ?: return
    val unit = LocalGlucoseUnit.current
    val change = (if (rise.averageRise >= 0) "+" else "−") + unit.format(kotlin.math.abs(rise.averageRise))
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.ui.res.painterResource(R.drawable.ic_activity),
            contentDescription = null,
            tint = dev.ytosko.neutrino.ui.theme.NeutrinoTheme.colors.glucose,
            modifier = androidx.compose.ui.Modifier.size(12.dp),
        )
        androidx.compose.material3.Text(
            androidx.compose.ui.res.stringResource(R.string.food_usual_rise, change, unit.label, rise.times),
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = dev.ytosko.neutrino.ui.theme.NeutrinoTheme.colors.glucose,
            maxLines = 1,
        )
    }
}

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
    GlucoseBand.InRange -> NeutrinoTheme.colors.good
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
