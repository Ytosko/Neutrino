package dev.ytosko.neutrino.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import dev.ytosko.neutrino.MainActivity
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.appContainer
import dev.ytosko.neutrino.ui.theme.DarkColors
import dev.ytosko.neutrino.ui.theme.LightColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the glucose widget shows: the latest reading, or nothing yet. */
private data class GlucoseWidgetData(
    /** "6.4", "HI" or "LO"; null without readings. */
    val value: String?,
    val unit: String,
    /** "2:57 AM · In range". */
    val detail: String,
    val band: Band,
)

private enum class Band(val day: Long, val night: Long) {
    Low(0xFFB42318, 0xFFFF8A80),
    InRange(0xFF15803D, 0xFF4ADE80),
    High(0xFFB45309, 0xFFFBBF24),
    None(0xFF6B5E5A, 0xFFB8ABA6),
    ;

    val provider: GlanceColorProvider get() = ColorProvider(day = Color(day), night = Color(night))
}

/**
 * A small 2×1 widget with just the latest blood glucose reading, coloured by the user's target
 * range. Tapping it opens Neutrino. It shows only what the app already stores on the phone.
 */
class GlucoseWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = data(context).first()
        provideContent {
            val data by remember { data(context) }.collectAsState(initial)
            GlanceTheme(colors = ColorProviders(light = LightColors, dark = DarkColors)) { Content(context, data) }
        }
    }

    private fun data(context: Context): Flow<GlucoseWidgetData> {
        val container = context.appContainer
        val zone = ZoneId.systemDefault()
        return combine(container.glucose.latestReading, container.settings.settings) { reading, settings ->
            val unit = settings.glucoseUnit.label
            if (reading == null) {
                return@combine GlucoseWidgetData(null, unit, context.getString(R.string.glucose_widget_none), Band.None)
            }
            val at = Instant.ofEpochMilli(reading.measuredAtEpochMs).atZone(zone)
            val whenText = if (at.toLocalDate() == LocalDate.now(zone)) {
                at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            } else {
                at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
            }
            val band = when {
                reading.rangeFlag == "Low" || reading.mmolPerL < settings.glucoseLow -> Band.Low
                reading.rangeFlag == "High" || reading.mmolPerL > settings.glucoseHigh -> Band.High
                else -> Band.InRange
            }
            val bandText = context.getString(
                when (band) {
                    Band.Low -> R.string.glucose_low
                    Band.High -> R.string.glucose_high
                    else -> R.string.glucose_in_range
                },
            )
            val value = when (reading.rangeFlag) {
                "High" -> "HI"
                "Low" -> "LO"
                else -> settings.glucoseUnit.format(reading.mmolPerL)
            }
            GlucoseWidgetData(value, unit, "$whenText · $bandText", band)
        }
    }

    @Composable
    private fun Content(context: Context, data: GlucoseWidgetData) {
        val colors = GlanceTheme.colors
        val open = actionStartActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(colors.widgetBackground)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clickable(open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                ImageProvider(R.drawable.ic_activity),
                contentDescription = context.getString(R.string.glucose_title),
                colorFilter = ColorFilter.tint(GlucoseMagenta),
                modifier = GlanceModifier.size(24.dp),
            )
            Spacer(GlanceModifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        data.value ?: "–",
                        style = TextStyle(color = data.band.provider, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    if (data.value != null) {
                        Spacer(GlanceModifier.width(4.dp))
                        Text(
                            data.unit,
                            style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp),
                            maxLines = 1,
                            modifier = GlanceModifier.padding(bottom = 3.dp),
                        )
                    }
                }
                Text(data.detail, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 1)
            }
        }
    }
}

private val GlucoseMagenta = ColorProvider(day = Color(0xFFBE185D), night = Color(0xFFF9A8D4))

class GlucoseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GlucoseWidget()
}
