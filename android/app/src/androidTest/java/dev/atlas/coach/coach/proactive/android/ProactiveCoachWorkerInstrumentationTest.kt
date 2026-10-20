package dev.atlas.coach.coach.proactive.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import dev.atlas.coach.app.AtlasApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProactiveCoachWorkerInstrumentationTest {
    @Test
    fun periodicJobKeepsOneThirtyMinuteRequestAndWorkerRunsWithoutActivity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val workManager = WorkManager.getInstance(context)

        ProactiveCoachWorkScheduler.refresh(context, eligible = true)
        val first = workManager.getWorkInfosForUniqueWork(ProactiveCoachWorkScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)
        ProactiveCoachWorkScheduler.refresh(context, eligible = true)
        val second = workManager.getWorkInfosForUniqueWork(ProactiveCoachWorkScheduler.UNIQUE_WORK_NAME)
            .get(5, TimeUnit.SECONDS)

        assertEquals(30L, ProactiveCoachWorkScheduler.REPEAT_INTERVAL_MINUTES)
        assertEquals(1, first.size)
        assertEquals(first.single().id, second.single().id)

        val worker = TestListenableWorkerBuilder<ProactiveCoachWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(context is AtlasApplication)
        ProactiveCoachWorkScheduler.cancel(context)
    }
}
