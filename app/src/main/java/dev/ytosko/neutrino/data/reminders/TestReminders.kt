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
import dev.ytosko.neutrino.domain.MealType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Optional "time to test" reminder some time (2 hours by default) after a meal. Only the most
 * recent meal counts, and it's skipped if a glucose reading already arrived after eating.
 */
// Notifications are posted only after MealReminders.canNotify() confirmed the permission.
@android.annotation.SuppressLint("MissingPermission")
object TestReminders {

    private const val CHANNEL = "glucose_test"
    private const val NOTIFICATION_ID = 310
    private const val WINDOW_MS = 5 * 60 * 1000L
    private const val EXTRA_EATEN_AT = "eaten_at"
    private const val EXTRA_MEAL = "meal"

    /** Called after a meal is logged; books the reminder if it's on and the meal was just now. */
    suspend fun onMealLogged(context: Context, eatenAt: Instant, mealType: MealType) {
        val settings = context.appContainer.settings.settings.first()
        if (!settings.afterMealReminder) return
        val due = eatenAt.plus(Duration.ofMinutes(settings.afterMealMinutes.toLong()))
        // A meal logged long after eating (or on a past day) doesn't get a reminder.
        if (!due.isAfter(Instant.now())) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setWindow(AlarmManager.RTC_WAKEUP, due.toEpochMilli(), WINDOW_MS, pendingIntent(context, eatenAt, mealType))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, Instant.EPOCH, MealType.Snack))
    }

    private fun pendingIntent(context: Context, eatenAt: Instant, mealType: MealType): PendingIntent = PendingIntent.getBroadcast(
        context,
        NOTIFICATION_ID,
        Intent(context, TestReminderReceiver::class.java)
            .putExtra(EXTRA_EATEN_AT, eatenAt.toEpochMilli())
            .putExtra(EXTRA_MEAL, mealType.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    internal suspend fun fire(context: Context, intent: Intent) {
        val container = context.appContainer
        if (!container.settings.settings.first().afterMealReminder) return
        val eatenAt = Instant.ofEpochMilli(intent.getLongExtra(EXTRA_EATEN_AT, 0))
        val meal = runCatching { MealType.valueOf(intent.getStringExtra(EXTRA_MEAL).orEmpty()) }.getOrDefault(MealType.Snack)
        // Already tested since eating (from the meter or typed in): nothing to remind.
        val zone = ZoneId.systemDefault()
        val since = eatenAt.plus(Duration.ofMinutes(30))
        val readings = container.glucose.observeBetween(since.atZone(zone).toLocalDate(), java.time.LocalDate.now(zone), zone).first()
        if (readings.any { it.measuredAtEpochMs >= since.toEpochMilli() }) return
        show(context, meal)
    }

    private fun show(context: Context, meal: MealType) {
        if (!MealReminders.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.test_reminder_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.test_reminder_channel_body)
            },
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mealName = context.getString(
            when (meal) {
                MealType.Breakfast -> R.string.meal_breakfast
                MealType.Lunch -> R.string.meal_lunch
                MealType.Dinner -> R.string.meal_dinner
                MealType.Snack -> R.string.meal_snack
            },
        ).lowercase()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neutrino_mark)
            .setColor(context.getColor(R.color.brand_coral))
            .setContentTitle(context.getString(R.string.test_reminder_title))
            .setContentText(context.getString(R.string.test_reminder_body, mealName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

class TestReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                TestReminders.fire(app, intent)
            } finally {
                pending.finish()
            }
        }
    }
}
