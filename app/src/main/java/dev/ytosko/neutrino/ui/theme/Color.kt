package dev.ytosko.neutrino.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Logo coral. Decorative / large surfaces only; use `colorScheme.primary` for actions. */
val BrandCoral = Color(0xFFFF5757)

// Contrast-checked; mirrors the website tokens in web/public/assets/css/site.css.
internal val LightColors = lightColorScheme(
    primary = Color(0xFFD93838),              // white text 4.6:1
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE1DC),
    onPrimaryContainer = Color(0xFF5C0A0A),
    inversePrimary = Color(0xFFFF8A80),
    secondary = Color(0xFF775651),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE9E5),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF0F766E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF04302C),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF2A1A18),         // 15.9:1
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF2A1A18),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF6B5B57),     // 6.1:1
    surfaceTint = Color(0xFFD93838),
    inverseSurface = Color(0xFF2A1A18),
    inverseOnSurface = Color(0xFFFFF0ED),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EE),
    surfaceContainer = Color(0xFFFCEBE8),
    surfaceContainerHigh = Color(0xFFF7E5E1),
    surfaceContainerHighest = Color(0xFFF1DEDA),
    outline = Color(0xFF857370),
    outlineVariant = Color(0xFFF1DEDA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5757),              // dark ink 6.2:1
    onPrimary = Color(0xFF1A0B0A),
    primaryContainer = Color(0xFF4A1614),      // coral icon on it 4.8:1
    onPrimaryContainer = Color(0xFFFFDAD6),
    inversePrimary = Color(0xFFD93838),
    secondary = Color(0xFFE7BDB7),
    onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF3A2D2A),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFF2DD4BF),
    onTertiary = Color(0xFF00201C),
    tertiaryContainer = Color(0xFF0B4A44),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF17110F),
    onBackground = Color(0xFFF6EEEC),         // 16.3:1
    surface = Color(0xFF17110F),
    onSurface = Color(0xFFF6EEEC),
    surfaceVariant = Color(0xFF3A2D2A),
    onSurfaceVariant = Color(0xFFB8A9A5),     // 8.2:1
    surfaceTint = Color(0xFFFF5757),
    inverseSurface = Color(0xFFF6EEEC),
    inverseOnSurface = Color(0xFF2A1A18),
    surfaceContainerLowest = Color(0xFF120D0B),
    surfaceContainerLow = Color(0xFF1D1513),
    surfaceContainer = Color(0xFF211917),
    surfaceContainerHigh = Color(0xFF2A201E),
    surfaceContainerHighest = Color(0xFF342926),
    outline = Color(0xFFA08C88),
    outlineVariant = Color(0xFF3A2D2A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/** Colours outside the Material scheme. Macros are always shown with a text label too. */
@Immutable
data class NeutrinoColors(
    val brand: Color,
    val onBrand: Color,
    val carbs: Color,
    val carbsContainer: Color,
    val protein: Color,
    val proteinContainer: Color,
    val fat: Color,
    val fatContainer: Color,
    val water: Color,
    val waterContainer: Color,
)

internal val LightNeutrinoColors = NeutrinoColors(
    brand = BrandCoral,
    onBrand = Color(0xFF2A1A18),
    carbs = Color(0xFFB45309),
    carbsContainer = Color(0xFFFEF3C7),
    protein = Color(0xFF0F766E),
    proteinContainer = Color(0xFFCCFBF1),
    fat = Color(0xFF6D28D9),
    fatContainer = Color(0xFFEDE9FE),
    water = Color(0xFF0369A1),
    waterContainer = Color(0xFFE0F2FE),
)

internal val DarkNeutrinoColors = NeutrinoColors(
    brand = BrandCoral,
    onBrand = Color(0xFF1A0B0A),
    carbs = Color(0xFFFBBF24),
    carbsContainer = Color(0x24FBBF24),
    protein = Color(0xFF2DD4BF),
    proteinContainer = Color(0x242DD4BF),
    fat = Color(0xFFC4B5FD),
    fatContainer = Color(0x24C4B5FD),
    water = Color(0xFF7DD3FC),
    waterContainer = Color(0x247DD3FC),
)

val LocalNeutrinoColors = staticCompositionLocalOf { LightNeutrinoColors }
