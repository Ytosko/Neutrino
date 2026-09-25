package dev.ytosko.neutrino.widget

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
                .padding(horizontal = PADDING, vertical = 12.dp)
                .clickable(open),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.home_title),
                    style = TextStyle(color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.defaultWeight())
                when {
                    data.glucose != null -> Text(data.glucose, style = TextStyle(color = GlucoseColor, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                    !showTiles -> Text(
                        context.getString(R.string.widget_totals, data.carbs, data.kcal),
                        style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp),
                        maxLines = 1,
                    )
                }
            }
            if (showTiles) {
                Spacer(GlanceModifier.defaultWeight())
                val tileWidth = (size.width - PADDING * 2 - TILE_GAP * 3) / 4
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    val tiles = listOf(
                        Tile(data.carbs, context.getString(R.string.macro_carbs), MacroColor.Carbs, data.carbsProgress),
                        Tile(data.protein, context.getString(R.string.macro_protein), MacroColor.Protein, data.proteinProgress),
                        Tile(data.fat, context.getString(R.string.macro_fat), MacroColor.Fat, data.fatProgress),
                        Tile(data.kcal, context.getString(R.string.macro_energy), MacroColor.Kcal, data.kcalProgress),
                    )
                    tiles.forEachIndexed { index, tile ->
                        if (index > 0) Spacer(GlanceModifier.width(TILE_GAP))
                        MacroTile(context, tile, tileWidth)
                    }
                }
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(context.getString(R.string.home_water), style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp))
                    Text(data.water, style = TextStyle(color = WaterColor, fontSize = 16.sp, fontWeight = FontWeight.Bold))
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
     * A macro tile. Widgets can't draw shapes, so the goal ring is an image: a white mask tinted in
     * the macro's day or night colour, over the tile's own day/night background.
     */
    @Composable
    private fun MacroTile(context: Context, tile: Tile, width: Dp) {
        val density = context.resources.displayMetrics.density
        Box(
            modifier = GlanceModifier.width(width).height(TILE_HEIGHT).cornerRadius(14.dp).background(TileFill),
            contentAlignment = Alignment.Center,
        ) {
            if (tile.progress != null) {
                val mask = ringMask(
                    widthPx = (width.value * density).toInt().coerceAtLeast(1),
                    heightPx = (TILE_HEIGHT.value * density).toInt(),
                    density = density,
                    progress = tile.progress,
                )
                Image(
                    ImageProvider(mask),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(tile.color.provider),
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    tile.value,
                    style = TextStyle(color = tile.color.provider, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(tile.label, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp), maxLines = 1)
            }
        }
    }

    companion object {
        private val PADDING = 12.dp
        private val TILE_GAP = 6.dp
        private val TILE_HEIGHT = 54.dp
        const val GLASS_ML = 250
        const val MAX_WATER_ML = 10_000

        /** Redraws every Neutrino widget, e.g. after a meal, water or reading changes. */
        suspend fun refresh(context: Context) {
            runCatching { NeutrinoWidget().updateAll(context) }
        }
    }
}

private data class Tile(val value: String, val label: String, val color: MacroColor, val progress: Float?)

/** The app's macro colours for light and dark home screens. */
private enum class MacroColor(val day: Long, val night: Long) {
    Carbs(0xFFB45309, 0xFFFBBF24),
    Protein(0xFF0F766E, 0xFF2DD4BF),
    Fat(0xFF6D28D9, 0xFFC4B5FD),
    Kcal(0xFF1A1D23, 0xFFECEEF2),
    ;

    val provider get() = androidx.glance.color.ColorProvider(day = androidx.compose.ui.graphics.Color(day), night = androidx.compose.ui.graphics.Color(night))
}

private val WaterColor = androidx.glance.color.ColorProvider(day = androidx.compose.ui.graphics.Color(0xFF0369A1), night = androidx.compose.ui.graphics.Color(0xFF7DD3FC))
private val WaterContainerColor = androidx.glance.color.ColorProvider(day = androidx.compose.ui.graphics.Color(0xFFE0F2FE), night = androidx.compose.ui.graphics.Color(0xFF1B3A4D))

private val TileFill = androidx.glance.color.ColorProvider(day = androidx.compose.ui.graphics.Color(0xFFFFFFFF), night = androidx.compose.ui.graphics.Color(0xFF1A1D24))

/**
 * The goal ring as a white mask (tinted by the widget): a faint track all round and the progress
 * from the top centre, clockwise, closing thicker once the goal is reached.
 */
private fun ringMask(widthPx: Int, heightPx: Int, density: Float, progress: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val radius = 14f * density
    val done = progress >= 1f
    val stroke = (if (done) 3f else 2.2f) * density
    val inset = stroke / 2
    val path = AndroidPath().apply {
        val l = inset
        val t = inset
        val r = widthPx - inset
        val b = heightPx - inset
        val rr = radius - inset
        moveTo((l + r) / 2, t)
        lineTo(r - rr, t)
        arcTo(RectF(r - 2 * rr, t, r, t + 2 * rr), -90f, 90f)
        lineTo(r, b - rr)
        arcTo(RectF(r - 2 * rr, b - 2 * rr, r, b), 0f, 90f)
        lineTo(l + rr, b)
        arcTo(RectF(l, b - 2 * rr, l + 2 * rr, b), 90f, 90f)
        lineTo(l, t + rr)
        arcTo(RectF(l, t, l + 2 * rr, t + 2 * rr), 180f, 90f)
        close()
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = 0x38FFFFFF
    }
    canvas.drawPath(path, paint)
    val fraction = progress.coerceIn(0f, 1f)
    if (fraction > 0f) {
        val measure = AndroidPathMeasure(path, false)
        val part = AndroidPath()
        measure.getSegment(0f, measure.length * fraction, part, true)
        paint.color = 0xFFFFFFFF.toInt()
        paint.strokeWidth = stroke
        paint.strokeCap = if (done) Paint.Cap.BUTT else Paint.Cap.ROUND
        canvas.drawPath(part, paint)
    }
    return bitmap
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
