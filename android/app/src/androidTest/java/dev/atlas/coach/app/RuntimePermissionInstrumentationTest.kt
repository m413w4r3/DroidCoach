package dev.atlas.coach.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.location.LocationCaptureException
import dev.atlas.coach.location.LocationCaptureManager
import dev.atlas.coach.location.LocationCaptureRequest
import dev.atlas.coach.location.LocationErrorCode
import dev.atlas.coach.test.BackendArgumentsAwareRunner
import dev.atlas.coach.test.RequiresBackendArguments
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies real Android runtime permission state set by the disposable-AVD host recipe. */
@RunWith(BackendArgumentsAwareRunner::class)
class RuntimePermissionInstrumentationTest {
    @Test
    @RequiresBackendArguments(
        keys = ["permission_state"],
        reason = "requires the disposable-AVD host to grant or revoke Android runtime permissions",
    )
    fun systemGrantAndRevocationMatchAppPermissionGate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedState = requireNotNull(InstrumentationRegistry.getArguments().getString("permission_state"))
        val coarseGranted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val notificationsGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val locationManager = LocationCaptureManager(context)

        when (expectedState) {
            "denied", "revoked" -> {
                assertFalse("Approximate location must remain denied", coarseGranted)
                assertFalse("Fine location must remain denied", fineGranted)
                assertFalse("Notification permission must be denied in this state", notificationsGranted)
                assertFalse("The production location gate must close after runtime denial", locationManager.permissionState().canCapture)
                val failure = runBlocking {
                    runCatching {
                        locationManager.capture(LocationCaptureRequest(timeoutMs = 1_000L, maxAgeMs = 0L))
                    }.exceptionOrNull()
                } as? LocationCaptureException
                assertEquals(LocationErrorCode.PERMISSION_REQUIRED, failure?.code)
            }

            "granted" -> {
                assertTrue("The host should grant approximate location", coarseGranted)
                assertFalse("The host must keep fine location denied", fineGranted)
                assertTrue("The host should grant notification permission", notificationsGranted)
                assertTrue("The production location gate should open for approximate access", locationManager.permissionState().canCapture)
            }

            else -> error("permission_state must be denied, granted, or revoked; got '$expectedState'")
        }
    }
}
