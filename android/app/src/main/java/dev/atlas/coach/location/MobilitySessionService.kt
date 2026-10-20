package dev.atlas.coach.location

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MobilityStartResult(
    val started: Boolean,
    val errorCode: String? = null,
    val notificationsUnavailable: Boolean = false,
)

/**
 * Explicit, visible-start foreground service. The manifest declaration and Android permissions are
 * owned by S6; callers must use [startFromVisibleActivity] from the visible button action.
 */
class MobilitySessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var samplingJob: Job? = null
    private var session: MobilitySession? = null
    private lateinit var consentStore: LocationConsentStore
    private lateinit var repository: LocationRepository
    private var sharingListener: AutoCloseable? = null
    private lateinit var appOpsManager: AppOpsManager
    private var appOpsListenerRegistered = false
    private var stopping = false
    @Volatile private var sharingRevoked = false

    private val appOpsListener = AppOpsManager.OnOpChangedListener { _, changedPackage ->
        if ((changedPackage == null || changedPackage == packageName) && !hasForegroundLocationPermission()) {
            mainThread { finishSession(MobilitySessionStopReason.PERMISSION_REVOKED) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        consentStore = LocationConsentStore.open(applicationContext)
        repository = LocationRepository(LocationCaptureManager(applicationContext), consentStore)
        sharingListener = consentStore.addSharingRevokedListener {
            sharingRevoked = true
            mainThread { finishSession(MobilitySessionStopReason.SHARING_REVOKED) }
        }
        appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOpsManager.startWatchingMode(AppOpsManager.OPSTR_COARSE_LOCATION, packageName, appOpsListener)
        appOpsManager.startWatchingMode(AppOpsManager.OPSTR_FINE_LOCATION, packageName, appOpsListener)
        appOpsListenerRegistered = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishSession(MobilitySessionStopReason.MANUAL)
            ACTION_START -> startSession(startId)
            else -> stopSelf(startId)
        }
        // A killed process/session is never recreated in the background or after reboot.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession(startId: Int) {
        if (session != null || stopping) return
        if (!hasForegroundLocationPermission() || !notificationsAvailable()) {
            stopSelf(startId)
            return
        }
        val created = MobilitySession.start(SystemClock.elapsedRealtime())
        try {
            startForegroundCompat(buildNotification())
        } catch (_: RuntimeException) {
            stopSelf(startId)
            return
        }
        session = created
        sharingRevoked = false
        MobilitySessionStatusStore.sessionStarted(System.currentTimeMillis())
        samplingJob = serviceScope.launch { sampleUntilFinished() }
    }

    private suspend fun sampleUntilFinished() {
        while (serviceScope.isActive) {
            val current = session ?: return
            val now = SystemClock.elapsedRealtime()
            if (current.isFinished(now)) {
                finishSession(MobilitySessionStopReason.DURATION_LIMIT)
                return
            }

            val sampling = current.beginMeasurement(now)
            if (sampling != null) {
                session = sampling
                try {
                    val summary = repository.captureMobilitySummary()
                    if (sharingRevoked) {
                        finishSession(MobilitySessionStopReason.SHARING_REVOKED)
                        return
                    }
                    if (!hasForegroundLocationPermission()) {
                        finishSession(MobilitySessionStopReason.PERMISSION_REVOKED)
                        return
                    }
                    if (checkNotNull(session).isFinished(SystemClock.elapsedRealtime())) {
                        finishSession(MobilitySessionStopReason.DURATION_LIMIT)
                        return
                    }
                    val recording = checkNotNull(session).recordZone(summary)
                    session = recording.session
                    if (recording.zoneChanged) repository.recordMobilityZone(summary)
                    MobilitySessionStatusStore.measurementRecorded(summary, recording.zoneChanged)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: LocationCaptureException) {
                    if (sharingRevoked) {
                        finishSession(MobilitySessionStopReason.SHARING_REVOKED)
                        return
                    }
                    if (failure.code == LocationErrorCode.PERMISSION_REQUIRED ||
                        failure.code == LocationErrorCode.BACKGROUND_UNAVAILABLE) {
                        finishSession(MobilitySessionStopReason.PERMISSION_REVOKED)
                        return
                    }
                    if (failure.code == LocationErrorCode.DISABLED) {
                        finishSession(MobilitySessionStopReason.LOCATION_DISABLED)
                        return
                    }
                    // Timeout/provider loss retries at the next permitted 5 minute boundary.
                } catch (_: Exception) {
                    // A failed provider does not create or persist an event.
                }
            }

            val after = SystemClock.elapsedRealtime()
            val activeSession = session ?: return
            if (activeSession.isFinished(after)) {
                finishSession(MobilitySessionStopReason.DURATION_LIMIT)
                return
            }
            val waitMs = activeSession.delayUntilNextMeasurement(after)
            delay(waitMs.coerceAtLeast(1L))
        }
    }

    private fun finishSession(reason: MobilitySessionStopReason) {
        if (stopping) return
        stopping = true
        samplingJob?.cancel()
        samplingJob = null
        val hadSession = session != null
        session = null
        if (hadSession) MobilitySessionStatusStore.sessionStopped(reason, System.currentTimeMillis())
        stopForegroundAndRemoveNotification()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundAndRemoveNotification() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, MobilitySessionService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Suivi de déplacement ATLAS")
            .setContentText("Zones approximatives, arrêt automatique dans 60 minutes")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Arrêter", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Suivi de déplacement",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Session de déplacement ATLAS démarrée explicitement" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun notificationsAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()
    }

    private fun hasForegroundLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun mainThread(action: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) action()
        else android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }

    override fun onDestroy() {
        samplingJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        sharingListener?.close()
        sharingListener = null
        if (appOpsListenerRegistered) {
            appOpsManager.stopWatchingMode(appOpsListener)
            appOpsListenerRegistered = false
        }
        if (MobilitySessionStatusStore.status.value.active) {
            MobilitySessionStatusStore.sessionStopped(
                MobilitySessionStopReason.SERVICE_INTERRUPTED,
                System.currentTimeMillis(),
            )
        }
        session = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "dev.atlas.coach.location.action.START_MOBILITY"
        private const val ACTION_STOP = "dev.atlas.coach.location.action.STOP_MOBILITY"
        private const val CHANNEL_ID = "atlas_mobility_session"
        private const val NOTIFICATION_ID = 6231
        private const val STOP_REQUEST_CODE = 6232

        /** Start only from an explicit action in an Activity that currently has window focus. */
        @JvmStatic
        fun startFromVisibleActivity(activity: Activity): MobilityStartResult {
            if (activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) {
                return MobilityStartResult(false, LocationErrorCode.BACKGROUND_UNAVAILABLE)
            }
            val context = activity.applicationContext
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return MobilityStartResult(false, LocationErrorCode.PERMISSION_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return MobilityStartResult(false, notificationsUnavailable = true)
            }
            if (!(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()) {
                return MobilityStartResult(false, notificationsUnavailable = true)
            }

            val intent = Intent(context, MobilitySessionService::class.java).setAction(ACTION_START)
            return try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                MobilityStartResult(started = true)
            } catch (_: RuntimeException) {
                MobilityStartResult(false, LocationErrorCode.BACKGROUND_UNAVAILABLE)
            }
        }

        /** Stop from ATLAS UI/controller; the notification action calls the same service action. */
        @JvmStatic
        fun stop(
            context: Context,
            reason: MobilitySessionStopReason = MobilitySessionStopReason.MANUAL,
        ) {
            MobilitySessionStatusStore.sessionStopped(reason, System.currentTimeMillis())
            context.stopService(Intent(context, MobilitySessionService::class.java))
        }
    }
}
