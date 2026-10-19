package dev.atlas.coach

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.focus.FakeClock
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.android.AndroidFocusNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class FocusIntegrationInstrumentationTest {
    @Test
    fun finiteTwoCyclePomodoroFinishesAndPostsPhaseNotification() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)

        val preferences = context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.cancelAll()
        val clock = FakeClock(System.currentTimeMillis(), SystemClock.elapsedRealtime())
        lateinit var controller: AtlasController

        instrumentation.runOnMainSync {
            controller = AtlasController(context, clock)
            controller.setCapability(AtlasController.CAP_FOCUS_NOTIFICATIONS, true)
            controller.startFocus(
                FocusPlan(
                    presetId = "h1-two-cycle-fast",
                    workSeconds = 1,
                    shortBreakSeconds = 1,
                    longBreakSeconds = 1,
                    longBreakEveryCycles = 9,
                    totalWorkCycles = 2,
                    continuous = false,
                ),
            )
            repeat(3) {
                clock.advanceMillis(1_000)
                controller.tickFocus()
            }
        }

        assertEquals(FocusStatus.FINISHED, controller.focusSession?.status)
        assertEquals(2, controller.focusSession?.completedWorkCycles)
        val postedPhaseNotification = notifications.activeNotifications.any {
            it.notification.channelId == AndroidFocusNotifications.CHANNEL_ID
        }
        assertTrue("A final Focus phase notification was not active", postedPhaseNotification)
        android.util.Log.i("ATLAS_H1", "focus_two_cycle_status=${controller.focusSession?.status} completed=${controller.focusSession?.completedWorkCycles} notification=$postedPhaseNotification")

        shell("input keyevent 4")
        shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
        SystemClock.sleep(300)
        shell("am start -n ${context.packageName}/dev.atlas.coach.app.MainActivity -f 0x10008000")
        SystemClock.sleep(700)
        shell("input tap 370 1810")
        SystemClock.sleep(500)
        shell("screencap -p /sdcard/H1-focus-finished.png")

        notifications.cancelAll()
        instrumentation.runOnMainSync {
            controller.setCapability(AtlasController.CAP_FOCUS_NOTIFICATIONS, false)
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { descriptor -> BufferedReader(InputStreamReader(descriptor)).readText() }
}
