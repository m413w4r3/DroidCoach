package dev.atlas.coach.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.atlas.coach.coach.proactive.CoachTriggerSource
import dev.atlas.coach.coach.proactive.ProactiveEngagementMode
import dev.atlas.coach.coach.proactive.ProactiveNotification
import dev.atlas.coach.coach.proactive.ProactivePublicationRejectedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class ProactiveCoachNotificationPublisherInstrumentationTest {
    @Test
    fun revocationRemovesOnlyProactiveChannelIncludingLegacyUntaggedNotice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantNotificationPermissionIfNeeded(context.packageName)
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val publisher = ProactiveCoachNotificationPublisher(context)
        val witnessChannel = "atlas_test_witness_${UUID.randomUUID()}"
        val witnessId = 71_003
        val legacyId = 71_004
        val notification = notification("targeted-${UUID.randomUUID()}")
        val targetTag = ProactiveCoachNotificationPublisher.publicationTag(notification.notificationId)

        try {
            manager.createNotificationChannel(NotificationChannel(
                witnessChannel,
                "Test witness",
                NotificationManager.IMPORTANCE_DEFAULT,
            ))
            manager.createNotificationChannel(NotificationChannel(
                ProactiveCoachNotificationPublisher.CHANNEL_ID,
                "Coach proactif",
                NotificationManager.IMPORTANCE_DEFAULT,
            ))
            manager.notify(
                legacyId,
                NotificationCompat.Builder(context, ProactiveCoachNotificationPublisher.CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("legacy proactive")
                    .setContentText("untagged test notice")
                    .build(),
            )
            manager.notify(
                witnessId,
                NotificationCompat.Builder(context, witnessChannel)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("unrelated witness")
                    .setContentText("must remain visible")
                    .build(),
            )
            publisher.show(notification) { true }

            awaitActive(manager, "tagged, legacy and witness notifications must all become active") { active ->
                active.any { it.tag == targetTag } &&
                    active.any { it.tag == null && it.id == legacyId && it.notification.channelId == ProactiveCoachNotificationPublisher.CHANNEL_ID } &&
                    active.any { it.id == witnessId && it.notification.channelId == witnessChannel }
            }

            val removed = publisher.cancelPublishedProactiveNotifications()
            val activeAfterRemoval = awaitActive(manager, "only proactive-channel notices should disappear") { active ->
                active.none { it.notification.channelId == ProactiveCoachNotificationPublisher.CHANNEL_ID } &&
                    active.any { it.id == witnessId && it.notification.channelId == witnessChannel }
            }
            assertTrue("At least the legacy and newly tagged notices should be removed", removed >= 2)
            assertTrue(activeAfterRemoval.any { it.id == witnessId && it.notification.channelId == witnessChannel })
        } finally {
            runCatching { manager.cancel(targetTag, 0) }
            runCatching { manager.cancel(legacyId) }
            runCatching { manager.cancel(witnessId) }
            runCatching { manager.deleteNotificationChannel(witnessChannel) }
        }
    }

    @Test
    fun finalAuthorizationGateRejectsAndConcurrentRevocationSerializesTargetedRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantNotificationPermissionIfNeeded(context.packageName)
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val publisher = ProactiveCoachNotificationPublisher(context)

        val rejected = notification("rejected-${UUID.randomUUID()}")
        val rejectedTag = ProactiveCoachNotificationPublisher.publicationTag(rejected.notificationId)
        try {
            var finalGateCalled = false
            val failure = runCatching {
                publisher.show(rejected) {
                    finalGateCalled = true
                    false
                }
            }.exceptionOrNull()
            assertTrue(finalGateCalled)
            assertTrue(failure is ProactivePublicationRejectedException)
            awaitActive(manager, "a rejected final gate must leave no active tagged notice") { active ->
                active.none { it.tag == rejectedTag }
            }
        } finally {
            manager.cancel(rejectedTag, 0)
        }

        // Keep a publication staged outside the publisher, invalidate and remove concurrently,
        // then release it to the final gate. The stale epoch must be rejected before notify.
        val invalidatedBeforeGate = notification("invalidated-before-gate-${UUID.randomUUID()}")
        val invalidatedBeforeGateTag = ProactiveCoachNotificationPublisher.publicationTag(invalidatedBeforeGate.notificationId)
        val beforeGateAuthorized = AtomicBoolean(true)
        val beforeGateStaged = CountDownLatch(1)
        val releaseBeforeGate = CountDownLatch(1)
        val beforeGateEntered = CountDownLatch(1)
        val beforeGateRemovalFinished = CountDownLatch(1)
        val beforeGateFailure = AtomicReference<Throwable>()
        var beforeGateRemover: Thread? = null
        val beforeGateShowThread = Thread {
            beforeGateStaged.countDown()
            try {
                check(releaseBeforeGate.await(5, TimeUnit.SECONDS)) { "Pre-gate barrier timed out" }
                publisher.show(invalidatedBeforeGate) {
                    beforeGateEntered.countDown()
                    beforeGateAuthorized.get()
                }
            } catch (failure: Throwable) {
                beforeGateFailure.set(failure)
            }
        }
        try {
            beforeGateShowThread.start()
            assertTrue("Publication did not reach the controlled pre-gate boundary", beforeGateStaged.await(5, TimeUnit.SECONDS))
            val remover = Thread {
                beforeGateAuthorized.set(false)
                try {
                    publisher.cancelPublishedProactiveNotifications()
                } finally {
                    beforeGateRemovalFinished.countDown()
                }
            }
            beforeGateRemover = remover
            remover.start()
            assertTrue("Concurrent invalidation/removal did not finish before the final gate", beforeGateRemovalFinished.await(5, TimeUnit.SECONDS))
            releaseBeforeGate.countDown()
            assertTrue("Publisher never evaluated the final gate after invalidation", beforeGateEntered.await(5, TimeUnit.SECONDS))
            beforeGateShowThread.join(5_000L)
            beforeGateRemover.join(5_000L)

            assertFalse("Pre-gate publisher thread did not finish", beforeGateShowThread.isAlive)
            assertFalse("Pre-gate removal thread did not finish", requireNotNull(beforeGateRemover).isAlive)
            assertTrue(beforeGateFailure.get() is ProactivePublicationRejectedException)
            awaitActive(manager, "invalidation before the final gate must prevent notify") { active ->
                active.none { it.tag == invalidatedBeforeGateTag }
            }
        } finally {
            releaseBeforeGate.countDown()
            beforeGateShowThread.join(5_000L)
            beforeGateRemover?.join(5_000L)
            manager.cancel(invalidatedBeforeGateTag, 0)
        }

        val concurrent = notification("concurrent-${UUID.randomUUID()}")
        val concurrentTag = ProactiveCoachNotificationPublisher.publicationTag(concurrent.notificationId)
        val authorized = AtomicBoolean(true)
        val finalGateEntered = CountDownLatch(1)
        val releaseFinalGate = CountDownLatch(1)
        val removalStarted = CountDownLatch(1)
        val removalFinished = CountDownLatch(1)
        val publicationFailure = AtomicReference<Throwable>()
        val showThread = Thread {
            try {
                publisher.show(concurrent) {
                    finalGateEntered.countDown()
                    check(releaseFinalGate.await(5, TimeUnit.SECONDS)) { "Final gate barrier timed out" }
                    authorized.get()
                }
            } catch (failure: Throwable) {
                publicationFailure.set(failure)
            }
        }
        var removalThread: Thread? = null

        try {
            showThread.start()
            assertTrue("Publisher did not reach the final authorization gate", finalGateEntered.await(5, TimeUnit.SECONDS))
            authorized.set(false)
            val remover = Thread {
                removalStarted.countDown()
                try {
                    publisher.cancelPublishedProactiveNotifications()
                } finally {
                    removalFinished.countDown()
                }
            }
            removalThread = remover
            remover.start()
            assertTrue("Revocation removal did not start", removalStarted.await(5, TimeUnit.SECONDS))
            assertFalse("Removal must wait for the in-flight final gate lock", removalFinished.await(100, TimeUnit.MILLISECONDS))
            releaseFinalGate.countDown()
            showThread.join(5_000L)
            remover.join(5_000L)

            assertFalse("Publisher thread did not finish", showThread.isAlive)
            assertFalse("Removal thread did not finish", remover.isAlive)
            assertTrue(publicationFailure.get() is ProactivePublicationRejectedException)
            awaitActive(manager, "invalidated publication must not become active") { active ->
                active.none { it.tag == concurrentTag }
            }
        } finally {
            releaseFinalGate.countDown()
            showThread.join(5_000L)
            removalThread?.join(5_000L)
            manager.cancel(concurrentTag, 0)
        }

        // Complementary boundary: if notify already returned, a later revocation removes that notice.
        val alreadyPosted = notification("already-posted-${UUID.randomUUID()}")
        val alreadyPostedTag = ProactiveCoachNotificationPublisher.publicationTag(alreadyPosted.notificationId)
        try {
            publisher.show(alreadyPosted) { true }
            awaitActive(manager, "authorized notice should become active before the later revocation") { active ->
                active.any { it.tag == alreadyPostedTag }
            }
            publisher.cancelPublishedProactiveNotifications()
            awaitActive(manager, "later targeted removal should clear the already-posted notice") { active ->
                active.none { it.tag == alreadyPostedTag }
            }
        } finally {
            manager.cancel(alreadyPostedTag, 0)
        }
    }

    private fun awaitActive(
        manager: NotificationManager,
        description: String,
        condition: (List<StatusBarNotification>) -> Boolean,
    ): List<StatusBarNotification> {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var active = manager.activeNotifications.filter { it.packageName == InstrumentationRegistry.getInstrumentation().targetContext.packageName }
        while (!condition(active) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(25L)
            active = manager.activeNotifications.filter { it.packageName == InstrumentationRegistry.getInstrumentation().targetContext.packageName }
        }
        assertTrue(description, condition(active))
        return active
    }

    private fun grantNotificationPermissionIfNeeded(packageName: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    private fun notification(id: String) = ProactiveNotification(
        notificationId = id,
        localProposalId = null,
        title = "Plan de test",
        body = "Contenu synthétique de publication.",
        mode = ProactiveEngagementMode.NORMAL,
        triggerSources = setOf(CoachTriggerSource.DAILY_PLAN_DUE),
    )
}
