package dev.atlas.coach.app

import android.Manifest
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.atlas.coach.coach.CoachJournalEntry
import dev.atlas.coach.coach.CoachRepositoryState
import dev.atlas.coach.coach.Directive
import dev.atlas.coach.coach.DirectiveChoice
import dev.atlas.coach.coach.DirectiveResult
import dev.atlas.coach.coach.MockCoachRepository
import dev.atlas.coach.content.AndroidContentOpener
import dev.atlas.coach.content.ContentCatalog
import dev.atlas.coach.content.ContentItem
import dev.atlas.coach.content.ContentOpenResult
import dev.atlas.coach.content.ContentUrlValidator
import dev.atlas.coach.data.AndroidStateBackend
import dev.atlas.coach.data.AndroidPersistenceDiagnosticsStore
import dev.atlas.coach.data.AndroidRevocationBarrierStorage
import dev.atlas.coach.data.AtlasStateRecovery
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.PersistFailureKind
import dev.atlas.coach.data.PersistResult
import dev.atlas.coach.data.PersistWriteException
import dev.atlas.coach.data.PersistenceDiagnostics
import dev.atlas.coach.data.PersistenceDiagnosticsStore
import dev.atlas.coach.data.RecoveredAtlasState
import dev.atlas.coach.data.RevocationBarrier
import dev.atlas.coach.data.RevocationBarrierStorage
import dev.atlas.coach.data.StateBackend
import dev.atlas.coach.data.StateReadResult
import dev.atlas.coach.data.localPolicyFromStorage
import dev.atlas.coach.data.ruleEvaluatorStateFromStorage
import dev.atlas.coach.data.toStorageMap
import dev.atlas.coach.goals.GoalMetric
import dev.atlas.coach.goals.GoalRecord
import dev.atlas.coach.goals.GoalRepository
import dev.atlas.coach.goals.GoalStatus
import dev.atlas.coach.focus.Clock
import dev.atlas.coach.focus.FocusEngine
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusPreset
import dev.atlas.coach.focus.FocusSession
import dev.atlas.coach.focus.FocusStatus
import dev.atlas.coach.focus.FocusHabitLinkCondition
import dev.atlas.coach.focus.FocusLinkResult
import dev.atlas.coach.focus.FocusSessionLinker
import dev.atlas.coach.focus.FocusJournalLink
import dev.atlas.coach.focus.InMemoryFocusJournalLinkStore
import dev.atlas.coach.focus.PhaseNotificationResult
import dev.atlas.coach.focus.PhaseTransition
import dev.atlas.coach.focus.ReefPomodoroConfig
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitReminder
import dev.atlas.coach.habits.HabitLinkPolicy
import dev.atlas.coach.habits.HabitSchedule
import dev.atlas.coach.habits.InMemoryHabitRepository
import dev.atlas.coach.habits.android.AndroidHabitReminderScheduler
import dev.atlas.coach.focus.android.AndroidFocusNotifications
import dev.atlas.coach.focus.android.AndroidPhaseAlarmScheduler
import dev.atlas.coach.focus.android.AndroidRoutineProposalScheduler
import dev.atlas.coach.usage.ObservationConsent
import dev.atlas.coach.usage.ObservationPurpose
import dev.atlas.coach.usage.UsageObserver
import dev.atlas.coach.usage.UsagePollStatus
import dev.atlas.coach.usage.UsageRetention
import dev.atlas.coach.usage.android.AndroidEmergencyPauseStore
import dev.atlas.coach.usage.android.AndroidFrictionPresenter
import dev.atlas.coach.usage.android.AndroidProtectedPackages
import dev.atlas.coach.usage.android.AndroidUsageAccessStatus
import dev.atlas.coach.usage.android.AndroidUsageStatsEventSource
import dev.atlas.coach.usage.android.SharedPreferencesUsageStores
import dev.atlas.coach.usage.android.UsageObservationWorkScheduler
import dev.atlas.coach.usage.android.usageAccessSettingsIntent as systemUsageAccessSettingsIntent
import dev.atlas.coach.rules.AppEventSimulator
import dev.atlas.coach.rules.AppOpenEvent
import dev.atlas.coach.rules.Intervention
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.QuietHours
import dev.atlas.coach.rules.RuleEvaluator
import dev.atlas.coach.rules.SafetyDenylist
import dev.atlas.coach.routines.FocusRoutine
import dev.atlas.coach.routines.InMemoryFocusRoutineRepository
import dev.atlas.coach.routines.RoutineEvaluator
import dev.atlas.coach.routines.RoutineSchedule
import dev.atlas.coach.sync.AndroidSyncStorage
import dev.atlas.coach.sync.FileOutboxStorage
import dev.atlas.coach.sync.CoachGateway
import dev.atlas.coach.sync.DataScope
import dev.atlas.coach.sync.EventOutbox
import dev.atlas.coach.sync.LocalMockCoachGateway
import dev.atlas.coach.sync.LocalMockSyncGateway
import dev.atlas.coach.sync.RemoteBackendGateway
import dev.atlas.coach.sync.SharedPreferencesBearerTokenStore
import dev.atlas.coach.sync.SyncConsent
import dev.atlas.coach.sync.SyncDataCategory
import dev.atlas.coach.sync.SyncEvent
import dev.atlas.coach.sync.SyncEventType
import dev.atlas.coach.sync.SyncGateway
import dev.atlas.coach.sync.SyncResult
import dev.atlas.coach.sync.SyncStatus
import dev.atlas.coach.sync.agent.AgentMandate
import dev.atlas.coach.sync.agent.AgentActionType
import dev.atlas.coach.sync.agent.AgentMode
import dev.atlas.coach.sync.agent.AgentHomeState
import dev.atlas.coach.sync.agent.AgentHomeDirective
import dev.atlas.coach.sync.agent.AgentFocusProposalCard
import dev.atlas.coach.sync.agent.AgentFocusCardState
import dev.atlas.coach.sync.agent.AgentContentCard
import dev.atlas.coach.sync.agent.AgentVerificationContext
import dev.atlas.coach.sync.agent.LocalMandateStore
import dev.atlas.coach.sync.agent.MandateVerifier
import dev.atlas.coach.sync.agent.ProposalV2
import dev.atlas.coach.sync.agent.ProposalLevel
import dev.atlas.coach.sync.agent.ProposalState
import dev.atlas.coach.sync.agent.inbox.AgentInboxEntry
import dev.atlas.coach.sync.agent.inbox.AgentInboxRepository
import dev.atlas.coach.sync.agent.dispatch.ActionDispatcher
import dev.atlas.coach.sync.agent.dispatch.AgentActionOutcome
import dev.atlas.coach.sync.agent.dispatch.AgentActionPort
import dev.atlas.coach.sync.agent.dispatch.AgentEffectRecord
import dev.atlas.coach.sync.agent.dispatch.AgentEffectReconciliation
import dev.atlas.coach.sync.agent.dispatch.AgentEffectJournal
import dev.atlas.coach.sync.agent.dispatch.JsonAgentEffectJournal
import dev.atlas.coach.sync.agent.dispatch.cooldownHistoryByActionType
import dev.atlas.coach.sync.agent.withRemoteDirective
import dev.atlas.coach.sync.agent.coach.AgentApiClient
import dev.atlas.coach.sync.agent.coach.AgentApiException
import dev.atlas.coach.sync.agent.coach.AgentContextCoordinator
import dev.atlas.coach.sync.agent.coach.AgentConversationMessage
import dev.atlas.coach.sync.agent.coach.AgentMessageRole
import dev.atlas.coach.sync.agent.coach.AgentStatus
import dev.atlas.coach.coach.proactive.CoachTrigger
import dev.atlas.coach.coach.proactive.CoachTriggerReliability
import dev.atlas.coach.coach.proactive.CoachTriggerSource
import dev.atlas.coach.coach.proactive.ProactiveConsentSnapshot
import dev.atlas.coach.coach.proactive.ProactiveEngagementMode
import dev.atlas.coach.coach.proactive.ProactiveNotificationContent
import dev.atlas.coach.coach.proactive.ProactivePlanRequest
import dev.atlas.coach.coach.proactive.ProactivePlanResponse
import dev.atlas.coach.location.MobilitySessionCompletion
import dev.atlas.coach.location.MobilitySessionStatusStore
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import dev.atlas.coach.sync.agent.context.AgentContextPreview
import dev.atlas.coach.sync.agent.context.AgentContextSnapshot
import dev.atlas.coach.location.LocationSummary
import dev.atlas.coach.location.MobilitySessionStopReason
import dev.atlas.coach.usage.ObservationRecord
import java.time.LocalDate
import java.time.ZoneId
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID
import java.net.URI
import java.io.File
import java.time.Instant
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import org.json.JSONObject

