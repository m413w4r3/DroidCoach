package dev.atlas.coach.sync

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidTokenStoreInstrumentationTest {
    @Test
    fun tokenIsEncryptedAtRestAndCanBeRevoked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SharedPreferencesBearerTokenStore(context)
        val token = "test-${UUID.randomUUID()}"
        try {
            store.configureToken(token)
            assertEquals(token, SharedPreferencesBearerTokenStore(context).token())
            val savedValue = context.getSharedPreferences(
                SharedPreferencesBearerTokenStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ).getString(SharedPreferencesBearerTokenStore.TOKEN_KEY, null)
            assertNotEquals(token, savedValue)
        } finally {
            store.configureToken(null)
        }
        assertNull(store.token())
    }
}
