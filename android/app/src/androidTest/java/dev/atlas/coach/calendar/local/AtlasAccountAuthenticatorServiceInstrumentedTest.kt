package dev.atlas.coach.calendar.local

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtlasAccountAuthenticatorServiceInstrumentedTest {
    @Test
    fun systemCanBindTheNoOpAuthenticatorForALocalAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AccountManager.get(context)
        val account = Account("atlas-r3a-bind-${System.nanoTime()}", AtlasLocalCalendar.ACCOUNT_TYPE)

        assertTrue(manager.addAccountExplicitly(account, null, null))
        try {
            // Reaching the authenticator proves that AccountManager (system UID) can
            // bind through the ACCOUNT_MANAGER permission. The expected response is
            // the deliberate unsupported-operation error, surfaced as an exception.
            manager.getAuthToken(account, "test", null, false, null, null)
                .getResult(10, TimeUnit.SECONDS)
            throw AssertionError("expected the no-op authenticator to reject token requests")
        } catch (expected: AuthenticatorException) {
            assertTrue(expected.cause is UnsupportedOperationException)
            assertTrue(expected.cause?.message?.contains("not interactive") == true)
        } finally {
            manager.removeAccountExplicitly(account)
        }
    }
}
