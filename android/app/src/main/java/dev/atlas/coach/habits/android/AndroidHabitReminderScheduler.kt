package dev.atlas.coach.habits.android

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.atlas.coach.data.AndroidStateBackend
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.StateReadResult
import org.json.JSONObject
import dev.atlas.coach.habits.HabitReminder
import dev.atlas.coach.habits.HabitReminderScheduler
import dev.atlas.coach.habits.HabitReminderSchedulingResult
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** Inexact AlarmManager adapter. It does not request exact-alarm access. */
class AndroidHabitReminderScheduler(context: Context) : HabitReminderScheduler {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun schedule(
        habitId: String,
        habitName: String,
        reminder: HabitReminder,
        zone: ZoneId,
    ): HabitReminderSchedulingResult {
        require(habitId.isNotBlank())
        if (!reminder.enabled) {
            cancel(habitId)
            return HabitReminderSchedulingResult(false, notificationsAvailable(), "Rappel désactivé.")
        }
        save(habitId, habitName, reminder, zone)
        val trigger = reminder.nextOccurrence(ZonedDateTime.now(zone), zone)
        if (trigger == null) {
            cancelAlarm(habitId)
            return HabitReminderSchedulingResult(false, notificationsAvailable(), "Aucune prochaine occurrence calculable.")
        }
        val pendingIntent = alarmIntent(habitId, habitName, reminder, zone)
        alarmManager.set(AlarmManager.RTC_WAKEUP, trigger.toInstant().toEpochMilli(), pendingIntent)
        val available = notificationsAvailable()
        return HabitReminderSchedulingResult(
            alarmScheduled = true,
            notificationsAvailable = available,
            message = if (available) null else "L'alarme reste planifiée, mais Android ne peut pas afficher la notification.",
        )
    }

    override fun cancel(habitId: String) {
        preferences.edit()
            .putStringSet(KEY_IDS, preferences.getStringSet(KEY_IDS, emptySet()).orEmpty() - habitId)
            .remove(scheduleKey(habitId))
            .apply()
        cancelAlarm(habitId)
        notificationManager().cancel(habitId.hashCode())
    }

    /** Called at boot and after wall-clock/time-zone changes. */
    fun rescheduleAll() {
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toList()
        if (!habitReminderCapabilityEnabled(appContext)) {
            ids.forEach(::cancel)
            return
        }
        ids.forEach { habitId ->
            read(habitId)?.let { (name, reminder) -> schedule(habitId, name, reminder, ZoneId.systemDefault()) }
        }
    }

