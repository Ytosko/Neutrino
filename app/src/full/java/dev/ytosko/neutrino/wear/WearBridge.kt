package dev.ytosko.neutrino.wear

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.ytosko.neutrino.appContainer
import dev.ytosko.neutrino.data.glucose.GlucoseEntity
import dev.ytosko.neutrino.data.meal.DaySummary
import dev.ytosko.neutrino.data.settings.AppSettings
import dev.ytosko.neutrino.domain.insights.compactGrams
import dev.ytosko.neutrino.domain.insights.compactNumber
import dev.ytosko.neutrino.domain.insights.formatWater
import dev.ytosko.neutrino.wear.protocol.GlucoseBand
import dev.ytosko.neutrino.wear.protocol.WearGlucose
import dev.ytosko.neutrino.wear.protocol.WearMacro
import dev.ytosko.neutrino.wear.protocol.WearPaths
import dev.ytosko.neutrino.wear.protocol.WearSnapshot
import dev.ytosko.neutrino.widget.NeutrinoWidget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sends today's totals, water and latest glucose reading to the user's own Wear OS watch, as one
 * DataItem at [WearPaths.TODAY]. It goes only to watches paired with this phone through Wear OS;
 * nothing is sent anywhere else. Without a watch (or without Wear OS on the phone) it quietly
 * does nothing.
 */
object WearBridge {

    /** The last snapshot sent, without its timestamp, so unchanged days aren't sent again. */
    @Volatile
    private var lastSent: WearSnapshot? = null

    suspend fun publish(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val container = app.appContainer
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val snapshot = snapshot(
            day = container.meals.observeDay(today, zone).first(),
            settings = container.settings.settings.first(),
            latest = container.glucose.latestReading.first(),
            today = today,
            now = System.currentTimeMillis(),
        )
        val comparable = snapshot.copy(publishedAtEpochMs = 0)
        if (!force && comparable == lastSent) return
        val request = PutDataMapRequest.create(WearPaths.TODAY).apply {
            dataMap.putString(WearPaths.SNAPSHOT_KEY, snapshot.encode())
        }.asPutDataRequest().setUrgent()
        val sent = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching { Wearable.getDataClient(app).putDataItem(request).await() }.isSuccess
        } == true
        if (sent) lastSent = comparable
    }

    private fun snapshot(day: DaySummary, settings: AppSettings, latest: GlucoseEntity?, today: LocalDate, now: Long): WearSnapshot {
        val n = day.totals
        fun grams(amount: Double, goal: Int?) = WearMacro(
            amount = amount,
            text = compactGrams(amount),
            goal = goal?.toDouble(),
            goalText = goal?.let { compactGrams(it.toDouble()) },
        )
        return WearSnapshot(
            date = today.toString(),
            carbs = grams(n.carbsG, settings.carbGoalG),
            protein = grams(n.proteinG, settings.proteinGoalG),
            fat = grams(n.fatG, settings.fatGoalG),
            kcal = WearMacro(
                amount = n.calories,
                text = compactNumber(n.calories),
                goal = settings.kcalGoal?.toDouble(),
                goalText = settings.kcalGoal?.let { compactNumber(it.toDouble()) },
            ),
            waterMl = day.waterMl,
            waterText = formatWater(day.waterMl),
            waterGoalMl = settings.waterGoalMl,
            canAddWater = day.waterMl + NeutrinoWidget.GLASS_ML <= NeutrinoWidget.MAX_WATER_ML,
            glucose = latest?.let { reading ->
                // Same wording and colours as the glucose widget.
                WearGlucose(
                    value = when (reading.rangeFlag) {
                        "High" -> "HI"
                        "Low" -> "LO"
                        else -> settings.glucoseUnit.format(reading.mmolPerL)
                    },
                    unit = settings.glucoseUnit.label,
                    mmolPerL = reading.mmolPerL,
                    measuredAtEpochMs = reading.measuredAtEpochMs,
                    band = when {
                        reading.rangeFlag == "Low" || reading.mmolPerL < settings.glucoseLow -> GlucoseBand.Low
                        reading.rangeFlag == "High" || reading.mmolPerL > settings.glucoseHigh -> GlucoseBand.High
                        else -> GlucoseBand.InRange
                    },
                )
            },
            publishedAtEpochMs = now,
        )
    }

    private const val TIMEOUT_MS = 10_000L
}

/** Waits for a Play services [Task] without pulling in another library. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
