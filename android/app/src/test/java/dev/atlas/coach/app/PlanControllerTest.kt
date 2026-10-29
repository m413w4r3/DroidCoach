package dev.atlas.coach.app

import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.PlanClock
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.StatusProvenance
import dev.atlas.coach.plan.StatusSource
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlanControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Paris")
    private val dayOne = LocalDate.of(2027, 1, 15) // 09:00 Europe/Paris at fixedNow
    private val fixedNow = 1_800_000_000_000L

    private fun controller(
        directory: File = temporaryFolder.root,
        now: Long = fixedNow,
        date: LocalDate = dayOne,
    ) = PlanController(
        directory = directory,
        clock = PlanClock { now },
        zoneIdProvider = { zone },
        todayProvider = { date },
    )

    private fun addSample(controller: PlanController): String {
        assertTrue(controller.createTodayPlan())
        assertTrue(
            controller.addActivity(
                title = "Préparer la journée",
                kind = ActivityKind.FOCUS,
                startMinute = 9 * 60,
                durationMinutes = 50,
                priority = Priority.IMPORTANT,
            ),
        )
        assertTrue(controller.confirmPreview())
        return controller.plan!!.activities.single().activityId
    }

    @Test
    fun createdPlanPersistsAndReloadsAfterRestart() {
        val first = controller()
        val activityId = addSample(first)
        assertEquals(2, first.plan!!.version)

        val restarted = controller()
        assertEquals(2, restarted.plan!!.version)
        assertEquals(activityId, restarted.plan!!.activities.single().activityId)
        assertEquals("Préparer la journée", restarted.plan!!.activities.single().title)
    }

    @Test
    fun previewDoesNotMutateThePlanBeforeExplicitConfirmation() {
        val controller = controller()
        assertTrue(controller.createTodayPlan())
        assertTrue(
            controller.addActivity(
                title = "Réviser",
                kind = ActivityKind.FOCUS,
                startMinute = 10 * 60,
                durationMinutes = 25,
                priority = Priority.REQUIRED,
            ),
        )
        assertTrue(controller.plan!!.activities.isEmpty())
        assertEquals(1, controller.preview!!.revision.changes.size)
        assertTrue(controller.confirmPreview())
        assertEquals(1, controller.plan!!.activities.size)
        assertNull(controller.preview)
    }

    @Test
    fun cancellingAPreviewAppliesNothing() {
        val controller = controller()
        assertTrue(controller.createTodayPlan())
        assertTrue(
            controller.addActivity(
                title = "Course",
                kind = ActivityKind.TRAINING,
                startMinute = 18 * 60,
                durationMinutes = 40,
                priority = Priority.IMPORTANT,
            ),
        )
        controller.cancelPreview()
        assertTrue(controller.plan!!.activities.isEmpty())
        assertEquals(1, controller.plan!!.version)
    }

    @Test
    fun movingAnActivityRequiresAPreviewAndUpdatesTheStart() {
        val controller = controller()
        val activityId = addSample(controller)
        assertTrue(controller.moveActivity(activityId, 11 * 60))
        assertEquals(9 * 60, controller.plan!!.activities.single().scheduledLocal.startMinute)
        assertTrue(controller.confirmPreview())
        assertEquals(11 * 60, controller.plan!!.activities.single().scheduledLocal.startMinute)
    }

    @Test
    fun statusIsExplicitAndDoneActivityCannotBeRemoved() {
        val controller = controller()
        val activityId = addSample(controller)
        assertTrue(controller.setStatusAndConfirm(activityId, ActivityStatus.DONE))
        assertEquals(ActivityStatus.DONE, controller.plan!!.activities.single().status)
        assertEquals(StatusProvenance.CONFIRMED, controller.statusEvents().single().provenance)
        assertEquals(StatusSource.USER_UI, controller.statusEvents().single().source)
        assertFalse(controller.removeActivity(activityId))
        assertTrue(controller.plan!!.activities.any { it.activityId == activityId })
    }

    @Test
    fun futureActivityCannotBeMarkedMissed() {
        val controller = controller()
        val activityId = addSample(controller)
        assertFalse(controller.setStatus(activityId, ActivityStatus.MISSED))
        assertTrue(controller.allowedStatuses(controller.plan!!.activities.single()).none { it == ActivityStatus.MISSED })
    }

    @Test
    fun missedIsAllowedOnlyAfterTheSlotEnds() {
        val controller = controller(now = fixedNow + 60L * 60L * 1_000L)
        val activityId = addSample(controller)
        assertTrue(controller.allowedStatuses(controller.plan!!.activities.single()).contains(ActivityStatus.MISSED))
        assertTrue(controller.setStatus(activityId, ActivityStatus.MISSED))
        assertTrue(controller.confirmPreview())
        assertEquals(ActivityStatus.MISSED, controller.plan!!.activities.single().status)
    }

    @Test
    fun cancelledAndPostponedRulesAreEnforced() {
        val controller = controller()
        val activityId = addSample(controller)
        assertFalse(controller.setStatus(activityId, ActivityStatus.POSTPONED))
        assertTrue(controller.setStatus(activityId, ActivityStatus.CANCELLED))
        assertTrue(controller.confirmPreview())
        assertFalse(controller.setStatus(activityId, ActivityStatus.PLANNED))
        assertTrue(controller.allowedStatuses(controller.plan!!.activities.single()).isEmpty())
    }

    @Test
    fun crossDateReportSurfacesUnsupportedMessageWithoutChangingThePlan() {
        val controller = controller()
        val activityId = addSample(controller)
        val version = controller.plan!!.version

        assertFalse(
            controller.reportActivity(
                activityId = activityId,
                targetLocalDate = dayOne.plusDays(1).toString(),
                targetStartMinute = 10 * 60,
                targetDurationMinutes = 50,
                reason = "Report choisi par l’utilisateur",
            ),
        )

        assertEquals(version, controller.plan!!.version)
        assertEquals(ActivityStatus.PLANNED, controller.plan!!.activities.single().status)
        assertTrue(controller.message!!.contains("n'est pas encore disponible"))
    }

    @Test
    fun editingAnActivityRequiresPreviewAndApplies() {
        val controller = controller()
        val activityId = addSample(controller)
        assertTrue(controller.editActivity(activityId, "Titre modifié", ActivityKind.ADMIN, Priority.REQUIRED, "note"))
        assertEquals("Préparer la journée", controller.plan!!.activities.single().title)
        assertTrue(controller.confirmPreview())
        assertEquals("Titre modifié", controller.plan!!.activities.single().title)
        assertEquals(ActivityKind.ADMIN, controller.plan!!.activities.single().kind)
        assertEquals("note", controller.plan!!.activities.single().notes)
    }

    @Test
    fun removingAPlannedActivityRequiresPreviewAndApplies() {
        val controller = controller()
        val activityId = addSample(controller)
        assertTrue(controller.removeActivity(activityId))
        assertEquals(1, controller.plan!!.activities.size)
        assertTrue(controller.confirmPreview())
        assertTrue(controller.plan!!.activities.isEmpty())
    }

    @Test
    fun sameControllerReloadsTheNextDateWithoutLosingThePreviousDocument() {
        var date = dayOne
        val controller = PlanController(
            directory = temporaryFolder.root,
            clock = PlanClock { fixedNow },
            zoneIdProvider = { zone },
            todayProvider = { date },
        )
        addSample(controller)
        assertEquals(dayOne.toString(), controller.today)
        date = dayOne.plusDays(1)
        controller.refresh()
        assertNull(controller.plan)
        assertTrue(controller.createTodayPlan())
        assertEquals(dayOne.plusDays(1).toString(), controller.plan!!.localDate)
        date = dayOne
        controller.refresh()
        assertEquals(2, controller.plan!!.version)
        assertEquals(dayOne.toString(), controller.plan!!.localDate)
    }

    @Test
    fun pastTimeDoesNotCompleteAnActivity() {
        val controller = controller()
        val activityId = addSample(controller)
        val later = controller(now = fixedNow + 6L * 60L * 60L * 1_000L)
        later.refresh()
        assertEquals(ActivityStatus.PLANNED, later.plan!!.activities.single { it.activityId == activityId }.status)
    }

    @Test
    fun eachLocalDateHasItsOwnDocument() {
        val first = controller()
        addSample(first)
        val nextDay = controller(date = dayOne.plusDays(1))
        assertNull(nextDay.plan)
        assertEquals(0, nextDay.preview?.revision?.changes?.size ?: 0)
        val previous = controller()
        assertEquals(2, previous.plan!!.version)
    }

    @Test
    fun invalidWindowIsRejectedBeforeCreatingARevision() {
        val controller = controller()
        assertTrue(controller.createTodayPlan())
        assertFalse(
            controller.addActivity(
                title = "Trop tard",
                kind = ActivityKind.OTHER,
                startMinute = 23 * 60 + 50,
                durationMinutes = 30,
                priority = Priority.OPTIONAL,
            ),
        )
        assertNull(controller.preview)
        assertTrue(controller.plan!!.activities.isEmpty())
    }

    @Test
    fun secondConfirmationWithoutAPreviewIsRejected() {
        val controller = controller()
        val activityId = addSample(controller)
        assertFalse(controller.confirmPreview())
        assertTrue(controller.plan!!.activities.any { it.activityId == activityId })
        assertEquals(1, controller.plan!!.activities.size)
    }

    @Test
    fun timeHelpersAreStrictAndBounded() {
        assertEquals(9 * 60 + 5, PlanController.parseTime("09:05"))
        assertEquals(0, PlanController.parseTime("0:00"))
        assertNull(PlanController.parseTime("24:00"))
        assertNull(PlanController.parseTime("9h05"))
        assertEquals("23:59", PlanController.timeLabel(1439))
        assertEquals("10:30", PlanController.endTimeLabel(9 * 60, 90))
    }

    @Test
    fun nextActivitySkipsPostponedOccurrenceAndSelectsItsPlannedReplacement() {
        val controller = controller()
        val originalId = addSample(controller)
        assertTrue(
            controller.reportActivity(
                activityId = originalId,
                targetLocalDate = dayOne.toString(),
                targetStartMinute = 12 * 60,
                targetDurationMinutes = 45,
                reason = "Réunion prolongée",
                baseVersion = controller.plan!!.version,
                idempotencyKey = "80000000-0000-4000-8000-000000000099",
            ),
        )
        val activities = controller.plan!!.activities
        assertEquals(controller.plan, controller.planForDate(dayOne))
        val postponed = activities.single { it.activityId == originalId }
        val replacement = activities.single { it.activityId != originalId }

        assertEquals(ActivityStatus.POSTPONED, postponed.status)
        assertEquals(ActivityStatus.PLANNED, replacement.status)
        assertEquals(replacement.activityId, PlanUiLogic.nextActivity(activities, 10 * 60)?.activityId)
    }
}
