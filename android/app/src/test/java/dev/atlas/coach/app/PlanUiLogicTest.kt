package dev.atlas.coach.app

import dev.atlas.coach.plan.ActivityKind
import dev.atlas.coach.plan.ActivityStatus
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.LocalSchedule
import dev.atlas.coach.plan.PlanActivity
import dev.atlas.coach.plan.PlanChange
import dev.atlas.coach.plan.PlanOrigin
import dev.atlas.coach.plan.PlanStatusEvent
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.Priority
import dev.atlas.coach.plan.StatusProvenance
import dev.atlas.coach.plan.StatusSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanUiLogicTest {
    @Test
    fun activitiesAreSortedChronologicallyAndTiesStayStable() {
        val late = activity(id = ID_1, title = "Soir", start = 18 * 60 + 30)
        val early = activity(id = ID_2, title = "Matin", start = 10 * 60)
        val secondEarly = activity(id = ID_3, title = "Pause", start = 10 * 60)
        val afternoon = activity(id = ID_4, title = "Après-midi", start = 16 * 60 + 30)

        assertEquals(
            listOf(early, secondEarly, afternoon, late),
            PlanUiLogic.sortActivities(listOf(late, early, secondEarly, afternoon)),
        )
    }

    @Test
    fun nextActivitySkipsPastEntriesSelectsCurrentThenUpcomingAndReturnsNullWhenDone() {
        val morning = activity(id = ID_1, title = "Lecture", start = 10 * 60, duration = 60)
        val afternoon = activity(id = ID_2, title = "Marche", start = 16 * 60 + 30, duration = 60)
        val evening = activity(id = ID_3, title = "Étirements", start = 18 * 60 + 30, duration = 30)
        val activities = listOf(evening, morning, afternoon)

        assertEquals(morning, PlanUiLogic.nextActivity(activities, nowMinute = 9 * 60))
        assertEquals(morning, PlanUiLogic.nextActivity(activities, nowMinute = 10 * 60 + 15))
        assertEquals(afternoon, PlanUiLogic.nextActivity(activities, nowMinute = 11 * 60))
        assertEquals(evening, PlanUiLogic.nextActivity(activities, nowMinute = 17 * 60 + 30))
        assertNull(PlanUiLogic.nextActivity(activities, nowMinute = 19 * 60))
    }

    @Test
    fun terminalActivitiesAreNotSelectedAsNext() {
        val missed = activity(id = ID_1, title = "Manquée", start = 16 * 60, status = ActivityStatus.MISSED)
        val cancelled = activity(id = ID_2, title = "Annulée", start = 17 * 60, status = ActivityStatus.CANCELLED)
        val planned = activity(id = ID_3, title = "À faire", start = 18 * 60)

        assertEquals(planned, PlanUiLogic.nextActivity(listOf(missed, cancelled, planned), nowMinute = 15 * 60))
    }

    @Test
    fun temporalLabelsDistinguishPastCurrentAndUpcoming() {
        val scheduled = activity(id = ID_1, title = "Marche", start = 16 * 60 + 30, duration = 60)

        assertEquals(PlanActivityTiming.PAST, PlanUiLogic.timing(scheduled, nowMinute = 17 * 60 + 30))
        assertEquals(PlanActivityTiming.CURRENT, PlanUiLogic.timing(scheduled, nowMinute = 16 * 60 + 45))
        assertEquals(PlanActivityTiming.UPCOMING, PlanUiLogic.timing(scheduled, nowMinute = 16 * 60))
    }

    @Test
    fun statusAndPriorityLabelsAreExplicit() {
        assertEquals(
            listOf("Prévue", "En cours", "Terminée", "Manquée", "Annulée", "Reportée"),
            ActivityStatus.entries.map { PlanUiLogic.statusLabel(it) },
        )
        assertEquals(
            listOf("Requise", "Importante", "Optionnelle"),
            Priority.entries.map { PlanUiLogic.priorityLabel(it) },
        )
    }

    @Test
    fun statusOriginUsesLatestMatchingEventAndKeepsMissingHistoryUnknown() {
        val done = activity(ID_1, "Lecture", 10 * 60, status = ActivityStatus.DONE)
        val older = statusEvent(
            eventId = ID_3,
            activity = done,
            version = 2,
            provenance = StatusProvenance.DECLARED,
            source = StatusSource.USER_UI,
        )
        val latest = statusEvent(
            eventId = ID_4,
            activity = done,
            version = 3,
            provenance = StatusProvenance.CONFIRMED,
            source = StatusSource.USER_UI,
        )
        val unrelated = statusEvent(
            eventId = ID_2,
            activity = done.copy(activityId = ID_2),
            version = 9,
            provenance = StatusProvenance.MEASURED,
            source = StatusSource.USAGE_STATS,
        )

        val selected = PlanUiLogic.statusEventForActivity(done, listOf(latest, unrelated, older))
        assertEquals(latest, selected)
        assertEquals("Confirmé par vous · vous", PlanUiLogic.statusOriginLabel(selected))
        assertEquals("Origine du statut inconnue", PlanUiLogic.statusOriginLabel(null))
    }

    @Test
    fun provenanceAndSourceEnumsHaveClearUserLabels() {
        assertEquals(
            listOf("Déclaré", "Mesuré", "Confirmé par vous", "Inconnu"),
            StatusProvenance.entries.map(PlanUiLogic::provenanceLabel),
        )
        assertEquals(
            listOf("vous", "minuteur Focus", "usage des apps", "lecture de l’entraînement", "import"),
            StatusSource.entries.map(PlanUiLogic::sourceLabel),
        )
    }

    @Test
    fun coachMessageIsHumanReadableAndDoesNotContainTheActivityIdentifier() {
        val message = buildHumanPlanActivityCoachMessage(
            activityTitle = "Lecture $ID_1",
            note = "  Vérifie cette activité\nmerci  ",
            activityId = ID_1,
        )

        assertEquals("À propos de «Lecture» : Vérifie cette activité merci", message)
        assertFalse(message.contains(ID_1))
        assertFalse(message.contains("activity_id"))
        assertFalse(message.startsWith("{"))
        assertFalse(message.contains("\n"))
    }

    @Test
    fun structuredCoachContextIsStoredAsItsOwnLocalEvent() {
        val event = PlanActivityCoachContextEvent(
            eventId = "local-event-1",
            activityId = ID_1,
            planVersion = 7,
            action = PLAN_ACTIVITY_COACH_ACTION,
            note = "Pourquoi cette activité ?",
            createdAtUtcMillis = 1_800_000_000_000L,
        )

        val saved = event.toMap()
        assertEquals(ID_1, saved["activity_id"])
        assertEquals(7, saved["plan_version"])
        assertEquals("ASK_COACH", saved["action"])
        assertEquals("Pourquoi cette activité ?", saved["note"])
        assertEquals(event, PlanActivityCoachContextEvent.fromMap(saved))
        assertFalse(saved.containsKey("message"))
    }

    @Test
    fun legacyCoachEnvelopeIsRenderedAsAQuestionWithoutItsIdentifiersOrJson() {
        val legacy = """{"type":"plan_activity_coach_message","context":{"activity_id":"$ID_1","plan_version":7,"action":"ASK_COACH"},"message":"Pourquoi cette activité ?"}"""

        val displayed = readableConversationMessage(legacy)

        assertEquals("À propos d’une activité : Pourquoi cette activité ?", displayed)
        assertFalse(displayed.contains(ID_1))
        assertFalse(displayed.contains("activity_id"))
        assertFalse(displayed.startsWith("{"))
    }

    @Test
    fun activityActionsFollowStatusAndMissedWaitsForTheSlotEnd() {
        assertEquals("Démarrer", PlanUiLogic.primaryActionLabel(ActivityStatus.PLANNED))
        assertEquals("Terminé", PlanUiLogic.primaryActionLabel(ActivityStatus.IN_PROGRESS))
        assertEquals(null, PlanUiLogic.primaryActionLabel(ActivityStatus.DONE))
        assertEquals(null, PlanUiLogic.primaryActionLabel(ActivityStatus.CANCELLED))
        assertEquals(null, PlanUiLogic.primaryActionLabel(ActivityStatus.MISSED))
        assertEquals(null, PlanUiLogic.primaryActionLabel(ActivityStatus.POSTPONED))

        val planned = activity(ID_1, "Lecture", 10 * 60, duration = 45)
        assertEquals(false, PlanUiLogic.canMarkMissed(planned, nowMinute = 10 * 60 + 44))
        assertEquals(true, PlanUiLogic.canMarkMissed(planned, nowMinute = 10 * 60 + 45))
        assertEquals(false, PlanUiLogic.canMarkMissed(planned.copy(status = ActivityStatus.DONE), nowMinute = 23 * 60))
    }

    @Test
    fun reportChoicesKeepDurationAndIdentifySameDayConflicts() {
        val source = activity(ID_1, "Lecture", 10 * 60, duration = 45)
        val conflict = activity(ID_2, "Rendez-vous", 10 * 60 + 30, duration = 30)
        val dayPlan = dayPlan(listOf(source, conflict))

        val plus15 = PlanUiLogic.reportTarget(DATE, source, PlanReportChoice.PLUS_15)!!
        assertEquals(PlanReportTarget(DATE, 10 * 60 + 15, 45), plus15)
        assertEquals(listOf(conflict), PlanUiLogic.reportConflicts(dayPlan, source, plus15))

        val tomorrow = PlanUiLogic.reportTarget(DATE, source, PlanReportChoice.TOMORROW)!!
        assertEquals("2026-10-27", tomorrow.localDate)
        assertEquals(10 * 60, tomorrow.startMinute)
        assertEquals(45, tomorrow.durationMinutes)
        assertEquals(emptyList<PlanActivity>(), PlanUiLogic.reportConflicts(dayPlan, source, tomorrow))
        assertNull(PlanUiLogic.reportTarget(DATE, source, PlanReportChoice.CUSTOM, customStartMinute = 1_420))
    }

    @Test
    fun emptyStateAndProgressLabelsDistinguishMissingPlanFromFreeDay() {
        assertEquals("Aucun programme pour cette date", PlanUiLogic.emptyStateLabel(false, 0))
        assertEquals("Journée libre", PlanUiLogic.emptyStateLabel(true, 0))
        assertNull(PlanUiLogic.emptyStateLabel(true, 3))
        assertEquals("Journée libre · aucune activité", PlanUiLogic.progressLabel(emptyList()))
        assertEquals("1 sur 3 terminée", PlanUiLogic.progressLabel(listOf(
            activity(ID_1, "Terminée", 10 * 60, ActivityStatus.DONE),
            activity(ID_2, "En cours", 11 * 60, ActivityStatus.IN_PROGRESS),
            activity(ID_3, "Prévue", 12 * 60),
        )))
    }

    @Test
    fun revisionChangeLabelsKeepEveryChangeTypeReadable() {
        val before = activity(ID_1, "Lecture", 10 * 60)
        val after = before.copy(title = "Lecture active", kind = ActivityKind.FOCUS)
        val movedTo = before.scheduledLocal.copy(startMinute = 16 * 60 + 30)
        val added = activity(ID_2, "Marche", 18 * 60 + 30)

        assertEquals("Ajouter « Marche » à 18:30", PlanUiLogic.changeLabel(PlanChange.SlotAdded(added)))
        assertEquals("Supprimer « Lecture »", PlanUiLogic.changeLabel(PlanChange.SlotRemoved(ID_1, before)))
        assertEquals(
            "Déplacer de 10:00 à 16:30",
            PlanUiLogic.changeLabel(PlanChange.SlotMoved(ID_1, before.scheduledLocal, movedTo)),
        )
        assertEquals("Modifier « Lecture active »", PlanUiLogic.changeLabel(PlanChange.SlotEdited(ID_1, before, after)))
        assertEquals(
            "Statut : Prévue → Terminée",
            PlanUiLogic.changeLabel(PlanChange.StatusChanged(ID_1, ActivityStatus.PLANNED, ActivityStatus.DONE)),
        )
    }

    private fun activity(
        id: String,
        title: String,
        start: Int,
        status: ActivityStatus = ActivityStatus.PLANNED,
        duration: Int = 30,
    ) = PlanActivity(
        activityId = id,
        planId = PLAN_ID,
        kind = ActivityKind.FOCUS,
        title = title,
        status = status,
        scheduledLocal = LocalSchedule(
            localDate = DATE,
            startMinute = start,
            durationMinutes = duration,
            timezone = "Europe/Paris",
        ),
        priority = Priority.IMPORTANT,
        createdRevisionId = REVISION_ID,
        lastRevisionId = REVISION_ID,
    )

    private fun dayPlan(activities: List<PlanActivity>) = DayPlan(
        planId = PLAN_ID,
        localDate = DATE,
        timezone = "Europe/Paris",
        version = 1,
        currentRevisionId = REVISION_ID,
        state = PlanState.APPROVED,
        activities = activities,
        createdAt = 0L,
        updatedAt = 0L,
        approvedAt = 0L,
        origin = PlanOrigin.LOCAL,
    )

    private fun statusEvent(
        eventId: String,
        activity: PlanActivity,
        version: Int,
        provenance: StatusProvenance,
        source: StatusSource,
    ) = PlanStatusEvent(
        statusEventId = eventId,
        planId = activity.planId,
        activityId = activity.activityId,
        continuityId = null,
        fromStatus = ActivityStatus.IN_PROGRESS,
        toStatus = activity.status,
        provenance = provenance,
        source = source,
        decidedAt = "2026-10-26T10:00:00Z",
        observedAt = "2026-10-26T10:00:00Z",
        planVersion = version,
        consentVersion = 0,
    )

    private companion object {
        const val DATE = "2026-10-26"
        const val PLAN_ID = "00000000-0000-0000-0000-000000000001"
        const val REVISION_ID = "00000000-0000-0000-0000-000000000002"
        const val ID_1 = "00000000-0000-0000-0000-000000000011"
        const val ID_2 = "00000000-0000-0000-0000-000000000012"
        const val ID_3 = "00000000-0000-0000-0000-000000000013"
        const val ID_4 = "00000000-0000-0000-0000-000000000014"
    }
}
