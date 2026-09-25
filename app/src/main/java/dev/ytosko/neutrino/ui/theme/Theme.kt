package dev.ytosko.neutrino.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp

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
        // Bangla: no tightened letter spacing. Android mis-measures joined Bangla letters with it,
        // so short headings like "সেটিংস" would wrap mid-word.
        val bangla = LocalConfiguration.current.locales[0].language == "bn"
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = if (bangla) BanglaTypography else NeutrinoTypography,
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

/** The same type scale with normal letter spacing, for Bangla. */
private val BanglaTypography = with(NeutrinoTypography) {
    fun androidx.compose.ui.text.TextStyle.plain() = copy(letterSpacing = 0.sp)
    copy(
        displayLarge = displayLarge.plain(), displayMedium = displayMedium.plain(), displaySmall = displaySmall.plain(),
        headlineLarge = headlineLarge.plain(), headlineMedium = headlineMedium.plain(), headlineSmall = headlineSmall.plain(),
        titleLarge = titleLarge.plain(), titleMedium = titleMedium.plain(), titleSmall = titleSmall.plain(),
        bodyLarge = bodyLarge.plain(), bodyMedium = bodyMedium.plain(), bodySmall = bodySmall.plain(),
        labelLarge = labelLarge.plain(), labelMedium = labelMedium.plain(), labelSmall = labelSmall.plain(),
    )
}
