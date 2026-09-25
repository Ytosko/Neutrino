package dev.ytosko.neutrino.data.reminders

import dev.ytosko.neutrino.data.settings.AppSettings
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.ytosko.neutrino.MainActivity
import dev.ytosko.neutrino.R
import dev.ytosko.neutrino.appContainer
import dev.ytosko.neutrino.domain.MealType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One daily reminder: which meal, its default time, and what it says. */
enum class MealReminder(val mealType: MealType, val time: LocalTime, @StringRes val title: Int, @StringRes val body: Int) {
    Breakfast(MealType.Breakfast, LocalTime.of(10, 0), R.string.reminder_breakfast_title, R.string.reminder_breakfast_body),
    Lunch(MealType.Lunch, LocalTime.of(14, 0), R.string.reminder_lunch_title, R.string.reminder_lunch_body),
    Dinner(MealType.Dinner, LocalTime.of(18, 0), R.string.reminder_dinner_title, R.string.reminder_dinner_body),
    ;

    /** The user's chosen time for this reminder. */
    fun timeIn(settings: AppSettings): LocalTime = when (this) {
        Breakfast -> settings.breakfastReminder
        Lunch -> settings.lunchReminder
        Dinner -> settings.dinnerReminder
    }
}

/**
 * Daily breakfast, lunch and dinner reminders. Uses inexact alarms (no special permission): each
 * fires within a few minutes of its time, then schedules the next day's. A reminder is skipped
 * when that meal is already logged.
 */
object MealReminders {

    private const val CHANNEL = "meal_reminders"
    private const val WINDOW_MS = 10 * 60 * 1000L
    const val EXTRA_REMINDER = "reminder"

    /** Next time [time] happens after [now]: today if still ahead, otherwise tomorrow. */
    fun nextTrigger(now: ZonedDateTime, time: LocalTime): ZonedDateTime {
        val today = now.with(time).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** Books all three at the times chosen in settings. */
    suspend fun scheduleAll(context: Context) {
        val settings = context.appContainer.settings.settings.first()
        MealReminder.entries.forEach { schedule(context, it, it.timeIn(settings)) }
    }

    fun cancelAll(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        MealReminder.entries.forEach { alarms.cancel(pendingIntent(context, it)) }
    }

    fun schedule(context: Context, reminder: MealReminder, time: LocalTime, now: ZonedDateTime = ZonedDateTime.now()) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = nextTrigger(now, time).toInstant().toEpochMilli()
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pendingIntent(context, reminder))
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    internal fun show(context: Context, reminder: MealReminder) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.reminder_channel_body)
            },
        )
        val open = PendingIntent.getActivity(
            context,
            reminder.ordinal,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neutrino_mark)
            .setColor(context.getColor(R.color.brand_coral))
            .setContentTitle(context.getString(reminder.title))
            .setContentText(context.getString(reminder.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(reminder.ordinal + 100, notification) }
    }

    private fun pendingIntent(context: Context, reminder: MealReminder): PendingIntent = PendingIntent.getBroadcast(
        context,
        reminder.ordinal,
        Intent(context, MealReminderReceiver::class.java).putExtra(EXTRA_REMINDER, reminder.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires a reminder (and books tomorrow's), and re-books all of them after a reboot or clock change. */
class MealReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val container = app.appContainer
                // After a reboot or update, also restart the meter's background wake-ups.
                if (intent.getStringExtra(MealReminders.EXTRA_REMINDER) == null) runCatching { container.watchMeters() }
                val settings = container.settings.settings.first()
                val enabled = settings.remindersEnabled
                val reminder = intent.getStringExtra(MealReminders.EXTRA_REMINDER)
                    ?.let { name -> MealReminder.entries.firstOrNull { it.name == name } }
                when {
                    !enabled -> MealReminders.cancelAll(app)
                    reminder == null -> MealReminders.scheduleAll(app) // boot, time or time zone change, update
                    else -> {
                        MealReminders.schedule(app, reminder, reminder.timeIn(settings))
                        val zone = ZoneId.systemDefault()
                        if (!container.meals.hasMeal(reminder.mealType, LocalDate.now(zone), zone)) {
                            MealReminders.show(app, reminder)
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
