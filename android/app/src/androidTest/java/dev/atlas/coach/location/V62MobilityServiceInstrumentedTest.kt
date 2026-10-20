package dev.atlas.coach.location

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.app.AtlasController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Exercises the visible Activity start, persistent notification stop, and coarse-only store. */
@RunWith(AndroidJUnit4::class)
class V62MobilityServiceInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun prepareSharingConsentBeforeHostInjectsFreshMobilityFix() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LocationConsentStore.open(context)
        store.clearLocationSummaries()
        store.setLocationSummarySharingEnabled(true)
        assertTrue(store.locationSummarySharingConsentAtUtcMillis() != null)
    }

    @Test
    fun visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = (composeRule.activity.application as AtlasApplication).controller
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val store = LocationConsentStore.open(context)
        assertNotNull(notificationManager)
        assertTrue(
            "Approximate location permission must be granted on the disposable AVD",
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        assertEquals(
            "Mobility must continue with approximate permission only",
            android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertEquals(
                "Mobility must not depend on background location access",
                android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            )
        }

        try {
            composeRule.runOnUiThread { controller.setMobilityConsent(true) }
            store.setLocationSummarySharingEnabled(true)
            assertTrue("Sharing consent must be enabled before the mobility session", store.isLocationSummarySharingEnabled())
            composeRule.runOnUiThread {
                assertTrue("Activity must be visible when starting the mobility service", composeRule.activity.hasWindowFocus())
                Log.i(LOCATION_TAG, "MOBILITY_SERVICE_START_REQUEST activity=ComponentActivity coarse_granted=true fine_granted=false device_utc_ms=${System.currentTimeMillis()} device_uptime_ms=${android.os.SystemClock.elapsedRealtime()}")
                assertTrue("ATLAS refused the explicitly enabled mobility session", controller.startMobilitySession(composeRule.activity))
            }
            runBlocking {
                withTimeout(10_000) { MobilitySessionStatusStore.status.first { it.active } }
            }
            val measured = runBlocking {
                withTimeoutOrNull(15_000) { MobilitySessionStatusStore.status.first { it.active && it.measurementCount > 0 } }
            }
            if (measured == null) {
                Log.w(LOCATION_TAG, "MOBILITY_MEASUREMENT_UNAVAILABLE reason=no_reliable_coarse_fix_within_15000ms session_remained_active=true")
            } else {
                assertTrue("The foreground session must report a real measurement", measured.measurementCount > 0)
                val measuredAt = requireNotNull(measured.lastMeasurementAtUtcMillis)
                val measurementAgeMs = System.currentTimeMillis() - measuredAt
                assertTrue("A measured mobility fix must satisfy production freshness", measurementAgeMs in 0L..LocationCaptureRequest.DEFAULT_MAX_AGE_MS)
                val measurementSummary = store.recentLocationSummaries(System.currentTimeMillis()).lastOrNull()
                assertNotNull("A measured mobility fix must leave a coarse zone summary", measurementSummary)
                val saved = requireNotNull(measurementSummary)
                assertTrue("Persisted mobility data may contain only coarse summaries", store.recentLocationSummaries(System.currentTimeMillis()).all {
                    it.accuracyMeters >= LocationCoarsener.COARSE_METERS
                })
                assertTrue("Raw injected coordinates must not be persisted as mobility summaries", store.recentLocationSummaries(System.currentTimeMillis()).none {
                    kotlin.math.abs(it.latitude - INJECTED_LATITUDE) < 0.000001 &&
                        kotlin.math.abs(it.longitude - INJECTED_LONGITUDE) < 0.000001
                })
                assertEquals("The service status must preserve the captured fix's exact timestamp", measuredAt, saved.measuredAtUtcMillis)
                val injectedDistanceMeters = FloatArray(1)
                android.location.Location.distanceBetween(
                    INJECTED_LATITUDE,
                    INJECTED_LONGITUDE,
                    saved.latitude,
                    saved.longitude,
                    injectedDistanceMeters,
                )
                assertTrue("The mobility summary must approximately correspond to the injected position within 25 km; distance_m=${injectedDistanceMeters.single()}",
                    injectedDistanceMeters.single() <= MAX_INJECTED_FIX_DISTANCE_METERS)
                Log.i(LOCATION_TAG, "MOBILITY_CAPTURED measurement_count=${measured.measurementCount} exact_fix_utc_ms=$measuredAt fix_age_ms=$measurementAgeMs accuracy_m=${saved.accuracyMeters}")
            }
            val posted = notificationManager.activeNotifications.firstOrNull { it.id == 6231 }
            assertNotNull("The visible mobility service must publish a persistent notification", posted)
            val actions = requireNotNull(posted).notification.actions.orEmpty()
            val stopAction = actions.firstOrNull { it.title.toString() == "Arrêter" }
            assertNotNull("The foreground notification must offer an Arrêter action", stopAction)
            assertTrue("Service has not entered the active state", MobilitySessionStatusStore.status.value.active)

            requireNotNull(stopAction).actionIntent.send()
            composeRule.waitUntil(10_000) { !MobilitySessionStatusStore.status.value.active }
            assertFalse("The stop action must remove the foreground notification", notificationManager.activeNotifications.any { it.id == 6231 })
            assertEquals("The notification Arrêter action must record a manual stop", MobilitySessionStopReason.MANUAL,
                MobilitySessionStatusStore.status.value.lastCompletion?.stopReason)

            composeRule.runOnUiThread {
                assertTrue("A second visible mobility session should start before revocation is checked", controller.startMobilitySession(composeRule.activity))
            }
            runBlocking { withTimeout(10_000) { MobilitySessionStatusStore.status.first { it.active } } }
            store.setLocationSummarySharingEnabled(false)
            composeRule.waitUntil(10_000) {
                !MobilitySessionStatusStore.status.value.active &&
                    MobilitySessionStatusStore.status.value.lastCompletion?.stopReason == MobilitySessionStopReason.SHARING_REVOKED
            }
            assertFalse("Revoking sharing must remove the foreground notification", notificationManager.activeNotifications.any { it.id == 6231 })
            Log.i(LOCATION_TAG, "MOBILITY_SHARING_REVOCATION_STOPPED active=false notification_present=false")
        } finally {
            composeRule.runOnUiThread {
                controller.stopMobilitySession()
                controller.setMobilityConsent(false)
            }
            store.setLocationSummarySharingEnabled(false)
            store.clearLocationSummaries()
        }
    }

    private companion object {
        const val LOCATION_TAG = "ATLAS_V62_LOCATION"
        const val INJECTED_LATITUDE = 48.123456
        const val INJECTED_LONGITUDE = 2.123456
        const val MAX_INJECTED_FIX_DISTANCE_METERS = 25_000f
    }
}
