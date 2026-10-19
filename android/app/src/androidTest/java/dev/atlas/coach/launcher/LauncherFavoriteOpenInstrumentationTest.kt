package dev.atlas.coach.launcher

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.app.AtlasController
import dev.atlas.coach.app.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class LauncherFavoriteOpenInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun resetLocalState() {
        context.getSharedPreferences("atlas_local_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun favoritePinSurvivesControllerRecreationAndLaunchesSettings() {
        val catalog = AndroidLauncherCatalog(context)
        val settingsApp = catalog.list().firstOrNull { it.appPackage == "com.android.settings" }
        assertNotNull("Android Settings must be discoverable from MAIN/LAUNCHER", settingsApp)
        val app = requireNotNull(settingsApp)
        assertFalse("ATLAS does not appear in its own launcher list", catalog.list().any { it.appPackage == context.packageName })

        val firstController = AtlasController(context)
        firstController.setCapability(AtlasController.CAP_LAUNCHER_HOME, true)
        firstController.toggleLauncherPin(app.appPackage)
        val restoredController = AtlasController(context)
        assertTrue(app.appPackage in restoredController.launcherPinnedPackages)

        assertTrue(catalog.launch(app))
        instrumentation.waitForIdleSync()
        val visibleActivity = shell("dumpsys activity activities | grep mResumedActivity")
        assertTrue("The pinned Android Settings activity should open", visibleActivity.contains("com.android.settings"))

        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        instrumentation.waitForIdleSync()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { descriptor -> BufferedReader(InputStreamReader(descriptor)).readText() }
}
