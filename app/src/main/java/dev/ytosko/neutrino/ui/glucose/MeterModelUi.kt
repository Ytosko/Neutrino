package dev.ytosko.neutrino.ui.glucose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.data.glucose.MeterModel

@DrawableRes
fun MeterModel.imageRes(): Int = when (this) {
    MeterModel.ContourPlusOne -> R.drawable.meter_contour_plus_one
    MeterModel.ContourPlusElite -> R.drawable.meter_contour_plus_elite
    MeterModel.ContourPlusBlue -> R.drawable.meter_contour_plus_blue
}

/** How to put this model into pairing mode. */
@StringRes
fun MeterModel.pairingModeStep(): Int = when (this) {
    MeterModel.ContourPlusOne -> R.string.meter_step_mode_one
    MeterModel.ContourPlusElite, MeterModel.ContourPlusBlue -> R.string.meter_step_mode_elite
}

/** The meter's photo on a soft rounded tile, so every model sits at the same size. */
@Composable
fun MeterPicture(model: MeterModel, size: Dp, modifier: Modifier = Modifier, cutout: Boolean = false) {
    Box(
        modifier = modifier
            .size(size)
            .then(if (cutout) Modifier.border(3.dp, MaterialTheme.colorScheme.background, MaterialTheme.shapes.large) else Modifier)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(model.imageRes()),
            // The model name is always shown next to the picture.
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.padding(size * 0.1f).size(size * 0.8f),
        )
    }
}
