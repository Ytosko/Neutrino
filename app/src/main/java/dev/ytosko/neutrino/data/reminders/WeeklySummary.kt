package dev.ytosko.neutrino.data.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.ytosko.neutrino.MainActivity
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.appContainer
import dev.ytosko.neutrino.domain.insights.compactNumber
import dev.ytosko.neutrino.domain.insights.formatWater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** Optional Sunday-evening notification summing up the last 7 days. Values are hidden on the lock screen. */
// Notifications are posted only after MealReminders.canNotify() confirmed the permission.
@android.annotation.SuppressLint("MissingPermission")
object WeeklySummary {

    private const val CHANNEL = "weekly_summary"
    private const val NOTIFICATION_ID = 320
    private const val WINDOW_MS = 30 * 60 * 1000L
    private val TIME: LocalTime = LocalTime.of(19, 0)

    /** Next Sunday at 7 PM (today, if it's Sunday before 7 PM). */
    fun nextTrigger(now: ZonedDateTime): ZonedDateTime {
        val sunday = now.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).with(TIME).withSecond(0).withNano(0)
        return if (sunday.isAfter(now)) sunday else sunday.plusWeeks(1)
    }

    fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        context.getSystemService(AlarmManager::class.java)
            .setWindow(AlarmManager.RTC_WAKEUP, nextTrigger(now).toInstant().toEpochMilli(), WINDOW_MS, pendingIntent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    /** Books or cancels to match the setting. */
    suspend fun sync(context: Context) {
        if (context.appContainer.settings.settings.first().weeklySummary) schedule(context) else cancel(context)
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, NOTIFICATION_ID, Intent(context, WeeklySummaryReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal suspend fun fire(context: Context) {
        val container = context.appContainer
        val settings = container.settings.settings.first()
        if (!settings.weeklySummary) return
        schedule(context)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.minusDays(6)
        val data = container.meals.observeRange(from, today, zone).first()
        val loggedDays = data.meals.mapTo(HashSet()) { it.date }.size
        val waterDays = data.water.mapTo(HashSet()) { it.date }.size
        val parts = mutableListOf<String>()
        if (loggedDays > 0) {
            val carbs = data.meals.sumOf { it.nutrition.carbsG } / loggedDays
            val kcal = data.meals.sumOf { it.nutrition.calories } / loggedDays
            parts += context.getString(R.string.weekly_food, carbs.roundToInt(), compactNumber(kcal), loggedDays)
        }
        if (waterDays > 0) parts += context.getString(R.string.weekly_water, formatWater(data.water.sumOf { it.ml } / waterDays))
        val readings = container.glucose.observeBetween(from, today, zone).first()
        if (readings.isNotEmpty()) {
            val inRange = readings.count { it.mmolPerL in settings.glucoseLow..settings.glucoseHigh }
            parts += context.getString(R.string.weekly_glucose, (inRange * 100.0 / readings.size).roundToInt(), readings.size)
        }
        if (parts.isEmpty()) return
        show(context, parts.joinToString("\n"))
    }

    private fun show(context: Context, text: String) {
        if (!MealReminders.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.weekly_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.weekly_channel_body)
            },
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(R.string.weekly_title)
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neutrino_mark)
            .setContentTitle(title)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neutrino_mark)
            .setColor(context.getColor(R.color.brand_coral))
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

class WeeklySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                WeeklySummary.fire(app)
            } finally {
                pending.finish()
            }
        }
    }
}
