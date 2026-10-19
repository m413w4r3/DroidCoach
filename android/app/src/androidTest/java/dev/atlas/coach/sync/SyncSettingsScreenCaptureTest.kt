package dev.atlas.coach.sync

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.app.SyncSettingsScreen
import dev.atlas.coach.app.ui.AtlasTheme
import dev.atlas.coach.app.ui.AtlasRoutes
import dev.atlas.coach.app.ui.AtlasScreenFrame
import dev.atlas.coach.focus.FocusPlan
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class SyncSettingsScreenCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun captureRequestedState() {
        val arguments = InstrumentationRegistry.getArguments()
        val state = arguments.getString("capture_state")
        if (state !in setOf("disabled", "synchronized", "offline")) {
            android.util.Log.i("ATLAS_H2", "capture_state not requested; no screenshot scenario executed")
            return
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        resetSyncState(context)
        lateinit var controller: AtlasController
        composeRule.runOnUiThread { controller = AtlasController(context) }

        composeRule.setContent {
            AtlasTheme {
                AtlasScreenFrame(
                    title = "Partage backend",
                    activeDestination = AtlasRoutes.MORE,
                    canGoBack = false,
                    emergencyPaused = controller.isEmergencyPaused,
                    onBack = {},
                    onEmergencyPause = { if (controller.isEmergencyPaused) controller.endEmergencyPause() else controller.setEmergencyPause() },
                    onNavigate = {},
                ) {
                    SyncSettingsScreen(controller, controller.revision)
                }
            }
        }
        composeRule.onNodeWithText("Partage backend facultatif").assertIsDisplayed()
        composeRule.onNodeWithTag("sync_status_card").assertIsDisplayed()

        when (state) {
            "disabled" -> {
                composeRule.onNodeWithText("Désactivée").assertIsDisplayed()
                assertEquals(SyncStatus.LOCAL_ONLY, controller.syncStatus)
            }
            "synchronized", "offline" -> {
                val backendUrl = arguments.getString("backend_url") ?: error("backend_url is required")
                val token = arguments.getString("backend_token") ?: error("backend_token is required")
                composeRule.runOnUiThread {
                    controller.setSyncCategory(SyncDataCategory.FOCUS, true)
                    controller.saveSyncConfiguration(backendUrl, token)
                    controller.setSyncEnabled(true)
                    controller.startFocus(FocusPlan.custom(20).copy(presetId = "h2-screen-capture"))
                }
                if (state == "synchronized") {
                    composeRule.waitUntil(15_000) {
                        controller.syncStatus == SyncStatus.SUCCESS && controller.syncPendingCount == 0
                    }
                    composeRule.onNodeWithTag("sync_status_card").assertIsDisplayed()
                } else {
                    composeRule.waitUntil(15_000) {
                        controller.syncStatus == SyncStatus.RETRY_SCHEDULED && controller.syncPendingCount == 1
                    }
                    composeRule.onNodeWithTag("sync_status_card").assertIsDisplayed()
                }
            }
        }
        composeRule.waitForIdle()
        if (state != "disabled") shell("screencap -p /sdcard/H2-$state.png")
        else android.util.Log.i("ATLAS_H2", "capture_state=disabled; screenshot omitted because this standalone host is blank; use the AtlasVisualCaptureTest LocalMock capture")
        android.util.Log.i("ATLAS_H2", "capture_state=$state status=${controller.syncStatus} pending=${controller.syncPendingCount}")
        if (state != "disabled") {
            composeRule.runOnUiThread {
                controller.disableAndPurgeSync()
                controller.clearSyncToken()
                controller.saveSyncConfiguration("", null)
            }
        }
    }

    private fun resetSyncState(context: Context) {
        context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
        AndroidSyncStorage.settings(context).write("", emptySet())
        SharedPreferencesBearerTokenStore(context).configureToken(null)
        AndroidSyncStorage.outbox(context).clear()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { descriptor -> BufferedReader(InputStreamReader(descriptor)).readText() }
}
