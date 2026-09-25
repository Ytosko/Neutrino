package dev.ytosko.neutrino.widget

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
    val kcal: String,
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
        return WidgetData(
            carbs = compactGrams(day.totals.carbsG),
            kcal = compactNumber(day.totals.calories),
            water = formatWater(day.waterMl),
            glucose = glucose,
            canAddWater = day.waterMl + GLASS_ML <= MAX_WATER_ML,
        )
    }

    @Composable
    private fun Content(context: Context, data: WidgetData) {
        val colors = GlanceTheme.colors
        val open = actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(colors.widgetBackground)
                .padding(14.dp)
                .clickable(open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.getString(R.string.home_title),
                    style = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    context.getString(R.string.widget_totals, data.carbs, data.kcal),
                    style = TextStyle(color = colors.onSurfaceVariant, fontSize = 14.sp),
                    maxLines = 1,
                )
            }
            if (data.glucose != null) {
                Spacer(GlanceModifier.height(6.dp))
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        context.getString(R.string.glucose_title),
                        style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp),
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Text(data.glucose, style = TextStyle(color = colors.tertiary, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                }
            }
            Spacer(GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(context.getString(R.string.home_water), style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp))
                    Text(data.water, style = TextStyle(color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                }
                if (data.canAddWater) {
                    Button(
                        text = context.getString(R.string.home_add_glass),
                        onClick = actionRunCallback<AddWaterAction>(),
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer),
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

    companion object {
        const val GLASS_ML = 250
        const val MAX_WATER_ML = 10_000

        /** Redraws every Neutrino widget, e.g. after a meal, water or reading changes. */
        suspend fun refresh(context: Context) {
            runCatching { NeutrinoWidget().updateAll(context) }
        }
    }
}

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
