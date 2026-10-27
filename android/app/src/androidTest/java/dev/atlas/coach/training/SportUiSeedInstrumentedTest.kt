package dev.atlas.coach.training

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.sync.AndroidSyncSettingsStore
import dev.atlas.coach.sync.SharedPreferencesBearerTokenStore
import dev.atlas.coach.sync.SyncDataCategory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Seeds the synthetic backend URL and token so the Sport screen can perform its real read. */
@RunWith(AndroidJUnit4::class)
class SportUiSeedInstrumentedTest {
    @Test
    fun seedBackendForSportRead() {
        val arguments = InstrumentationRegistry.getArguments()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backendUrl = arguments.getString("backendUrl") ?: "http://10.0.2.2:8100"
        val token = arguments.getString("token") ?: error("missing token")
        SharedPreferencesBearerTokenStore(context).configureToken(token)
        AndroidSyncSettingsStore(context).write(backendUrl, setOf(SyncDataCategory.FOCUS))
        assertTrue(SharedPreferencesBearerTokenStore(context).token() != null)
        assertTrue(AndroidSyncSettingsStore(context).read().baseUrl == backendUrl)
    }
}
