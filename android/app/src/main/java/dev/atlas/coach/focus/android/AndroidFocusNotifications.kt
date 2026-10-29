package dev.atlas.coach.focus.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.atlas.coach.focus.PhaseNotificationResult
import dev.atlas.coach.focus.PhaseNotifier
import dev.atlas.coach.focus.PhaseTransition
import dev.atlas.coach.routines.RoutineEvaluator
import dev.atlas.coach.routines.RoutineProposalNotifier

/** Android notification adapter. Permission prompting remains an explicit foreground UI action. */
class AndroidFocusNotifications(context: Context) : PhaseNotifier, RoutineProposalNotifier {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private val notificationPreferences = appContext.getSharedPreferences("atlas_notification_channels", Context.MODE_PRIVATE)

    override fun notify(transition: PhaseTransition): PhaseNotificationResult {
        ensureChannel()
        val permission = notificationPermissionResult()
        if (permission != PhaseNotificationResult.POSTED) return permission
        val title = if (transition.completesSession) "Focus terminé" else "Phase de focus terminée"
        val body = when {
            transition.completesSession -> "Votre dernier cycle de concentration est terminé."
            transition.nextPhase?.name == "WORK" -> "La pause est terminée. Le prochain travail vous attend."
            transition.nextPhase?.name == "LONG_BREAK" -> "Le cycle est terminé. La pause longue commence."
            else -> "Le cycle est terminé. La pause courte commence."
        }
        manager.notify(
            notificationId("focus:${transition.sessionId}"),
            buildNotification(
                title = title,
                body = body,
                dataUri = "atlas-focus://open/${Uri.encode(transition.sessionId)}",
                extras = mapOf("atlas_open_focus_session" to transition.sessionId),
            ),
        )
        return PhaseNotificationResult.POSTED
    }

    override fun notify(proposal: RoutineEvaluator.Proposal): Boolean {
        ensureChannel()
        if (notificationPermissionResult() != PhaseNotificationResult.POSTED) return false
        val routine = proposal.routine
        val body = if (proposal.requiresConfirmation) {
            "${routine.name} est prévue maintenant. Ouvrez ATLAS pour décider si vous commencez."
        } else {
            "${routine.name} est prévue maintenant. Ouvrez ATLAS pour consulter la proposition autorisée."
        }
        manager.notify(
            notificationId("routine:${routine.id}"),
            buildNotification(
                title = "Proposition de focus",
                body = body,
                dataUri = "atlas-routine://open/${Uri.encode(routine.id)}",
                extras = mapOf("atlas_open_routine_id" to routine.id),
            ),
        )
        return true
    }

    override fun cancel(sessionId: String) {
        manager.cancel(notificationId("focus:$sessionId"))
    }

    /** Creates the dedicated channel only after the user has opted in from the plan screen. */
    fun ensurePlanActivityChannelForOptIn() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = manager ?: return
        if (notificationManager.getNotificationChannel(PLAN_ACTIVITY_CHANNEL_ID) != null) {
            if (!notificationPreferences.getBoolean(KEY_PLAN_ACTIVITY_CHANNEL_CREATED, false)) {
                notificationPreferences.edit().putBoolean(KEY_PLAN_ACTIVITY_CHANNEL_CREATED, true).commit()
            }
            return
        }
        if (notificationPreferences.getBoolean(KEY_PLAN_ACTIVITY_CHANNEL_CREATED, false)) return
        notificationManager.createNotificationChannel(
            NotificationChannel(PLAN_ACTIVITY_CHANNEL_ID, "Rappels du programme", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Rappels locaux neutres pour les activités planifiées" },
        )
        notificationPreferences.edit().putBoolean(KEY_PLAN_ACTIVITY_CHANNEL_CREATED, true).commit()
    }

    /** A deleted, muted, permission-blocked, or globally disabled channel cannot publish. */
    fun canPostPlanActivityReminder(): Boolean {
        val notificationManager = manager ?: return false
        if (!notificationPermissionGranted() || !notificationManager.areNotificationsEnabled()) return false
        return channelAllowsPosting(notificationManager, PLAN_ACTIVITY_CHANNEL_ID)
    }

