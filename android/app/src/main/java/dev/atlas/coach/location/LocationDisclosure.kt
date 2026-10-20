package dev.atlas.coach.location

/**
 * Adapted from OpenClaw `apps/android/app/src/main/java/ai/openclaw/app/node/LocationDisclosure.kt`.
 * Source: openclaw/openclaw @ e3fe108378f6eca2d4e2a4333fd08bf81bb87105, MIT (OpenClaw LICENSE).
 * ATLAS separates OS permission from sharing consent and only offers automatic coarse summaries.
 */
internal class LocationDisclosure(
    private val provider: AtlasLocationProvider,
    private val coarsener: LocationCoarsener = LocationCoarsener.processWide,
) {
    /** Capture for an on-device use case. Precise capture is opt-in and never used for Coach context. */
    suspend fun captureForLocalUse(
        desiredAccuracy: LocationAccuracy = LocationAccuracy.COARSE,
    ): AtlasLocationFix {
        val before = provider.permissionState()
        if (!before.canCapture) throw permissionRequired()
        val resolvedAccuracy = if (desiredAccuracy == LocationAccuracy.PRECISE && before.fineGranted) {
            LocationAccuracy.PRECISE
        } else {
            LocationAccuracy.COARSE
        }
        val fix = provider.capture(LocationCaptureRequest(desiredAccuracy = resolvedAccuracy))
        val after = provider.permissionState()
        if (!after.canCapture || (resolvedAccuracy == LocationAccuracy.PRECISE && !after.fineGranted)) {
            throw permissionRequired()
        }
        return if (resolvedAccuracy == LocationAccuracy.COARSE) coarsener.coarsen(fix).asFix() else fix
    }

    /**
     * Returns null when ATLAS sharing consent is absent or was revoked while capture suspended.
     * This path always asks for coarse and applies the 2 km process-wide coarsener.
     */
    suspend fun captureAutomaticSummary(consentStore: LocationConsentStore): LocationSummary? {
        if (!consentStore.isLocationSummarySharingEnabled()) return null
        val consentedAt = consentStore.locationSummarySharingConsentAtUtcMillis() ?: return null
        val fix = captureCoarseFix()
        if (!consentStore.isLocationSummarySharingEnabled()) return null
        val currentConsentedAt = consentStore.locationSummarySharingConsentAtUtcMillis() ?: return null
        val summary = coarsener.coarsen(fix)
        return summary.takeIf {
            it.measuredAtUtcMillis >= consentedAt && it.measuredAtUtcMillis >= currentConsentedAt
        }
    }

    /** User-started tracking uses coarse local summaries and needs no Coach sharing grant. */
    suspend fun captureMobilitySummary(): LocationSummary =
        coarsener.coarsen(captureCoarseFix())

    /** A one-shot Coach summary is saved only while the separate sharing consent remains active. */
    suspend fun captureManualSummaryForCoach(consentStore: LocationConsentStore): LocationSummary? {
        val summary = captureAutomaticSummary(consentStore) ?: return null
        return summary.takeIf(consentStore::appendManualSummary)
    }

    private suspend fun captureCoarseFix(): AtlasLocationFix {
        if (!provider.permissionState().canCapture) throw permissionRequired()
        val fix = provider.capture(LocationCaptureRequest(desiredAccuracy = LocationAccuracy.COARSE))
        if (!provider.permissionState().canCapture) throw permissionRequired()
        return fix
    }

    private fun permissionRequired() = LocationCaptureException(
        LocationErrorCode.PERMISSION_REQUIRED,
        "${LocationErrorCode.PERMISSION_REQUIRED}: grant foreground location access in Android settings",
    )

    private fun LocationSummary.asFix() = AtlasLocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = accuracyMeters,
        capturedAtUtcMillis = measuredAtUtcMillis,
    )
}
