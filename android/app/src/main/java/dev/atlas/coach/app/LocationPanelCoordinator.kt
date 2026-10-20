package dev.atlas.coach.app

import android.app.Activity
import android.content.Context
import dev.atlas.coach.location.AtlasLocationFix
import dev.atlas.coach.location.LocationAccuracy
import dev.atlas.coach.location.LocationCaptureException
import dev.atlas.coach.location.LocationCaptureManager
import dev.atlas.coach.location.LocationConsentStore
import dev.atlas.coach.location.LocationPermissionPolicy
import dev.atlas.coach.location.LocationRepository
import dev.atlas.coach.location.LocationSummary
import dev.atlas.coach.location.LocationCoarsener
import dev.atlas.coach.location.MobilitySessionService
import dev.atlas.coach.location.MobilitySessionStatusStore
import dev.atlas.coach.location.MobilitySessionStopReason
import java.time.Instant
import java.time.ZoneId

/** Small Android adapter for the consent, capture, and visible-service UI callbacks. */
class LocationPanelCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val consentStore: LocationConsentStore = LocationConsentStore.open(appContext)
    private val repository = LocationRepository(LocationCaptureManager(appContext), consentStore)

    var statusMessage: String? = null
        private set
    private var lastLocalCaptureAtUtcMillis: Long? = null

    fun coarseRequestConsent(): Boolean = preferences.getBoolean(KEY_COARSE_CONSENT, false)
    fun mobilityConsent(): Boolean = preferences.getBoolean(KEY_MOBILITY_CONSENT, false)

    fun setCoarseRequestConsent(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_COARSE_CONSENT, enabled).apply()
        statusMessage = if (enabled) "Une demande approximative est disponible après une action visible." else null
    }

    fun setMobilityConsent(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_MOBILITY_CONSENT, enabled).apply()
        if (!enabled) stopMobility(MobilitySessionStopReason.CAPABILITY_DISABLED)
        statusMessage = if (enabled) "Le suivi temporaire pourra démarrer depuis ATLAS." else "Suivi temporaire désactivé."
    }

    fun setCoachSummaryConsent(enabled: Boolean) {
        consentStore.setLocationSummarySharingEnabled(enabled)
        if (!enabled) {
            MobilitySessionService.stop(appContext, MobilitySessionStopReason.SHARING_REVOKED)
            statusMessage = "Partage du résumé révoqué; les résumés enregistrés ont été supprimés."
        } else {
            statusMessage = "Le partage approximatif est autorisé localement; l'envoi dépend aussi du mandat du Coach."
        }
    }

    fun panelState(locationCapability: Boolean, mobilityCapability: Boolean): LocationPanelState {
        val recent = consentStore.recentLocationSummaries(System.currentTimeMillis()).lastOrNull()
        val localCaptureAt = lastLocalCaptureAtUtcMillis
        val displayAt = recent?.measuredAtUtcMillis ?: localCaptureAt
        return LocationPanelState(
            coarseRequestConsent = coarseRequestConsent() && locationCapability,
            coachSummaryConsent = consentStore.isLocationSummarySharingEnabled(),
            mobilityConsent = mobilityConsent() && mobilityCapability,
            mobilitySessionActive = MobilitySessionStatusStore.status.value.active,
            coarseSummaryLabel = when {
                recent != null -> "Dernière zone approximative enregistrée localement."
                localCaptureAt != null -> "Position approximative obtenue sur cet appareil; aucun point n'est conservé sans consentement de partage."
                else -> null
            },
            measuredAtLabel = displayAt?.let(::formatLocalTime),
            statusMessage = statusMessage,
        )
    }

    suspend fun captureCoarsePosition(): LocationSummary? {
        if (!coarseRequestConsent()) {
            statusMessage = "Autorisez d'abord une demande ponctuelle de position."
            return null
        }
        return try {
            val sharingAtCaptureStart = consentStore.isLocationSummarySharingEnabled()
            val summary = if (sharingAtCaptureStart) {
                repository.captureManualSummaryForCoach() ?: run {
                    statusMessage = "Le partage a été révoqué pendant la capture; aucun résumé n'a été conservé."
                    return null
                }
            } else {
                // The repository already coarsens this local-use fix; do not coarsen it twice.
                repository.captureForLocalUse(LocationAccuracy.COARSE).toSummary()
            }
            lastLocalCaptureAtUtcMillis = summary.measuredAtUtcMillis
            statusMessage = if (sharingAtCaptureStart) {
                "Résumé approximatif conservé localement selon votre consentement."
            } else {
                "Position approximative obtenue localement; elle n'a pas été conservée ni envoyée."
            }
            summary
        } catch (failure: LocationCaptureException) {
            statusMessage = failure.message?.take(180) ?: failure.code
            null
        } catch (_: Exception) {
            statusMessage = "La position approximative n'est pas disponible pour le moment."
            null
        }
    }

    fun startMobility(activity: Activity, capabilityEnabled: Boolean): Boolean {
        if (!capabilityEnabled || !mobilityConsent()) {
            statusMessage = "Activez explicitement le suivi temporaire avant de le démarrer."
            return false
        }
        val result = MobilitySessionService.startFromVisibleActivity(activity)
        statusMessage = when {
            result.started -> "Suivi de déplacement démarré; arrêt automatique dans une heure au plus."
            result.notificationsUnavailable -> "Autorisez les notifications Android pour afficher le contrôle d'arrêt du suivi."
            result.errorCode != null -> "Suivi non démarré (${result.errorCode})."
            else -> "Suivi non démarré."
        }
        return result.started
    }

    fun stopMobility(reason: MobilitySessionStopReason = MobilitySessionStopReason.MANUAL) {
        MobilitySessionService.stop(appContext, reason)
        statusMessage = "Suivi de déplacement arrêté."
    }

    fun onEmergencyPause() = stopMobility(MobilitySessionStopReason.EMERGENCY_PAUSE)

    fun onLocationCapabilityDisabled() {
        setCoarseRequestConsent(false)
        stopMobility(MobilitySessionStopReason.CAPABILITY_DISABLED)
    }

    fun onLocationPermissionRevoked() {
        if (!repositoryPermissionGranted()) {
            stopMobility(MobilitySessionStopReason.PERMISSION_REVOKED)
            statusMessage = "Permission de localisation révoquée; le suivi est arrêté."
        }
    }

    fun initialPermissionRequest(): Array<String> = LocationPermissionPolicy.initialRequestPermissions()

    fun permissionGranted(): Boolean = repositoryPermissionGranted()

    fun recentSummaries(nowUtcMillis: Long): List<LocationSummary> =
        repository.recentSummariesForCoach(nowUtcMillis)

    fun handlePermissionResult(granted: Boolean) {
        statusMessage = if (granted) "Permission approximative autorisée pour votre action." else "Permission de localisation refusée."
    }

    private fun repositoryPermissionGranted(): Boolean =
        appContext.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun AtlasLocationFix.toSummary() = LocationSummary(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters.coerceAtLeast(LocationCoarsener.COARSE_METERS),
        measuredAtUtcMillis = capturedAtUtcMillis,
    )

    private fun formatLocalTime(time: Long): String =
        Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDateTime().toString()

    private companion object {
        const val PREFERENCES_NAME = "atlas_location_panel"
        const val KEY_COARSE_CONSENT = "coarse_request_consent"
        const val KEY_MOBILITY_CONSENT = "mobility_consent"
    }
}
