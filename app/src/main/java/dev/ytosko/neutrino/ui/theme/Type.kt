package dev.ytosko.neutrino.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.ytosko.neutrino.R

private fun jakarta(weight: Int) = Font(
    resId = R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Plus Jakarta Sans (variable font, OFL), the same typeface as the website. */
val PlusJakartaSans = FontFamily(
    jakarta(400),
    jakarta(500),
    jakarta(600),
    jakarta(700),
    jakarta(800),
)

private val base = TextStyle(fontFamily = PlusJakartaSans)

/*
 * Type scale: sizes are the original design scaled to 80% and rounded to whole numbers, never
 * below 11 sp so the smallest text stays readable. The phone's own font-size setting applies on top.
 */
internal val NeutrinoTypography = Typography(
    displayLarge = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum"),
    displayMedium = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum"),
    displaySmall = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 27.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum"),
    headlineLarge = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum"),
    headlineMedium = base.copy(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum"),
    headlineSmall = base.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = (-0.01).em, fontFeatureSettings = "tnum"),
    titleLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp, fontFeatureSettings = "tnum"),
    titleMedium = base.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum"),
    titleSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp),
    bodyLarge = base.copy(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodyMedium = base.copy(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 17.sp),
    bodySmall = base.copy(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp),
    labelLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
    labelMedium = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.04.em),
)
