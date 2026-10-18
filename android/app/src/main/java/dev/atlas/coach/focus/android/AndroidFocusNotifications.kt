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
import dev.atlas.coach.focus.PhaseNotificationResult
import dev.atlas.coach.focus.PhaseNotifier
import dev.atlas.coach.focus.PhaseTransition
import dev.atlas.coach.routines.RoutineEvaluator
import dev.atlas.coach.routines.RoutineProposalNotifier

/** Android notification adapter. Permission prompting remains an explicit foreground UI action. */
class AndroidFocusNotifications(context: Context) : PhaseNotifier, RoutineProposalNotifier {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

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
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return PhaseNotificationResult.PERMISSION_REQUIRED
        if (!manager.areNotificationsEnabled()) return PhaseNotificationResult.NOTIFICATIONS_DISABLED
        return PhaseNotificationResult.POSTED
    }

    private fun notificationId(key: String): Int = key.hashCode()

    companion object {
        const val CHANNEL_ID = "atlas_focus_phase"
        const val ACTION_OPEN_FROM_NOTIFICATION = "dev.atlas.coach.action.OPEN_FOCUS_NOTIFICATION"
    }
}