    private fun save(habitId: String, name: String, reminder: HabitReminder, zone: ZoneId) {
        val dayMask = reminder.days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }
        val json = JSONObject()
            .put("name", name)
            .put("hour", reminder.hour)
            .put("minute", reminder.minute)
            .put("daysMask", dayMask)
            .put("enabled", reminder.enabled)
            .put("zone", zone.id)
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet().apply { add(habitId) }
        preferences.edit().putStringSet(KEY_IDS, ids).putString(scheduleKey(habitId), json.toString()).apply()
    }

    private fun read(habitId: String): Pair<String, HabitReminder>? = runCatching {
        val json = JSONObject(preferences.getString(scheduleKey(habitId), null) ?: return null)
        val daysMask = json.getInt("daysMask")
        val days = DayOfWeek.values().filter { daysMask and (1 shl (it.value - 1)) != 0 }.toSet()
        Pair(
            json.getString("name"),
            HabitReminder(json.getInt("hour"), json.getInt("minute"), days, json.optBoolean("enabled", true)),
        )
    }.getOrNull()

    private fun alarmIntent(habitId: String, habitName: String, reminder: HabitReminder, zone: ZoneId): PendingIntent {
        val dayMask = reminder.days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }
        val intent = Intent(appContext, HabitReminderReceiver::class.java)
            .setAction(ACTION_DELIVER_REMINDER)
            .putExtra(EXTRA_HABIT_ID, habitId)
            .putExtra(EXTRA_HABIT_NAME, habitName)
            .putExtra(EXTRA_HOUR, reminder.hour)
            .putExtra(EXTRA_MINUTE, reminder.minute)
            .putExtra(EXTRA_DAYS_MASK, dayMask)
            .putExtra(EXTRA_ZONE, zone.id)
        return PendingIntent.getBroadcast(
            appContext,
            habitId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelAlarm(habitId: String) {
        val intent = Intent(appContext, HabitReminderReceiver::class.java).setAction(ACTION_DELIVER_REMINDER)
        PendingIntent.getBroadcast(
            appContext,
            habitId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let(alarmManager::cancel)
    }

    private fun notificationsAvailable(): Boolean =
        (Build.VERSION.SDK_INT < 33 || appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notificationManager().areNotificationsEnabled()

    private fun notificationManager() = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun scheduleKey(habitId: String) = "reminder:$habitId"

    companion object {
        internal const val PREFERENCES = "atlas_habit_reminder_alarms"
        internal const val KEY_IDS = "habit_ids"
        internal const val ACTION_DELIVER_REMINDER = "dev.atlas.coach.habits.action.DELIVER_REMINDER"
        internal const val ACTION_RESCHEDULE = "dev.atlas.coach.habits.action.RESCHEDULE_REMINDERS"
        internal const val EXTRA_HABIT_ID = "habit_id"
        internal const val EXTRA_HABIT_NAME = "habit_name"
        internal const val EXTRA_HOUR = "hour"
        internal const val EXTRA_MINUTE = "minute"
        internal const val EXTRA_DAYS_MASK = "days_mask"
        internal const val EXTRA_ZONE = "zone"
        internal const val CHANNEL_ID = "habit_reminders"
    }
}

/** Receives an alarm, posts a notification only, then schedules the next occurrence. */
class HabitReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidHabitReminderScheduler.ACTION_DELIVER_REMINDER) return
        val habitId = intent.getStringExtra(AndroidHabitReminderScheduler.EXTRA_HABIT_ID) ?: return
        if (!habitReminderCapabilityEnabled(context)) {
            AndroidHabitReminderScheduler(context).cancel(habitId)
            return
        }
        val habitName = intent.getStringExtra(AndroidHabitReminderScheduler.EXTRA_HABIT_NAME).orEmpty()
        val zone = runCatching {
            ZoneId.of(intent.getStringExtra(AndroidHabitReminderScheduler.EXTRA_ZONE) ?: ZoneId.systemDefault().id)
        }.getOrDefault(ZoneId.systemDefault())
        val dayMask = intent.getIntExtra(AndroidHabitReminderScheduler.EXTRA_DAYS_MASK, 0)
        val days = DayOfWeek.values().filter { dayMask and (1 shl (it.value - 1)) != 0 }.toSet()
        if (days.isEmpty()) return
        val reminder = HabitReminder(
            hour = intent.getIntExtra(AndroidHabitReminderScheduler.EXTRA_HOUR, 9),
            minute = intent.getIntExtra(AndroidHabitReminderScheduler.EXTRA_MINUTE, 0),
            days = days,
        )
        val appContext = context.applicationContext
        if (ZonedDateTime.now(zone).dayOfWeek in days && canPost(appContext)) {
            postNotification(appContext, habitId, habitName)
        }
        AndroidHabitReminderScheduler(appContext).schedule(habitId, habitName, reminder, zone)
    }

    private fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

    private fun postNotification(context: Context, habitId: String, habitName: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    AndroidHabitReminderScheduler.CHANNEL_ID,
                    "Rappels d'habitudes",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, AndroidHabitReminderScheduler.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(habitName.ifBlank { "Rappel d'habitude" })
            .setContentText("Ouvrez ATLAS pour décider si vous souhaitez valider cette habitude.")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(habitId.hashCode(), notification) }
    }
}

/** Manifest receiver for boot, time and time-zone changes; registration is proposed in E.md. */
class HabitReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) {
            AndroidHabitReminderScheduler(context.applicationContext).rescheduleAll()
        }
    }
}

private fun habitReminderCapabilityEnabled(context: Context): Boolean {
    val loaded = LocalStateStore(AndroidStateBackend(context.applicationContext)).readDocument() as? StateReadResult.Loaded
        ?: return false
    val capabilities = loaded.state["capabilities"] as? Map<*, *> ?: return false
    return capabilities["habitReminders"] == true
}
