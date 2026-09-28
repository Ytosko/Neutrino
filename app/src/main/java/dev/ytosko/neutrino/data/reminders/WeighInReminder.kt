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
import dev.ytosko.neutrino.data.goals.WeighIn
import dev.ytosko.neutrino.widget.LaunchAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * The weekly weigh-in: a reminder (Monday 9 AM unless changed) to enter the current weight, which
 * opens Daily goals with the weight editor. Only booked once a weight has been entered.
 */
// Notifications are posted only after MealReminders.canNotify() confirmed the permission.
@android.annotation.SuppressLint("MissingPermission")
object WeighInReminder {

    private const val CHANNEL = "weigh_in"
    private const val NOTIFICATION_ID = 330
    private const val WINDOW_MS = 15 * 60 * 1000L

    fun nextTrigger(w: WeighIn, now: ZonedDateTime): ZonedDateTime {
        val time = LocalTime.of(w.minute / 60, w.minute % 60)
        val day = DayOfWeek.of(w.day.coerceIn(1, 7))
        val next = now.with(TemporalAdjusters.nextOrSame(day)).with(time).withSecond(0).withNano(0)
        return if (next.isAfter(now)) next else next.plusWeeks(1)
    }

    /** Books or cancels to match the setting (and whether there's a weight to update). */
    suspend fun sync(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val profile = context.appContainer.goalProfile
        val w = profile.weighIn.first()
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (!w.on || profile.physique.first().weightKg == null) {
            alarms.cancel(pendingIntent(context))
            return
        }
        alarms.setWindow(AlarmManager.RTC_WAKEUP, nextTrigger(w, now).toInstant().toEpochMilli(), WINDOW_MS, pendingIntent(context))
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, NOTIFICATION_ID, Intent(context, WeighInReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal suspend fun fire(context: Context) {
        sync(context, ZonedDateTime.now().plusMinutes(1))
        val profile = context.appContainer.goalProfile
        if (!profile.weighIn.first().on || profile.physique.first().weightKg == null) return
        if (!MealReminders.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.weigh_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.weigh_channel_body)
            },
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .setAction(LaunchAction.UPDATE_WEIGHT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neutrino_mark)
            .setColor(context.getColor(R.color.brand_coral))
            .setContentTitle(context.getString(R.string.weigh_title))
            .setContentText(context.getString(R.string.weigh_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

class WeighInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                WeighInReminder.fire(app)
            } finally {
                pending.finish()
            }
        }
    }
}
