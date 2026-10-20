package dev.atlas.coach.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.atlas.coach.coach.proactive.isLocalProposalId
import dev.atlas.coach.coach.proactive.ProactiveNotification

/** Posts only the plan copy returned by the agent; Android keeps it private on the lock screen. */
class ProactiveCoachNotificationPublisher(context: Context) {
    private val appContext = context.applicationContext

    fun show(notification: ProactiveNotification) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
            ?: error("Notification service is unavailable")
        check(notificationsAvailable(appContext)) { "Coach notifications are disabled" }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Coach proactif", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val platformNotification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(contentIntentFor(notification))
            .setAutoCancel(true)
            .build()
        manager.notify(notification.notificationId.hashCode(), platformNotification)
    }

    internal fun contentIntentFor(notification: ProactiveNotification): PendingIntent {
        require(notification.localProposalId == null || isLocalProposalId(notification.localProposalId))
        val intent = Intent(appContext, MainActivity::class.java)
            .setAction(ACTION_OPEN_PROPOSAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_DESTINATION, DESTINATION_COACH)
        notification.localProposalId?.let { proposalId -> intent.putExtra(EXTRA_LOCAL_PROPOSAL_ID, proposalId) }
        return PendingIntent.getActivity(
            appContext,
            notification.notificationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val CHANNEL_ID = "atlas_proactive_coach"
        const val ACTION_OPEN_PROPOSAL = "dev.atlas.coach.action.OPEN_PROACTIVE_PROPOSAL"
        const val EXTRA_LOCAL_PROPOSAL_ID = "atlas_local_proposal_id"
        const val EXTRA_DESTINATION = "atlas_destination"
        const val DESTINATION_COACH = "coach"

        fun notificationsAvailable(context: Context): Boolean {
            val appContext = context.applicationContext
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: return false
            val permissionGranted = Build.VERSION.SDK_INT < 33 ||
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!permissionGranted || !manager.areNotificationsEnabled()) return false
            return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
