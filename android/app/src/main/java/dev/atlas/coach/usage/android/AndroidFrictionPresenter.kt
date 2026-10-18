package dev.atlas.coach.usage.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import dev.atlas.coach.rules.FrictionPresenter
import dev.atlas.coach.rules.FrictionPresentationResult
import dev.atlas.coach.rules.Intervention

class AndroidFrictionPresenter(private val context: Context) : FrictionPresenter {
    override fun present(intervention: Intervention): FrictionPresentationResult {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return FrictionPresentationResult.PERMISSION_REQUIRED
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Friction douce", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Propositions ATLAS après une ouverture répétée d'une app choisie."
                setShowBadge(false)
            },
        )

        val label = runCatching {
            context.packageManager.getApplicationInfo(intervention.packageName, 0).let {
                context.packageManager.getApplicationLabel(it).toString()
            }
        }.getOrDefault(intervention.packageName)
        val intention = PendingIntent.getActivity(
            context,
            stableRequestCode(intervention.interventionId),
            Intent(context, IntentionActivity::class.java)
                .putExtra(IntentionActivity.EXTRA_PACKAGE_NAME, intervention.packageName)
                .putExtra(IntentionActivity.EXTRA_INTERVENTION_ID, intervention.interventionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pause = PendingIntent.getBroadcast(
            context,
            stableRequestCode("pause:${intervention.interventionId}"),
            Intent(context, EmergencyPauseReceiver::class.java)
                .setAction(EmergencyPauseReceiver.ACTION_PAUSE_30_MINUTES),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Une pause ?")
            .setContentText("Vous avez ouvert $label plusieurs fois. Cette proposition reste facultative.")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(action("Ouvrir l'écran d'intention", intention))
            .addAction(action("Pause 30 min", pause))
            .build()
        return try {
            manager.notify(stableRequestCode(intervention.interventionId), notification)
            FrictionPresentationResult.POSTED
        } catch (_: SecurityException) {
            FrictionPresentationResult.PERMISSION_REQUIRED
        }
    }

    private fun action(title: String, pendingIntent: PendingIntent): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, android.R.drawable.ic_dialog_info),
            title,
            pendingIntent,
        ).build()

    private fun stableRequestCode(value: String): Int = value.hashCode() and Int.MAX_VALUE

    private companion object {
        const val CHANNEL_ID = "atlas_app_open_friction_v1"
    }
}
