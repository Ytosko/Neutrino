package dev.ytosko.neutrino.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Logo coral. Decorative / large surfaces only; use `colorScheme.primary` for actions. */
val BrandCoral = Color(0xFFFF5757)

/*
 * Neutrino's palette: a warm blush page with white cards, so food photos and data colours stand
 * out, coral (the logo) for the brand and main actions only, and a family
 * of accent colours that always mean the same thing: amber carbs, teal protein, violet fat,
 * sky water, magenta glucose, green "in range / goal reached". Errors use a deeper crimson.
 * Text contrast is at least 4.5:1 on its background in both themes.
 */
internal val LightColors = lightColorScheme(
    primary = Color(0xFFD93838),              // white text 4.6:1
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE4E1),
    onPrimaryContainer = Color(0xFF5C0A0A),
    inversePrimary = Color(0xFFFF8A80),
    secondary = Color(0xFF775651),            // warm rosewood: calm secondary actions
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFCE7E3),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF15803D),             // green: in range, goals, success
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCFCE7),
    onTertiaryContainer = Color(0xFF052E16),
    background = Color(0xFFFFF8F6),           // the warm blush page
    onBackground = Color(0xFF2A1A18),         // 15.9:1
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF2A1A18),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF6B5B57),     // 6.1:1
    surfaceTint = Color(0xFFD93838),
    inverseSurface = Color(0xFF2A1A18),
    inverseOnSurface = Color(0xFFFFF0ED),
    surfaceContainerLowest = Color(0xFFFFFFFF),  // cards: white on the blush
    surfaceContainerLow = Color(0xFFFFF1EE),
    surfaceContainer = Color(0xFFFCEBE8),
    surfaceContainerHigh = Color(0xFFF7E5E1),
    surfaceContainerHighest = Color(0xFFF1DEDA),
    outline = Color(0xFF857370),
    outlineVariant = Color(0xFFF1DEDA),
    error = Color(0xFFB42318),                // crimson, distinct from the coral
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF55160C),
    scrim = Color(0xFF000000),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B63),              // dark ink 6.6:1
    onPrimary = Color(0xFF2A0707),
    primaryContainer = Color(0xFF4A1A18),
    onPrimaryContainer = Color(0xFFFFDAD6),
    inversePrimary = Color(0xFFD93838),
    secondary = Color(0xFFE7BDB7),
    onSecondary = Color(0xFF442925),
    secondaryContainer = Color(0xFF3A2D2A),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFF4ADE80),
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = Color(0xFF14391F),
    onTertiaryContainer = Color(0xFFDCFCE7),
    background = Color(0xFF17110F),
    onBackground = Color(0xFFF6EEEC),         // 16.3:1
    surface = Color(0xFF17110F),
    onSurface = Color(0xFFF6EEEC),
    surfaceVariant = Color(0xFF3A2D2A),
    onSurfaceVariant = Color(0xFFB8A9A5),     // 8.2:1
    surfaceTint = Color(0xFFFF6B63),
    inverseSurface = Color(0xFFF6EEEC),
    inverseOnSurface = Color(0xFF2A1A18),
    surfaceContainerLowest = Color(0xFF231A18),  // cards: a step lighter than the page
    surfaceContainerLow = Color(0xFF1D1513),
    surfaceContainer = Color(0xFF211917),
    surfaceContainerHigh = Color(0xFF2A201E),
    surfaceContainerHighest = Color(0xFF342926),
    outline = Color(0xFFA08C88),
    outlineVariant = Color(0xFF3A2D2A),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF4A0A06),
    errorContainer = Color(0xFF5C1510),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

/** A colour and its soft background, e.g. for an icon badge. */
@Immutable
data class Tint(val content: Color, val container: Color, val solid: Color = content)

/** Colours outside the Material scheme. Data colours are always shown with a text label too. */
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
    val glucose: Color,
    val glucoseContainer: Color,
    val good: Color,
    val goodContainer: Color,
    /** Extra accents for icons that aren't data, so each part of the app is recognisable. */
    val indigo: Tint,
    val sky: Tint,
    val amber: Tint,
    val violet: Tint,
    val teal: Tint,
    val rose: Tint,
    val slate: Tint,
    val coral: Tint,
    val green: Tint,
    val cyan: Tint,
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
    glucose = Color(0xFFBE185D),
    glucoseContainer = Color(0xFFFCE7F3),
    good = Color(0xFF15803D),
    goodContainer = Color(0xFFDCFCE7),
    indigo = Tint(Color(0xFF4338CA), Color(0xFFE0E7FF)),
    sky = Tint(Color(0xFF0369A1), Color(0xFFE0F2FE)),
    amber = Tint(Color(0xFFB45309), Color(0xFFFEF3C7)),
    violet = Tint(Color(0xFF6D28D9), Color(0xFFEDE9FE)),
    teal = Tint(Color(0xFF0F766E), Color(0xFFCCFBF1)),
    rose = Tint(Color(0xFFBE185D), Color(0xFFFCE7F3)),
    slate = Tint(Color(0xFF475467), Color(0xFFE9EDF3)),
    coral = Tint(Color(0xFFD93838), Color(0xFFFFE4E1)),
    green = Tint(Color(0xFF15803D), Color(0xFFDCFCE7)),
    cyan = Tint(Color(0xFF0E7490), Color(0xFFCFFAFE)),
)

internal val DarkNeutrinoColors = NeutrinoColors(
    brand = BrandCoral,
    onBrand = Color(0xFF1A0B0A),
    carbs = Color(0xFFFBBF24),
    carbsContainer = Color(0x29FBBF24),
    protein = Color(0xFF2DD4BF),
    proteinContainer = Color(0x292DD4BF),
    fat = Color(0xFFC4B5FD),
    fatContainer = Color(0x29C4B5FD),
    water = Color(0xFF7DD3FC),
    waterContainer = Color(0x297DD3FC),
    glucose = Color(0xFFF9A8D4),
    glucoseContainer = Color(0x29F9A8D4),
    good = Color(0xFF4ADE80),
    goodContainer = Color(0x294ADE80),
    indigo = Tint(Color(0xFFA5B4FC), Color(0x29A5B4FC), solid = Color(0xFF4338CA)),
    sky = Tint(Color(0xFF7DD3FC), Color(0x297DD3FC), solid = Color(0xFF0369A1)),
    amber = Tint(Color(0xFFFBBF24), Color(0x29FBBF24), solid = Color(0xFFB45309)),
    violet = Tint(Color(0xFFC4B5FD), Color(0x29C4B5FD), solid = Color(0xFF6D28D9)),
    teal = Tint(Color(0xFF2DD4BF), Color(0x292DD4BF), solid = Color(0xFF0F766E)),
    rose = Tint(Color(0xFFF9A8D4), Color(0x29F9A8D4), solid = Color(0xFFBE185D)),
    slate = Tint(Color(0xFFB8C2D1), Color(0x29B8C2D1), solid = Color(0xFF475467)),
    coral = Tint(Color(0xFFFF6B63), Color(0x29FF6B63), solid = Color(0xFFD93838)),
    green = Tint(Color(0xFF4ADE80), Color(0x294ADE80), solid = Color(0xFF15803D)),
    cyan = Tint(Color(0xFF67E8F9), Color(0x2967E8F9), solid = Color(0xFF0E7490)),
)

val LocalNeutrinoColors = staticCompositionLocalOf { LightNeutrinoColors }
