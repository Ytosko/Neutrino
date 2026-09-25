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

internal val NeutrinoTypography = Typography(
    displayLarge = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum"),
    displayMedium = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, lineHeight = 48.sp, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum"),
    displaySmall = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum"),
    headlineLarge = base.copy(fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum"),
    headlineMedium = base.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum"),
    headlineSmall = base.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, fontFeatureSettings = "tnum"),
    titleLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, fontFeatureSettings = "tnum"),
    titleMedium = base.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum"),
    titleSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = base.copy(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.copy(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = base.copy(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = base.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum"),
    labelMedium = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = base.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.04.em),
)
