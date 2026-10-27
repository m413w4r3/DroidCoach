package dev.atlas.coach.calendar.local

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder

/**
 * Minimal, offline sync adapter for the ATLAS local calendar account.
 *
 * Its only purpose is to satisfy CalendarProvider's legitimate sync-adapter
 * requirement when the local calendar row is created. It never contacts a
 * network, never opens a socket and never mutates events.
 */
class AtlasCalendarSyncService : android.app.Service() {
    private var adapter: AbstractThreadedSyncAdapter? = null

    override fun onCreate() {
        super.onCreate()
        adapter = object : AbstractThreadedSyncAdapter(applicationContext, false) {
            override fun onPerformSync(
                account: Account,
                extras: Bundle,
                authority: String,
                provider: ContentProviderClient?,
                syncResult: SyncResult,
            ) {
                // Intentionally empty: the ATLAS local calendar is not synchronized.
            }

            override fun onSyncCanceled() {
                // No work to cancel.
            }
        }
    }

    override fun onBind(intent: android.content.Intent?): IBinder? = adapter?.syncAdapterBinder

    override fun onDestroy() {
        adapter = null
        super.onDestroy()
    }
}
