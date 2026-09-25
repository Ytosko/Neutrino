package dev.ytosko.neutrino.widget

import androidx.glance.layout.size
import androidx.glance.ColorFilter
import android.graphics.PathMeasure as AndroidPathMeasure
import android.graphics.Path as AndroidPath
import android.graphics.Canvas as AndroidCanvas
import androidx.glance.layout.Box
import androidx.glance.LocalSize
import androidx.glance.Image
import androidx.compose.ui.unit.Dp
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Bitmap
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.material3.ColorProviders
import dev.ytosko.neutrino.ui.theme.DarkColors
import dev.ytosko.neutrino.ui.theme.LightColors
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.ytosko.neutrino.MainActivity
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.appContainer
import dev.ytosko.neutrino.domain.insights.compactGrams
import dev.ytosko.neutrino.domain.insights.compactNumber
import dev.ytosko.neutrino.domain.insights.formatWater
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the widget shows, read fresh each time it updates. */
private data class WidgetData(
    val carbs: String,
    val protein: String,
    val fat: String,
    val kcal: String,
    /** Progress toward each daily goal (0..1+), or null without a goal. */
    val carbsProgress: Float?,
    val proteinProgress: Float?,
    val fatProgress: Float?,
    val kcalProgress: Float?,
    /** Bare numbers ("243") and goals ("150g") for the line under each ring; goal null without one. */
    val carbsNumber: String,
    val proteinNumber: String,
    val fatNumber: String,
    val kcalNumber: String,
    val carbsGoal: String?,
    val proteinGoal: String?,
    val fatGoal: String?,
    val kcalGoal: String?,
    val water: String,
    /** "7.2 mmol/L · 6:08 PM", or null when hidden or there are no readings. */
    val glucose: String?,
    val canAddWater: Boolean,
)

/**
 * Home screen widget: today's carbs and calories, water with a one-tap +250 ml, a camera button
 * that opens straight into logging a meal, and (unless turned off) the latest glucose reading.
 */
class NeutrinoWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = data(context).first()
        provideContent {
            // Live while the widget is showing: water, meals and readings update it straight away.
            val data by remember { data(context) }.collectAsState(initial)
            GlanceTheme(colors = NeutrinoWidgetColors.colors) { Content(context, data) }
        }
    }

    private fun data(context: Context): Flow<WidgetData> {
        val container = context.appContainer
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return combine(container.meals.observeDay(today, zone), container.settings.settings, container.glucose.latestReading) { day, settings, latest ->
            toData(day, settings, latest, zone, today)
        }
    }

    private fun toData(
        day: dev.ytosko.neutrino.data.meal.DaySummary,
        settings: dev.ytosko.neutrino.data.settings.AppSettings,
        latest: dev.ytosko.neutrino.data.glucose.GlucoseEntity?,
        zone: ZoneId,
        today: LocalDate,
    ): WidgetData {
        val glucose = if (settings.widgetShowsGlucose) {
            latest?.let { reading ->
                val at = Instant.ofEpochMilli(reading.measuredAtEpochMs).atZone(zone)
                val whenText = if (at.toLocalDate() == today) {
                    at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                } else {
                    at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
                }
                val value = when (reading.rangeFlag) {
                    "High" -> "HI"
                    "Low" -> "LO"
                    else -> settings.glucoseUnit.format(reading.mmolPerL)
                }
                "$value ${settings.glucoseUnit.label} · $whenText"
            }
        } else {
            null
        }
        val n = day.totals
        return WidgetData(
            carbs = compactGrams(n.carbsG),
            protein = compactGrams(n.proteinG),
            fat = compactGrams(n.fatG),
            kcal = compactNumber(n.calories),
            carbsProgress = settings.carbGoalG?.let { (n.carbsG / it).toFloat() },
            proteinProgress = settings.proteinGoalG?.let { (n.proteinG / it).toFloat() },
            fatProgress = settings.fatGoalG?.let { (n.fatG / it).toFloat() },
            kcalProgress = settings.kcalGoal?.let { (n.calories / it).toFloat() },
            carbsNumber = compactNumber(n.carbsG),
            proteinNumber = compactNumber(n.proteinG),
            fatNumber = compactNumber(n.fatG),
            kcalNumber = compactNumber(n.calories),
            carbsGoal = settings.carbGoalG?.let { compactGrams(it.toDouble()) },
            proteinGoal = settings.proteinGoalG?.let { compactGrams(it.toDouble()) },
            fatGoal = settings.fatGoalG?.let { compactGrams(it.toDouble()) },
            kcalGoal = settings.kcalGoal?.let { compactNumber(it.toDouble()) },
            water = formatWater(day.waterMl),
            glucose = glucose,
            canAddWater = day.waterMl + GLASS_ML <= MAX_WATER_ML,
        )
    }

    @Composable
    private fun Content(context: Context, data: WidgetData) {
        val colors = GlanceTheme.colors
        val size = LocalSize.current
        val open = actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Room for the four macro tiles on a normal 4×2 widget; a squat widget keeps one summary line.
        val showTiles = size.height >= 150.dp
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(colors.widgetBackground)
                .padding(horizontal = PADDING, vertical = PADDING)
                .clickable(open),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.home_title),
                    style = TextStyle(color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.defaultWeight())
                when {
                    data.glucose != null -> Text(data.glucose, style = TextStyle(color = GlucoseColor, fontSize = 11.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    !showTiles -> Text(
                        context.getString(R.string.widget_totals, data.carbs, data.kcal),
                        style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
            if (showTiles) {
                // Four activity-style rings, as big as the space allows, with equal room above and below.
                val cell = (size.width - PADDING * 2 - RING_GAP * 3) / 4
                val diameter = minOf(cell, size.height - PADDING * 2 - HEADER_HEIGHT - BOTTOM_HEIGHT - 8.dp).coerceAtLeast(48.dp)
                fun of(number: String, goal: String?) = goal?.let { context.getString(R.string.widget_of_goal, number, it) }
                val tiles = listOf(
                    Tile(data.carbs, of(data.carbsNumber, data.carbsGoal), context.getString(R.string.macro_carbs), MacroColor.Carbs, data.carbsProgress),
                    Tile(data.protein, of(data.proteinNumber, data.proteinGoal), context.getString(R.string.macro_protein), MacroColor.Protein, data.proteinProgress),
                    Tile(data.fat, of(data.fatNumber, data.fatGoal), context.getString(R.string.macro_fat), MacroColor.Fat, data.fatProgress),
                    Tile(data.kcal, of(data.kcalNumber, data.kcalGoal), context.getString(R.string.macro_energy), MacroColor.Kcal, data.kcalProgress),
                )
                Spacer(GlanceModifier.defaultWeight())
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    tiles.forEachIndexed { index, tile ->
                        if (index > 0) Spacer(GlanceModifier.width(RING_GAP))
                        Box(modifier = GlanceModifier.width(cell), contentAlignment = Alignment.Center) {
                            MacroRing(context, tile, diameter)
                        }
                    }
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(context.getString(R.string.home_water), style = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp))
                    Text(data.water, style = TextStyle(color = WaterColor, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                }
                if (data.canAddWater) {
                    Button(
                        text = context.getString(R.string.home_add_glass),
                        onClick = actionRunCallback<AddWaterAction>(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = WaterContainerColor, contentColor = WaterColor),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                }
                CircleIconButton(
                    imageProvider = ImageProvider(R.drawable.ic_camera),
                    contentDescription = context.getString(R.string.home_log_meal),
                    onClick = actionStartActivity(
                        Intent(context, MainActivity::class.java)
                            .setAction(LaunchAction.LOG_MEAL_PHOTO)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    ),
                    backgroundColor = colors.primary,
                    contentColor = colors.onPrimary,
                )
            }
        }
    }

    /**
     * One macro as a ring: progress toward the goal round the outside, and the amount with its
     * name ("30g" over "Protein") in the middle. Widgets can't draw, so the track and the arc are
     * white masks tinted with day/night colours. Without a goal only the track shows.
     */
    @Composable
    private fun MacroRing(context: Context, tile: Tile, diameter: Dp) {
        val density = context.resources.displayMetrics.density
        val px = (diameter.value * density).toInt().coerceAtLeast(1)
        val layers = ringLayers(px, density, tile.progress ?: 0f)
        Box(modifier = GlanceModifier.size(diameter), contentAlignment = Alignment.Center) {
            Image(ImageProvider(layers.track), contentDescription = null, colorFilter = ColorFilter.tint(tile.color.track), modifier = GlanceModifier.fillMaxSize())
            Image(
                ImageProvider(layers.arc),
                contentDescription = listOfNotNull(tile.label, tile.ofGoal ?: tile.value).joinToString(" "),
                colorFilter = ColorFilter.tint(tile.color.provider),
                modifier = GlanceModifier.fillMaxSize(),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    tile.value,
                    style = TextStyle(
                        color = tile.color.provider,
                        fontSize = (diameter.value * 0.19f).coerceIn(12f, 20f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
                Text(
                    tile.label,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
        }
    }

    companion object {
        private val PADDING = 12.dp
        private val RING_GAP = 4.dp
        /** Measured: the "Today" line, and the water row with its buttons. */
        private val HEADER_HEIGHT = 18.dp
        private val BOTTOM_HEIGHT = 48.dp
        const val GLASS_ML = 250
        const val MAX_WATER_ML = 10_000

        /** Redraws every Neutrino widget, e.g. after a meal, water or reading changes. */
        suspend fun refresh(context: Context) {
            runCatching { NeutrinoWidget().updateAll(context) }
        }
    }
}

private data class Tile(val value: String, val ofGoal: String?, val label: String, val color: MacroColor, val progress: Float?)

/** The app's macro colours for light and dark home screens. */
private enum class MacroColor(val day: Long, val night: Long) {
    Carbs(0xFFB45309, 0xFFFBBF24),
    Protein(0xFF0F766E, 0xFF2DD4BF),
    Fat(0xFF6D28D9, 0xFFC4B5FD),
    Kcal(0xFF1A1D23, 0xFFECEEF2),
    ;

    val provider get() = androidx.glance.color.ColorProvider(day = androidx.compose.ui.graphics.Color(day), night = androidx.compose.ui.graphics.Color(night))

    /** The ring's faint track in the same colour. */
    val track get() = androidx.glance.color.ColorProvider(
        day = androidx.compose.ui.graphics.Color(day).copy(alpha = 0.16f),
        night = androidx.compose.ui.graphics.Color(night).copy(alpha = 0.22f),
    )
}

private val WaterColor = androidx.glance.color.ColorProvider(day = androidx.compose.ui.graphics.Color(0xFF0369A1), night = androidx.compose.ui.graphics.Color(0xFF7DD3FC))
private val WaterContainerColor = androidx.glance.color.ColorProvider(day = androidx.compose.ui.graphics.Color(0xFFE0F2FE), night = androidx.compose.ui.graphics.Color(0xFF1B3A4D))

private class RingLayers(val track: Bitmap, val arc: Bitmap)

/** White masks for one ring, [px] square: the full track, and the progress arc from 12 o'clock, clockwise. */
private fun ringLayers(px: Int, density: Float, progress: Float): RingLayers {
    val stroke = px * 0.09f
    val radius = px / 2f - stroke / 2 - density
    val c = px / 2f
    fun blank() = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = 0xFFFFFFFF.toInt()
    }
    val oval = RectF(c - radius, c - radius, c + radius, c + radius)
    val track = blank().also { AndroidCanvas(it).drawOval(oval, paint) }
    val arc = blank().also { bitmap ->
        val fraction = progress.coerceIn(0f, 1f)
        if (fraction > 0f) {
            paint.strokeCap = if (fraction >= 1f) Paint.Cap.BUTT else Paint.Cap.ROUND
            AndroidCanvas(bitmap).drawArc(oval, -90f, 360f * fraction, false, paint)
        }
    }
    return RingLayers(track, arc)
}

/** Glucose magenta, as in the app. */
private val GlucoseColor = androidx.glance.color.ColorProvider(day = androidx.compose.ui.graphics.Color(0xFFBE185D), night = androidx.compose.ui.graphics.Color(0xFFF9A8D4))

/** The app's own colours (light and dark), so the widget looks like Neutrino. */
private object NeutrinoWidgetColors {
    val colors = ColorProviders(light = LightColors, dark = DarkColors)
}

/** +250 ml from the widget, without opening the app. */
class AddWaterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val container = context.appContainer
        val zone = ZoneId.systemDefault()
        val water = container.meals.observeDay(LocalDate.now(zone), zone).first().waterMl
        if (water + NeutrinoWidget.GLASS_ML <= NeutrinoWidget.MAX_WATER_ML) {
            container.meals.addWater(NeutrinoWidget.GLASS_ML, zone)
        }
        NeutrinoWidget.refresh(context)
    }
}

class NeutrinoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NeutrinoWidget()
}

/** Things a widget or app shortcut asks the app to do when it opens. */
object LaunchAction {
    const val LOG_MEAL_PHOTO = "dev.ytosko.neutrino.action.LOG_MEAL_PHOTO"
    const val LOG_MEAL = "dev.ytosko.neutrino.action.LOG_MEAL"
    const val ADD_WATER = "dev.ytosko.neutrino.action.ADD_WATER"
}
