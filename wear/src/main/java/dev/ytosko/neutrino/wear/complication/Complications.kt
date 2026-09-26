package dev.ytosko.neutrino.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.ytosko.neutrino.wear.MainActivity
import dev.ytosko.neutrino.wear.R
import dev.ytosko.neutrino.wear.data.SnapshotStore
import dev.ytosko.neutrino.wear.protocol.GlucoseBand
import dev.ytosko.neutrino.wear.protocol.WearGlucose
import dev.ytosko.neutrino.wear.protocol.WearMacro
import dev.ytosko.neutrino.wear.ui.spoken

/** The latest blood glucose reading on a watch face: "6.4" over "mmol/L", or as a gauge. */
class GlucoseComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val glucose = SnapshotStore.current(this)?.glucose ?: return NoDataComplicationData()
        return glucoseData(this, request.complicationType, glucose)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        glucoseData(this, type, WearGlucose("6.4", "mmol/L", 6.4, System.currentTimeMillis(), GlucoseBand.InRange))

    private fun glucoseData(context: Context, type: ComplicationType, glucose: WearGlucose): ComplicationData? {
        val description = plain(glucose.spoken(context))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(glucose.value), description)
                .setTitle(plain(glucose.unit))
                .setTapAction(openApp(context))
                .build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = glucose.mmolPerL.toFloat().coerceIn(GAUGE_MIN_MMOL, GAUGE_MAX_MMOL),
                min = GAUGE_MIN_MMOL,
                max = GAUGE_MAX_MMOL,
                contentDescription = description,
            )
                .setText(plain(glucose.value))
                .setTitle(plain(glucose.unit))
                .setTapAction(openApp(context))
                .build()
            else -> null
        }
    }

    private companion object {
        /** The gauge runs over the meter's usual range, always in mmol/L whatever unit is shown. */
        const val GAUGE_MIN_MMOL = 2f
        const val GAUGE_MAX_MMOL = 20f
    }
}

/** Today's carbs on a watch face: "142g", or progress toward the carb goal when there is one. */
class CarbsComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val carbs = SnapshotStore.current(this)?.carbs ?: return NoDataComplicationData()
        return carbsData(this, request.complicationType, carbs)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        carbsData(this, type, WearMacro(amount = 96.0, text = "96g", goal = 150.0, goalText = "150g"))

    private fun carbsData(context: Context, type: ComplicationType, carbs: WearMacro): ComplicationData? {
        val label = context.getString(R.string.macro_carbs)
        val amount = carbs.goalText?.let { context.getString(R.string.widget_of_goal, carbs.text, it) } ?: carbs.text
        val description = plain("$label $amount")
        val goal = carbs.goal?.takeIf { it > 0 }
        return when (type) {
            // Without a goal the gauge stays empty (like the widget's bare track) and shows the amount.
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = goal?.let { carbs.amount.toFloat().coerceIn(0f, it.toFloat()) } ?: 0f,
                min = 0f,
                max = goal?.toFloat() ?: 1f,
                contentDescription = description,
            )
                .setText(plain(carbs.text))
                .setTitle(plain(label))
                .setTapAction(openApp(context))
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(carbs.text), description)
                .setTitle(plain(label))
                .setTapAction(openApp(context))
                .build()
            else -> null
        }
    }
}

private fun plain(text: String) = PlainComplicationText.Builder(text).build()

private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
