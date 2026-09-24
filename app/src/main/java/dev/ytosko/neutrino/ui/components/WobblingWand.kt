package dev.ytosko.neutrino.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.ytosko.neutrino.R

/** A magic wand that tilts left and right while the AI works (still when animations are off). */
@Composable
fun WobblingWand(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animationsOn = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val angle = if (animationsOn) {
        rememberInfiniteTransition(label = "wand").animateFloat(
            initialValue = -16f,
            targetValue = 16f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 420, easing = LinearEasing), RepeatMode.Reverse),
            label = "wandAngle",
        ).value
    } else {
        0f
    }
    Icon(
        painterResource(R.drawable.ic_wand_sparkles),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(24.dp).rotate(angle),
    )
}

