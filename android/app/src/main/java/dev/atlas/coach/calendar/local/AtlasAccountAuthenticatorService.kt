package dev.atlas.coach.calendar.local

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Exposes the no-op authenticator required by AccountManager for the local account type. */
class AtlasAccountAuthenticatorService : Service() {
    private lateinit var authenticator: AtlasAccountAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = AtlasAccountAuthenticator(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
