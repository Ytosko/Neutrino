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
import dev.ytosko.neutrino.data.medicine.MedicineEntity
import dev.ytosko.neutrino.data.medicine.MedicineKind
import dev.ytosko.neutrino.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Daily medicine and insulin reminders at the times the user set for each one. One alarm is
 * booked for the next due time; when it fires, every medicine due then gets a notification (unless
 * a dose was already logged in the last 90 minutes), and the next alarm is booked. The
 * notification's "Taken" button logs the usual dose. On the lock screen it only says "Medicine
 * reminder", never the medicine.
 */
@android.annotation.SuppressLint("MissingPermission") // posted only after canNotify()
object DoseReminders {

    private const val CHANNEL = "medicine_reminders"
    private const val REQUEST_ALARM = 7_100
    private const val WINDOW_MS = 5 * 60 * 1000L
    internal const val ACTION_FIRE = "dev.ytosko.neutrino.action.DOSE_REMINDER"
    internal const val ACTION_TAKEN = "dev.ytosko.neutrino.action.DOSE_TAKEN"
    internal const val EXTRA_MEDICINE = "medicine"

    /** Medicines whose reminders are on: only kinds the user turned on in Settings. */
    fun remindable(medicines: List<MedicineEntity>, settings: AppSettings): List<MedicineEntity> =
        medicines.filter {
            it.reminders.isNotEmpty() && when (it.kindEnum) {
                MedicineKind.Medicine -> settings.takesMedicine
                MedicineKind.Insulin -> settings.usesInsulin
            }
        }

    /** The next moment after [now] any of [medicines] is due. */
    fun nextTrigger(medicines: List<MedicineEntity>, now: ZonedDateTime): ZonedDateTime? =
        medicines.flatMap { it.reminders }.distinct().minOfOrNull { MealReminders.nextTrigger(now, it) }

    /** Medicines due at [at]: a reminder time within the 15 minutes up to it. */
    fun dueAt(medicines: List<MedicineEntity>, at: LocalTime): List<MedicineEntity> =
        medicines.filter { m ->
            m.reminders.any { t ->
                val minutes = java.time.Duration.between(t, at).toMinutes().let { if (it < -720) it + 1440 else it }
                minutes in 0..15
            }
        }

    /** Books (or cancels) the next reminder from the current list and settings. */
    suspend fun sync(context: Context) {
        val container = context.appContainer
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context)
        val settings = container.settings.settings.first()
        val medicines = remindable(container.medicines.activeMedicines(), settings)
        val next = nextTrigger(medicines, ZonedDateTime.now())
        if (next == null) {
            alarms.cancel(pending)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), WINDOW_MS, pending)
        }
    }

    internal suspend fun fire(context: Context) {
        val container = context.appContainer
        val settings = container.settings.settings.first()
        val now = ZonedDateTime.now()
        val due = dueAt(remindable(container.medicines.activeMedicines(), settings), now.toLocalTime())
        due.forEach { medicine ->
            if (!container.medicines.takenRecently(medicine.id, now.toInstant())) show(context, medicine)
        }
        sync(context)
    }

    internal suspend fun markTaken(context: Context, medicineId: String) {
        val container = context.appContainer
        val medicine = container.medicines.medicine(medicineId) ?: return
        val amount = medicine.usualDose ?: return
        container.medicines.logDose(medicine, amount, Instant.now(), ZoneId.systemDefault())
        NotificationManagerCompat.from(context).cancel(notificationId(medicineId))
    }

    private fun show(context: Context, medicine: MedicineEntity) {
        if (!MealReminders.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.dose_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.dose_channel_body)
            },
        )
        val open = PendingIntent.getActivity(
            context, notificationId(medicine.id),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(R.string.dose_reminder_title, medicine.displayName)
        val body = medicine.usualDose?.let { context.getString(R.string.dose_reminder_body, doseText(context, it, medicine.unitEnum)) }
            ?: context.getString(R.string.dose_reminder_body_open)
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neutrino_mark)
            .setContentTitle(context.getString(R.string.dose_reminder_public))
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_neutrino_mark)
            .setColor(context.getColor(R.color.brand_coral))
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
        if (medicine.usualDose != null) {
            val taken = PendingIntent.getBroadcast(
                context, notificationId(medicine.id),
                Intent(context, DoseReminderReceiver::class.java).setAction(ACTION_TAKEN).putExtra(EXTRA_MEDICINE, medicine.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, context.getString(R.string.dose_taken), taken)
        }
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(medicine.id), builder.build()) }
    }

    private fun notificationId(medicineId: String) = 7_200 + (medicineId.hashCode() and 0xFFFF)

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_ALARM,
        Intent(context, DoseReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** "1 tablet", "2.5 ml", "10 units". */
fun doseText(context: Context, amount: Double, unit: dev.ytosko.neutrino.data.medicine.DoseUnit): String {
    val number = if (amount % 1.0 == 0.0) amount.toLong().toString() else String.format(java.util.Locale.US, "%.1f", amount).removeSuffix(".0")
    val count = if (amount == 1.0) 1 else 2
    return context.resources.getQuantityString(unit.plural, count, number)
}

val dev.ytosko.neutrino.data.medicine.DoseUnit.plural: Int
    get() = when (this) {
        dev.ytosko.neutrino.data.medicine.DoseUnit.Tablet -> R.plurals.dose_unit_tablet
        dev.ytosko.neutrino.data.medicine.DoseUnit.Capsule -> R.plurals.dose_unit_capsule
        dev.ytosko.neutrino.data.medicine.DoseUnit.Ml -> R.plurals.dose_unit_ml
        dev.ytosko.neutrino.data.medicine.DoseUnit.Mg -> R.plurals.dose_unit_mg
        dev.ytosko.neutrino.data.medicine.DoseUnit.Units -> R.plurals.dose_unit_units
        dev.ytosko.neutrino.data.medicine.DoseUnit.Puff -> R.plurals.dose_unit_puff
        dev.ytosko.neutrino.data.medicine.DoseUnit.Drop -> R.plurals.dose_unit_drop
    }

class DoseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    DoseReminders.ACTION_TAKEN -> intent.getStringExtra(DoseReminders.EXTRA_MEDICINE)?.let { DoseReminders.markTaken(app, it) }
                    else -> DoseReminders.fire(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
