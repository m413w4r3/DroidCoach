package dev.atlas.coach.sync.relay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.RelaySettingsController
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seeds the relay settings from synthetic instrumentation arguments so the
 * production settings screen can be qualified without typing the token into a
 * shell command. The application process re-reads these values on launch.
 */
@RunWith(AndroidJUnit4::class)
class RelayUiSeedInstrumentedTest {
    @Test
    fun seedRelayConfiguration() {
        val arguments = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = RelaySettingsController(context)
        try {
            val result = controller.saveConfiguration(
                relayUrl = requireArg(arguments, "relayUrl"),
                serverId = requireArg(arguments, "serverId"),
                serverPublicKey = requireArg(arguments, "serverPublicKey"),
                deviceId = requireArg(arguments, "deviceId"),
                deviceToken = requireArg(arguments, "deviceToken"),
            )
            assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
            controller.selectMode(RelayTransportMode.RELAY)
        } finally {
            controller.close()
        }
    }

    private fun requireArg(arguments: android.os.Bundle, name: String): String =
        arguments.getString(name)?.takeIf { it.isNotBlank() }
            ?: error("missing instrumentation argument: $name")
}
