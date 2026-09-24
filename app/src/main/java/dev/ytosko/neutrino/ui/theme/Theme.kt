package dev.ytosko.neutrino.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Neutrino theme. Dynamic (wallpaper) colour is intentionally not used so the
 * brand stays consistent with the website and store listing.
 */
@Composable
fun NeutrinoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalNeutrinoColors provides if (darkTheme) DarkNeutrinoColors else LightNeutrinoColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = NeutrinoTypography,
            shapes = NeutrinoShapes,
            content = content,
        )
    }
}

/** Neutrino's extra colours, e.g. `NeutrinoTheme.colors.carbs`. */
object NeutrinoTheme {
    val colors: NeutrinoColors
        @Composable @ReadOnlyComposable
        get() = LocalNeutrinoColors.current
}
