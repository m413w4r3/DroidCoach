package dev.atlas.coach.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasApplication
import dev.atlas.coach.sync.relay.RelayConnectionState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class V7_06C_RevokedDeviceReconnectInstrumentedTest {
    @Test
    fun revokedDeviceCredentialsCannotReconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as AtlasApplication
        val relaySettings = app.controller.relaySettings

        var credentialsConfigured = false
        var observedState: RelayConnectionState? = null
        var closeCode = "null"
        var failure: Throwable? = null
        var failureReason = "probe_error"

        try {
            credentialsConfigured = relaySettings.credentialsConfigured
            if (!credentialsConfigured) failureReason = "credentials_not_configured"
            assertTrue("QA relay credentials must already be configured", credentialsConfigured)

            instrumentation.runOnMainSync { relaySettings.connect() }
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
            while (SystemClock.uptimeMillis() < deadline) {
                observedState = relaySettings.state
                if (isExplicitRefusal(observedState)) break
                SystemClock.sleep(POLL_INTERVAL_MILLIS)
            }
            observedState = relaySettings.state
            closeCode = relaySettings.lastCloseCode?.toString() ?: "null"
            if (!isExplicitRefusal(observedState)) failureReason = "explicit_refusal_not_observed"
            assertTrue(
                "Revoked device reconnect was not explicitly refused within 30s; state=${observedState?.name}",
                isExplicitRefusal(observedState),
            )
            failureReason = "none"
        } catch (caught: Throwable) {
            failure = caught
            if (failureReason == "probe_error") failureReason = caught.javaClass.simpleName
        } finally {
            try {
                instrumentation.runOnMainSync { relaySettings.disconnect() }
            } catch (cleanupFailure: Throwable) {
                val earlierFailure = failure
                if (earlierFailure == null) {
                    failure = cleanupFailure
                    failureReason = "disconnect_failed"
                } else {
                    earlierFailure.addSuppressed(cleanupFailure)
                }
            }
        }

        val failureToThrow = failure
        if (failureToThrow != null) {
            Log.e(
                LOG_TAG,
                "v7_06c_revoked_reconnect result=FAIL state=${observedState?.name ?: "unavailable"} " +
                    "close_code=$closeCode credentials=$credentialsConfigured reason=$failureReason",
            )
            throw failureToThrow
        }
        Log.i(
            LOG_TAG,
            "v7_06c_revoked_reconnect result=PASS state=${observedState?.name} " +
                "close_code=$closeCode credentials=true",
        )
    }

    private fun isExplicitRefusal(state: RelayConnectionState?): Boolean =
        state == RelayConnectionState.REVOKED || state == RelayConnectionState.AUTH_REQUIRED

    private companion object {
        const val LOG_TAG = "V7_06C_RevokeProbe"
        const val TIMEOUT_MILLIS = 30_000L
        const val POLL_INTERVAL_MILLIS = 250L
    }
}
