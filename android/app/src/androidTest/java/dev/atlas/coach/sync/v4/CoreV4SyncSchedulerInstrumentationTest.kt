package dev.atlas.coach.sync.v4

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

@RunWith(AndroidJUnit4::class)
class CoreV4SyncSchedulerInstrumentationTest {
    @Test
    fun requestNowEnqueuesOneImmediateWorkForRepeatedTaps() = runBlocking {
        val context = isolatedContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val workManager = WorkManager.getInstance(context)
        CoreV4SyncScheduler.finishRequestNow()
        workManager.cancelAllWork().result.get(5, TimeUnit.SECONDS)

        try {
            assertTrue(CoreV4SyncScheduler.requestNow(context))
            assertFalse("a second tap while the UI action is busy is discarded", CoreV4SyncScheduler.requestNow(context))
            assertTrue(CoreV4SyncScheduler.isRequestNowBusy())

            val first = workManager.getWorkInfosForUniqueWork(CoreV4SyncScheduler.IMMEDIATE_WORK)
                .get(5, TimeUnit.SECONDS)
            assertEquals(1, first.size)
            assertEquals(WorkInfo.State.ENQUEUED, first.single().state)

            delay(CoreV4SyncScheduler.REQUEST_NOW_DEBOUNCE_MILLIS + 1L)
            assertTrue("a later tap reuses the active unique work through KEEP", CoreV4SyncScheduler.requestNow(context))
            val afterRepeat = workManager.getWorkInfosForUniqueWork(CoreV4SyncScheduler.IMMEDIATE_WORK)
                .get(5, TimeUnit.SECONDS)
            assertEquals(1, afterRepeat.size)
            assertEquals(first.single().id, afterRepeat.single().id)
        } finally {
            CoreV4SyncScheduler.finishRequestNow()
            workManager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        }
    }

    private fun isolatedContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(base.filesDir, "core-v4-sync-now-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("${files.name}_$name", mode)
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = File(files, name)
        }
    }
}
