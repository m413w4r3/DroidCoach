package dev.atlas.coach.location

/**
 * Local location boundary for UI, mobility capture, and the future Context Bridge.
 * It has no Activity dependency and never returns raw Android Location objects.
 */
class LocationRepository(
    private val provider: AtlasLocationProvider,
    val consentStore: LocationConsentStore,
) {
    private val disclosure = LocationDisclosure(provider)

    /** On-device point capture. Fine accuracy is possible only when explicitly requested and OS-granted. */
    suspend fun captureForLocalUse(
        desiredAccuracy: LocationAccuracy = LocationAccuracy.COARSE,
    ): AtlasLocationFix = disclosure.captureForLocalUse(desiredAccuracy)

    /** Null means sharing is off or was revoked during the suspended Android capture. */
    suspend fun captureAutomaticSummary(): LocationSummary? =
        disclosure.captureAutomaticSummary(consentStore)

    /** Captures and locally stores one coarse point only while sharing consent is active. */
    suspend fun captureManualSummaryForCoach(): LocationSummary? =
        disclosure.captureManualSummaryForCoach(consentStore)

    /** Coarse local tracking is authorized by the explicit visible service start, not Coach sharing. */
    suspend fun captureMobilitySummary(): LocationSummary = disclosure.captureMobilitySummary()

    /** Recent, already-minimized records for the Coach; S6 must still check category consent at send time. */
    fun recentSummariesForCoach(
        nowUtcMillis: Long,
        maxAgeMs: Long = LocationConsentStore.DEFAULT_AGENT_SUMMARY_MAX_AGE_MS,
    ): List<LocationSummary> {
        if (!consentStore.isLocationSummarySharingEnabled()) return emptyList()
        return consentStore.recentLocationSummaries(nowUtcMillis, maxAgeMs)
    }

    /** Persists an already-coarsened zone summary unchanged; raw fixes enter through Disclosure. */
    fun recordMobilityZone(summary: LocationSummary): Boolean =
        consentStore.appendMobilitySummary(summary)
}
