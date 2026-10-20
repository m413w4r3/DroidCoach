package dev.atlas.coach.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.atlas.coach.coach.proactive.ProactiveNotification

/** Posts only the plan copy returned by the agent; Android keeps it private on the lock screen. */
class ProactiveCoachNotificationPublisher(context: Context) {
    private val appContext = context.applicationContext

    fun show(notification: ProactiveNotification) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
            ?: error("Notification service is unavailable")
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        check(permissionGranted && manager.areNotificationsEnabled()) { "Coach notifications are disabled" }
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Coach proactif", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val platformNotification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        manager.notify(notification.notificationId.hashCode(), platformNotification)
    }

    private companion object {
        const val CHANNEL_ID = "atlas_proactive_coach"
    }
}