/** Owns the small, synchronous local graph used by the Compose demo. */
class AtlasController(
    context: Context,
    private val clock: Clock = object : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
    },
    stateBackendOverride: StateBackend? = null,
    revocationBarrierStorageOverride: RevocationBarrierStorage? = null,
    persistenceDiagnosticsStoreOverride: PersistenceDiagnosticsStore? = null,
) : AgentActionPort {
    private val appContext = context.applicationContext
    // This deny marker is read before any agent client can be constructed or request scheduled.
    private val revocationBarrier = RevocationBarrier(
        revocationBarrierStorageOverride ?: AndroidRevocationBarrierStorage(appContext),
    )
    private val persistenceDiagnostics = PersistenceDiagnostics(
        persistenceDiagnosticsStoreOverride ?: AndroidPersistenceDiagnosticsStore(appContext),
    )
    private val store = LocalStateStore(stateBackendOverride ?: AndroidStateBackend(appContext))
    private val syncOutbox: EventOutbox = AndroidSyncStorage.outbox(appContext)
    private val syncTokenStore = SharedPreferencesBearerTokenStore(appContext)
    private val syncSettingsStore = AndroidSyncStorage.settings(appContext)
    private val persistedSyncSettings = syncSettingsStore.read()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val syncWorker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "atlas-sync-worker").apply { isDaemon = true }
    }
    private val syncRunning = AtomicBoolean(false)
    private val syncGeneration = AtomicLong(0L)
    private val proactiveEffectGeneration = AtomicLong(0L)
    private val proactiveDrainScheduled = AtomicBoolean(false)
    private val pendingProactiveEvents = ConcurrentLinkedQueue<CoachTrigger>()
    private val effectiveConsent = AtomicReference(SyncConsent.DISABLED)
    @Volatile private var remoteBackend: RemoteBackendGateway? = null
    @Volatile private var syncRetryFuture: ScheduledFuture<*>? = null
    @Volatile private var coachGateway: CoachGateway = LocalMockCoachGateway()
    @Volatile private var syncGateway: SyncGateway = LocalMockSyncGateway()
    private val phaseAlarms = AndroidPhaseAlarmScheduler(appContext)
    private val focusNotifications = AndroidFocusNotifications(appContext)
    private val routineAlarms = AndroidRoutineProposalScheduler(appContext)
    private val habitReminderScheduler = AndroidHabitReminderScheduler(appContext)
    private val stateReadResult = runCatching { store.readDocument() }
        .getOrElse { StateReadResult.Corrupt(null, "impossible de lire le stockage local") }
    private val recovery: RecoveredAtlasState = when (val result = stateReadResult) {
        is StateReadResult.Loaded -> runCatching { AtlasStateRecovery.recover(result.state) }
            .getOrElse { RecoveredAtlasState(emptyMap(), setOf("document")) }
        StateReadResult.Missing, is StateReadResult.Corrupt -> RecoveredAtlasState(emptyMap())
    }
    private val restored = recovery.values
    private val recoveryReason: String? = when (val result = stateReadResult) {
        is StateReadResult.Corrupt -> result.reason
        is StateReadResult.Loaded -> recovery.invalidSubstates.takeIf { it.isNotEmpty() }
            ?.let { "sous-états invalides : ${it.sorted().joinToString(", ")}" }
        StateReadResult.Missing -> null
    }
    private val quarantineSucceeded = recoveryReason?.let { reason ->
        val raw = when (val result = stateReadResult) {
            is StateReadResult.Loaded -> result.rawJson.takeIf { recovery.invalidSubstates.isNotEmpty() }
            is StateReadResult.Corrupt -> result.rawJson
            StateReadResult.Missing -> null
        }
        runCatching { store.quarantine(raw, reason) }.getOrDefault(false)
    } ?: true
    private val persistenceAllowed = recoveryReason == null || quarantineSucceeded
    private val recoveryNotice = recoveryReason?.let { reason ->
        if (quarantineSucceeded) {
            "Certaines données locales ont été écartées ($reason). Une copie brute a été mise en quarantaine; les valeurs de secours sont actives."
        } else {
            "Certaines données locales sont illisibles ($reason). La copie de secours n'a pas pu être garantie; les valeurs de secours sont chargées sans écraser le stockage."
        }
    }
    private val focusEngine = FocusEngine(clock)
    private var focusSnapshot by mutableStateOf<FocusSession?>(null)
    private var focusHistoryEntries by mutableStateOf(
        restored["focusHistory"].asList().mapNotNull { it.asMap()?.let { values -> runCatching { FocusSession.fromMap(values) }.getOrNull() } }
            .filter { it.status in setOf(dev.atlas.coach.focus.FocusStatus.FINISHED, dev.atlas.coach.focus.FocusStatus.CANCELLED) }
            .sortedByDescending { it.createdAtUtcMillis }
            .take(MAX_FOCUS_HISTORY),
    )
    private val focusRoutineRepository = InMemoryFocusRoutineRepository(
        restored["focusRoutines"].asList().mapNotNull { it.asMap()?.let { values -> runCatching { FocusRoutine.fromMap(values) }.getOrNull() } }.take(MAX_FOCUS_ROUTINES),
    )
    private var focusRoutinesSnapshot by mutableStateOf(focusRoutineRepository.list())
    val habits = InMemoryHabitRepository(clock = { clock.nowMillis() })
    private val initialDirective = restored["coach"].asMap()?.get("directives").asList()
        .firstNotNullOfOrNull { it.asMap()?.let(Directive::fromMap) }
        ?: Directive.demo(clock.nowMillis())
    private val coach = MockCoachRepository(
        clock = { clock.nowMillis() },
        initialDirective = initialDirective,
    )
    private val ruleState = restored["rules"].asMap()?.let(::ruleEvaluatorStateFromStorage)
        ?: dev.atlas.coach.rules.RuleEvaluatorState()
    private val ruleEvaluator = RuleEvaluator(ZoneId.systemDefault(), ruleState)
    private var policy by mutableStateOf(restored["policy"].asMap()?.let(::localPolicyFromStorage) ?: LocalPolicy())
    private val usageStores = SharedPreferencesUsageStores(appContext)
    private val usageAccessStatus = AndroidUsageAccessStatus(appContext)
    private val usageObserver = UsageObserver(
        eventSource = AndroidUsageStatsEventSource(
            appContext.getSystemService(UsageStatsManager::class.java)
                ?: error("UsageStatsManager is unavailable"),
        ),
        accessStatus = usageAccessStatus,
        consentStore = usageStores,
        cursorStore = usageStores,
        stateStore = usageStores,
        recordStore = usageStores,
        ruleEvaluator = ruleEvaluator,
        policyProvider = { policy },
        frictionPresenter = AndroidFrictionPresenter(appContext),
        platformProtectedPackages = {
            AndroidProtectedPackages.protectedPackages(
                appContext,
                usageStores.currentConsent().allowlistedPackageNames,
            )
        },
    )
    private val pendingUsagePreferences = appContext.getSharedPreferences("atlas_usage_pending_consent", Context.MODE_PRIVATE)
    private val focusLinkStore = InMemoryFocusJournalLinkStore(
        restored["focusJournalLinks"].asList().mapNotNull { it.asMap()?.let { values -> runCatching { FocusJournalLink.fromMap(values) }.getOrNull() } },
    )
    private val focusSessionLinker = FocusSessionLinker(focusLinkStore)
    private var directiveSnapshot by mutableStateOf(initialDirective)
    private var habitsSnapshot by mutableStateOf<List<Habit>>(emptyList())
    private var checkedHabitIdsSnapshot by mutableStateOf<Set<String>>(emptySet())
    private var journalSnapshot by mutableStateOf<List<CoachJournalEntry>>(emptyList())
    private var interventionsSnapshot by mutableStateOf<List<Intervention>>(emptyList())
    private var coachPausedSnapshot by mutableStateOf(false)
    private var catalog = restored["content"]?.let { raw ->
        raw.asList().mapNotNull { it.asMap() }.let(ContentCatalog::fromMaps)
    } ?: ContentCatalog.demo()
    private var contentItemsSnapshot by mutableStateOf(catalog.items)
    private val agentMandateStore = LocalMandateStore(
        (restored["agentMandate"] as? String)?.let { runCatching { AgentMandate.fromJson(it) }.getOrNull() }
            ?: AgentMandate.default(),
    )
    private val agentInboxRepository = AgentInboxRepository(
        FileOutboxStorage(File(appContext.filesDir, "atlas_agent_inbox_v2.json")),
        clock::nowMillis,
    )
    private val agentEffectJournal: AgentEffectJournal = JsonAgentEffectJournal(
        FileOutboxStorage(File(appContext.filesDir, "atlas_agent_effects_v1.json")),
    )
    private var agentHomeStateSnapshot by mutableStateOf(AgentHomeState.fromJson(restored["agentHomeState"] as? String))
    private val appliedLocalAgentEffectKeys = restored["appliedLocalAgentEffectKeys"].asList()
        .filterIsInstance<String>().toMutableSet()
    private var agentConversationSnapshot by mutableStateOf(
        restored["agentConversation"].asList().mapNotNull { it.asMap()?.let { values -> runCatching { AgentConversationMessage.fromMap(values) }.getOrNull() } }.takeLast(80),
    )
    private var agentSuggestionsSnapshot by mutableStateOf(
        restored["agentSuggestions"].asList().filterIsInstance<String>().takeLast(100),
    )
    private var agentConversationId: String? = restored["agentConversationId"] as? String
    @Volatile private var agentApiClient: AgentApiClient? = null
    private val agentRequestGeneration = AtomicLong(0L)
    private val agentAuthorizationCommitInProgress = AtomicBoolean(false)
    private val agentConsentMigrationReady = AtomicBoolean(restored.containsKey(KEY_AGENT_CONTEXT_CONSENT_DATES))
    private val agentVerifier = MandateVerifier()
    private val agentDispatcher by lazy {
        ActionDispatcher(
            mandates = agentMandateStore,
            inbox = agentInboxRepository,
            verifier = agentVerifier,
            context = ::agentVerificationContext,
            actions = this,
            journal = agentEffectJournal,
            nowUtcMillis = clock::nowMillis,
        )
    }
    private var habitLinkPolicy: HabitLinkPolicy
    private var lastSimulatedEvent: AppOpenEvent? = runCatching {
        restored["lastSimulatedEvent"].asMap()?.let(::eventFromMap)
    }.getOrNull()
    private var lastFocusCheckpointAtElapsed = 0L

    internal val navigationState = AtlasNavigationState()
    internal val launcherState = AtlasLauncherState(restored["launcherPins"].asList().filterIsInstance<String>().toSet())
    var revision by mutableIntStateOf(0)
        private set
    var latestMessage by mutableStateOf(recoveryNotice ?: (restored["latestMessage"] as? String))
        private set
    var capabilities by mutableStateOf(readCapabilities(restored["capabilities"].asMap()))
        private set
    var syncBaseUrl by mutableStateOf(persistedSyncSettings.baseUrl)
        private set
    var selectedSyncCategories by mutableStateOf(persistedSyncSettings.selectedCategories - SyncDataCategory.APP_USAGE_DETAIL)
        private set
    var syncTokenConfigured by mutableStateOf(syncTokenStore.token() != null)
        private set
    var syncStatus by mutableStateOf(SyncStatus.LOCAL_ONLY)
        private set
    var syncPendingCount by mutableIntStateOf(syncOutbox.size())
        private set
    var syncInProgress by mutableStateOf(false)
        private set
    var syncStatusMessage by mutableStateOf("Le mode local ne contacte aucun serveur.")
        private set
    var agentStatus by mutableStateOf(AgentStatus.UNAVAILABLE)
        private set
    var agentStatusMessage by mutableStateOf("Agent indisponible — fonctions locales actives")
        private set
    val launcherPinnedPackages: Set<String> get() = launcherState.pinnedPackages
    var historyDays by mutableIntStateOf(7)
        private set
    var simulationMessage by mutableStateOf(restored["simulationMessage"] as? String)
        private set
    var lastSimulationInterventions by mutableIntStateOf(
        (restored["lastSimulationInterventions"] as? Number)?.toInt() ?: 0,
    )
        private set

    private var goalsSubdocument: Map<String, Any?>? = restored["goals"].asMap()
    private var contentActivityTimestamps = restored["agentContentActivity"].asList()
        .mapNotNull { (it as? Number)?.toLong() }
        .filter { it > 0L }
        .takeLast(MAX_AGENT_CONTENT_ACTIVITY)
    private var agentConsentMigrationPending = !restored.containsKey(KEY_AGENT_CONTEXT_CONSENT_DATES)
    private var stateGraphReady = false
    private var persistRequestedDuringInitialization = false
    private var lastSharedAgentContextJsonSnapshot by mutableStateOf(restored["lastAgentSharedContext"] as? String)
    private var lastSharedAgentContextAtUtcMillisSnapshot by mutableStateOf(
        (restored["lastAgentSharedContextAt"] as? Number)?.toLong(),
    )
    private var agentPlanReply by mutableStateOf(restored["agentPlanReply"] as? String)
    private var proactiveEngagementModeSnapshot by mutableStateOf(ProactiveEngagementMode.NORMAL)
    @Volatile private var proactiveCoachStateValue: Any? = restored["proactiveCoach"]

    private val locationPanelCoordinator = LocationPanelCoordinator(appContext)
    private val agentContextCoordinator = AgentContextCoordinator(
        initialConsentAtUtcMillis = restored[KEY_AGENT_CONTEXT_CONSENT_DATES].asMap()?.mapNotNull { (key, value) ->
            val timestamp = (value as? Number)?.toLong()
            if (key in AgentContextCategory.wireValueSet && timestamp != null && timestamp > 0L) key to timestamp else null
        }?.toMap().takeIf { restored.containsKey(KEY_AGENT_CONTEXT_CONSENT_DATES) },
        legacyConsentedCategories = agentMandateStore.current().sharedDataCategories,
        mandateProvider = agentMandateStore::current,
        domainsProvider = ::agentContextDomainViews,
        nowUtcMillis = clock::nowMillis,
        persistConsentDates = {
            if (persistAfterInitializationOrThrow("agent.consent_dates")) {
                agentConsentMigrationPending = false
                agentConsentMigrationReady.set(true)
            }
        },
        additionalConsentDates = {
            locationPanelCoordinator.consentStore.locationSummarySharingConsentAtUtcMillis()
                ?.let { mapOf(AgentContextCategory.LOCATION_SUMMARY.wireValue to it) }
                .orEmpty()
        },
        agentAccessAllowed = ::isAgentRequestAllowed,
        showConsentedContext = ::isAgentContextVisible,
    )
    private val goalPanelCoordinator = GoalPanelCoordinator(
        readSubdocument = { goalsSubdocument },
        persistSubdocument = { subdocument ->
            val previous = goalsSubdocument
            goalsSubdocument = subdocument
            try {
                persistAfterInitializationOrThrow("goals.write")
            } catch (error: Exception) {
                goalsSubdocument = previous
                throw error
            }
        },
        nowUtcMillis = clock::nowMillis,
        focusSessions = ::goalFocusSessions,
        habits = habits::list,
        habitCheckIns = ::allHabitCheckIns,
    )
    private val proactiveNotificationPublisher = ProactiveCoachNotificationPublisher(appContext)
    private val proactiveCoachCoordinator = ProactiveCoachCoordinator(
        readStateJson = {
            when (val stored = proactiveCoachStateValue) {
                null -> null
                is String -> stored
                else -> throw IllegalStateException("Persisted proactive coach state is not a JSON string")
            }
        },
        writeStateJson = ::persistProactiveCoachState,
        currentConsent = ::proactiveConsentSnapshot,
        isEnabled = { isProactiveCoachEnabled() },
        nowUtcMillis = clock::nowMillis,
        mandateZoneId = ::currentMandateZoneId,
        requestPlan = ::requestProactivePlan,
        showNotification = proactiveNotificationPublisher::show,
    )

    init {
        stateGraphReady = true
        proactiveEngagementModeSnapshot = proactiveCoachCoordinator.mode()
        policy = AndroidEmergencyPauseStore.apply(appContext, policy, clock.nowMillis())
        restored["focus"].asMap()?.let { saved ->
            runCatching { focusEngine.restore(FocusSession.fromMap(saved)) }
        }
        if (capabilities[CAP_FOCUS] != true) {
            focusEngine.currentSession()?.takeIf { it.status == FocusStatus.RUNNING }?.let { running ->
                focusSnapshot = focusEngine.pause(running.id)
                phaseAlarms.cancel(running.id)
                focusNotifications.cancel(running.id)
                latestMessage = "Focus désactivé. La session est en pause."
                persistRequestedDuringInitialization = true
            }
        }
        focusSnapshot = focusEngine.currentSession()
        if (focusSnapshot?.status == FocusStatus.RUNNING && capabilities[CAP_FOCUS_NOTIFICATIONS] == true) {
            focusSnapshot?.let(phaseAlarms::schedule)
        }
        if (focusSnapshot?.status in setOf(FocusStatus.RUNNING, FocusStatus.PAUSED)) {
            navigationState.replace(AtlasScreenId.FOCUS)
        }
        lastFocusCheckpointAtElapsed = clock.monotonicMillis()
        restored["habits"].asList().mapNotNull { it.asMap() }.takeIf { it.isNotEmpty() }?.let { maps ->
            runCatching { habits.restoreMaps(maps) }
        }
        habitLinkPolicy = restored["habitLink"].asMap()?.let { map ->
            runCatching { HabitLinkPolicy.fromMap(habits, map) }.getOrNull()
        } ?: HabitLinkPolicy(habits)
        restoreCoach(restored["coach"].asMap())
        directiveSnapshot = coach.currentDirective()
        refreshDomainSnapshots()
        if (capabilities[CAP_FOCUS_ROUTINES] == true) {
            routineAlarms.scheduleAll(focusRoutinesSnapshot, ZonedDateTime.now(), ZoneId.systemDefault())
        }
        if (capabilities[CAP_HABIT_REMINDERS] == true) habitReminderScheduler.rescheduleAll()
        refreshSyncRuntime()
        refreshBackgroundUsageWork()
        pruneExpiredAgentInbox()
        reconcilePersistedBackendObservation()
        val interruptedEffects = agentDispatcher.reconcileInterrupted()
        if (interruptedEffects.any { it.status == dev.atlas.coach.sync.agent.dispatch.DispatchStatus.NEEDS_RECONCILIATION }) {
            latestMessage = "Un effet local reste en attente de réconciliation; aucune répétition incertaine n'a été lancée."
        }
        val deferredPersistenceRequested = persistRequestedDuringInitialization
        persistRequestedDuringInitialization = false
        when {
            recoveryReason != null && persistenceAllowed && agentConsentMigrationPending -> {
                try {
                    persistOrThrow("agent.consent_dates")
                } catch (_: PersistOperationException) {
                    blockAgentForRevocation("agent.consent_dates")
                    latestMessage = "Les autorisations héritées n'ont pas pu être enregistrées; l'agent reste bloqué."
                }
            }
            recoveryReason != null && persistenceAllowed -> persist()
            recoveryReason != null -> touch()
            agentConsentMigrationPending && persistenceAllowed -> {
                try {
                    persistOrThrow("agent.consent_dates")
                    agentConsentMigrationPending = false
                    agentConsentMigrationReady.set(true)
                } catch (_: PersistOperationException) {
                    blockAgentForRevocation("agent.consent_dates")
                    latestMessage = "Les autorisations héritées n'ont pas pu être enregistrées; l'agent reste bloqué."
                }
            }
            stateReadResult == StateReadResult.Missing -> persist()
            deferredPersistenceRequested && persistenceAllowed -> persist()
        }
        refreshAgentApiRuntime()
        if (canUseRemoteSync() && syncOutbox.size() > 0) scheduleSync(0L)
    }

    val directive: Directive get() = directiveSnapshot
    fun directiveTitle(id: String?): String = id?.let { coach.findDirective(it)?.title } ?: "focus libre"
    val focusSession: FocusSession? get() = focusSnapshot
    val focusHistory: List<FocusSession> get() = focusHistoryEntries
    val focusRoutines: List<FocusRoutine> get() = focusRoutinesSnapshot
    val activeHabits: List<Habit> get() = habitsSnapshot
    val selectableUsageApps: List<UsageApp> get() = querySelectableUsageApps()
    val selectedUsagePackages: Set<String> get() = usageStores.currentConsent().allowlistedPackageNames
    val protectedUsagePackages: Set<String> get() = AndroidProtectedPackages.protectedPackages(
        appContext,
        usageStores.currentConsent().allowlistedPackageNames,
    ).sorted().toSet()
    val usagePermissionGranted: Boolean get() = usageAccessStatus.isAllowed()
    val usageConsentActive: Boolean get() = usageStores.currentConsent().isExplicit && capabilities[CAP_APP_TRACKING] == true
    val usageHistoryCount: Int get() = usageStores.allRecords().size
    val usageRetentionDescription: String get() =
        "${UsageRetention.EVENT_RETENTION_MILLIS / (24L * 60L * 60L * 1_000L)} jours maximum · ${UsageRetention.MAX_STORED_EVENTS} ouvertures locales au plus"
    val checkedHabitIds: Set<String> get() = checkedHabitIdsSnapshot
    val coachJournal: List<CoachJournalEntry> get() = journalSnapshot
    val interventions: List<Intervention> get() = interventionsSnapshot
    val contentItems: List<ContentItem> get() = contentItemsSnapshot
    val isCoachPaused: Boolean get() = coachPausedSnapshot
    val isEmergencyPaused: Boolean get() = policy.pauseUntil?.let { it > clock.nowMillis() } == true
    val quietHoursEnabled: Boolean get() = policy.quietHours != null
    val agentMandate: AgentMandate get() = agentMandateStore.current()
    val agentHomeState: AgentHomeState get() {
        val homeObservation = agentHomeStateSnapshot.backendObservation
        val inboxObservation = agentInboxRepository.backendObservation()
        val homeChecked = homeObservation.checkedAtUtcMillis ?: -1L
        val inboxChecked = inboxObservation.checkedAtUtcMillis ?: -1L
        val current = if (inboxChecked > homeChecked) {
            agentHomeStateSnapshot.copy(backendObservation = inboxObservation)
        } else agentHomeStateSnapshot
        val nextAction = nextPriorityGoalAction
        return if (current.directive == null && nextAction != null) current.copy(
            directive = AgentHomeDirective(
                title = nextAction,
                body = "",
                primaryAction = "open_coach",
                focusProposalId = null,
                provenance = dev.atlas.coach.sync.agent.DirectiveProvenance.MANUAL_ENTRY,
            ),
        ) else current
    }
    val agentConversation: List<AgentConversationMessage> get() = agentConversationSnapshot
    val agentInbox: List<AgentInboxEntry> get() = agentInboxRepository.all()
    val visibleAgentProposals: List<AgentInboxEntry> get() = agentInboxRepository.visible()
    val agentEffectHistory: List<AgentEffectRecord> get() = agentEffectJournal.entries().asReversed()
    val agentSuggestions: List<String> get() = agentSuggestionsSnapshot
    val isAgentEnabled: Boolean get() = isAgentRequestAllowed()
    val coachGoals: List<GoalsPanelEntry> get() = goalPanelCoordinator.entries()
    val goalFocusLinkOptions: List<GoalPanelLinkOption> get() = goalPanelCoordinator.focusLinkOptions()
    val goalHabitLinkOptions: List<GoalPanelLinkOption> get() = goalPanelCoordinator.habitLinkOptions()
    val nextPriorityGoalAction: String? get() = goalPanelCoordinator.nextPriorityAction()
    val agentContextPreview: AgentContextPreview get() = agentContextCoordinator.preview()
    val lastSharedAgentContextJson: String? get() = lastSharedAgentContextJsonSnapshot
    val lastSharedAgentContextAtUtcMillis: Long? get() = lastSharedAgentContextAtUtcMillisSnapshot
    val latestAgentPlanReply: String? get() = agentPlanReply
    val proactiveEngagementMode: ProactiveEngagementMode get() = proactiveEngagementModeSnapshot
    val locationPanelState: LocationPanelState
        get() = locationPanelCoordinator.panelState(
            locationCapability = capabilities[CAP_LOCATION] == true,
            mobilityCapability = capabilities[CAP_MOBILITY] == true,
        )

    val canLinkCompletedFocusToHabit: Boolean
        get() = focusSession?.let { FocusHabitLinkCondition.AllWorkCyclesCompleted.isSatisfiedBy(it) } == true

    fun createGoal(draft: GoalPanelDraft) = goalAction("Objectif enregistré.") {
        goalPanelCoordinator.create(draft)
    }

    fun updateGoal(goalId: String, draft: GoalPanelDraft) = goalAction("Modifications de l'objectif enregistrées.") {
        goalPanelCoordinator.update(goalId, draft)
    }

    fun changeGoalStatus(goalId: String, status: GoalStatus) = goalAction("Statut de l'objectif mis à jour.") {
        goalPanelCoordinator.changeStatus(goalId, status)
    }

    fun completeGoalFromProgress(goalId: String) = goalAction("Objectif terminé selon sa progression réelle.") {
        goalPanelCoordinator.completeFromProgress(goalId)
    }

    fun confirmManualGoalCompletion(goalId: String) = goalAction("Accomplissement confirmé manuellement.") {
        goalPanelCoordinator.confirmManualCompletion(goalId)
    }

    fun linkFocusToGoal(goalId: String, focusSessionId: String, linked: Boolean) = goalAction("Lien Focus mis à jour.") {
        goalPanelCoordinator.linkFocus(goalId, focusSessionId, linked)
    }

    fun linkHabitToGoal(goalId: String, habitId: String, linked: Boolean) = goalAction("Lien d'habitude mis à jour.") {
        goalPanelCoordinator.linkHabit(goalId, habitId, linked)
    }

    private inline fun goalAction(successMessage: String, operation: () -> Any?) {
        runCatching(operation)
            .onSuccess {
                latestMessage = successMessage
                recordDueGoalReviews()
            }
            .onFailure { error ->
                latestMessage = if (error is PersistOperationException) {
                    "Impossible d'enregistrer l'objectif. Son état précédent est conservé."
                } else {
                    error.message?.take(180) ?: "Modification de l'objectif impossible."
                }
                touch()
            }
    }

    fun setLocationRequestConsent(enabled: Boolean) {
        locationPanelCoordinator.setCoarseRequestConsent(enabled)
        capabilities = capabilities + (CAP_LOCATION to enabled)
        if (!enabled) locationPanelCoordinator.onLocationCapabilityDisabled()
        persist()
    }

    fun setLocationSummaryConsent(enabled: Boolean) {
        agentAuthorizationCommitInProgress.set(true)
        val locationCategory = AgentContextCategory.LOCATION_SUMMARY.wireValue
        val barrierStored = if (enabled) true else blockAgentForRevocation("agent.location_consent", locationCategory)
        try {
            if (!enabled) agentRequestGeneration.incrementAndGet()
            locationPanelCoordinator.setCoachSummaryConsent(enabled)
            if (!enabled) locationPanelCoordinator.stopMobility(MobilitySessionStopReason.SHARING_REVOKED)
            queueProactivePurgeRevoked()
            if (!enabled) {
                val stateStored = runCatching { persistOrThrow("agent.location_consent") }.isSuccess
                latestMessage = if (barrierStored && stateStored) {
                    "Partage de localisation retiré; l'agent est bloqué jusqu'à une nouvelle activation."
                } else {
                    "Partage retiré dans cette session; stockage en échec, l'agent reste bloqué et la révocation n'est pas confirmée."
                }
            } else {
                persistOrThrow("agent.location_consent")
                if (!revocationBarrier.clearCategoryAfterDurableOptIn(locationCategory, true)) {
                    recordPersistenceFailure("agent.location_consent", PersistFailureKind.STORAGE_IO)
                    latestMessage = "Consentement enregistré, mais l'agent reste bloqué car la barrière locale n'a pas pu être levée."
                } else if (revocationBarrier.isBlocked()) {
                    latestMessage = "Consentement de localisation enregistré; l'agent reste bloqué par une autre révocation en attente."
                } else {
                    latestMessage = "Partage du résumé de localisation activé."
                }
            }
            touch()
        } catch (_: PersistOperationException) {
            latestMessage = "Impossible d'enregistrer le consentement. L'agent reste bloqué."
            touch()
        } finally {
            agentAuthorizationCommitInProgress.set(false)
            refreshAgentApiRuntime()
        }
    }

    fun setMobilityConsent(enabled: Boolean) {
        locationPanelCoordinator.setMobilityConsent(enabled)
        capabilities = capabilities + (CAP_MOBILITY to enabled)
        if (!enabled) locationPanelCoordinator.stopMobility(MobilitySessionStopReason.CAPABILITY_DISABLED)
        persist()
    }

    suspend fun captureCoarseLocation(): LocationSummary? = locationPanelCoordinator.captureCoarsePosition()
        .also { touch() }

    fun startMobilitySession(activity: android.app.Activity): Boolean = locationPanelCoordinator.startMobility(
        activity,
        capabilityEnabled = capabilities[CAP_MOBILITY] == true,
    ).also { touch() }

    fun stopMobilitySession(reason: MobilitySessionStopReason = MobilitySessionStopReason.MANUAL) =
        locationPanelCoordinator.stopMobility(reason).also { touch() }

    /** Called by the app lifecycle bridge when S3 reports a measured session completion. */
    fun onMobilitySessionCompletion(completion: MobilitySessionCompletion?) {
        if (completion == null || !completion.shouldEmitCompletedEvent) return
        if (completion.stopReason !in setOf(MobilitySessionStopReason.MANUAL, MobilitySessionStopReason.DURATION_LIMIT)) return
        recordProactiveEvents(listOf(
            CoachTrigger(
                eventId = "mobility:${completion.startedAtUtcMillis}",
                source = CoachTriggerSource.MOBILITY_SESSION_COMPLETED,
                occurredAtUtcMillis = completion.stoppedAtUtcMillis,
                reliability = CoachTriggerReliability.VERIFIED,
            ),
        ))
    }

    fun setProactiveEngagementMode(mode: ProactiveEngagementMode) {
        syncWorker.execute {
            val saved = proactiveCoachCoordinator.setMode(mode)
            mainHandler.post {
                if (saved) {
                    proactiveEngagementModeSnapshot = mode
                    latestMessage = if (mode == ProactiveEngagementMode.REINFORCED) {
                        "Mode renforcé activé · au plus 5 notifications par jour."
                    } else {
                        "Mode normal activé · au plus 3 notifications par jour."
                    }
                    touch()
                } else {
                    latestMessage = "Mode proactif non enregistré."
                    touch()
                }
            }
        }
    }

    fun initialLocationPermissionRequest(): Array<String> = locationPanelCoordinator.initialPermissionRequest()

    fun locationPermissionGranted(): Boolean = locationPanelCoordinator.permissionGranted()

    fun onLocationPermissionResult(granted: Boolean) {
        locationPanelCoordinator.handlePermissionResult(granted)
        if (!granted) locationPanelCoordinator.onLocationPermissionRevoked()
        touch()
    }

    fun notificationPermissionGranted(): Boolean =
        (Build.VERSION.SDK_INT < 33 || appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            appContext.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    fun onNotificationPermissionResult(granted: Boolean) {
        latestMessage = if (granted) {
            "Notifications autorisées. Les alertes restent facultatives et réglables dans Android."
        } else {
            "Notifications refusées. Focus, routines et rappels restent utilisables dans ATLAS au premier plan."
        }
        touch()
    }

    fun handleNotificationIntent(intent: Intent?) {
        if (intent?.action != AndroidFocusNotifications.ACTION_OPEN_FROM_NOTIFICATION) return
        when {
            intent.getStringExtra("atlas_open_routine_id") != null -> {
                navigationState.navigate(AtlasScreenId.ROUTINES)
                latestMessage = "Proposition de routine ouverte. Démarrez-la explicitement si vous le souhaitez."
            }
            intent.getStringExtra("atlas_open_focus_session") != null -> {
                navigationState.navigate(AtlasScreenId.FOCUS)
                latestMessage = "La phase Focus est terminée."
            }
        }
        touch()
    }

    fun setSyncEnabled(enabled: Boolean) {
        if (enabled) {
            capabilities = capabilities + (CAP_SYNC to true)
            refreshSyncRuntime()
            syncStatusMessage = when {
                selectedSyncCategories.isEmpty() -> "Choisissez au moins une catégorie avant tout envoi."
                syncBaseUrl.isBlank() -> "Saisissez l'URL du backend et enregistrez-la."
                !syncTokenConfigured -> "Saisissez un jeton Bearer pour authentifier le backend."
                else -> "Sync activée selon les catégories consenties."
            }
        } else {
            disableAndPurgeSync()
            return
        }
        persist()
    }

    fun setSyncCategory(category: SyncDataCategory, enabled: Boolean) {
        if (category == SyncDataCategory.APP_USAGE_DETAIL) return
        selectedSyncCategories = if (enabled) selectedSyncCategories + category else selectedSyncCategories - category
        syncSettingsStore.write(syncBaseUrl, selectedSyncCategories)
        refreshSyncRuntime()
        syncPendingCount = syncOutbox.size()
        syncStatusMessage = if (syncPendingCount > 0) {
            "En attente de synchronisation : $syncPendingCount événement(s)."
        } else if (capabilities[CAP_SYNC] == true && selectedSyncCategories.isEmpty()) {
            "Aucune catégorie consentie : aucune requête ne sera envoyée."
        } else {
            "Consentements enregistrés. Les catégories décochées sont purgées de la file."
        }
        persist()
    }

    /** Saves only the URL and an optional new token. The token is encrypted by Android Keystore. */
    fun saveSyncConfiguration(baseUrl: String, newToken: String?) {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        if (normalizedUrl.isNotEmpty() && !isAllowedBackendUrl(normalizedUrl)) {
            syncStatusMessage = "URL invalide. Utilisez une adresse HTTP ou HTTPS complète."
            touch()
            return
        }
        try {
            if (!newToken.isNullOrBlank()) syncTokenStore.configureToken(newToken)
            syncBaseUrl = normalizedUrl
            syncTokenConfigured = syncTokenStore.token() != null
            syncSettingsStore.write(syncBaseUrl, selectedSyncCategories)
            refreshSyncRuntime()
            refreshAgentApiRuntime()
            syncStatusMessage = when {
                syncBaseUrl.isBlank() -> "URL du backend effacée. Aucune requête ne sera envoyée."
                !syncTokenConfigured -> "URL enregistrée; un jeton Bearer est requis avant la synchronisation."
                else -> "Configuration enregistrée. Le jeton est chiffré par Android Keystore."
            }
            if (canUseRemoteSync() && syncOutbox.size() > 0 && syncTokenConfigured) scheduleSync(0L)
        } catch (_: Exception) {
            syncStatusMessage = "Configuration non enregistrée. Vérifiez la valeur puis réessayez."
        }
        touch()
    }

    fun clearSyncToken() {
        runCatching { syncTokenStore.configureToken(null) }
        syncTokenConfigured = false
        refreshSyncRuntime()
        refreshAgentApiRuntime()
        syncStatus = if (syncPendingCount > 0) SyncStatus.AUTH_REQUIRED else SyncStatus.LOCAL_ONLY
        syncStatusMessage = "Jeton supprimé. Aucun envoi authentifié n'est possible."
        touch()
    }

    fun syncNow() {
        if (!canUseRemoteSync()) {
            syncStatus = if (capabilities[CAP_SYNC] == true) SyncStatus.NOTHING_TO_SYNC else SyncStatus.LOCAL_ONLY
            syncPendingCount = syncOutbox.size()
            syncStatusMessage = when {
                capabilities[CAP_SYNC] != true -> "Sync désactivée. LocalMock reste disponible hors ligne."
                selectedSyncCategories.isEmpty() -> "Aucune catégorie consentie : aucune requête n'a été envoyée."
                syncBaseUrl.isBlank() -> "URL du backend manquante. La file reste locale."
                else -> "URL invalide. La file reste locale."
            }
            touch()
            return
        }
        if (!syncTokenConfigured) {
            syncStatus = SyncStatus.AUTH_REQUIRED
            syncStatusMessage = "Jeton Bearer requis. La file reste sur cet appareil."
            syncPendingCount = syncOutbox.size()
            touch()
            return
        }
        syncOutbox.makeDueNow()
        syncInProgress = true
        syncStatusMessage = "Synchronisation en cours…"
        touch()
        scheduleSync(0L)
    }

    fun clearSyncQueue() {
        syncGeneration.incrementAndGet()
        syncRetryFuture?.cancel(false)
        val removed = syncOutbox.clear()
        syncPendingCount = 0
        syncInProgress = false
        syncStatus = if (capabilities[CAP_SYNC] == true) SyncStatus.NOTHING_TO_SYNC else SyncStatus.LOCAL_ONLY
        syncStatusMessage = "$removed événement(s) supprimé(s) de la file locale."
        touch()
    }

    fun disableAndPurgeSync() {
        syncGeneration.incrementAndGet()
        syncRetryFuture?.cancel(false)
        effectiveConsent.set(SyncConsent.DISABLED)
        val removed = syncOutbox.clear()
        capabilities = capabilities + (CAP_SYNC to false)
        selectedSyncCategories = emptySet()
        syncSettingsStore.write(syncBaseUrl, emptySet())
        remoteBackend = null
        coachGateway = LocalMockCoachGateway()
        syncGateway = LocalMockSyncGateway()
        syncPendingCount = 0
        syncInProgress = false
        syncStatus = SyncStatus.LOCAL_ONLY
        syncStatusMessage = "Sync désactivée; $removed événement(s) supprimé(s). Aucun réseau ne sera ouvert."
        persist()
    }

    private fun refreshSyncRuntime() {
        val consent = if (capabilities[CAP_SYNC] == true) {
            SyncConsent(selectedSyncCategories, sharePrivateAppDetails = false)
        } else SyncConsent.DISABLED
        effectiveConsent.set(consent)
        val removed = syncOutbox.discardDisallowed(consent)
        syncPendingCount = syncOutbox.size()
        val generation = syncGeneration.incrementAndGet()
        syncRetryFuture?.cancel(false)

        val configuredRemote = if (consent.enabledCategories.isNotEmpty() && isAllowedBackendUrl(syncBaseUrl)) {
            RemoteBackendGateway(
                baseUrl = syncBaseUrl,
                tokenProvider = syncTokenStore,
                outbox = syncOutbox,
                consentProvider = effectiveConsent::get,
            )
        } else null
        remoteBackend = configuredRemote
        if (configuredRemote == null) {
            coachGateway = LocalMockCoachGateway()
            syncGateway = LocalMockSyncGateway()
        } else {
            coachGateway = configuredRemote
            syncGateway = configuredRemote
        }
        if (removed > 0 && syncStatus != SyncStatus.LOCAL_ONLY) {
            syncStatusMessage = "$removed événement(s) retiré(s) car le consentement correspondant est désactivé."
        }
        if (generation > 0L && capabilities[CAP_SYNC] != true) syncStatus = SyncStatus.LOCAL_ONLY
    }

    private fun canUseRemoteSync(): Boolean =
        capabilities[CAP_SYNC] == true &&
            effectiveConsent.get().enabledCategories.isNotEmpty() &&
            isAllowedBackendUrl(syncBaseUrl) && remoteBackend != null

    private fun refreshBackgroundUsageWork() {
        val enabled = CapabilityRules.mayObserveUsageInBackground(
            capabilities = capabilities,
            usageConsentActive = usageConsentActive,
            osPermissionGranted = usagePermissionGranted,
        )
        runCatching {
            if (enabled) UsageObservationWorkScheduler.enqueueUniquePeriodicWork(appContext)
            else UsageObservationWorkScheduler.cancel(appContext)
        }
            .onFailure { latestMessage = "La planification UsageStats n'est pas disponible; l'observation visible reste active." }
    }

    private fun scheduleSync(delayMillis: Long) {
        if (!canUseRemoteSync()) return
        val generation = syncGeneration.get()
        syncRetryFuture?.cancel(false)
        syncRetryFuture = syncWorker.schedule({ executeSync(generation) }, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private fun executeSync(generation: Long) {
        if (generation != syncGeneration.get() || !syncRunning.compareAndSet(false, true)) return
        val result = try {
            syncGateway.syncNow()
        } catch (_: Exception) {
            SyncResult(SyncStatus.PROTOCOL_ERROR, pending = syncOutbox.size(), message = "La synchronisation a échoué; la file locale est conservée.")
        } finally {
            syncRunning.set(false)
        }
        mainHandler.post {
            if (generation != syncGeneration.get()) return@post
            if (remoteBackend != null) {
                when (result.status) {
                    SyncStatus.SUCCESS -> recordBackendContactSucceeded()
                    SyncStatus.AUTH_REQUIRED, SyncStatus.CONTRACT_ERROR -> if (syncTokenConfigured) {
                        recordBackendContactSucceeded()
                    }
                    SyncStatus.RETRY_SCHEDULED, SyncStatus.PROTOCOL_ERROR, SyncStatus.REJECTED ->
                        recordBackendContactFailed()
                    SyncStatus.LOCAL_ONLY, SyncStatus.NOTHING_TO_SYNC -> Unit
                }
            }
            syncInProgress = false
            syncStatus = result.status
            syncPendingCount = syncOutbox.size()
            syncStatusMessage = when (result.status) {
                SyncStatus.SUCCESS -> "${result.acknowledged} événement(s) synchronisé(s); $syncPendingCount en attente."
                SyncStatus.NOTHING_TO_SYNC -> if (syncPendingCount > 0) {
                    "$syncPendingCount événement(s) en attente du prochain délai de reprise."
                } else "Aucun événement en attente."
                SyncStatus.RETRY_SCHEDULED -> "Backend hors ligne ou temporairement indisponible; $syncPendingCount événement(s) restent en attente."
                SyncStatus.AUTH_REQUIRED -> "Authentification refusée ou jeton manquant; $syncPendingCount événement(s) restent sur l'appareil."
                else -> result.message ?: "Réponse backend non reconnue; la file locale est conservée."
            }
            touch()
            val nextAt = when (result.status) {
                SyncStatus.RETRY_SCHEDULED -> result.retryAtUtcMillis
                SyncStatus.NOTHING_TO_SYNC -> syncOutbox.nextAttemptAtUtcMillis()
                else -> null
            }
            if (nextAt != null && syncPendingCount > 0 && canUseRemoteSync()) {
                scheduleSync((nextAt - System.currentTimeMillis()).coerceAtLeast(0L))
            }
        }
    }

    private fun enqueueSyncEvent(
        type: SyncEventType,
        category: SyncDataCategory,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        val consent = effectiveConsent.get()
        if (!consent.enabledCategories.contains(category)) return
        runCatching {
            val event = SyncEvent(
                eventType = type,
                occurredAtUtcMillis = clock.nowMillis(),
                category = category,
                dataScope = DataScope.SHAREABLE,
                payload = payload,
            )
            if (syncOutbox.enqueue(event, consent)) {
                syncPendingCount = syncOutbox.size()
                syncStatusMessage = "$syncPendingCount événement(s) en attente."
                if (canUseRemoteSync() && syncTokenConfigured) scheduleSync(0L)
            }
        }.onFailure {
            syncStatusMessage = "Événement conservé localement; impossible de l'ajouter à la file sync."
            syncStatus = SyncStatus.PROTOCOL_ERROR
            touch()
        }
    }

    /** LocalCoach remains authoritative; the selected response is also sent remotely only after consent. */
    private fun sendRemoteCoachResponse(choice: DirectiveChoice) {
        if (!effectiveConsent.get().enabledCategories.contains(SyncDataCategory.COACH_INTERACTION)) return
        val generation = syncGeneration.get()
        syncWorker.execute {
            if (generation != syncGeneration.get()) return@execute
            val remote = coachGateway as? RemoteBackendGateway ?: return@execute
            try {
                val remoteDirective = remote.currentDirective()
                if (choice.contractValue in remoteDirective.choices) remote.respond(remoteDirective.id, choice.contractValue)
            } catch (_: Exception) {
                mainHandler.post {
                    if (generation == syncGeneration.get()) {
                        syncStatus = SyncStatus.AUTH_REQUIRED
                        syncStatusMessage = "Le backend coach n'a pas répondu; la réponse locale reste enregistrée."
                        touch()
                    }
                }
            }
        }
    }

    private fun agentContextDomainViews(): AgentContextDomainViews {
        val usageConsent = usageStores.currentConsent().takeIf { usageConsentActive && usagePermissionGranted }
        val habitIds = (habits.list().map(Habit::id) + goalPanelCoordinator.repository.all().flatMap { it.linkedHabitIds })
            .distinct()
        val summaries = locationPanelCoordinator.recentSummaries(clock.nowMillis())
        return AgentContextDomainViews(
            focusSessions = goalFocusSessions(),
            habits = habits.list(),
            habitCheckIns = habitIds.flatMap(habits::checkIns),
            usageConsent = usageConsent,
            usageRecords = if (usageConsent != null) usageStores.allRecords() else emptyList(),
            contentActivity = contentActivityTimestamps.map { timestamp ->
                SyncEvent(
                    clientEventId = UUID.nameUUIDFromBytes("atlas-content-$timestamp".toByteArray()).toString(),
                    eventType = SyncEventType.CONTENT_OPENED,
                    occurredAtUtcMillis = timestamp,
                    category = SyncDataCategory.CONTENT,
                    dataScope = DataScope.SHAREABLE,
                )
            },
            coachMessages = agentConversationSnapshot,
            goals = goalPanelCoordinator.repository.all(),
            locationSummarySharingEnabled = locationPanelCoordinator.consentStore.isLocationSummarySharingEnabled(),
            locationSummaries = summaries,
        )
    }

    private fun goalFocusSessions(): List<FocusSession> = buildList {
        addAll(focusHistoryEntries)
        focusSnapshot?.takeIf { it.status in setOf(FocusStatus.FINISHED, FocusStatus.CANCELLED) }?.let(::add)
    }.distinctBy(FocusSession::id)

    private fun allHabitCheckIns(): List<dev.atlas.coach.habits.HabitCheckIn> {
        val habitIds = (habits.list().map(Habit::id) + goalPanelCoordinator.repository.all().flatMap { it.linkedHabitIds })
            .distinct()
        return habitIds.flatMap(habits::checkIns)
    }

    private fun refreshAgentApiRuntime() {
        val configured = isAgentRequestAllowed() && syncTokenStore.token() != null &&
            syncBaseUrl.isNotBlank() && isAllowedAgentBackendUrl(syncBaseUrl)
        agentApiClient = if (configured) runCatching {
            AgentApiClient(
                baseUrl = syncBaseUrl,
                tokenProvider = syncTokenStore,
                freshRequestContext = agentContextCoordinator::requestContext,
                onContextWritten = { snapshot ->
                    check(runOnMainAndWait {
                        lastSharedAgentContextJsonSnapshot = snapshot.toJsonString()
                        lastSharedAgentContextAtUtcMillisSnapshot = clock.nowMillis()
                        persist(operation = "agent.shared_context")
                        true
                    }) { "Could not persist the exact shared context" }
                },
                requestAllowed = ::isAgentRequestAllowed,
            )
        }.getOrNull() else null
        if (!configured || agentApiClient == null) {
            agentStatus = AgentStatus.UNAVAILABLE
            agentStatusMessage = "Agent indisponible — fonctions locales actives"
        }
    }

    private fun isAgentRequestAllowed(): Boolean {
        if (agentAuthorizationCommitInProgress.get() || !agentConsentMigrationReady.get() || revocationBarrier.isBlocked()) return false
        val mandate = agentMandateStore.current()
        val now = clock.nowMillis()
        if (capabilities[CAP_AGENT] != true || mandate.revoked || mandate.suspended || now >= mandate.expiresAtUtcMillis) return false
        val dates = agentContextCoordinator.consentDates()
        if (mandate.sharedDataCategories.any { dates[it]?.let { at -> at in 1L..now } != true }) return false
        if (AgentContextCategory.APP_USAGE_SUMMARY.wireValue in mandate.sharedDataCategories && !usageConsentActive) return false
        if (AgentContextCategory.LOCATION_SUMMARY.wireValue in mandate.sharedDataCategories &&
            !locationPanelCoordinator.consentStore.isLocationSummarySharingEnabled()
        ) return false
        return true
    }

    private fun isAgentContextVisible(): Boolean {
        if (!agentConsentMigrationReady.get() || revocationBarrier.isBlocked()) return false
        val mandate = agentMandateStore.current()
        val now = clock.nowMillis()
        if (mandate.revoked || mandate.suspended || now >= mandate.expiresAtUtcMillis) return false
        val dates = agentContextCoordinator.consentDates()
        return mandate.sharedDataCategories.all { dates[it]?.let { at -> at in 1L..now } == true }
    }

    private fun canExplicitOptInClearBarrier(): Boolean {
        val mandate = agentMandateStore.current()
        return capabilities[CAP_AGENT] == true && !mandate.revoked && !mandate.suspended &&
            clock.nowMillis() < mandate.expiresAtUtcMillis
    }

    private fun blockAgentForRevocation(operation: String, category: String? = null): Boolean {
        agentRequestGeneration.incrementAndGet()
        val stored = if (category == null) revocationBarrier.block() else revocationBarrier.blockCategory(category)
        return stored.also {
            if (!stored) recordPersistenceFailure(operation, PersistFailureKind.STORAGE_IO)
        }
    }

    private fun recordPersistenceFailure(operation: String, reason: PersistFailureKind) {
        persistenceDiagnostics.record(operation, reason, clock.nowMillis())
    }

    private fun isProactiveCoachEnabled(): Boolean =
        CapabilityRules.mayRunProactiveCoach(capabilities, isAgentEnabled) && !isEmergencyPaused

    private fun currentMandateZoneId(): ZoneId = ZoneId.of(agentMandateStore.current().focusWindow.timezone)

    private fun proactiveConsentSnapshot(): ProactiveConsentSnapshot {
        if (!isAgentRequestAllowed()) return ProactiveConsentSnapshot.EMPTY
        val mandate = agentMandateStore.current()
        if (mandate.revoked || mandate.suspended || clock.nowMillis() >= mandate.expiresAtUtcMillis) {
            return ProactiveConsentSnapshot.EMPTY
        }
        val dates = agentContextCoordinator.consentDates().toMutableMap()
        locationPanelCoordinator.consentStore.locationSummarySharingConsentAtUtcMillis()?.let { grantedAt ->
            dates[AgentContextCategory.LOCATION_SUMMARY.wireValue] = maxOf(
                dates[AgentContextCategory.LOCATION_SUMMARY.wireValue] ?: 0L,
                grantedAt,
            )
        }
        val grants = mandate.sharedDataCategories
            .intersect(AgentContextCategory.wireValueSet)
            .mapNotNull { category ->
                val grantedAt = dates[category]?.takeIf { it > 0L } ?: return@mapNotNull null
                if (category == AgentContextCategory.APP_USAGE_SUMMARY.wireValue && !usageConsentActive) return@mapNotNull null
                if (category == AgentContextCategory.LOCATION_SUMMARY.wireValue &&
                    !locationPanelCoordinator.consentStore.isLocationSummarySharingEnabled()
                ) return@mapNotNull null
                category to grantedAt
            }.toMap()
        return ProactiveConsentSnapshot(grants)
    }

    private fun recordProactiveEvents(events: Iterable<CoachTrigger>) {
        if (!isProactiveCoachEnabled()) return
        val batch = events.toList()
        if (batch.isEmpty()) return
        pendingProactiveEvents.addAll(batch)
        scheduleProactiveEventDrain()
    }

    private fun queueProactiveRun() {
        if (!isProactiveCoachEnabled()) return
        syncWorker.schedule(
            { if (isProactiveCoachEnabled()) runCatching { proactiveCoachCoordinator.runDue() } },
            PROACTIVE_EVENT_DEBOUNCE_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun scheduleProactiveEventDrain() {
        if (!proactiveDrainScheduled.compareAndSet(false, true)) return
        syncWorker.schedule({
            try {
                val eventBatch = buildList {
                    while (true) {
                        val event = pendingProactiveEvents.poll() ?: break
                        add(event)
                    }
                }
                if (eventBatch.isNotEmpty() && isProactiveCoachEnabled()) {
                    val recorded = proactiveCoachCoordinator.recordAll(eventBatch)
                    if (recorded.any { it == dev.atlas.coach.coach.proactive.CoachTriggerRecordStatus.RECORDED }) {
                        runCatching { proactiveCoachCoordinator.runDue() }
                    }
                }
            } finally {
                proactiveDrainScheduled.set(false)
                if (pendingProactiveEvents.isNotEmpty()) scheduleProactiveEventDrain()
            }
        }, PROACTIVE_EVENT_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun queueProactivePurgeRevoked() {
        syncWorker.execute { runCatching { proactiveCoachCoordinator.purgeRevoked() } }
    }

    private fun queueProactiveDisableAndPurge() {
        syncWorker.execute { runCatching { proactiveCoachCoordinator.disableAndPurgePending() } }
    }

    private fun requestProactivePlan(request: ProactivePlanRequest): ProactivePlanResponse? {
        if (!isProactiveCoachEnabled()) return null
        val client = agentApiClient ?: return null
        val mandate = agentMandateStore.current()
        val context = agentContextCoordinator.assembleCurrent()
        val requestGeneration = agentRequestGeneration.get()
        val proactiveGeneration = proactiveEffectGeneration.get()
        val requestStillAuthorized = {
            requestGeneration == agentRequestGeneration.get() &&
                proactiveGeneration == proactiveEffectGeneration.get() &&
                agentApiClient === client && isProactiveCoachEnabled() &&
                request.triggers.all(proactiveConsentSnapshot()::allows)
        }
        val response = try {
            client.plan(
                conversationId = agentConversationId ?: UUID.randomUUID().toString().also { agentConversationId = it },
                mandate = mandate,
                context = context,
                request = request,
                allowSend = requestStillAuthorized,
            )
        } catch (_: Exception) {
            return null
        }
        if (!requestStillAuthorized() || !applyProactivePlanResponse(
                response,
                client,
                requestGeneration,
                proactiveGeneration,
                request,
            )
        ) return null
        val notificationBody = response.reply.trim().take(ProactiveNotificationContent.MAX_BODY_LENGTH)
            .ifBlank { "Ouvrez ATLAS pour consulter votre prochaine étape." }
        return ProactivePlanResponse(
            ProactiveNotificationContent("Votre plan ATLAS est prêt", notificationBody),
        )
    }

    private fun applyProactivePlanResponse(
        response: dev.atlas.coach.sync.agent.coach.AgentChatResponse,
        client: AgentApiClient,
        requestGeneration: Long,
        proactiveGeneration: Long,
        request: ProactivePlanRequest,
    ): Boolean = runOnMainAndWait {
        if (requestGeneration != agentRequestGeneration.get() ||
            proactiveGeneration != proactiveEffectGeneration.get() ||
            !isAgentEnabled || !isProactiveCoachEnabled() || agentApiClient !== client ||
            request.triggers.any { !proactiveConsentSnapshot().allows(it) }
        ) return@runOnMainAndWait false
        agentPlanReply = response.reply
        agentStatus = response.status
        agentStatusMessage = if (response.status == AgentStatus.CONNECTED) "Agent connecté"
            else "Agent indisponible — fonctions locales actives"
        agentInboxRepository.mergePulled(response.proposals, null)
        recordBackendContactSucceeded(inboxAlreadyMarked = true)
        if (!dispatchPreauthorizedInbox()) return@runOnMainAndWait false
        persist()
        syncWorker.execute { sendPendingAgentAcks(client) }
        true
    }

    private fun runOnMainAndWait(action: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val result = CompletableFuture<Boolean>()
        mainHandler.post {
            runCatching(action).fold(result::complete, result::completeExceptionally)
        }
        return runCatching { result.get(20, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    private fun persistProactiveCoachState(json: String) {
        val write = {
            val previous = proactiveCoachStateValue
            proactiveCoachStateValue = json
            try {
                persistOrThrow("proactive.state")
                true
            } catch (error: Exception) {
                proactiveCoachStateValue = previous
                throw error
            }
        }
        check(runOnMainAndWait(write)) { "Proactive state could not be durably persisted" }
    }

    private fun recordDueGoalReviews(nowUtcMillis: Long = clock.nowMillis()) {
        val dueGoals = goalPanelCoordinator.repository.all().filter { goal ->
            goal.status == GoalStatus.ACTIVE && goal.dueAt != null && goal.dueAt <= nowUtcMillis
        }
        recordProactiveEvents(dueGoals.map { goal ->
            val dueAt = requireNotNull(goal.dueAt)
            CoachTrigger(
                eventId = "goal-review:${goal.id}:$dueAt",
                source = CoachTriggerSource.GOAL_REVIEW_DUE,
                occurredAtUtcMillis = dueAt,
                reliability = CoachTriggerReliability.VERIFIED,
            )
        })
    }

    private fun recordFocusCompletion(session: FocusSession) {
        if (session.status != FocusStatus.FINISHED) return
        recordProactiveEvents(listOf(
            CoachTrigger(
                eventId = "focus:${session.id}",
                source = CoachTriggerSource.FOCUS_COMPLETED,
                occurredAtUtcMillis = session.updatedAtUtcMillis,
                reliability = CoachTriggerReliability.VERIFIED,
            ),
        ))
    }

    private fun recordDueHabitReminders(nowUtcMillis: Long = clock.nowMillis()) {
        if (capabilities[CAP_HABITS] != true || capabilities[CAP_HABIT_REMINDERS] != true) return
        val zone = currentMandateZoneId()
        val now = Instant.ofEpochMilli(nowUtcMillis).atZone(zone)
        val date = now.toLocalDate()
        val due = habits.list().mapNotNull { habit ->
            val reminder = habit.reminder?.takeIf(HabitReminder::enabled) ?: return@mapNotNull null
            if (date.dayOfWeek !in reminder.days || now.toLocalTime().isBefore(LocalTime.of(reminder.hour, reminder.minute))) {
                return@mapNotNull null
            }
            val checkIns = habits.checkIns(habit.id)
            if (!habit.schedule.isDueOn(date, checkIns) || checkIns.any { it.date == date }) return@mapNotNull null
            val dueAt = date.atTime(reminder.hour, reminder.minute).atZone(zone).toInstant().toEpochMilli()
            CoachTrigger(
                eventId = "habit-reminder:${habit.id}:${date}",
                source = CoachTriggerSource.HABIT_REMINDER_DUE,
                occurredAtUtcMillis = dueAt,
                reliability = CoachTriggerReliability.VERIFIED,
            )
        }
        recordProactiveEvents(due)
    }

    private fun recordDailyPlanDue(nowUtcMillis: Long = clock.nowMillis()) {
        val zone = currentMandateZoneId()
        val now = Instant.ofEpochMilli(nowUtcMillis).atZone(zone)
        if (now.toLocalTime() < DAILY_PLAN_DUE_TIME || now.toLocalTime() >= PROACTIVE_QUIET_HOURS_START) return
        val dueAt = now.toLocalDate().atTime(DAILY_PLAN_DUE_TIME).atZone(zone).toInstant().toEpochMilli()
        recordProactiveEvents(listOf(
            CoachTrigger(
                eventId = "daily-plan:${now.toLocalDate()}",
                source = CoachTriggerSource.DAILY_PLAN_DUE,
                occurredAtUtcMillis = dueAt,
                reliability = CoachTriggerReliability.VERIFIED,
            ),
        ))
    }

    private fun recordMobilityCompletionIfPresent() {
        onMobilitySessionCompletion(MobilitySessionStatusStore.status.value.lastCompletion)
    }

    /** Called when Coach opens and by its explicit refresh button. No background worker is required. */
    fun pullAgentInbox() {
        pruneExpiredAgentInbox()
        if (!isAgentEnabled) {
            agentStatus = AgentStatus.UNAVAILABLE
            agentStatusMessage = "Agent désactivé — fonctions locales actives"
            touch()
            return
        }
        val client = agentApiClient
        if (client == null) {
            agentStatus = AgentStatus.UNAVAILABLE
            agentStatusMessage = "Agent indisponible — fonctions locales actives"
            touch()
            return
        }
        agentStatusMessage = "Connexion à l'agent…"
        touch()
        val requestGeneration = agentRequestGeneration.get()
        syncWorker.execute {
            runCatching { client.pullInbox(agentInboxRepository.cursor()) }
                .onSuccess { response -> mainHandler.post {
                    if (requestGeneration != agentRequestGeneration.get() || !isAgentEnabled) return@post
                    agentInboxRepository.mergePulled(response.proposals, response.cursor)
                    recordBackendContactSucceeded(inboxAlreadyMarked = true)
                    agentStatus = AgentStatus.CONNECTED
                    agentStatusMessage = "Agent connecté"
                    if (!dispatchPreauthorizedInbox()) return@post
                    persist()
                    syncWorker.execute { sendPendingAgentAcks(client) }
                } }
                .onFailure { mainHandler.post {
                    recordBackendContactFailed()
                    agentStatus = AgentStatus.UNAVAILABLE
                    agentStatusMessage = "Agent indisponible — fonctions locales actives"
                    touch()
                } }
        }
    }

    fun sendAgentMessage(rawMessage: String) {
        val message = rawMessage.trim()
        if (message.isEmpty() || message.length > MAX_AGENT_MESSAGE_LENGTH) {
            latestMessage = "Le message du coach doit contenir au plus 2 000 caractères."
            touch()
            return
        }
        if (!isAgentEnabled) {
            latestMessage = "Agent désactivé. Réactivez-le dans Mandat et autonomie."
            touch()
            return
        }
        val previousConversationId = agentConversationId
        val conversationId = previousConversationId ?: UUID.randomUUID().toString().also { agentConversationId = it }
        addAgentConversationMessage(AgentConversationMessage(
            role = AgentMessageRole.USER,
            text = message,
            createdAtUtcMillis = clock.nowMillis(),
        ))
        if (!runCatching { persistOrThrow("agent.chat_message") }.isSuccess) {
            agentConversationSnapshot = agentConversationSnapshot.dropLast(1)
            agentConversationId = previousConversationId
            latestMessage = "Impossible d'enregistrer votre message; aucune requête agent n'a été envoyée."
            touch()
            return
        }
        val client = agentApiClient
        if (client == null) {
            agentStatus = AgentStatus.UNAVAILABLE
            agentStatusMessage = "Agent indisponible — fonctions locales actives"
            addLocalAgentReply(conversationId)
            persist()
            return
        }
        val mandate = agentMandateStore.current()
        val initialContext = agentContextCoordinator.assembleCurrent()
        val requestGeneration = agentRequestGeneration.get()
        syncWorker.execute {
            runCatching { client.chat(conversationId, message, mandate, initialContext) }
                .onSuccess { response -> mainHandler.post {
                    if (requestGeneration != agentRequestGeneration.get() || !isAgentEnabled) return@post
                    agentStatus = response.status
                    agentStatusMessage = if (response.status == AgentStatus.CONNECTED) "Agent connecté"
                        else "Agent indisponible — fonctions locales actives"
                    agentInboxRepository.mergePulled(response.proposals, null)
                    recordBackendContactSucceeded(inboxAlreadyMarked = true)
                    addAgentConversationMessage(AgentConversationMessage(
                        role = AgentMessageRole.ASSISTANT,
                        text = response.reply,
                        createdAtUtcMillis = clock.nowMillis(),
                        contextSources = response.proposals.flatMap(::agentProposalContextSources).distinct().ifEmpty {
                            listOf(sharedDataSummary(mandate))
                        },
                    ))
                    if (!dispatchPreauthorizedInbox()) return@post
                    persist()
                    syncWorker.execute { sendPendingAgentAcks(client) }
                } }
                .onFailure { error -> mainHandler.post {
                    if (requestGeneration != agentRequestGeneration.get() || !isAgentEnabled ||
                        (error as? AgentApiException)?.statusCode == 403
                    ) return@post
                    val statusCode = (error as? dev.atlas.coach.sync.agent.coach.AgentApiException)?.statusCode ?: 0
                    android.util.Log.w(
                        "ATLAS_AGENT",
                        "V2 chat failed; error_type=${error.javaClass.simpleName}, http_status=$statusCode",
                    )
                    agentStatus = AgentStatus.UNAVAILABLE
                    agentStatusMessage = "Agent indisponible — fonctions locales actives"
                    recordBackendContactFailed()
                    addLocalAgentReply(conversationId)
                    persist()
                } }
        }
    }

    /** Requests a programmatic plan without adding an artificial message to chat history. */
    fun requestAgentPlan() {
        if (!isAgentEnabled) {
            agentPlanReply = "Réactivez le Coach et son mandat pour demander un plan."
            touch()
            return
        }
        val mandate = agentMandateStore.current()
        val context = agentContextCoordinator.assembleCurrent()
        val conversationId = agentConversationId ?: UUID.randomUUID().toString().also { agentConversationId = it }
        val requestGeneration = agentRequestGeneration.get()
        val client = agentApiClient
        if (client == null) {
            agentPlanReply = nextPriorityGoalAction?.let { "Prochaine étape locale : $it" }
                ?: "Aucune prochaine étape prioritaire n'est définie."
            persist()
            return
        }
        agentPlanReply = "Préparation du plan…"
        touch()
        syncWorker.execute {
            runCatching { client.plan(conversationId, mandate, context) }
                .onSuccess { response -> mainHandler.post {
                    if (requestGeneration != agentRequestGeneration.get() || !isAgentEnabled) return@post
                    agentPlanReply = response.reply
                    agentStatus = response.status
                    agentStatusMessage = if (response.status == AgentStatus.CONNECTED) "Agent connecté"
                        else "Agent indisponible — fonctions locales actives"
                    agentInboxRepository.mergePulled(response.proposals, null)
                    recordBackendContactSucceeded(inboxAlreadyMarked = true)
                    if (!dispatchPreauthorizedInbox()) return@post
                    persist()
                    syncWorker.execute { sendPendingAgentAcks(client) }
                } }
                .onFailure { error -> mainHandler.post {
                    if (requestGeneration != agentRequestGeneration.get() || !isAgentEnabled ||
                        (error as? AgentApiException)?.statusCode == 403
                    ) return@post
                    agentPlanReply = nextPriorityGoalAction?.let { "Prochaine étape locale : $it" }
                        ?: "La planification distante est indisponible; aucune proposition locale n'a été créée."
                    agentStatus = AgentStatus.UNAVAILABLE
                    agentStatusMessage = "Agent indisponible — fonctions locales actives"
                    recordBackendContactFailed()
                    persist()
                } }
        }
    }

    private fun addLocalAgentReply(conversationId: String) {
        val now = clock.nowMillis()
        val mandate = agentMandateStore.current()
        val proposal = ProposalV2(
            schemaVersion = 2,
            proposalId = UUID.randomUUID().toString(),
            idempotencyKey = "local-${UUID.randomUUID()}",
            conversationId = conversationId,
            mandateId = mandate.id,
            mandateVersion = mandate.version,
            contextVersion = "local-demo-v1",
            createdAtUtcMillis = now,
            expiresAtUtcMillis = now + ProposalV2.MAX_TTL_MILLIS,
            actionType = AgentActionType.FOCUS_PROPOSE.wireValue,
            parameters = mapOf("focus_minutes" to 10, "break_minutes" to 2, "cycles" to 1, "label" to "Prochaine étape"),
            reason = "Une courte session locale est une prochaine étape simple.",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        agentInboxRepository.insertLocal(proposal)
        addAgentConversationMessage(AgentConversationMessage(
            role = AgentMessageRole.ASSISTANT,
            text = "Je peux vous proposer une session de focus de 10 minutes. Cette démonstration fonctionne localement et n'envoie aucune donnée.",
            createdAtUtcMillis = now,
            contextSources = listOf(sharedDataSummary(mandate), "Adaptateur local déterministe"),
        ))
    }

    private fun addAgentConversationMessage(message: AgentConversationMessage) {
        agentConversationSnapshot = (agentConversationSnapshot + message).takeLast(MAX_AGENT_CONVERSATION_MESSAGES)
    }

    private fun agentProposalContextSources(proposal: ProposalV2): List<String> = listOf(
        "Version de contexte ${proposal.contextVersion}",
        sharedDataSummary(agentMandateStore.current()),
    )

    private fun sharedDataSummary(mandate: AgentMandate): String = if (mandate.sharedDataCategories.isEmpty()) {
        "Aucune donnée locale partagée"
    } else {
        "Catégories consenties : ${mandate.sharedDataCategories.sorted().joinToString(", ")}"
    }

    private fun dispatchPreauthorizedInbox(): Boolean {
        if (!isAgentRequestAllowed()) return false
        if (!runCatching { persistOrThrow("agent.inbox") }.isSuccess) {
            latestMessage = "Impossible d'enregistrer les propositions; aucune action automatique n'a été exécutée."
            touch()
            return false
        }
        try {
            agentInboxRepository.visible().forEach { entry ->
                if (agentMandate.mode == AgentMode.PREAUTHORIZED) {
                    agentDispatcher.dispatchPreauthorized(entry.id)
                }
            }
            persistOrThrow("agent.inbox")
            return true
        } catch (_: Exception) {
            latestMessage = "Impossible de confirmer localement les propositions; les actions automatiques restent suspendues."
            touch()
            return false
        }
    }

    private fun reconcilePersistedBackendObservation() {
        val homeObservation = agentHomeStateSnapshot.backendObservation
        val inboxObservation = agentInboxRepository.backendObservation()
        val homeChecked = homeObservation.checkedAtUtcMillis ?: -1L
        val inboxChecked = inboxObservation.checkedAtUtcMillis ?: -1L
        if (inboxChecked > homeChecked) {
            agentHomeStateSnapshot = agentHomeStateSnapshot.copy(backendObservation = inboxObservation)
        }
    }

    private fun recordBackendContactSucceeded(
        atUtcMillis: Long = clock.nowMillis(),
        inboxAlreadyMarked: Boolean = false,
    ) {
        if (!inboxAlreadyMarked) runCatching { agentInboxRepository.markBackendContactSucceeded(atUtcMillis) }
        agentHomeStateSnapshot = agentHomeStateSnapshot.withBackendContactSucceeded(atUtcMillis)
        persist()
    }

    private fun recordBackendContactFailed(atUtcMillis: Long = clock.nowMillis()) {
        runCatching { agentInboxRepository.markBackendUnavailable(atUtcMillis) }
        agentHomeStateSnapshot = agentHomeStateSnapshot.withBackendContactFailed(atUtcMillis)
        persist()
    }

    private fun sendPendingAgentAcks(client: AgentApiClient) {
        if (!isAgentRequestAllowed()) return
        agentInboxRepository.pendingAcks().forEach { entry ->
            try {
                if (entry.proposal.state == ProposalState.EXECUTED) {
                    client.acknowledgeExecution(entry.id, entry.effect, clock.nowMillis())
                } else {
                    client.acknowledge(
                        entry.id,
                        entry.proposal.state.wireValue,
                        entry.effect,
                        clock.nowMillis(),
                    )
                }
                agentInboxRepository.markAcked(entry.id)
            } catch (_: Exception) {
                // Ack remains durable and is retried on the next Coach open or explicit pull.
            }
        }
    }

    private fun pruneExpiredAgentInbox() {
        agentInboxRepository.all()
    }

    fun acceptAgentProposal(proposalId: String) {
        val result = agentDispatcher.accept(proposalId)
        latestMessage = when (result.status) {
            dev.atlas.coach.sync.agent.dispatch.DispatchStatus.EXECUTED ->
                if (result.effect.contains("planifi", ignoreCase = true)) "Action planifiée" else result.effect
            dev.atlas.coach.sync.agent.dispatch.DispatchStatus.REFUSED -> result.effect
            else -> result.effect
        }
        persist()
        agentApiClient?.let { client -> syncWorker.execute { sendPendingAgentAcks(client) } }
    }

    fun rejectAgentProposal(proposalId: String) {
        val result = agentDispatcher.reject(proposalId)
        latestMessage = result.effect
        persist()
        agentApiClient?.let { client -> syncWorker.execute { sendPendingAgentAcks(client) } }
    }

    fun postponeAgentProposal(proposalId: String) {
        latestMessage = agentDispatcher.postpone(proposalId).effect
        persist()
    }

    fun editAgentProposal(proposalId: String, parameters: Map<String, Any?>) {
        val result = agentDispatcher.edit(proposalId, parameters)
        latestMessage = result.effect
        persist()
    }

    fun startAgentProposedFocus(
        sessionProposalId: String,
        taskTitle: String? = null,
        config: ReefPomodoroConfig? = null,
    ) {
        val chosenTask = taskTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (taskTitle != null && (chosenTask == null || chosenTask.length > FocusPlan.MAX_TASK_TITLE_LENGTH)) {
            latestMessage = "Le titre de tâche doit contenir entre 1 et ${FocusPlan.MAX_TASK_TITLE_LENGTH} caractères."
            touch()
            return
        }
        if (chosenTask != null || config != null) {
            val card = agentHomeStateSnapshot.focusProposal?.takeIf { it.proposalId == sessionProposalId } ?: run {
                latestMessage = "La session proposée n'est plus disponible."
                touch()
                return
            }
            agentHomeStateSnapshot = agentHomeStateSnapshot.copy(focusProposal = card.copy(
                label = chosenTask ?: card.label,
                focusMinutes = config?.focusMinutes ?: card.focusMinutes,
                breakMinutes = config?.shortBreakMinutes ?: card.breakMinutes,
                cycles = config?.totalWorkCycles ?: card.cycles,
                longBreakMinutes = config?.longBreakMinutes ?: card.longBreakMinutes,
                longBreakEveryCycles = config?.cycles ?: card.longBreakEveryCycles,
                continuous = config?.continuous ?: card.continuous,
            ))
            persist()
        }
        val mandate = agentMandateStore.current()
        val now = clock.nowMillis()
        val start = ProposalV2(
            schemaVersion = 2,
            proposalId = UUID.randomUUID().toString(),
            idempotencyKey = "local-start-${UUID.randomUUID()}",
            conversationId = agentConversationId,
            mandateId = mandate.id,
            mandateVersion = mandate.version,
            contextVersion = "local-ui-confirmation",
            createdAtUtcMillis = now,
            expiresAtUtcMillis = now + 60L * 60L * 1_000L,
            actionType = AgentActionType.FOCUS_START.wireValue,
            parameters = mapOf("session_ref" to sessionProposalId),
            reason = "Démarrage demandé depuis le Coach.",
            requiredLevel = ProposalLevel.CONFIRM,
        )
        if (agentInboxRepository.insertLocal(start) == null) {
            latestMessage = "La session proposée n'est plus disponible."
            touch()
            return
        }
        acceptAgentProposal(start.proposalId)
    }

    fun agentProposalSourceSummary(proposal: ProposalV2): String = agentProposalContextSources(proposal).joinToString(" · ")

    fun cancelAgentFocusProposal(proposalId: String) {
        val card = agentHomeStateSnapshot.focusProposal?.takeIf { it.proposalId == proposalId } ?: return
        card.routineId?.let { routineId ->
            routineAlarms.cancel(routineId)
            focusRoutineRepository.delete(routineId)
        }
        focusSnapshot?.takeIf { it.directiveId == proposalId }?.let { active ->
            focusEngine.cancel(active.id)?.let(::archiveFocusSession)
            phaseAlarms.cancel(active.id)
            focusNotifications.cancel(active.id)
        }
        agentHomeStateSnapshot = agentHomeStateSnapshot.copy(
            focusProposal = card.copy(state = AgentFocusCardState.CANCELLED),
            cancelledFocusProposalIds = agentHomeStateSnapshot.cancelledFocusProposalIds + proposalId,
        )
        agentInboxRepository.get(proposalId)?.takeIf { !it.proposal.state.terminal }
            ?.let { agentInboxRepository.update(proposalId, ProposalState.CANCELLED, "Session annulée localement") }
        latestMessage = "Session proposée annulée localement."
        persist()
        agentApiClient?.let { client -> syncWorker.execute { sendPendingAgentAcks(client) } }
    }

    fun disableAgent() {
        val barrierStored = blockAgentForRevocation("agent.disable")
        agentAuthorizationCommitInProgress.set(true)
        try {
            proactiveEffectGeneration.incrementAndGet()
            queueProactiveDisableAndPurge()
            capabilities = capabilities + (CAP_AGENT to false)
            agentRequestGeneration.incrementAndGet()
            agentMandateStore.revoke()
            cancelPendingAgentWork("Agent désactivé")
            agentApiClient = null
            agentStatus = AgentStatus.UNAVAILABLE
            agentStatusMessage = "Agent désactivé — fonctions locales actives"
            val stateStored = runCatching { persistOrThrow("agent.disable") }.isSuccess
            latestMessage = if (barrierStored && stateStored) {
                "Agent désactivé. Les fonctions locales restent actives."
            } else {
                "Agent bloqué dans cette session; stockage en échec, la désactivation n'est pas confirmée."
            }
            touch()
        } catch (_: Exception) {
            latestMessage = "Agent bloqué dans cette session; stockage en échec, la désactivation n'est pas confirmée."
            touch()
        } finally {
            agentAuthorizationCommitInProgress.set(false)
            refreshAgentApiRuntime()
        }
    }

    fun enableAgent() {
        agentAuthorizationCommitInProgress.set(true)
        val previousCapability = capabilities[CAP_AGENT] == true
        val previousMandate = agentMandateStore.current()
        try {
            capabilities = capabilities + (CAP_AGENT to true)
            if (previousMandate.revoked || previousMandate.suspended) {
                agentContextCoordinator.resetCategoryConsents(previousMandate.sharedDataCategories)
                agentMandateStore.replace(previousMandate.copy(revoked = false, suspended = false))
                agentRequestGeneration.incrementAndGet()
            }
            persistOrThrow("agent.enable")
            val eligible = canExplicitOptInClearBarrier()
            val barrierWriteSucceeded = if (eligible) revocationBarrier.clearAfterDurableOptIn(true) else true
            if (!barrierWriteSucceeded) {
                recordPersistenceFailure("agent.enable", PersistFailureKind.STORAGE_IO)
                latestMessage = "Activation enregistrée, mais l'agent reste bloqué car la barrière locale n'a pas pu être levée."
            } else if (!eligible) {
                latestMessage = "Agent activé localement; le mandat est encore suspendu ou expiré."
            } else if (revocationBarrier.isBlocked()) {
                latestMessage = "Agent activé; les autorisations retirées restent bloquées jusqu'à leur nouvelle activation."
            } else {
                latestMessage = "Agent réactivé en mode propositions seulement."
            }
            agentStatus = AgentStatus.UNAVAILABLE
            touch()
        } catch (_: Exception) {
            capabilities = capabilities + (CAP_AGENT to previousCapability)
            agentMandateStore.replace(previousMandate)
            agentRequestGeneration.incrementAndGet()
            latestMessage = "Impossible d'enregistrer la réactivation. L'agent reste bloqué."
            touch()
        } finally {
            agentAuthorizationCommitInProgress.set(false)
            refreshAgentApiRuntime()
        }
    }

    fun suspendAgentMandate() {
        val barrierStored = blockAgentForRevocation("agent.suspend")
        agentAuthorizationCommitInProgress.set(true)
        try {
            proactiveEffectGeneration.incrementAndGet()
            agentMandateStore.replace(agentMandateStore.current().copy(suspended = true))
            agentRequestGeneration.incrementAndGet()
            queueProactiveDisableAndPurge()
            cancelPendingAgentWork("Mandat suspendu")
            val stateStored = runCatching { persistOrThrow("agent.suspend") }.isSuccess
            latestMessage = if (barrierStored && stateStored) {
                "Mandat suspendu. Les planifications de l'agent sont annulées."
            } else {
                "Agent bloqué dans cette session; stockage en échec, la suspension n'est pas confirmée."
            }
            touch()
        } catch (_: Exception) {
            latestMessage = "Agent bloqué dans cette session; stockage en échec, la suspension n'est pas confirmée."
            touch()
        } finally {
            agentAuthorizationCommitInProgress.set(false)
            refreshAgentApiRuntime()
        }
    }

    fun resumeAgentMandate() {
        agentAuthorizationCommitInProgress.set(true)
        val previous = agentMandateStore.current()
        try {
            agentContextCoordinator.resetCategoryConsents(previous.sharedDataCategories)
            agentMandateStore.replace(previous.copy(suspended = false, revoked = false))
            agentRequestGeneration.incrementAndGet()
            persistOrThrow("agent.resume")
            val eligible = canExplicitOptInClearBarrier()
            val barrierWriteSucceeded = if (eligible) revocationBarrier.clearAfterDurableOptIn(true) else true
            if (!barrierWriteSucceeded) {
                recordPersistenceFailure("agent.resume", PersistFailureKind.STORAGE_IO)
                latestMessage = "Mandat enregistré, mais l'agent reste bloqué car la barrière locale n'a pas pu être levée."
            } else if (eligible && !revocationBarrier.isBlocked()) {
                latestMessage = "Mandat réactivé en mode propositions seulement."
            } else {
                latestMessage = "Mandat enregistré, mais l'agent reste bloqué."
            }
            touch()
        } catch (_: Exception) {
            agentMandateStore.replace(previous)
            agentRequestGeneration.incrementAndGet()
            latestMessage = "Impossible d'enregistrer la réactivation du mandat. L'agent reste bloqué."
            touch()
        } finally {
            agentAuthorizationCommitInProgress.set(false)
            refreshAgentApiRuntime()
        }
    }

    fun revokeAgentMandate() {
        val barrierStored = blockAgentForRevocation("agent.revoke")
        agentAuthorizationCommitInProgress.set(true)
        try {
            proactiveEffectGeneration.incrementAndGet()
            queueProactiveDisableAndPurge()
            agentRequestGeneration.incrementAndGet()
            agentMandateStore.revoke()
            cancelPendingAgentWork("Mandat révoqué")
            val stateStored = runCatching { persistOrThrow("agent.revoke") }.isSuccess
            latestMessage = if (barrierStored && stateStored) {
                "Mandat révoqué. Les actions en attente sont annulées."
            } else {
                "Agent bloqué dans cette session; stockage en échec, la révocation n'est pas confirmée."
            }
            touch()
        } catch (_: Exception) {
            latestMessage = "Agent bloqué dans cette session; stockage en échec, la révocation n'est pas confirmée."
            touch()
        } finally {
            agentAuthorizationCommitInProgress.set(false)
            refreshAgentApiRuntime()
        }
    }

    fun setAgentMode(mode: AgentMode) = updateAgentMandate { it.copy(mode = mode) }

    fun setAgentActionAllowed(action: String, allowed: Boolean) = updateAgentMandate { mandate ->
        mandate.copy(allowedActions = if (allowed) mandate.allowedActions + action else mandate.allowedActions - action)
    }

    fun setAgentSharedCategory(category: String, allowed: Boolean) {
        require(category in AgentContextCategory.wireValueSet) { "Unknown context category" }
        if (!allowed) blockAgentForRevocation("agent.category_consent", category)
        updateAgentMandate(
            operation = "agent.category_consent",
            explicitOptIn = allowed,
            revocation = !allowed,
            optInCategory = category.takeIf { allowed },
        ) { mandate ->
            val next = mandate.copy(
                sharedDataCategories = if (allowed) mandate.sharedDataCategories + category else mandate.sharedDataCategories - category,
            )
            agentContextCoordinator.setCategoryConsent(category, allowed)
            if (!allowed) queueProactivePurgeRevoked()
            if (!allowed && category == AgentContextCategory.LOCATION_SUMMARY.wireValue) {
                locationPanelCoordinator.setCoachSummaryConsent(false)
            }
            next
        }
    }

    fun setAgentFocusWindow(
        days: Set<Int>,
        start: String,
        end: String,
        maxMinutes: Int,
        maxSessionsPerDay: Int,
        maxChangesPerDay: Int,
    ) {
        updateAgentMandate(operation = "agent.focus_window") { current ->
            current.copy(
                focusWindow = current.focusWindow.copy(
                    days = days,
                    start = start,
                    end = end,
                    maxMinutesPerSession = maxMinutes,
                    maxSessionsPerDay = maxSessionsPerDay,
                ),
                maxChangesPerDay = maxChangesPerDay,
            )
        }
    }

    private fun updateAgentMandate(
        operation: String = "agent.mandate",
        explicitOptIn: Boolean = false,
        revocation: Boolean = false,
        optInCategory: String? = null,
        transform: (AgentMandate) -> AgentMandate,
    ) {
        agentAuthorizationCommitInProgress.set(true)
        val current = agentMandateStore.current()
        try {
            val updated = transform(current)
            replaceAgentMandate(updated, refreshRuntime = false)
            try {
                persistOrThrow(operation)
            } catch (error: Exception) {
                if (!revocation) replaceAgentMandate(current, refreshRuntime = false)
                latestMessage = if (revocation) {
                    "Agent bloqué dans cette session; stockage en échec, le retrait n'est pas confirmé."
                } else {
                    "Impossible d'enregistrer la modification du mandat."
                }
                touch()
                return
            }
            if (explicitOptIn && optInCategory != null &&
                !revocationBarrier.clearCategoryAfterDurableOptIn(optInCategory, true)
            ) {
                recordPersistenceFailure(operation, PersistFailureKind.STORAGE_IO)
                latestMessage = "Consentement enregistré, mais l'agent reste bloqué car la barrière locale n'a pas pu être levée."
            } else if (revocation && revocationBarrier.isBlocked()) {
                latestMessage = "Autorisation retirée durablement; l'agent reste bloqué jusqu'à une nouvelle activation explicite."
            } else if (explicitOptIn && revocationBarrier.isBlocked()) {
                latestMessage = "Consentement enregistré; l'agent reste bloqué par une autre révocation en attente."
            } else {
                latestMessage = "Mandat enregistré · ${agentMandate.version}."
            }
            touch()
        } catch (error: Exception) {
            latestMessage = if (error is PersistOperationException) {
                "Impossible d'enregistrer le changement; l'agent reste bloqué et le retrait n'est pas confirmé."
            } else {
                "Modification du mandat invalide."
            }
            touch()
        } finally {
            agentAuthorizationCommitInProgress.set(false)
            refreshAgentApiRuntime()
        }
    }

    private fun replaceAgentMandate(mandate: AgentMandate, refreshRuntime: Boolean = true) {
        agentMandateStore.replace(mandate.copy(id = agentMandateStore.current().id))
        agentRequestGeneration.incrementAndGet()
        if (mandate.revoked || mandate.suspended) queueProactiveDisableAndPurge()
        else queueProactivePurgeRevoked()
        if (refreshRuntime) refreshAgentApiRuntime()
    }

    private fun cancelPendingAgentWork(reason: String) {
        agentHomeStateSnapshot.focusProposal?.let { card ->
            if (card.state in setOf(AgentFocusCardState.PROPOSED, AgentFocusCardState.PLANNED, AgentFocusCardState.RUNNING, AgentFocusCardState.PAUSED)) {
                cancelAgentFocusProposal(card.proposalId)
            }
        }
        agentInboxRepository.all().filterNot { it.proposal.state.terminal }.forEach { entry ->
            agentInboxRepository.update(entry.id, ProposalState.CANCELLED, reason)
        }
    }

    private fun agentVerificationContext(mandate: AgentMandate): AgentVerificationContext {
        val now = clock.nowMillis()
        val usageConsent = usageConsentActive && usagePermissionGranted
        val observed = if (usageConsent) usageStores.currentConsent().allowlistedPackageNames else emptySet()
        val protected = AndroidProtectedPackages.protectedPackages(appContext, observed)
        val mandateZone = ZoneId.of(mandate.focusWindow.timezone)
        val localDate = Instant.ofEpochMilli(now).atZone(mandateZone).toLocalDate()
        val dayStart = localDate.atStartOfDay(mandateZone).toInstant().toEpochMilli()
        val journal = agentEffectJournal.entries().filter { it.atUtcMillis >= dayStart }
        val runningRef = focusEngine.currentSession()?.takeIf { it.status in setOf(FocusStatus.RUNNING, FocusStatus.PAUSED) }
            ?.let { it.directiveId ?: it.id }
        val card = agentHomeStateSnapshot.focusProposal
        val capabilities = buildSet {
            if (this@AtlasController.capabilities[CAP_COACH] == true) add("coach")
            if (this@AtlasController.capabilities[CAP_FOCUS] == true) add("focus")
            if (this@AtlasController.capabilities[CAP_HABITS] == true) add("habit")
            if (this@AtlasController.capabilities[CAP_CONTENT] == true) add("content")
            if (this@AtlasController.capabilities[CAP_FOCUS_ROUTINES] == true) add("focusRoutines")
        }
        val focusCount = journal.count { it.actionType == "focus.start" && it.state == ProposalState.EXECUTED }
        return AgentVerificationContext(
            nowUtcMillis = now,
            agentEnabled = isAgentRequestAllowed(),
            emergencyPaused = isEmergencyPaused,
            capabilities = capabilities,
            observedPackages = observed,
            vitalPackages = protected + policy.vitalPackageNames,
            changesToday = journal.count { it.state == ProposalState.EXECUTED },
            focusSessionsToday = focusCount,
            lastActionAtUtcByType = agentEffectJournal.cooldownHistoryByActionType(),
            existingFocusProposalIds = card?.takeIf { it.state != AgentFocusCardState.CANCELLED }?.let { setOf(it.proposalId) }.orEmpty(),
            cancelledFocusProposalIds = agentHomeStateSnapshot.cancelledFocusProposalIds +
                card?.takeIf { it.state == AgentFocusCardState.CANCELLED }?.let { setOf(it.proposalId) }.orEmpty(),
            runningFocusSessionRef = runningRef,
            focusMinutesByProposal = card?.let { mapOf(it.proposalId to it.focusMinutes) }.orEmpty(),
        )
    }

    private fun isAllowedBackendUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
    }.getOrDefault(false)

    private fun isAllowedAgentBackendUrl(value: String): Boolean = isAllowedBackendUrl(value) && runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) || (
            uri.scheme.equals("http", ignoreCase = true) &&
                uri.host.equals("10.0.2.2", ignoreCase = true) &&
                appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
            )
    }.getOrDefault(false)

    fun respond(choice: DirectiveChoice) {
        if (choice == DirectiveChoice.START && capabilities[CAP_FOCUS] != true) {
            latestMessage = "Le focus est désactivé dans les paramètres."
            touch()
            return
        }
        if (capabilities[CAP_COACH] != true) {
            latestMessage = "Le coach local est désactivé dans les paramètres."
            touch()
            return
        }
        val activeDirective = directive
        try {
            val result = coach.respond(activeDirective.id, choice)
            directiveSnapshot = coach.currentDirective()
            latestMessage = result.message
            enqueueSyncEvent(
                SyncEventType.DIRECTIVE_RESPONDED,
                SyncDataCategory.COACH_INTERACTION,
                mapOf("choice" to choice.contractValue),
            )
            sendRemoteCoachResponse(choice)
            if (choice == DirectiveChoice.START) {
                archiveCurrentTerminalFocus()
                val seconds = ((result.requestedDurationMinutes ?: 10) * 60L).coerceAtLeast(1L)
                focusEngine.start(FocusPlan.custom(seconds).copy(presetId = "directive-${activeDirective.id}"), activeDirective.id)
                enqueueSyncEvent(SyncEventType.FOCUS_STARTED, SyncDataCategory.FOCUS)
                navigationState.navigate(AtlasScreenId.FOCUS)
            }
        } catch (error: IllegalArgumentException) {
            latestMessage = error.message ?: "Cette réponse n'est pas disponible."
        }
        persist()
    }

    override fun setHomeDirective(proposal: ProposalV2): AgentActionOutcome {
        agentHomeStateSnapshot = agentHomeStateSnapshot.withRemoteDirective(proposal)
        recordAppliedLocalEffect(proposal)
        persist()
        return AgentActionOutcome(true, "Directive HOME enregistrée localement.")
    }

    override fun reorderHomeCards(proposal: ProposalV2): AgentActionOutcome {
        val requested = (proposal.parameters["order"] as List<*>).filterIsInstance<String>()
        val completed = requested + AgentHomeState.DEFAULT_CARD_ORDER.filterNot(requested::contains)
        agentHomeStateSnapshot = agentHomeStateSnapshot.copy(cardOrder = completed)
        recordAppliedLocalEffect(proposal)
        persist()
        return AgentActionOutcome(true, "Ordre des cartes HOME enregistré; les autres cartes restent visibles.")
    }

    override fun proposeFocus(proposal: ProposalV2): AgentActionOutcome {
        val previous = agentHomeStateSnapshot.focusProposal
        if (previous?.state in setOf(AgentFocusCardState.PLANNED, AgentFocusCardState.RUNNING, AgentFocusCardState.PAUSED)) {
            return AgentActionOutcome(false, "Une session Focus agent est déjà planifiée ou active; annulez-la avant de la remplacer.")
        }
        previous?.let { cancelAgentFocusProposal(it.proposalId) }
        val card = AgentFocusProposalCard(
            proposalId = proposal.proposalId,
            label = proposal.parameters["label"] as? String ?: "Session proposée",
            focusMinutes = (proposal.parameters["focus_minutes"] as Number).toInt(),
            breakMinutes = (proposal.parameters["break_minutes"] as Number).toInt(),
            cycles = (proposal.parameters["cycles"] as Number).toInt(),
            longBreakMinutes = 15,
            longBreakEveryCycles = minOf(4, (proposal.parameters["cycles"] as Number).toInt()),
        )
        agentHomeStateSnapshot = agentHomeStateSnapshot.copy(focusProposal = card)
        recordAppliedLocalEffect(proposal)
        persist()
        return AgentActionOutcome(true, "Session proposée enregistrée localement.")
    }

    override fun planFocus(proposal: ProposalV2): AgentActionOutcome {
        if (capabilities[CAP_FOCUS_ROUTINES] != true) {
            return AgentActionOutcome(false, "La planification inexacte des routines Focus est désactivée.")
        }
        val previous = agentHomeStateSnapshot.focusProposal
        if (previous?.state in setOf(AgentFocusCardState.RUNNING, AgentFocusCardState.PAUSED)) {
            return AgentActionOutcome(false, "Une session Focus agent est active; terminez-la avant de planifier une autre.")
        }
        val zone = ZoneId.of(proposal.parameters.getValue("timezone") as String)
        val startAt = Instant.parse(proposal.parameters.getValue("start_at") as String).atZone(zone)
        val minutes = (proposal.parameters.getValue("focus_minutes") as Number).toInt()
        val endAt = startAt.plusMinutes(minutes.toLong())
        val schedule = RoutineSchedule.daily(
            start = startAt.toLocalTime(),
            end = endAt.toLocalTime(),
            startDate = startAt.toLocalDate(),
            isRecurring = false,
        )
        val routine = focusRoutineRepository.create(
            name = proposal.parameters["label"] as? String ?: "Session proposée",
            schedule = schedule,
            focusMinutes = minutes,
            preauthorizedStart = false,
        )
        focusRoutinesSnapshot = focusRoutineRepository.list()
        routineAlarms.schedule(routine, ZonedDateTime.now(zone), zone)
        previous?.let { cancelAgentFocusProposal(it.proposalId) }
        val card = AgentFocusProposalCard(
            proposalId = proposal.proposalId,
            label = routine.name,
            focusMinutes = minutes,
            breakMinutes = (proposal.parameters.getValue("break_minutes") as Number).toInt(),
            cycles = (proposal.parameters.getValue("cycles") as Number).toInt(),
            state = AgentFocusCardState.PLANNED,
            startAtUtcMillis = startAt.toInstant().toEpochMilli(),
            routineId = routine.id,
            longBreakMinutes = 15,
            longBreakEveryCycles = minOf(4, (proposal.parameters.getValue("cycles") as Number).toInt()),
        )
        agentHomeStateSnapshot = agentHomeStateSnapshot.copy(focusProposal = card)
        recordAppliedLocalEffect(proposal)
        persist()
        return AgentActionOutcome(true, "Session planifiée avec une alarme inexacte; le démarrage demandera une confirmation.")
    }

    override fun startFocus(proposal: ProposalV2): AgentActionOutcome {
        val ref = proposal.parameters.getValue("session_ref") as String
        val card = agentHomeStateSnapshot.focusProposal?.takeIf {
            it.proposalId == ref && it.state !in setOf(AgentFocusCardState.CANCELLED, AgentFocusCardState.FINISHED)
        } ?: return AgentActionOutcome(false, "Session proposée absente ou annulée.")
        val active = focusEngine.currentSession()
        if (active?.status in setOf(FocusStatus.RUNNING, FocusStatus.PAUSED)) {
            return AgentActionOutcome(false, "Une autre session Focus est déjà active.")
        }
        archiveCurrentTerminalFocus()
        val plan = FocusPlan(
            presetId = "agent-${card.proposalId}",
            workSeconds = card.focusMinutes * 60L,
            shortBreakSeconds = card.breakMinutes.coerceAtLeast(1) * 60L,
            longBreakSeconds = card.longBreakMinutes * 60L,
            longBreakEveryCycles = card.longBreakEveryCycles,
            totalWorkCycles = card.cycles,
            continuous = card.continuous,
            taskTitle = card.label,
        )
        val started = focusEngine.start(plan, card.proposalId)
        focusSnapshot = started
        if (capabilities[CAP_FOCUS_NOTIFICATIONS] == true) phaseAlarms.schedule(started)
        agentHomeStateSnapshot = agentHomeStateSnapshot.copy(focusProposal = card.copy(state = AgentFocusCardState.RUNNING))
        recordAppliedLocalEffect(proposal)
        enqueueSyncEvent(SyncEventType.FOCUS_STARTED, SyncDataCategory.FOCUS)
        persist()
        return AgentActionOutcome(true, "Focus démarré localement sans ouvrir d'activité.")
    }

    override fun pauseFocus(proposal: ProposalV2): AgentActionOutcome {
        val active = focusEngine.currentSession() ?: return AgentActionOutcome(false, "Aucune session Focus active.")
        if (active.status != FocusStatus.RUNNING) return AgentActionOutcome(false, "La session Focus n'est pas en cours.")
        val ref = proposal.parameters.getValue("session_ref") as String
        if ((active.directiveId ?: active.id) != ref) return AgentActionOutcome(false, "La référence ne correspond pas à la session active.")
        focusSnapshot = focusEngine.pause(active.id)
        phaseAlarms.cancel(active.id)
        focusNotifications.cancel(active.id)
        agentHomeStateSnapshot.focusProposal?.takeIf { it.proposalId == ref }?.let { card ->
            agentHomeStateSnapshot = agentHomeStateSnapshot.copy(focusProposal = card.copy(state = AgentFocusCardState.PAUSED))
        }
        recordAppliedLocalEffect(proposal)
        persist()
        return AgentActionOutcome(true, "Session Focus mise en pause.")
    }

    override fun suggestHabit(proposal: ProposalV2): AgentActionOutcome {
        val name = proposal.parameters.getValue("name") as String
        val schedule = proposal.parameters.getValue("schedule") as String
        agentSuggestionsSnapshot = (agentSuggestionsSnapshot + "Habitude proposée : $name · $schedule").takeLast(100)
        recordAppliedLocalEffect(proposal)
        persist()
        return AgentActionOutcome(true, "Suggestion d'habitude enregistrée; aucune habitude n'a été créée sans confirmation.")
    }

    override fun proposeAppPolicy(proposal: ProposalV2): AgentActionOutcome {
        val packageRef = proposal.parameters.getValue("package_ref") as String
        val minutes = proposal.parameters.getValue("daily_limit_minutes") as Number
        agentSuggestionsSnapshot = (agentSuggestionsSnapshot +
            "Politique proposée pour $packageRef : ${minutes.toInt()} min/jour · aucune règle modifiée.").takeLast(100)
        recordAppliedLocalEffect(proposal)
        persist()
        return AgentActionOutcome(true, "Politique suggérée localement; elle n'est pas encore appliquée.")
    }

    override fun adjustAppPolicy(proposal: ProposalV2): AgentActionOutcome = AgentActionOutcome(
        false,
        "La règle locale actuelle mesure les ouvertures, pas une durée quotidienne; aucun plafond fictif n'a été appliqué.",
    )

    override fun recommendContent(proposal: ProposalV2): AgentActionOutcome {
        val contentId = proposal.parameters["content_id"] as? String
        val url = proposal.parameters["url"] as? String
        val title = proposal.parameters.getValue("title") as String
        if (contentId != null && catalog.find(contentId) == null) {
            return AgentActionOutcome(false, "Le contenu référencé n'existe pas dans la bibliothèque locale.")
        }
        if (url != null && !ContentUrlValidator.isAllowedSearchOrVideo(url)) {
            return AgentActionOutcome(false, "URL refusée par la liste de domaines autorisés.")
        }
        agentHomeStateSnapshot = agentHomeStateSnapshot.copy(contentRecommendation = AgentContentCard(contentId, url, title))
        recordAppliedLocalEffect(proposal)
        persist()
        return AgentActionOutcome(true, "Contenu recommandé sur HOME; son ouverture reste manuelle.")
    }

    override fun reconcile(proposal: ProposalV2, idempotencyKey: String): AgentEffectReconciliation {
        if (proposal.idempotencyKey != idempotencyKey) return AgentEffectReconciliation.UNKNOWN
        if (idempotencyKey in appliedLocalAgentEffectKeys) return AgentEffectReconciliation.APPLIED
        return when (AgentActionType.parse(proposal.actionType)) {
            AgentActionType.HOME_SET_DIRECTIVE,
            AgentActionType.HOME_REORDER_CARDS,
            AgentActionType.HABIT_SUGGEST,
            AgentActionType.APP_POLICY_PROPOSE,
            AgentActionType.APP_POLICY_ADJUST,
            AgentActionType.CONTENT_RECOMMEND -> AgentEffectReconciliation.NOT_APPLIED
            // Focus also schedules or cancels Android alarms; absence of the durable marker
            // cannot prove whether that platform side effect was observed before a crash.
            AgentActionType.FOCUS_PROPOSE,
            AgentActionType.FOCUS_PLAN,
            AgentActionType.FOCUS_START,
            AgentActionType.FOCUS_PAUSE,
            null -> AgentEffectReconciliation.UNKNOWN
        }
    }

    private fun recordAppliedLocalEffect(proposal: ProposalV2) {
        appliedLocalAgentEffectKeys += proposal.idempotencyKey
    }

    fun resumeCoach() {
        if (capabilities[CAP_COACH] != true) {
            latestMessage = "Le coach local est désactivé dans les paramètres."
            touch()
            return
        }
        coach.resume()
        latestMessage = "Le coach local est réactivé."
        persist()
    }

    fun startFocus(preset: FocusPreset) {
        if (capabilities[CAP_FOCUS] != true) {
            latestMessage = "Le focus est désactivé dans les paramètres."
            touch()
            return
        }
        val reefConfig = when (preset) {
            FocusPreset.POMODORO_25_5 -> ReefPomodoroConfig.DEFAULT
            FocusPreset.VARIANT_10_2 -> ReefPomodoroConfig(10, 2, 15, 4)
        }.toPomodoroConfig()
        startFocus(reefConfig.toFocusPlan(preset.presetId))
    }

    /** Stable host API for preset, custom and deterministic integration-test plans. */
    fun startFocus(plan: FocusPlan) {
        if (capabilities[CAP_FOCUS] != true) {
            latestMessage = "Le focus est désactivé dans les paramètres."
            touch()
            return
        }
        archiveCurrentTerminalFocus()
        val started = focusEngine.start(plan, directive.id)
        enqueueSyncEvent(SyncEventType.FOCUS_STARTED, SyncDataCategory.FOCUS)
        if (capabilities[CAP_FOCUS_NOTIFICATIONS] == true) phaseAlarms.schedule(started)
        latestMessage = if (plan.continuous) {
            "Focus continu démarré · ${plan.workSeconds}s de concentration par cycle."
        } else {
            "Focus démarré · ${plan.totalWorkCycles} cycle${if (plan.totalWorkCycles == 1) "" else "s"} au total."
        }
        navigationState.navigate(AtlasScreenId.FOCUS)
        persist()
    }

    fun startFocus(config: ReefPomodoroConfig) {
        if (capabilities[CAP_FOCUS] != true) {
            latestMessage = "Le focus est désactivé dans les paramètres."
            touch()
            return
        }
        val plan = config.toPomodoroConfig().toFocusPlan(
            "reef-pomodoro-${config.focusMinutes}-${config.shortBreakMinutes}-${config.longBreakMinutes}-${config.cycles}",
        )
        startFocus(plan)
        latestMessage = if (config.continuous) {
            "Pomodoro démarré · ${config.focusMinutes} min de concentration, " +
                "pause courte de ${config.shortBreakMinutes} min · mode continu."
        } else {
            "Pomodoro démarré · ${config.focusMinutes} min de concentration, " +
                "pause courte de ${config.shortBreakMinutes} min · " +
                "${plan.totalWorkCycles} cycles de travail au total."
        }
        persist()
    }

    fun startFocus(config: ReefPomodoroConfig, taskTitle: String) {
        val normalizedTitle = taskTitle.trim()
        if (normalizedTitle.isEmpty() || normalizedTitle.length > FocusPlan.MAX_TASK_TITLE_LENGTH) {
            latestMessage = "Le titre de tâche doit contenir entre 1 et ${FocusPlan.MAX_TASK_TITLE_LENGTH} caractères."
            touch()
            return
        }
        if (capabilities[CAP_FOCUS] != true) {
            latestMessage = "Le focus est désactivé dans les paramètres."
            touch()
            return
        }
        val plan = config.toPomodoroConfig().toFocusPlan(
            "pomodoro-${config.focusMinutes}-${config.shortBreakMinutes}-${config.longBreakMinutes}-${config.cycles}",
        ).copy(taskTitle = normalizedTitle)
        startFocus(plan)
        latestMessage = if (config.continuous) {
            "Pomodoro démarré · $normalizedTitle · ${config.focusMinutes} min par cycle, mode continu."
        } else {
            "Pomodoro démarré · $normalizedTitle · ${config.focusMinutes} min par cycle · ${plan.totalWorkCycles} cycles."
        }
        persist()
    }

    fun startDemoFocus() {
        if (capabilities[CAP_FOCUS] != true) {
            latestMessage = "Le focus est désactivé dans les paramètres."
            touch()
            return
        }
        archiveCurrentTerminalFocus()
        startFocus(FocusPlan.custom(20).copy(presetId = "demo-20-seconds"))
    }

    fun tickFocus() {
        if (!CapabilityRules.mayTickFocus(capabilities, focusSnapshot?.status)) return
        val previous = focusSnapshot
        val current = focusEngine.currentSession() ?: return
        focusSnapshot = current
        val transitioned = previous?.id != current.id || previous?.phase != current.phase || previous?.status != current.status
        if (transitioned && previous?.status == FocusStatus.RUNNING &&
            (previous.phase != current.phase || current.status == FocusStatus.FINISHED)
        ) {
            if (capabilities[CAP_FOCUS_NOTIFICATIONS] == true) {
                val result = focusNotifications.notify(
                    PhaseTransition(
                        sessionId = current.id,
                        sequence = 0,
                        atUtcMillis = current.updatedAtUtcMillis,
                        endedPhase = previous.phase,
                        nextPhase = current.phase.takeIf { current.status == FocusStatus.RUNNING },
                        completedWorkCycles = current.completedWorkCycles,
                    ),
                )
                if (result != PhaseNotificationResult.POSTED) {
                    latestMessage = "Phase terminée. Les notifications sont indisponibles; le compte à rebours continue dans ATLAS."
                }
            }
        }
        if (transitioned && current.status == FocusStatus.FINISHED) archiveFocusSession(current)
        if (transitioned && current.status == FocusStatus.FINISHED) {
            enqueueSyncEvent(
                SyncEventType.FOCUS_COMPLETED,
                SyncDataCategory.FOCUS,
                mapOf("completed_work_cycles" to current.completedWorkCycles),
            )
            recordFocusCompletion(current)
        }
        if (transitioned && capabilities[CAP_FOCUS_NOTIFICATIONS] == true) {
            if (current.status == FocusStatus.RUNNING) phaseAlarms.schedule(current) else phaseAlarms.cancel(current.id)
        }
        val elapsed = clock.monotonicMillis()
        val checkpointDue = current.status == dev.atlas.coach.focus.FocusStatus.RUNNING &&
            elapsed - lastFocusCheckpointAtElapsed >= FOCUS_CHECKPOINT_INTERVAL_MILLIS
        if (transitioned || checkpointDue) {
            persist(durable = transitioned)
        }
    }

    fun toggleFocusPause() {
        if (capabilities[CAP_FOCUS] != true) {
            latestMessage = "Le focus est désactivé. Réactivez-le pour reprendre la session mise en pause."
            touch()
            return
        }
        val session = focusSession ?: return
        if (session.status == FocusStatus.RUNNING) {
            focusEngine.pause(session.id)
            enqueueSyncEvent(SyncEventType.FOCUS_PAUSED, SyncDataCategory.FOCUS)
        }
        else if (session.status == FocusStatus.PAUSED) focusEngine.resume(session.id)
        focusSnapshot = focusEngine.currentSession()
        focusSnapshot?.let { current ->
            if (current.status == FocusStatus.RUNNING && capabilities[CAP_FOCUS_NOTIFICATIONS] == true) {
                phaseAlarms.schedule(current)
            } else {
                phaseAlarms.cancel(current.id)
                focusNotifications.cancel(current.id)
            }
        }
        persist()
    }

    fun cancelFocus() {
        focusSession?.let { phaseAlarms.cancel(it.id); focusNotifications.cancel(it.id) }
        focusSession?.let { focusEngine.cancel(it.id)?.let(::archiveFocusSession) }
        focusSession?.directiveId?.let { ref ->
            agentHomeStateSnapshot.focusProposal?.takeIf { it.proposalId == ref }?.let { card ->
                agentHomeStateSnapshot = agentHomeStateSnapshot.copy(
                    focusProposal = card.copy(state = AgentFocusCardState.CANCELLED),
                    cancelledFocusProposalIds = agentHomeStateSnapshot.cancelledFocusProposalIds + ref,
                )
                agentInboxRepository.get(ref)?.takeIf { !it.proposal.state.terminal }
                    ?.let { agentInboxRepository.update(ref, ProposalState.CANCELLED, "Session annulée localement") }
            }
        }
        focusSnapshot = focusEngine.currentSession()
        latestMessage = "Session annulée."
        persist()
    }

    fun finishFocus() {
        focusSession?.let { phaseAlarms.cancel(it.id); focusNotifications.cancel(it.id) }
        val finished = focusSession?.let { focusEngine.finish(it.id) }
        finished?.let(::archiveFocusSession)
        focusSnapshot = focusEngine.currentSession()
        if (focusSnapshot?.status == FocusStatus.FINISHED) {
            enqueueSyncEvent(
                SyncEventType.FOCUS_COMPLETED,
                SyncDataCategory.FOCUS,
                mapOf("completed_work_cycles" to (focusSnapshot?.completedWorkCycles ?: 0)),
            )
            recordFocusCompletion(checkNotNull(focusSnapshot))
        }
        latestMessage = if (focusSnapshot?.completedWorkCycles ?: 0 >= (focusSnapshot?.totalWorkCycles ?: Int.MAX_VALUE) && focusSnapshot?.continuous != true) {
            "Session terminée. Voulez-vous la relier à une habitude ?"
        } else {
            "Session terminée avant le dernier cycle. Aucun lien d'habitude ne sera proposé."
        }
        persist()
    }

    fun confirmHabitLink(habitId: String) {
        if (!CapabilityRules.mayConfirmHabitLink(capabilities)) {
            latestMessage = "Les habitudes sont désactivées dans les paramètres."
            touch()
            return
        }
        val session = focusSession ?: return
        when (val result = focusSessionLinker.link(
            session = session,
            condition = FocusHabitLinkCondition.AllWorkCyclesCompleted,
            habitId = habitId,
            habitConfirmed = true,
            linkedAtUtcMillis = clock.nowMillis(),
        )) {
            is FocusLinkResult.Recorded -> {
                habitLinkPolicy.confirmLink(session.id, habitId, LocalDate.now())
                enqueueSyncEvent(SyncEventType.HABIT_CHECKIN, SyncDataCategory.HABIT)
                latestMessage = "Habitude cochée après votre confirmation."
            }
            is FocusLinkResult.AlreadyRecorded -> latestMessage = "Cette session a déjà été reliée; aucun deuxième pointage n'a été ajouté."
            FocusLinkResult.ConditionNotMet -> latestMessage = "Le lien est disponible après le dernier cycle de travail d'une session finie."
            FocusLinkResult.HabitConfirmationRequired -> latestMessage = "Confirmez explicitement le lien avec l'habitude."
        }
        persist()
    }

    fun addHabit(
        name: String,
        schedule: HabitSchedule = HabitSchedule.daily(),
        reminder: HabitReminder? = null,
    ) {
        if (capabilities[CAP_HABITS] != true) {
            latestMessage = "Les habitudes sont désactivées dans les paramètres."
            touch()
            return
        }
        try {
            val habit = habits.add(name, schedule)
            if (reminder != null) habits.setReminder(habit.id, reminder)
            if (reminder?.enabled == true && capabilities[CAP_HABIT_REMINDERS] == true) {
                val result = habitReminderScheduler.schedule(habit.id, habit.name, reminder)
                result.message?.let { latestMessage = it }
            }
            latestMessage = "Habitude ajoutée."
            persist()
        } catch (error: IllegalArgumentException) {
            latestMessage = error.message ?: "Nom d'habitude invalide."
            touch()
        }
    }

    fun toggleHabitToday(habit: Habit) {
        if (capabilities[CAP_HABITS] != true) {
            latestMessage = "Les habitudes sont désactivées dans les paramètres."
            touch()
            return
        }
        val today = LocalDate.now()
        val checked = habits.checkIns(habit.id).any { it.date == today }
        if (checked) {
            habits.undoCheckIn(habit.id, today)
            latestMessage = "Validation annulée."
        } else {
            habits.checkIn(habit.id, today)
            enqueueSyncEvent(SyncEventType.HABIT_CHECKIN, SyncDataCategory.HABIT)
            latestMessage = "Habitude validée pour aujourd'hui."
        }
        persist()
    }

    fun updateHabit(habitId: String, name: String, schedule: HabitSchedule) {
        if (capabilities[CAP_HABITS] != true) return
        val current = habits.list().firstOrNull { it.id == habitId } ?: return
        try {
            val updated = current.copy(name = name.trim(), schedule = schedule)
            habits.update(updated)
            updated.reminder?.takeIf { it.enabled && capabilities[CAP_HABIT_REMINDERS] == true }?.let {
                habitReminderScheduler.schedule(updated.id, updated.name, it)
            }
            latestMessage = "Planning de l'habitude mis à jour."
            persist()
        } catch (error: IllegalArgumentException) {
            latestMessage = error.message ?: "Planning invalide."
            touch()
        }
    }

    fun archiveHabit(habitId: String) {
        runCatching { habits.archive(habitId) }
        habitReminderScheduler.cancel(habitId)
        latestMessage = "Habitude supprimée de la liste active. Son historique local reste dans l'export."
        persist()
    }

    fun setHabitReminder(habitId: String, reminder: HabitReminder?) {
        if (capabilities[CAP_HABITS] != true) return
        val saved = habits.setReminder(habitId, reminder)
        var message = if (reminder?.enabled == true) "Rappel enregistré." else "Rappel désactivé."
        if (reminder?.enabled == true && capabilities[CAP_HABIT_REMINDERS] == true) {
            val result = habitReminderScheduler.schedule(saved.id, saved.name, reminder)
            result.message?.let { message = it }
        } else {
            habitReminderScheduler.cancel(habitId)
            if (reminder?.enabled == true) message = "Rappel enregistré, mais la capacité Rappels d'habitudes est désactivée."
        }
        latestMessage = message
        persist()
    }

    fun changeHistoryDays(days: Int) {
        historyDays = if (days == 30) 30 else 7
        touch()
    }

    fun toggleLauncherPin(packageName: String) {
        if (packageName.isBlank()) return
        latestMessage = if (launcherState.togglePin(packageName)) "Application épinglée à l'accueil ATLAS." else "Application retirée des épingles ATLAS."
        persist()
    }

    fun syncLauncherHomeRole(isAtlasDefault: Boolean) {
        launcherState.refreshHomeRole(isAtlasDefault)
        if (capabilities[CAP_LAUNCHER_HOME] != isAtlasDefault) {
            capabilities = capabilities + (CAP_LAUNCHER_HOME to isAtlasDefault)
            persist()
        } else {
            touch()
        }
    }

    fun addFocusRoutine(name: String, start: String, end: String, weekdaysOnly: Boolean, focusMinutes: Int = 25): Boolean {
        if (capabilities[CAP_FOCUS_ROUTINES] != true) return false
        val normalizedName = name.trim()
        val startTime = runCatching { LocalTime.parse(start.trim()) }.getOrNull()
        val endTime = runCatching { LocalTime.parse(end.trim()) }.getOrNull()
        if (normalizedName.isEmpty() || startTime == null || endTime == null) {
            latestMessage = "Indiquez un nom et deux heures valides au format HH:mm."
            touch()
            return false
        }
        val schedule = if (weekdaysOnly) RoutineSchedule.weekdays(startTime, endTime) else RoutineSchedule.daily(startTime, endTime)
        val routine = focusRoutineRepository.create(normalizedName, schedule, focusMinutes.coerceIn(1, 180))
        focusRoutinesSnapshot = focusRoutineRepository.list().takeLast(MAX_FOCUS_ROUTINES)
        routineAlarms.schedule(routine, ZonedDateTime.now(), ZoneId.systemDefault())
        latestMessage = "Routine locale ajoutée. Elle proposera un focus pendant sa plage horaire."
        persist()
        return true
    }

    fun deleteFocusRoutine(id: String) {
        focusRoutineRepository.delete(id)
        focusRoutinesSnapshot = focusRoutineRepository.list()
        routineAlarms.cancel(id)
        persist()
    }

    fun updateFocusRoutine(id: String, name: String, start: String, end: String, weekdaysOnly: Boolean, focusMinutes: Int = 25): Boolean {
        if (capabilities[CAP_FOCUS_ROUTINES] != true) return false
        val current = focusRoutineRepository.find(id) ?: return false
        val startTime = runCatching { LocalTime.parse(start.trim()) }.getOrNull()
        val endTime = runCatching { LocalTime.parse(end.trim()) }.getOrNull()
        val normalizedName = name.trim()
        if (normalizedName.isEmpty() || startTime == null || endTime == null) {
            latestMessage = "Indiquez un nom et deux heures valides au format HH:mm."
            touch()
            return false
        }
        val schedule = if (weekdaysOnly) RoutineSchedule.weekdays(startTime, endTime) else RoutineSchedule.daily(startTime, endTime)
        val updated = current.copy(name = normalizedName, schedule = schedule, focusMinutes = focusMinutes.coerceIn(1, 180))
        focusRoutineRepository.update(updated)
        focusRoutinesSnapshot = focusRoutineRepository.list()
        routineAlarms.schedule(updated, ZonedDateTime.now(), ZoneId.systemDefault())
        latestMessage = "Routine mise à jour. Elle reste une proposition à confirmer."
        persist()
        return true
    }

    fun setFocusRoutineEnabled(id: String, enabled: Boolean) {
        val routine = focusRoutineRepository.setEnabled(id, enabled) ?: return
        focusRoutinesSnapshot = focusRoutineRepository.list()
        if (enabled && capabilities[CAP_FOCUS_ROUTINES] == true) {
            routineAlarms.schedule(routine, ZonedDateTime.now(), ZoneId.systemDefault())
        } else {
            routineAlarms.cancel(id)
        }
        latestMessage = if (enabled) "Routine activée; elle proposera une session." else "Routine désactivée; son alarme a été annulée."
        persist()
    }

    fun nextFocusRoutineTrigger(id: String, after: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? =
        focusRoutineRepository.nextTrigger(id, after, ZoneId.systemDefault())

    fun activeFocusRoutines(now: ZonedDateTime = ZonedDateTime.now()): List<FocusRoutine> =
        RoutineEvaluator.active(focusRoutinesSnapshot, now).map { it.first }

    fun startFocusRoutine(id: String) {
        if (capabilities[CAP_FOCUS_ROUTINES] != true || capabilities[CAP_FOCUS] != true) return
        val routine = focusRoutinesSnapshot.firstOrNull { it.id == id && it.isEnabled } ?: return
        if (routine !in activeFocusRoutines()) {
            latestMessage = "Cette routine est hors plage horaire; aucun focus n'a été démarré."
            touch()
            return
        }
        startFocus(ReefPomodoroConfig(routine.focusMinutes, 5, 15, 4))
    }

    fun habitHistorySummary(habit: Habit): String {
        val history = habits.history(habit.id, LocalDate.now(), historyDays)
        val strength = (habits.strength(habit.id, LocalDate.now()) * 100).toInt()
        val rate = (history.completionRate * 100).toInt()
        return "${history.completedDueDays}/${history.dueDays} échéances · taux ${rate}% · ${history.notDueDays} jours sans échéance · série ${history.currentStreak} (meilleure ${history.bestStreak}) · force ${strength}%"
    }

    fun exportHabits(): String = habits.exportCsv()

    fun addContentLink(title: String, url: String): Boolean {
        if (capabilities[CAP_CONTENT] != true) return false
        return try {
            catalog.addManualLink(title, url)
            latestMessage = "Lien vidéo ajouté à la bibliothèque."
            persist()
            true
        } catch (error: IllegalArgumentException) {
            latestMessage = error.message ?: "Lien vidéo invalide."
            touch()
            false
        }
    }

    fun removeContent(id: String) {
        if (catalog.remove(id)) {
            latestMessage = "Élément supprimé de la bibliothèque."
            persist()
        }
    }

    fun pinContent(id: String, pinned: Boolean) {
        if (catalog.setPinned(id, pinned)) persist()
    }

    fun setContentOpenCounting(id: String, enabled: Boolean) {
        if (catalog.setOpenCountingEnabled(id, enabled)) persist()
    }

    fun openContent(id: String, context: Context): ContentOpenResult {
        if (capabilities[CAP_CONTENT] != true) {
            return ContentOpenResult(dev.atlas.coach.content.ContentOpenStatus.INVALID_CONTENT, "La bibliothèque est désactivée dans les paramètres.")
                .also { latestMessage = it.message; touch() }
        }
        val countedByUser = catalog.items.firstOrNull { it.id == id }?.openCountingEnabled == true
        val result = catalog.open(id, AndroidContentOpener(context))
        if (result.status in setOf(
                dev.atlas.coach.content.ContentOpenStatus.OPENED_CHOOSER,
                dev.atlas.coach.content.ContentOpenStatus.OPENED_DIRECT_FALLBACK,
            )
        ) {
            if (countedByUser) {
                val now = clock.nowMillis()
                contentActivityTimestamps = (contentActivityTimestamps + now)
                    .filter { it in (now - 30L * 24L * 60L * 60L * 1_000L).coerceAtLeast(0L)..now }
                    .takeLast(MAX_AGENT_CONTENT_ACTIVITY)
            }
            enqueueSyncEvent(SyncEventType.CONTENT_OPENED, SyncDataCategory.CONTENT)
        }
        latestMessage = result.message
        persist()
        return result
    }

    /** Explicit consent action. False means the user must grant Usage Access in Android Settings. */
    fun activateUsageObservation(packageNames: Set<String>): Boolean {
        val selectable = selectableUsageApps.mapTo(mutableSetOf()) { it.packageName }
        val selected = packageNames.intersect(selectable)
        if (selected.isEmpty()) {
            latestMessage = "Choisissez au moins une application lançable."
            touch()
            return false
        }
        pendingUsagePreferences.edit().putStringSet(KEY_PENDING_USAGE_PACKAGES, selected.toSet()).apply()
        if (!usageAccessStatus.isAllowed()) {
            latestMessage = "Choix enregistré. Accordez Accès à l'utilisation dans Android, puis revenez dans ATLAS."
            touch()
            return false
        }
        return grantPendingUsageObservation(selected)
    }

    /** Called on Activity resume so permission changes made in Settings are observed immediately. */
    fun onHostResumed() {
        val pending = pendingUsagePreferences.getStringSet(KEY_PENDING_USAGE_PACKAGES, emptySet()).orEmpty()
        if (usageAccessStatus.isAllowed()) {
            if (pending.isNotEmpty()) grantPendingUsageObservation(pending)
        } else {
            if (pending.isNotEmpty()) {
                pendingUsagePreferences.edit().remove(KEY_PENDING_USAGE_PACKAGES).apply()
                latestMessage = "Accès à l'utilisation non accordé. Aucune observation n'est active."
                touch()
            }
            if (usageConsentActive) {
                revokeUsageObservation("Accès à l'utilisation a été retiré. L'observation est arrêtée et son historique a été purgé.")
            }
        }
        refreshBackgroundUsageWork()
        locationPanelCoordinator.onLocationPermissionRevoked()
        recordMobilityCompletionIfPresent()
        recordDueGoalReviews()
        recordDueHabitReminders()
        recordDailyPlanDue()
        queueProactiveRun()
    }

    fun usageAccessSettingsIntent(): Intent = systemUsageAccessSettingsIntent()

    /** S5 WorkManager entry point: an off switch or revoked AppOp prevents every background read. */
    fun pollUsageObservation() {
        if (!CapabilityRules.mayObserveUsageInBackground(
                capabilities = capabilities,
                usageConsentActive = usageConsentActive,
                osPermissionGranted = usagePermissionGranted,
            )
        ) return
        pollUsageObservationWithCurrentConsent()
    }

    /** Visible Activity polling remains available when the optional background capability is off. */
    fun pollVisibleUsageObservation() {
        if (!usageConsentActive) return
        pollUsageObservationWithCurrentConsent()
    }

    private fun pollUsageObservationWithCurrentConsent() {
        if (!usageConsentActive) return
        if (!usageAccessStatus.isAllowed()) {
            revokeUsageObservation("Accès à l'utilisation n'est plus autorisé. L'observation est arrêtée et son historique a été purgé.")
            return
        }
        val result = usageObserver.poll(clock.nowMillis())
        refreshDomainSnapshots()
        if (result.status == UsagePollStatus.PERMISSION_REQUIRED) {
            revokeUsageObservation("Accès à l'utilisation n'est plus autorisé. L'observation est arrêtée et son historique a été purgé.")
        } else if (result.interventions.isNotEmpty()) {
            latestMessage = "Une proposition de pause est disponible dans la notification. ATLAS n'a pas bloqué l'application."
            touch()
            recordProactiveEvents(result.interventions.map { intervention ->
                val eventId = UUID.nameUUIDFromBytes(intervention.interventionId.toByteArray()).toString()
                CoachTrigger(
                    eventId = "distraction:$eventId",
                    source = CoachTriggerSource.DISTRACTION_THRESHOLD_REACHED,
                    occurredAtUtcMillis = intervention.createdAtUtc,
                    motifKey = "distraction_threshold",
                    reliability = CoachTriggerReliability.VERIFIED,
                )
            })
        }
    }

    /** WorkManager invokes the same singleton observer after the same current consent checks. */
    fun pollUsageObservationFromWorker() {
        pollUsageObservation()
    }

    fun revokeUsageObservation(message: String = "Observation désactivée. L'historique et les données de déduplication ont été supprimés.") {
        runCatching { UsageObservationWorkScheduler.cancel(appContext) }
        usageObserver.revokeConsent()
        agentRequestGeneration.incrementAndGet()
        queueProactivePurgeRevoked()
        pendingUsagePreferences.edit().remove(KEY_PENDING_USAGE_PACKAGES).apply()
        policy = policy.copy(allowlistedPackageNames = emptySet())
        capabilities = capabilities + (CAP_APP_TRACKING to false)
        latestMessage = message
        persist()
    }

    fun deleteUsageHistory() {
        usageObserver.deleteHistory()
        latestMessage = "Historique d'observation supprimé. Le consentement sélectionné reste actif."
        touch()
    }

    fun exportUsageHistoryCsv(): String = usageObserver.exportHistoryCsv()

    fun usagePermissionStatusText(): String = if (usagePermissionGranted) "Accordé dans Android" else "Non accordé dans Android"

    private fun grantPendingUsageObservation(packageNames: Set<String>): Boolean {
        val selected = packageNames.intersect(selectableUsageApps.mapTo(mutableSetOf()) { it.packageName })
        if (selected.isEmpty() || !usageAccessStatus.isAllowed()) return false
        return try {
            val consent = ObservationConsent.grant(
                purpose = ObservationPurpose.GENTLE_FRICTION,
                allowlistedPackageNames = selected,
                nowUtc = clock.nowMillis(),
            )
            usageObserver.acceptConsent(consent, clock.nowMillis())
            pendingUsagePreferences.edit().remove(KEY_PENDING_USAGE_PACKAGES).apply()
            policy = policy.copy(allowlistedPackageNames = selected, excludedPackageNames = policy.excludedPackageNames + protectedUsagePackages)
            capabilities = capabilities + (CAP_APP_TRACKING to true)
            refreshBackgroundUsageWork()
            latestMessage = if (capabilities[CAP_USAGE_BACKGROUND] == true) {
                "Observation activée pour ${selected.size} application(s); le traitement visible et le contrôle périodique sont autorisés."
            } else {
                "Observation activée pour ${selected.size} application(s). Le traitement hors interface reste désactivé."
            }
            persist()
            true
        } catch (error: IllegalArgumentException) {
            latestMessage = error.message ?: "Le choix d'applications contient une app protégée."
            pendingUsagePreferences.edit().remove(KEY_PENDING_USAGE_PACKAGES).apply()
            touch()
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun querySelectableUsageApps(): List<UsageApp> {
        val pm = appContext.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launchIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(launchIntent, 0)
        }
        val packages = resolved.mapNotNull { it.activityInfo?.packageName }.toSet()
        val selectable = AndroidProtectedPackages.selectablePackages(appContext, packages)
        return resolved.asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName !in selectable) return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(packageName)
                UsageApp(packageName, label)
            }
            .distinctBy(UsageApp::packageName)
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun simulateFourOpenings() {
        if (capabilities[CAP_SIMULATED_RULES] != true) {
            simulationMessage = "Le simulateur est désactivé dans les paramètres."
            persist()
            return
        }
        val now = clock.nowMillis()
        val events = AppEventSimulator(clock) { UUID.randomUUID().toString() }
            .generate("dev.atlas.demo.distraction", 4, 5 * 60L, now - 15 * 60 * 1_000L)
        var emitted = 0
        val simulationPolicy = policy.copy(allowlistedPackageNames = setOf("dev.atlas.demo.distraction"))
        events.forEach { event -> if (ruleEvaluator.evaluate(event, simulationPolicy) != null) emitted++ }
        lastSimulatedEvent = events.lastOrNull()
        lastSimulationInterventions = emitted
        simulationMessage = "4 événements simulés · $emitted proposition${if (emitted == 1) "" else "s"} affichée${if (emitted == 1) "" else "s"}."
        if (emitted > 0) latestMessage = "Proposition locale : faites une courte pause avant de rouvrir l'application simulée."
        persist()
    }

    fun repeatLastEvent() {
        if (capabilities[CAP_SIMULATED_RULES] != true) {
            simulationMessage = "Le simulateur est désactivé dans les paramètres."
            persist()
            return
        }
        val event = lastSimulatedEvent
        if (event == null) {
            simulationMessage = "Aucun événement précédent à répéter."
        } else {
            val duplicateResult = ruleEvaluator.evaluate(event, policy.copy(allowlistedPackageNames = setOf("dev.atlas.demo.distraction")))
            simulationMessage = if (duplicateResult == null) {
                "Doublon ignoré · ${ruleEvaluator.snapshot().interventions.size} intervention(s) conservée(s)."
            } else {
                "Une nouvelle proposition a été créée."
            }
        }
        persist()
    }

    fun simulateCooldown() {
        if (capabilities[CAP_SIMULATED_RULES] != true) {
            simulationMessage = "Le simulateur est désactivé dans les paramètres."
            persist()
            return
        }
        val events = AppEventSimulator(clock) { UUID.randomUUID().toString() }
            .generate("dev.atlas.demo.distraction", 4, 10L, clock.nowMillis())
        val simulationPolicy = policy.copy(allowlistedPackageNames = setOf("dev.atlas.demo.distraction"))
        val emitted = events.count { ruleEvaluator.evaluate(it, simulationPolicy) != null }
        lastSimulationInterventions = emitted
        simulationMessage = "4 nouveaux événements · $emitted proposition${if (emitted == 1) "" else "s"} pendant la période d'attente."
        persist()
    }

    fun toggleQuietHours() {
        policy = policy.copy(quietHours = if (policy.quietHours == null) QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0)) else null)
        persist()
    }

    fun setEmergencyPause() {
        val now = clock.nowMillis()
        AndroidEmergencyPauseStore.pauseForThirtyMinutes(appContext, now)
        policy = policy.withEmergencyPause(now)
        locationPanelCoordinator.onEmergencyPause()
        proactiveEffectGeneration.incrementAndGet()
        queueProactiveDisableAndPurge()
        cancelPendingAgentWork("Pause d'urgence locale; action annulée")
        latestMessage = "Pause d'urgence active pendant 30 minutes."
        persist()
    }

    fun endEmergencyPause() {
        AndroidEmergencyPauseStore.clear(appContext)
        policy = policy.copy(pauseUntil = null)
        latestMessage = "La pause d'urgence est terminée."
        persist()
    }

    fun setCapability(name: String, enabled: Boolean) {
        if (name == CAP_SYNC) {
            setSyncEnabled(enabled)
            return
        }
        if (name == CAP_AGENT) {
            if (enabled) enableAgent() else disableAgent()
            return
        }
        if (name == CAP_APP_TRACKING) {
            if (enabled) {
                latestMessage = "L'observation ne peut être activée que depuis l'écran Observation, après choix des apps et vérification d'Android."
                touch()
            } else {
                revokeUsageObservation()
            }
            return
        }
        if (name == CAP_USAGE_BACKGROUND && enabled &&
            !CapabilityRules.mayObserveUsageInBackground(capabilities + (name to true), usageConsentActive, usagePermissionGranted)
        ) {
            latestMessage = "L'observation en arrière-plan requiert d'abord le consentement et l'accès Android existants."
            touch()
            return
        }
        if (name == CAP_AGENT_PROACTIVE && enabled && !CapabilityRules.mayRunProactiveCoach(capabilities + (name to true), isAgentEnabled)) {
            latestMessage = "La proactivité requiert un agent actif et un mandat valide."
            touch()
            return
        }
        capabilities = capabilities + (name to enabled)
        if (name == CAP_USAGE_BACKGROUND) refreshBackgroundUsageWork()
        if (name == CAP_AGENT_PROACTIVE && !enabled) {
            proactiveEffectGeneration.incrementAndGet()
            queueProactiveDisableAndPurge()
        }
        if (name == CAP_AGENT_PROACTIVE && enabled) {
            recordDueGoalReviews()
            recordDueHabitReminders()
            recordDailyPlanDue()
            recordMobilityCompletionIfPresent()
            queueProactiveRun()
        }
        if (name == CAP_LOCATION && !enabled) locationPanelCoordinator.onLocationCapabilityDisabled()
        if (name == CAP_MOBILITY) {
            locationPanelCoordinator.setMobilityConsent(enabled)
            if (!enabled) locationPanelCoordinator.stopMobility(MobilitySessionStopReason.CAPABILITY_DISABLED)
        }
        if (name == CAP_FOCUS && !enabled) {
            val current = focusEngine.currentSession()
            if (current != null && CapabilityRules.mustPauseFocusOnDisable(enabled, current.status)) {
                focusSnapshot = focusEngine.pause(current.id)
                phaseAlarms.cancel(current.id)
                focusNotifications.cancel(current.id)
                latestMessage = "Focus désactivé. La session est en pause."
            }
        }
        if (name == CAP_FOCUS_NOTIFICATIONS) {
            val active = focusEngine.currentSession()
            if (enabled && active?.status == FocusStatus.RUNNING) phaseAlarms.schedule(active)
            else if (!enabled && active != null) {
                phaseAlarms.cancel(active.id)
                focusNotifications.cancel(active.id)
            }
        }
        if (name == CAP_FOCUS_ROUTINES) {
            if (enabled) routineAlarms.scheduleAll(focusRoutineRepository.list(), ZonedDateTime.now(), ZoneId.systemDefault())
            else focusRoutineRepository.list().forEach { routineAlarms.cancel(it.id) }
        }
        if (name == CAP_HABIT_REMINDERS) {
            if (enabled) {
                habits.list().forEach { habit ->
                    habit.reminder?.takeIf(HabitReminder::enabled)?.let { habitReminderScheduler.schedule(habit.id, habit.name, it) }
                }
            } else {
                habits.list().forEach { habit -> habitReminderScheduler.cancel(habit.id) }
            }
        }
        persist()
    }

    fun exportAllData(): String = JSONObject(stateMap()).toString(2)

    fun navigateToFocusStats() {
        if (capabilities[CAP_FOCUS] != true) {
            latestMessage = "Les statistiques de focus sont désactivées avec Focus."
            touch()
            return
        }
        navigationState.navigate(AtlasScreenId.FOCUS_STATS)
    }

    fun touch() {
        revision++
    }

    fun reportMessage(message: String) {
        latestMessage = message
        persist()
    }

    private fun restoreCoach(values: Map<String, Any?>?) {
        if (values == null) return
        runCatching {
            val directives = values["directives"].asList().mapNotNull { it.asMap()?.let(Directive::fromMap) }
            val responses = values["responses"].asList().mapNotNull { it.asMap()?.let(DirectiveResult::fromMap) }
            val journal = values["journal"].asList().mapNotNull { it.asMap()?.let(CoachJournalEntry::fromMap) }
            val current = values["currentDirectiveId"] as? String ?: directives.first().id
            coach.restoreState(CoachRepositoryState(directives, responses, journal, current, values["paused"] as? Boolean ?: false))
        }
    }

    /** Non-critical UI writes report failures and leave the app usable in memory. */
    private fun persist(operation: String = "state", durable: Boolean = true): PersistResult {
        try {
            focusSnapshot = focusEngine.currentSession()
            refreshDomainSnapshots()
        } catch (error: Exception) {
            val reason = persistenceFailureKind(error)
            recordPersistenceFailure(operation, reason)
            latestMessage = "Impossible de préparer l'état local. La session reste disponible en mémoire."
            touch()
            return PersistResult.Failed(reason)
        }
        if (!persistenceAllowed) {
            recordPersistenceFailure(operation, PersistFailureKind.INVALID_STATE)
            latestMessage = "Impossible d'enregistrer l'état local; la récupération interdit de remplacer les données existantes."
            touch()
            return PersistResult.NotWritable
        }
        return try {
            val snapshot = try {
                stateMap()
            } catch (error: Exception) {
                val reason = persistenceFailureKind(error)
                recordPersistenceFailure(operation, reason)
                latestMessage = "Impossible de préparer l'état local. La session reste disponible en mémoire."
                touch()
                return PersistResult.Failed(reason)
            }
            store.write(snapshot, durable = durable)
            lastFocusCheckpointAtElapsed = clock.monotonicMillis()
            touch()
            PersistResult.Stored
        } catch (error: Exception) {
            val reason = persistenceFailureKind(error)
            recordPersistenceFailure(operation, reason)
            latestMessage = "Impossible d'enregistrer l'état local. La session reste disponible en mémoire."
            touch()
            PersistResult.Failed(reason)
        }
    }

    /** Critical writes use this boundary and cannot be reported as committed after an error. */
    private fun persistOrThrow(operation: String, durable: Boolean = true): PersistResult {
        val result = persist(operation, durable)
        if (result != PersistResult.Stored) throw PersistOperationException(result)
        if (agentConsentMigrationPending) {
            agentConsentMigrationPending = false
            agentConsentMigrationReady.set(true)
        }
        return result
    }

    private fun persistAfterInitializationOrThrow(operation: String): Boolean {
        if (!stateGraphReady) {
            persistRequestedDuringInitialization = true
            return false
        }
        persistOrThrow(operation)
        return true
    }

    private fun persistenceFailureKind(error: Exception): PersistFailureKind = when (error) {
        is PersistWriteException -> error.reason
        is java.io.IOException -> PersistFailureKind.STORAGE_IO
        is IllegalArgumentException -> PersistFailureKind.INVALID_STATE
        else -> PersistFailureKind.UNKNOWN
    }

    private class PersistOperationException(val result: PersistResult) : RuntimeException()

    private fun stateMap(): Map<String, Any?> {
        val coachState = coach.snapshotState()
        focusSnapshot = focusEngine.snapshot()
        return mapOf(
            "schemaVersion" to 1,
            "focus" to focusSnapshot?.toMap(),
            "focusHistory" to focusHistoryEntries.map(FocusSession::toMap),
            "focusRoutines" to focusRoutinesSnapshot.map(FocusRoutine::toMap),
            "focusJournalLinks" to focusLinkStore.all().map(FocusJournalLink::toMap),
            "habits" to habits.exportMaps(),
            "habitLink" to habitLinkPolicy.toMap(),
            "coach" to mapOf(
                "directives" to coachState.directives.map(Directive::toMap),
                "responses" to coachState.responses.map(DirectiveResult::toMap),
                "journal" to coachState.journalEntries.map(CoachJournalEntry::toMap),
                "currentDirectiveId" to coachState.currentDirectiveId,
                "paused" to coachState.paused,
            ),
            "policy" to policy.toStorageMap(),
            "rules" to ruleEvaluator.snapshot().toStorageMap(),
            "capabilities" to capabilities,
            "launcherPins" to launcherPinnedPackages.sorted(),
            "content" to catalog.toMaps(),
            "lastSimulatedEvent" to lastSimulatedEvent?.let(::eventToMap),
            "lastSimulationInterventions" to lastSimulationInterventions,
            "simulationMessage" to simulationMessage,
            "latestMessage" to latestMessage,
            "agentMandate" to agentMandateStore.current().toJson().toString(),
            "agentHomeState" to agentHomeStateSnapshot.toJson().toString(),
            "agentConversation" to agentConversationSnapshot.map(AgentConversationMessage::toMap),
            "agentConversationId" to agentConversationId,
            "agentSuggestions" to agentSuggestionsSnapshot,
            "appliedLocalAgentEffectKeys" to appliedLocalAgentEffectKeys.toList(),
            "goals" to goalsSubdocument,
            "agentContentActivity" to contentActivityTimestamps,
            KEY_AGENT_CONTEXT_CONSENT_DATES to agentContextCoordinator.consentDates(),
            "lastAgentSharedContext" to lastSharedAgentContextJsonSnapshot,
            "lastAgentSharedContextAt" to lastSharedAgentContextAtUtcMillisSnapshot,
            "agentPlanReply" to agentPlanReply,
            "proactiveCoach" to proactiveCoachStateValue,
        )
    }

    private fun refreshDomainSnapshots() {
        directiveSnapshot = coach.currentDirective()
        contentItemsSnapshot = catalog.items
        habitsSnapshot = habits.list()
        focusRoutinesSnapshot = focusRoutineRepository.list()
        val today = LocalDate.now()
        checkedHabitIdsSnapshot = habitsSnapshot.asSequence()
            .filter { habit -> habits.checkIns(habit.id).any { it.date == today } }
            .map { it.id }
            .toSet()
        journalSnapshot = coach.listJournal().asReversed()
        interventionsSnapshot = ruleEvaluator.snapshot().interventions.asReversed()
        coachPausedSnapshot = coach.isPaused()
    }

    private fun archiveCurrentTerminalFocus() {
        focusEngine.currentSession()?.takeIf {
            it.status == dev.atlas.coach.focus.FocusStatus.FINISHED || it.status == dev.atlas.coach.focus.FocusStatus.CANCELLED
        }?.let(::archiveFocusSession)
    }

    private fun archiveFocusSession(session: FocusSession) {
        if (session.status != dev.atlas.coach.focus.FocusStatus.FINISHED && session.status != dev.atlas.coach.focus.FocusStatus.CANCELLED) return
        focusHistoryEntries = (listOf(session) + focusHistoryEntries.filterNot { it.id == session.id })
            .sortedByDescending { it.createdAtUtcMillis }
            .take(MAX_FOCUS_HISTORY)
    }

    companion object {
        const val CAP_FOCUS = "focus"
        const val CAP_HABITS = "habits"
        const val CAP_CONTENT = "content"
        const val CAP_COACH = "coach"
        const val CAP_SIMULATED_RULES = "simulatedRules"
        const val CAP_APP_TRACKING = "appTrackingConsent"
        const val CAP_LAUNCHER_HOME = "launcherHome"
        const val CAP_FOCUS_ROUTINES = "focusRoutines"
        const val CAP_FOCUS_NOTIFICATIONS = "focusNotifications"
        const val CAP_HABIT_REMINDERS = "habitReminders"
        const val CAP_SYNC = "sync"
        const val CAP_AGENT = "agent"
        const val CAP_USAGE_BACKGROUND = "usageBackground"
        const val CAP_AGENT_PROACTIVE = "agentProactive"
        const val CAP_LOCATION = "locationCapture"
        const val CAP_MOBILITY = "mobilityTracking"
        const val FOCUS_CHECKPOINT_INTERVAL_MILLIS = 20_000L
        const val MAX_FOCUS_HISTORY = 100
        const val MAX_FOCUS_ROUTINES = 100
        const val MAX_AGENT_MESSAGE_LENGTH = 2_000
        const val MAX_AGENT_CONVERSATION_MESSAGES = 80
        private const val MAX_AGENT_CONTENT_ACTIVITY = 200
        private const val KEY_AGENT_CONTEXT_CONSENT_DATES = "agentContextConsentAt"
        private const val KEY_PENDING_USAGE_PACKAGES = "pending_allowlist"
        private val DAILY_PLAN_DUE_TIME: LocalTime = LocalTime.of(8, 0)
        private val PROACTIVE_QUIET_HOURS_START: LocalTime = LocalTime.of(22, 0)
        private const val PROACTIVE_EVENT_DEBOUNCE_MILLIS = 100L

        private fun readCapabilities(values: Map<String, Any?>?): Map<String, Boolean> {
            val defaults = mapOf(
                CAP_FOCUS to true,
                CAP_HABITS to true,
                CAP_CONTENT to true,
                CAP_COACH to true,
                CAP_SIMULATED_RULES to true,
                CAP_APP_TRACKING to false,
                CAP_LAUNCHER_HOME to false,
                CAP_FOCUS_ROUTINES to false,
                CAP_FOCUS_NOTIFICATIONS to false,
                CAP_HABIT_REMINDERS to false,
                CAP_SYNC to false,
                CAP_AGENT to true,
                CAP_USAGE_BACKGROUND to false,
                CAP_AGENT_PROACTIVE to false,
                CAP_LOCATION to false,
                CAP_MOBILITY to false,
            )
            return defaults + values.orEmpty().mapNotNull { (key, value) -> (value as? Boolean)?.let { key to it } }
        }

        private fun eventToMap(event: AppOpenEvent) = mapOf(
            "eventId" to event.eventId,
            "packageName" to event.packageName,
            "timestampUtc" to event.timestampUtc,
        )

        private fun eventFromMap(values: Map<String, Any?>) = AppOpenEvent(
            eventId = values["eventId"] as? String ?: error("Missing eventId"),
            packageName = values["packageName"] as? String ?: error("Missing packageName"),
            timestampUtc = (values["timestampUtc"] as? Number)?.toLong() ?: error("Missing timestampUtc"),
        )
    }
}

data class UsageApp(val packageName: String, val label: String)

private fun Any?.asMap(): Map<String, Any?>? = (this as? Map<*, *>)
    ?.entries?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }?.toMap()

private fun Any?.asList(): List<*> = this as? List<*> ?: emptyList<Any?>()