    /** Posts a deliberately generic lock-screen message after the final capability and plan gate. */
    fun postPlanActivityReminder(
        tag: String,
        contentIntent: PendingIntent,
        actions: List<Pair<String, PendingIntent>>,
        isStillAuthorized: () -> Boolean,
    ): Boolean = synchronized(PLAN_ACTIVITY_PUBLICATION_LOCK) {
        val notificationManager = manager ?: return@synchronized false
        if (!canPostPlanActivityReminder()) return@synchronized false
        if (!runCatching(isStillAuthorized).getOrDefault(false)) return@synchronized false
        val notification = NotificationCompat.Builder(appContext, PLAN_ACTIVITY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Rappel du programme")
            .setContentText("Une activité est prévue. Ouvrez ATLAS pour la consulter.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Une activité est prévue. Ouvrez ATLAS pour la consulter."))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(appContext, PLAN_ACTIVITY_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("ATLAS")
                    .setContentText("Rappel disponible")
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build(),
            )
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply {
                actions.forEachIndexed { index, (label, pendingIntent) ->
                    val icon = when (index) {
                        0 -> android.R.drawable.ic_media_play
                        1 -> android.R.drawable.ic_menu_save
                        else -> android.R.drawable.ic_menu_edit
                    }
                    addAction(NotificationCompat.Action.Builder(icon, label, pendingIntent).build())
                }
            }
            .build()
        notificationManager.notify(tag, PLAN_ACTIVITY_NOTIFICATION_ID, notification)
        true
    }

    /** Revocation removes only notices owned by the plan reminder channel. */
    fun cancelPlanActivityNotifications(): Int {
        val notificationManager = manager ?: return 0
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0
        return synchronized(PLAN_ACTIVITY_PUBLICATION_LOCK) {
            val active = runCatching { notificationManager.activeNotifications }
                .getOrDefault(emptyArray())
                .filter { notification ->
                    notification.packageName == appContext.packageName &&
                        notification.notification.channelId == PLAN_ACTIVITY_CHANNEL_ID
                }
            active.forEach { notification -> notificationManager.cancel(notification.tag, notification.id) }
            active.size
        }
    }

    private fun buildNotification(
        title: String,
        body: String,
        dataUri: String,
        extras: Map<String, String>,
    ): Notification {
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?.apply {
                action = ACTION_OPEN_FROM_NOTIFICATION
                data = Uri.parse(dataUri)
                extras.forEach { (key, value) -> putExtra(key, value) }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                appContext,
                notificationId(dataUri),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Focus et routines", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Fin des phases Focus et propositions de routines locales" },
            )
        }
    }

    private fun notificationPermissionResult(): PhaseNotificationResult {
        if (!notificationPermissionGranted()) return PhaseNotificationResult.PERMISSION_REQUIRED
        if (!manager.areNotificationsEnabled()) return PhaseNotificationResult.NOTIFICATIONS_DISABLED
        return PhaseNotificationResult.POSTED
    }

    private fun notificationPermissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(key: String): Int = key.hashCode()

    companion object {
        const val CHANNEL_ID = "atlas_focus_phase"
        const val ACTION_OPEN_FROM_NOTIFICATION = "dev.atlas.coach.action.OPEN_FOCUS_NOTIFICATION"
        const val PLAN_ACTIVITY_CHANNEL_ID = "atlas_plan_activity_reminders"
        private const val PLAN_ACTIVITY_NOTIFICATION_ID = 0
        private const val KEY_PLAN_ACTIVITY_CHANNEL_CREATED = "planActivityChannelCreated"
        private val PLAN_ACTIVITY_PUBLICATION_LOCK = Any()

        internal fun channelAllowsPosting(notificationManager: NotificationManager?, channelId: String): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val channel = notificationManager?.getNotificationChannel(channelId) ?: return false
            return channel.importance >= NotificationManager.IMPORTANCE_DEFAULT
        }
    }
}
