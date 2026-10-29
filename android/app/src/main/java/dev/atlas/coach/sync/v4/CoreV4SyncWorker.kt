package dev.atlas.coach.sync.v4

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.atlas.coach.data.AndroidStateBackend
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.StateReadResult
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.goals.GoalRepository
import dev.atlas.coach.habits.HabitCheckIn
import dev.atlas.coach.habits.InMemoryHabitRepository
import dev.atlas.coach.plan.DayPlan
import dev.atlas.coach.plan.PlanJsonCodec
import dev.atlas.coach.plan.PlanState
import dev.atlas.coach.plan.PlanStatusEvent
import dev.atlas.coach.plan.TrainingReferenceSyncState
import dev.atlas.coach.plan.v4.PlanV4JsonCodec
import dev.atlas.coach.sync.AndroidSyncStorage
import dev.atlas.coach.sync.CoreV4ConsentRecord
import dev.atlas.coach.sync.SharedPreferencesBearerTokenStore
import dev.atlas.coach.usage.android.SharedPreferencesGlobalUsageStores
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** One bounded outbound pass. It reads local sources only and never changes plans or domain events. */
class CoreV4SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    @Volatile private var activeClient: CoreV4SyncClient? = null

    override fun doWork(): Result {
        val store = AndroidSyncStorage.v4(applicationContext)
        var client: CoreV4SyncClient? = null
        try {
            store.recordFailure("RUNNING", "Synchronisation V4 en cours.")
            val syncConfig = AndroidSyncStorage.settings(applicationContext).read()
            val debuggable = applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
            val transport = CoreV4UrlConnectionTransport(syncConfig.baseUrl, debuggable)
            client = CoreV4SyncClient(transport)
            activeClient = client
            val token = SharedPreferencesBearerTokenStore(applicationContext).token()
                ?: throw CoreV4SyncException(CoreV4FailureKind.AUTHENTICATION, false, 401)

            syncConsentFirst(store, client, token)
            if (isStopped) return Result.success()
            val consent = store.consents().associateBy { it.snapshot.category }
            if (consent.values.any {
                    it.snapshot.state != CoreV4ConsentState.UNKNOWN &&
                        it.snapshot.version != it.snapshot.syncedVersion
                }
            ) {
                store.recordFailure("CONSENT_PENDING", "Le consentement courant attend son reçu V4.")
                return Result.success()
            }

            val now = Instant.now().toString()
            val planSnapshot = readTodayPlan(applicationContext)
            if (planSnapshot == null && todayPlanFile(applicationContext).isFile) {
                store.recordSourceState(
                    CoreV4Category.DAY_PLAN,
                    "MISMATCH",
                    "Le plan local est illisible ou incompatible; aucune projection n'est créée.",
                )
            }
            if (planSnapshot != null && planSnapshot.plan.state == PlanState.APPROVED) {
                pushApprovedPlan(
                    store = store,
                    client = client,
                    token = token,
                    consents = consent,
                    plan = planSnapshot.plan,
                    events = planSnapshot.events,
                    now = now,
                )
                if (isStopped) return Result.success()
            }

            val usageConsent = consent.getValue(CoreV4Category.USAGE_SUMMARY).snapshot
            if (usageConsent.state == CoreV4ConsentState.GRANTED) {
                pushUsage(store, client, token, usageConsent, now)
            }
            if (isStopped) return Result.success()

            val goalsConsent = consent.getValue(CoreV4Category.GOAL_PROGRESS).snapshot
            if (goalsConsent.state == CoreV4ConsentState.GRANTED) {
                pushGoals(store, client, token, goalsConsent, now)
            }
            if (isStopped) return Result.success()

            val trainingConsent = consent.getValue(CoreV4Category.TRAINING_PLAN).snapshot
            if (trainingConsent.state == CoreV4ConsentState.GRANTED) {
                pushTraining(store, client, token, trainingConsent, planSnapshot, now)
            }
            if (store.presentation().code == "RUNNING") {
                store.recordFailure("NO_PENDING_SOURCE", "Aucune nouvelle projection locale n'est disponible.")
            }
            return Result.success()
        } catch (error: CoreV4SyncException) {
            store.recordFailure(error.kind.name, error.message ?: "Erreur de synchronisation V4.")
            return if (error.retryable) Result.retry() else Result.failure()
        } catch (_: Exception) {
            store.recordFailure("LOCAL_ERROR", "La projection locale V4 est temporairement indisponible.")
            return Result.failure()
        } finally {
            activeClient = null
            client?.cancel()
        }
    }

    override fun onStopped() {
        activeClient?.cancel()
        super.onStopped()
    }

    private fun syncConsentFirst(store: dev.atlas.coach.sync.AndroidCoreV4SyncStore, client: CoreV4SyncClient, token: String) {
        for (record in store.pendingConsentRecords()) {
            if (isStopped) return
            val current = store.consent(record.snapshot.category)
            if (current.snapshot.version != record.snapshot.version ||
                current.snapshot.state != record.snapshot.state
            ) continue
            val body = store.prepareConsentBody(record, client)
            val receipt = client.sendConsent(body, token)
            store.acknowledgeConsent(record.snapshot.category, receipt)
        }
    }

    private fun pushApprovedPlan(
        store: dev.atlas.coach.sync.AndroidCoreV4SyncStore,
        client: CoreV4SyncClient,
        token: String,
        consents: Map<CoreV4Category, CoreV4ConsentRecord>,
        plan: DayPlan,
        events: List<PlanStatusEvent>,
        now: String,
    ) {
        val planConsent = consents.getValue(CoreV4Category.DAY_PLAN).snapshot
        if (planConsent.state == CoreV4ConsentState.GRANTED) {
            val data = CoreV4SyncClient.planData(plan, events, store.shareTitles(), now)
            val fingerprint = fingerprint(data, "observed_at")
            val sourceKey = "plan:" + plan.planId + ":consent:" + planConsent.version
            sendProjection(
                store, client, token, CoreV4Category.DAY_PLAN, sourceKey, plan.version,
                fingerprint, data, now, planConsent,
            )
        }

        store.pruneStaleStatusRequests(plan.planId, plan.version)
        val statusConsent = consents.getValue(CoreV4Category.ACTIVITY_STATUS).snapshot
        if (planConsent.state != CoreV4ConsentState.GRANTED ||
            statusConsent.state != CoreV4ConsentState.GRANTED ||
            store.receipt("plan:" + plan.planId + ":consent:" + planConsent.version)?.version != plan.version
        ) return
        for (event in events.filter { it.planId == plan.planId && it.planVersion == plan.version }) {
            if (isStopped) return
            val sequence = store.sequenceForStatusEvent(event.statusEventId)
            val data = CoreV4SyncClient.statusData(event, plan.version, sequence)
            val sourceKey = "status:" + event.statusEventId
            sendProjection(
                store, client, token, CoreV4Category.ACTIVITY_STATUS, sourceKey, sequence,
                fingerprint(data), data, now, statusConsent, planConsent,
            )
        }
    }

    private fun pushUsage(
        store: dev.atlas.coach.sync.AndroidCoreV4SyncStore,
        client: CoreV4SyncClient,
        token: String,
        v4Consent: CoreV4ConsentSnapshot,
        now: String,
    ) {
        val source = SharedPreferencesGlobalUsageStores(applicationContext)
        val sourceConsent = source.currentConsent()
        val summary = source.currentSummary()
        if (!sourceConsent.isExplicit || summary == null ||
            summary.consentVersion != sourceConsent.consentVersion
        ) {
            store.recordSourceState(CoreV4Category.USAGE_SUMMARY, "UNAVAILABLE",
                "Le résumé d'usage n'est pas disponible sous le consentement local courant.")
            return
        }
        val data = CoreV4SyncClient.usageData(summary, sourceConsent, v4Consent.version)
        // The backend key is the same window plus consent version. Freeze the first
        // body for that key instead of submitting a conflicting second summary.
        val sourceHash = sha256(
            v4Consent.version.toString() + "|" +
                data.getString("window_start") + "|" + data.getString("window_end"),
        )
        val sourceKey = "usage:" + v4Consent.version + ":" +
            data.getString("window_start") + ":" + data.getString("window_end")
        sendProjection(
            store, client, token, CoreV4Category.USAGE_SUMMARY,
            sourceKey, 1, sourceHash, data, now, v4Consent,
        )
    }

    private fun pushGoals(
        store: dev.atlas.coach.sync.AndroidCoreV4SyncStore,
        client: CoreV4SyncClient,
        token: String,
        consent: CoreV4ConsentSnapshot,
        now: String,
    ) {
        val state = (LocalStateStore(AndroidStateBackend(applicationContext)).readDocument() as? StateReadResult.Loaded)
            ?.state ?: run {
            store.recordSourceState(CoreV4Category.GOAL_PROGRESS, "UNAVAILABLE",
                "Les objectifs locaux ne sont pas disponibles.")
            return
        }
        val subdocument = state["goals"].asStringMap() ?: run {
            store.recordSourceState(CoreV4Category.GOAL_PROGRESS, "UNAVAILABLE",
                "Aucun état d'objectifs n'est disponible.")
            return
        }
        val goals = try {
            GoalRepository({ subdocument }, {}).all()
        } catch (_: Exception) {
            store.recordSourceState(CoreV4Category.GOAL_PROGRESS, "MISMATCH",
                "Un identifiant ou un enregistrement d'objectif est incompatible avec le contrat.")
            return
        }
        if (goals.any { !runCatching { CoreV4SyncClient.requireUuid(it.id, "goal_id") }.isSuccess }) {
            store.recordSourceState(CoreV4Category.GOAL_PROGRESS, "MISMATCH",
                "Un identifiant d'objectif n'est pas un UUID V4; l'envoi de la catégorie est suspendu.")
            return
        }
        val focus = (state["focusHistory"] as? List<*>).orEmpty().mapNotNull { row ->
            row.asStringMap()?.let { runCatching { FocusSession.fromMap(it) }.getOrNull() }
        }
        val habitMaps = (state["habits"] as? List<*>).orEmpty().mapNotNull { it.asStringMap() }
        val habits = InMemoryHabitRepository()
        val checkIns = runCatching {
            habits.restoreMaps(habitMaps)
            val ids = (habits.list().map { it.id } + goals.flatMap { it.linkedHabitIds }).toSet()
            ids.filter { id -> habits.list().any { it.id == id } }.flatMap(habits::checkIns)
        }.getOrDefault(emptyList<HabitCheckIn>())
        val progress = CoreV4SyncClient.goalProgress(goals, focus, checkIns)
        val raw = CoreV4SyncClient.goalData(goals, progress, 1, now, store.shareTitles())
        val sourceHash = fingerprint(raw, "observed_at", "goals_version")
        val version = store.nextSourceVersion("goals", sourceHash)
        val data = CoreV4SyncClient.goalData(goals, progress, version, now, store.shareTitles())
        sendProjection(
            store, client, token, CoreV4Category.GOAL_PROGRESS,
            "goals:consent:" + consent.version, version, sourceHash, data, now, consent,
        )
    }

    private fun pushTraining(
        store: dev.atlas.coach.sync.AndroidCoreV4SyncStore,
        client: CoreV4SyncClient,
        token: String,
        consent: CoreV4ConsentSnapshot,
        plan: PlanSnapshot?,
        now: String,
    ) {
        val refs = plan?.plan?.takeIf { it.state == PlanState.APPROVED }
            ?.activities?.mapNotNull { it.trainingRef }.orEmpty()
        if (refs.isEmpty()) {
            store.recordSourceState(CoreV4Category.TRAINING_PLAN, "UNAVAILABLE",
                "Aucune référence wger locale n'est disponible.")
            return
        }
        if (refs.any { it.syncState == TrainingReferenceSyncState.UNAVAILABLE }) {
            store.recordSourceState(CoreV4Category.TRAINING_PLAN, "UNAVAILABLE",
                "Une référence d'entraînement locale est indisponible; aucune projection n'est envoyée.")
            return
        }
        if (refs.any { it.syncState == TrainingReferenceSyncState.MISMATCH ||
                it.routineId !in 1..1_000_000_000 ||
                it.dayId?.let { day -> day !in 1..1_000_000_000 } == true
            }
        ) {
            store.recordSourceState(CoreV4Category.TRAINING_PLAN, "MISMATCH",
                "Une référence d'entraînement est incompatible; aucune référence n'est inventée.")
            return
        }
        val data = CoreV4SyncClient.trainingData(refs, now)
        val sourceHash = fingerprint(data, "observed_at")
        val version = store.nextSourceVersion("training", sourceHash)
        sendProjection(
            store, client, token, CoreV4Category.TRAINING_PLAN,
            "training:consent:" + consent.version, version, sourceHash, data, now, consent,
        )
    }

    private fun sendProjection(
        store: dev.atlas.coach.sync.AndroidCoreV4SyncStore,
        client: CoreV4SyncClient,
        token: String,
        category: CoreV4Category,
        sourceKey: String,
        sourceVersion: Int,
        sourceFingerprint: String,
        data: JSONObject,
        observedAt: String,
        consent: CoreV4ConsentSnapshot,
        dayPlanConsent: CoreV4ConsentSnapshot? = null,
    ) {
        if (isStopped) return
        val current = store.consent(category).snapshot
        if (current.state != CoreV4ConsentState.GRANTED || current.version != consent.version ||
            current.syncedVersion != current.version
        ) return
        if (dayPlanConsent != null) {
            val currentPlan = store.consent(CoreV4Category.DAY_PLAN).snapshot
            if (currentPlan.state != CoreV4ConsentState.GRANTED ||
                currentPlan.version != dayPlanConsent.version ||
                currentPlan.syncedVersion != currentPlan.version
            ) return
        }
        val pending = store.prepareProjection(
            sourceKey, sourceVersion, sourceFingerprint, category, consent.version, data, client, Instant.now().toString(),
        ) ?: return
        if (isStopped) return
        val freshConsent = store.consent(category).snapshot
        val freshPlanConsent = if (category == CoreV4Category.ACTIVITY_STATUS) {
            store.consent(CoreV4Category.DAY_PLAN).snapshot
        } else dayPlanConsent
        val receipt = client.sendProjection(pending.request, pending.body, token, freshConsent, freshPlanConsent)
        store.acknowledgeProjection(pending, receipt, observedAt)
    }

    private fun readTodayPlan(context: Context): PlanSnapshot? {
        val file = todayPlanFile(context)
        if (!file.isFile) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return runCatching {
            when (JSONObject(raw).optInt("schema_version")) {
                PlanV4JsonCodec.SCHEMA_VERSION -> PlanV4JsonCodec.decodeStore(raw)
                    .let { PlanSnapshot(it.store.dayPlan, it.statusEvents) }
                PlanJsonCodec.SCHEMA_VERSION -> PlanSnapshot(
                    PlanJsonCodec.decodeStore(raw).dayPlan,
                    emptyList(),
                )
                else -> null
            }
        }.getOrNull()
    }

    private fun todayPlanFile(context: Context): File {
        val date = LocalDate.now(ZoneId.systemDefault())
        return File(File(context.filesDir, "plans"), "day-plan-" + date + ".json")
    }

    private data class PlanSnapshot(val plan: DayPlan, val events: List<PlanStatusEvent>)

    private fun fingerprint(value: JSONObject, vararg ignored: String): String {
        val copy = JSONObject(value.toString())
        ignored.forEach(copy::remove)
        return MessageDigest.getInstance("SHA-256")
            .digest(copy.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun Any?.asStringMap(): Map<String, Any?>? {
        val source = this as? Map<*, *> ?: return null
        if (source.keys.any { it !is String }) return null
        return source.entries.associate { it.key as String to it.value }
    }
}

/** Enqueues only connected work; periodic work supplies eventual delivery after offline periods. */
object CoreV4SyncScheduler {
    const val IMMEDIATE_WORK = "atlas_core_v4_push_now"
    const val PERIODIC_WORK = "atlas_core_v4_push_periodic"

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val manager = WorkManager.getInstance(appContext)
        val connected = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val immediate = OneTimeWorkRequestBuilder<CoreV4SyncWorker>()
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, immediate)

        val hasGrantedCategory = AndroidSyncStorage.v4(appContext).consents()
            .any { it.snapshot.state == CoreV4ConsentState.GRANTED }
        if (hasGrantedCategory) {
            val periodic = PeriodicWorkRequestBuilder<CoreV4SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.REPLACE, periodic)
        } else {
            manager.cancelUniqueWork(PERIODIC_WORK)
        }
    }
}
