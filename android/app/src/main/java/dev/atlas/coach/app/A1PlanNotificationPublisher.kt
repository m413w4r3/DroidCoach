package dev.atlas.coach.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.atlas.coach.plan.A1RevisionReceipt
import dev.atlas.coach.plan.DayPlan

/** Posts one neutral plan update after a newly committed A1 revision. */
internal object A1PlanNotificationPublisher {
    const val ACTION_OPEN_A1_PLAN = "dev.atlas.coach.action.OPEN_A1_PLAN_UPDATE"
    const val EXTRA_PLAN_ID = "atlas_a1_plan_id"
    const val EXTRA_LOCAL_DATE = "atlas_a1_plan_local_date"
    const val EXTRA_PLAN_VERSION = "atlas_a1_plan_version"

    private const val CHANNEL_ID = "atlas_a1_plan_updates"
    private const val CHANNEL_NAME = "Mises à jour du programme"
    private const val NOTIFICATION_ID = 731
    private const val NOTIFICATION_TAG_PREFIX = "atlas_a1_plan_update:"

    data class LockScreenContent(val title: String, val body: String, val publicBody: String)

    /** Deliberately has no plan, activity, or proposal arguments to prevent content leakage. */
    fun lockScreenContent() = LockScreenContent(
        title = "Programme mis à jour",
        body = "Une modification confirmée est enregistrée. Ouvrez ATLAS pour consulter le planning.",
        publicBody = "Mise à jour disponible",
    )

    fun publish(context: Context, plan: DayPlan, receipt: A1RevisionReceipt): Boolean {
        if (plan.planId != receipt.planId || plan.version != receipt.versionAfter ||
            plan.currentRevisionId != receipt.revisionId
        ) return false

        val appContext = context.applicationContext
        val platformManager = appContext.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(platformManager)
        val manager = NotificationManagerCompat.from(appContext)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            platformManager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return false

        val targetUri = Uri.parse("atlas-a1://plan/${Uri.encode(plan.localDate)}/${plan.version}")
        val openPlan = PendingIntent.getActivity(
            appContext,
            31 * plan.planId.hashCode() + plan.version,
            Intent(appContext, MainActivity::class.java)
                .setAction(ACTION_OPEN_A1_PLAN)
                .setData(targetUri)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PLAN_ID, plan.planId)
                .putExtra(EXTRA_LOCAL_DATE, plan.localDate)
                .putExtra(EXTRA_PLAN_VERSION, plan.version),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = lockScreenContent()
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(appContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("ATLAS")
                    .setContentText(content.publicBody)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build(),
            )
            .setContentIntent(openPlan)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return runCatching {
            manager.notify(NOTIFICATION_TAG_PREFIX + plan.planId + ":v" + plan.version, NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications neutres après validation d'une révision locale du programme"
                lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            },
        )
    }
}
