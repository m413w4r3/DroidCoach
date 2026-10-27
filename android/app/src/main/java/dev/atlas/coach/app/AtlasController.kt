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
import dev.atlas.coach.focus.FocusPhase
import dev.atlas.coach.focus.FocusPlan
import dev.atlas.coach.focus.FocusPreset
import dev.atlas.coach.focus.PhaseAlarmScheduler
import dev.atlas.coach.focus.PhaseNotifier
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
import dev.atlas.coach.plan.PlanRevisionApiClient
import dev.atlas.coach.plan.PlanRevisionProposalClient
import dev.atlas.coach.sync.relay.RelayTransportMode
import dev.atlas.coach.training.SportPlanClient
import dev.atlas.coach.training.SportPlanUiState
import dev.atlas.coach.training.SyncState
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
import dev.atlas.coach.sync.agent.focusPlanEndWithinWindow
import dev.atlas.coach.sync.agent.focusSessionDurationMinutes
import dev.atlas.coach.sync.agent.focusTimeInWindow
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
import dev.atlas.coach.coach.proactive.ProactiveNotification
import dev.atlas.coach.coach.proactive.ProactiveNotificationContent
import dev.atlas.coach.coach.proactive.ProactivePublicationAuthorization
import dev.atlas.coach.coach.proactive.ProactivePublicationEpoch
import dev.atlas.coach.coach.proactive.ProactivePlanRequest
import dev.atlas.coach.coach.proactive.ProactivePlanResponse
import dev.atlas.coach.coach.proactive.ProactiveRunResult
import dev.atlas.coach.coach.proactive.ProactiveRunStatus
import dev.atlas.coach.coach.proactive.android.ProactiveCoachWorkScheduler
import dev.atlas.coach.location.MobilitySessionCompletion
import dev.atlas.coach.location.MobilitySessionStatusStore
import dev.atlas.coach.sync.agent.context.AgentContextCategory
import dev.atlas.coach.sync.agent.context.AgentContextDomainViews
import dev.atlas.coach.sync.agent.context.AgentContextPreview
import dev.atlas.coach.sync.agent.context.AgentContextSnapshot
import dev.atlas.coach.sync.agent.context.LastContextDisclosureAudit
import dev.atlas.coach.sync.agent.context.LastContextDisclosureStatus
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
class AtlasController @JvmOverloads constructor(
    context: Context,
    private val clock: Clock = object : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
    },
    stateBackendOverride: StateBackend? = null,
    revocationBarrierStorageOverride: RevocationBarrierStorage? = null,
    persistenceDiagnosticsStoreOverride: PersistenceDiagnosticsStore? = null,
    syncOutboxOverride: EventOutbox? = null,
    phaseAlarmSchedulerOverride: PhaseAlarmScheduler? = null,
    phaseNotifierOverride: PhaseNotifier? = null,
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
    private data class InitialState(
        val readResult: StateReadResult,
        val legacyContextAudit: LastContextDisclosureAudit?,
        val contextAuditMigrationPending: Boolean,
    )

    private val initialState = readInitialState()
    private val syncOutbox: EventOutbox = syncOutboxOverride ?: AndroidSyncStorage.outbox(appContext)
    private val syncTokenStore = SharedPreferencesBearerTokenStore(appContext)
    private val syncSettingsStore = AndroidSyncStorage.settings(appContext)
    /** Optional relayed transport; it never opens a socket in DIRECT or OFFLINE mode. */
    val relaySettings = RelaySettingsController(appContext, clock = { clock.nowMillis() })
    /** Local, durable memory facts and their user-facing controller. */
    val memoryController = MemoryController(File(appContext.filesDir, "memory"))
    /** Independent global UsageStats path; no collection happens until the user consents and refreshes. */
    val globalUsageController = GlobalUsageController.fromContext(appContext)
    var sportPlanState by mutableStateOf<SportPlanUiState?>(null)
        private set
    var sportBusy by mutableStateOf(false)
        private set
    var sportMessage by mutableStateOf<String?>(null)
        private set
    var sportDemoMode by mutableStateOf(false)
        private set
    private val persistedSyncSettings = syncSettingsStore.read()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val syncWorker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "atlas-sync-worker").apply { isDaemon = true }
    }
    private val syncRunning = AtomicBoolean(false)
    private val syncGeneration = AtomicLong(0L)
    private val proactiveEffectGeneration = AtomicLong(0L)
    private val proactiveAuthorizationEffectLock = Any()
    private val proactiveDrainScheduled = AtomicBoolean(false)
    private val proactiveDrainLock = Any()
    private val pendingProactiveEvents = ConcurrentLinkedQueue<CoachTrigger>()
    private val proactiveWorkerWaiters = ConcurrentLinkedQueue<CompletableFuture<ProactiveRunResult>>()
    private val effectiveConsent = AtomicReference(SyncConsent.DISABLED)
    @Volatile private var remoteBackend: RemoteBackendGateway? = null
    @Volatile private var syncRetryFuture: ScheduledFuture<*>? = null
    @Volatile private var coachGateway: CoachGateway = LocalMockCoachGateway()
    @Volatile private var syncGateway: SyncGateway = LocalMockSyncGateway()
    private val phaseAlarms: PhaseAlarmScheduler = phaseAlarmSchedulerOverride ?: AndroidPhaseAlarmScheduler(appContext)
    private val focusNotifications: PhaseNotifier = phaseNotifierOverride ?: AndroidFocusNotifications(appContext)
    private val routineAlarms = AndroidRoutineProposalScheduler(appContext)
    private val habitReminderScheduler = AndroidHabitReminderScheduler(appContext)
    private val stateReadResult = initialState.readResult
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
            "Certaines données locales ont été écartées ($reason). Une copie locale a été mise en quarantaine; les valeurs de secours sont actives."
        } else {
            "Certaines données locales sont illisibles ($reason). La copie de secours n'a pas pu être garantie; les valeurs de secours sont chargées sans écraser le stockage."
        }
    }
    private val goalFocusTransactionLock = Any()
    private val acceptedAgentFocusGoalId = ThreadLocal<String?>()
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

    /** Narrow hooks used by controller instrumentation race tests; no production flow sets them. */
    @Volatile internal var proactivePlanTransportForTesting:
        ((ProactivePlanRequest, () -> Boolean) -> dev.atlas.coach.sync.agent.coach.AgentChatResponse)? = null
    @Volatile internal var afterProactivePlanResponseForTesting: (() -> Unit)? = null
    @Volatile internal var proactiveNotificationAttemptObserverForTesting: (() -> Unit)? = null
    @Volatile internal var proactiveAckAttemptObserverForTesting: (() -> Unit)? = null

    internal fun setAgentApiClientForTesting(client: AgentApiClient?) {
        agentApiClient = client
    }

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
    private var lastSharedAgentContextJsonSnapshot by mutableStateOf<String?>(null)
    private var lastContextDisclosureAuditSnapshot by mutableStateOf(
        LastContextDisclosureAudit.fromMap(restored[KEY_LAST_CONTEXT_DISCLOSURE_AUDIT].asMap())
            ?: if (restored.containsKey(KEY_LAST_CONTEXT_DISCLOSURE_AUDIT)) null else initialState.legacyContextAudit,
    )
    private var lastContextAuditMigrationPending = initialState.contextAuditMigrationPending
    private var agentPlanReply by mutableStateOf(restored["agentPlanReply"] as? String)
    private var proactiveEngagementModeSnapshot by mutableStateOf(ProactiveEngagementMode.NORMAL)
    @Volatile private var proactiveCoachStateValue: Any? = restored["proactiveCoach"]

    private fun readInitialState(): InitialState {
        val result = runCatching { store.readDocument() }
            .getOrElse { StateReadResult.Corrupt(null, "impossible de lire le stockage local") }
        return when (result) {
            is StateReadResult.Loaded -> {
                val values = result.state
                val hasAudit = values.containsKey(KEY_LAST_CONTEXT_DISCLOSURE_AUDIT)
                val legacyPayload = values["lastAgentSharedContext"] as? String
                val audit = if (hasAudit) null else LastContextDisclosureAudit.fromLegacyPayload(
                    legacyPayload,
                    (values["lastAgentSharedContextAt"] as? Number)?.toLong(),
                )
                val sanitizedValues = values - setOf("lastAgentSharedContext", "lastAgentSharedContextAt")
                val sanitizedJson = runCatching {
                    JSONObject(result.rawJson).apply {
                        remove("lastAgentSharedContext")
                        remove("lastAgentSharedContextAt")
                    }.toString()
                }.getOrElse { JSONObject(sanitizedValues).toString() }
                InitialState(
                    readResult = StateReadResult.Loaded(sanitizedValues, sanitizedJson),
                    legacyContextAudit = audit,
                    contextAuditMigrationPending = !hasAudit && legacyPayload != null,
                )
            }
            is StateReadResult.Corrupt -> {
                // A parseable blob is quarantined without the legacy payload. An unparseable one cannot be
                // proven free of it (escaped keys defeat text filters), so only a value-free envelope is kept.
                val sanitizedJson = result.rawJson?.let { raw ->
                    runCatching {
                        JSONObject(raw).apply {
                            remove("lastAgentSharedContext")
                            remove("lastAgentSharedContextAt")
                        }.toString()
                    }.getOrElse { redactedCorruptDocumentEnvelope(raw, result.reason) }
                }
                InitialState(
                    readResult = StateReadResult.Corrupt(sanitizedJson, result.reason),
                    legacyContextAudit = null,
                    contextAuditMigrationPending = false,
                )
            }
            StateReadResult.Missing -> InitialState(StateReadResult.Missing, null, false)
        }
    }

    private fun redactedCorruptDocumentEnvelope(raw: String, reason: String): String {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return JSONObject()
            .put("quarantine_format", "redacted_corrupt_document_v1")
            .put("reason", reason)
            .put("at_utc_millis", System.currentTimeMillis())
            .put("raw_length_bytes", bytes.size)
            .put("raw_sha256", digest)
            .toString()
    }

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
        blockedCategories = revocationBarrier::blockedCategories,
        initialIncludeExistingGoals = restored[KEY_AGENT_INCLUDE_EXISTING_GOALS] as? Boolean ?: false,
        persistIncludeExistingGoals = {
            persistOrThrow("agent.goals_include_existing")
        },
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
        capturePublicationEpoch = ::captureProactivePublicationEpoch,
        isPublicationAuthorizationCurrent = ::isProactivePublicationAuthorized,
        requestPlan = ::requestProactivePlan,
        showNotification = ::showProactiveNotificationIfStillAllowed,
    )

    init {
        stateGraphReady = true
        proactiveEngagementModeSnapshot = proactiveCoachCoordinator.mode()
        policy = AndroidEmergencyPauseStore.apply(appContext, policy, clock.nowMillis())
        restored["focus"].asMap()?.let { saved ->
            runCatching {
                val savedSession = FocusSession.fromMap(saved)
                val restoredSession = focusEngine.restore(savedSession)
                if (savedSession.status == FocusStatus.RUNNING && (
                        restoredSession.status != savedSession.status ||
                            restoredSession.phase != savedSession.phase ||
                            restoredSession.remainingMillis != savedSession.remainingMillis ||
                            restoredSession.completedWorkCycles != savedSession.completedWorkCycles
                        )
                ) {
                    // Persist wall-clock catch-up once so a force-stop cannot replay it on each launch.
                    persistRequestedDuringInitialization = true
                }
            }
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
        if (lastContextAuditMigrationPending && persistenceAllowed) {
            try {
                persistOrThrow("agent.context_audit_migration")
                lastContextAuditMigrationPending = false
            } catch (_: PersistOperationException) {
                latestMessage = "Le résumé local du dernier partage n'a pas pu être migré. Les anciennes valeurs restent masquées."
                touch()
            }
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
    val lastSharedAgentContextJson: String?
        get() = lastSharedAgentContextJsonSnapshot.takeIf { agentContextPreview.authorizedCategories.isNotEmpty() }
    val lastContextDisclosureAudit: LastContextDisclosureAudit? get() = lastContextDisclosureAuditSnapshot
    val lastSharedAgentContextAtUtcMillis: Long? get() = lastContextDisclosureAuditSnapshot?.sentAtUtcMillis
    val includeExistingAgentGoals: Boolean get() = agentContextCoordinator.includesExistingGoals()
    val latestAgentPlanReply: String? get() = agentPlanReply
    val proactiveEngagementMode: ProactiveEngagementMode get() = proactiveEngagementModeSnapshot
    var highlightedProactiveProposalId by mutableStateOf<String?>(null)
        private set
    var proactiveNotificationProposalUnavailable by mutableStateOf(false)
        private set
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
        synchronized(goalFocusTransactionLock) {
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
    }

    fun setLocationRequestConsent(enabled: Boolean) {
        val update = {
            if (!enabled) invalidateProactiveAuthorizationNow()
            locationPanelCoordinator.setCoarseRequestConsent(enabled)
            capabilities = capabilities + (CAP_LOCATION to enabled)
            if (!enabled) locationPanelCoordinator.onLocationCapabilityDisabled()
            persist()
        }
        if (enabled) update() else withProactiveAuthorizationClose(action = update)
    }

    fun setLocationSummaryConsent(enabled: Boolean) {
        agentAuthorizationCommitInProgress.set(true)
        val locationCategory = AgentContextCategory.LOCATION_SUMMARY.wireValue
        val barrierStored = if (enabled) true else blockAgentForRevocation("agent.location_consent", locationCategory)
        try {
            locationPanelCoordinator.setCoachSummaryConsent(enabled)
            if (!enabled) purgeLastSharedContextCategory(AgentContextCategory.LOCATION_SUMMARY.wireValue)
            if (!enabled) locationPanelCoordinator.stopMobility(MobilitySessionStopReason.SHARING_REVOKED)
            queueProactivePurgeRevoked()
            if (!enabled) {
                val stateStored = runCatching { persistOrThrow("agent.location_consent") }.isSuccess
                latestMessage = if (barrierStored && stateStored) {
                    "Partage de localisation retiré; cette catégorie est exclue jusqu'à une nouvelle activation."
                } else if (revocationBarrier.isAgentBlocked()) {
                    "Agent bloqué dans cette session; stockage en échec, le retrait n'est pas confirmé."
                } else {
                    "Partage retiré dans cette session; cette catégorie reste exclue, mais son enregistrement durable a échoué."
                }
            } else {
                persistOrThrow("agent.location_consent")
                if (!revocationBarrier.clearCategoryAfterDurableOptIn(locationCategory, true)) {
                    recordPersistenceFailure("agent.location_consent", PersistFailureKind.STORAGE_IO)
                    latestMessage = "Consentement enregistré, mais la catégorie localisation reste exclue car la barrière locale n'a pas pu être levée."
                } else if (revocationBarrier.isAgentBlocked()) {
                    latestMessage = "Consentement de localisation enregistré; l'agent reste bloqué par une révocation générale en attente."
                } else if (revocationBarrier.blockedCategories().isNotEmpty()) {
                    latestMessage = "Consentement de localisation enregistré; les autres catégories retirées restent exclues jusqu'à leur réactivation."
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
        val update = {
            if (!enabled) invalidateProactiveAuthorizationNow()
            locationPanelCoordinator.setMobilityConsent(enabled)
            capabilities = capabilities + (CAP_MOBILITY to enabled)
            if (!enabled) locationPanelCoordinator.stopMobility(MobilitySessionStopReason.CAPABILITY_DISABLED)
            persist()
        }
        if (enabled) update() else withProactiveAuthorizationClose(action = update)
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
        val update = {
            if (!granted) invalidateProactiveAuthorizationNow()
            locationPanelCoordinator.handlePermissionResult(granted)
            if (!granted) locationPanelCoordinator.onLocationPermissionRevoked()
            touch()
        }
        if (granted) update() else withProactiveAuthorizationClose(action = update)
    }

    fun notificationPermissionGranted(): Boolean =
        (Build.VERSION.SDK_INT < 33 || appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            appContext.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    private fun proactiveWorkEligible(): Boolean =
        isAgentRequestAllowed() && isProactiveCoachEnabled() &&
            ProactiveCoachNotificationPublisher.notificationsAvailable(appContext)

    /** Rechecks all durable opt-ins and Android notification availability before scheduling work. */
    fun refreshProactiveCoachWork() {
        ProactiveCoachWorkScheduler.refresh(appContext, proactiveWorkEligible())
    }

    /** Worker entry point. Event collection stays on main; all plan I/O stays on [syncWorker]. */
    fun runProactiveWorkFromWorker(): CompletableFuture<ProactiveRunResult> {
        val result = CompletableFuture<ProactiveRunResult>()
        val prepare = Runnable {
            refreshProactiveCoachWork()
            if (!proactiveWorkEligible()) {
                result.complete(ProactiveRunResult(ProactiveRunStatus.CONSENT_REVOKED))
            } else {
                synchronized(proactiveDrainLock) {
                    if (result.isCancelled) return@Runnable
                    proactiveWorkerWaiters.add(result)
                    result.whenComplete { _, _ -> if (result.isCancelled) proactiveWorkerWaiters.remove(result) }
                    val now = clock.nowMillis()
                    recordDueGoalReviews(now)
                    recordDueHabitReminders(now)
                    recordDailyPlanDue(now)
                    scheduleProactiveEventDrain()
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) prepare.run() else mainHandler.post(prepare)
        return result
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        latestMessage = if (granted) {
            "Notifications autorisées. Les alertes restent facultatives et réglables dans Android."
        } else {
            "Notifications refusées. Focus, routines et rappels restent utilisables dans ATLAS au premier plan."
        }
        refreshProactiveCoachWork()
        touch()
    }

    /** Opens only a proposal already present in the local inbox; it never accepts or dispatches it. */
    fun openProactiveProposalFromNotification(proposalId: String) {
        if (!dev.atlas.coach.coach.proactive.isLocalProposalId(proposalId)) return
        if (!isAgentRequestAllowed()) {
            latestMessage = "Le mandat du Coach n'est pas actif. Aucune action n'a été effectuée."
            touch()
            return
        }
        val entry = agentInboxRepository.get(proposalId)
        if (entry == null) {
            openUnavailableProactiveProposalFromNotification()
            return
        }
        proactiveNotificationProposalUnavailable = false
        highlightedProactiveProposalId = entry.id
        navigationState.selectTab(AtlasScreenId.COACH)
        latestMessage = if (entry.proposal.state == ProposalState.EXPIRED) {
            "Cette proposition a expiré. Aucune action n'a été effectuée."
        } else {
            "Proposition ouverte dans le Coach. Aucune action n'a été effectuée."
        }
        touch()
    }

    /** Opens the Coach destination when a proactive plan has no local proposal to select. */
    fun openCoachFromProactiveNotification() {
        if (!isAgentRequestAllowed()) {
            latestMessage = "Le mandat du Coach n'est pas actif. Aucune action n'a été effectuée."
            touch()
            return
        }
        proactiveNotificationProposalUnavailable = false
        highlightedProactiveProposalId = null
        navigationState.selectTab(AtlasScreenId.COACH)
        latestMessage = "Plan ouvert dans le Coach. Aucune action n'a été effectuée."
        touch()
    }

    /** Shows a neutral, non-actionable state for a UUID pruned from the local inbox. */
    fun openUnavailableProactiveProposalFromNotification() {
        if (!isAgentRequestAllowed()) {
            latestMessage = "Le mandat du Coach n'est pas actif. Aucune action n'a été effectuée."
            touch()
            return
        }
        proactiveNotificationProposalUnavailable = true
        highlightedProactiveProposalId = null
        navigationState.selectTab(AtlasScreenId.COACH)
        latestMessage = PROACTIVE_PROPOSAL_UNAVAILABLE_MESSAGE
        touch()
    }

    fun clearUnavailableProactiveNotificationState() {
        if (!proactiveNotificationProposalUnavailable) return
        proactiveNotificationProposalUnavailable = false
        if (latestMessage == PROACTIVE_PROPOSAL_UNAVAILABLE_MESSAGE) latestMessage = null
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
                        val categoriesStillAllowed = agentContextCoordinator.preview().authorizedCategories.toSet()
                        var payloadJson = snapshot.toJsonString()
                        snapshot.consentedCategories.filterNot { it in categoriesStillAllowed }.forEach { revoked ->
                            payloadJson = LastContextDisclosureAudit.purgePayloadCategory(payloadJson, revoked) ?: ""
                        }
                        lastSharedAgentContextJsonSnapshot = payloadJson.takeIf {
                            it.isNotEmpty() && categoriesStillAllowed.isNotEmpty() && isAgentContextVisible()
                        }
                        lastContextDisclosureAuditSnapshot = LastContextDisclosureAudit(
                            sentAtUtcMillis = clock.nowMillis().coerceAtLeast(1L),
                            categoryNames = snapshot.consentedCategories,
                            status = LastContextDisclosureStatus.SUCCESS,
                        )
                        try {
                            persistOrThrow("agent.shared_context")
                            true
                        } catch (_: Exception) {
                            lastContextDisclosureAuditSnapshot = lastContextDisclosureAuditSnapshot?.copy(
                                status = LastContextDisclosureStatus.ATTEMPTED,
                            )
                            latestMessage = "Le contexte a été écrit vers le backend, mais son audit local n'a pas pu être enregistré. Une copie distante peut exister."
                            touch()
                            false
                        }
                    }) { "Could not persist the context disclosure audit" }
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
        if (agentAuthorizationCommitInProgress.get() || !agentConsentMigrationReady.get() || revocationBarrier.isAgentBlocked()) return false
        val mandate = agentMandateStore.current()
        val now = clock.nowMillis()
        if (capabilities[CAP_AGENT] != true || mandate.revoked || mandate.suspended || now >= mandate.expiresAtUtcMillis) return false
        val dates = agentContextCoordinator.consentDates()
        val effectiveCategories = agentContextCoordinator.effectiveMandate(mandate).sharedDataCategories
        if (effectiveCategories.any { dates[it]?.let { at -> at in 1L..now } != true }) return false
        if (AgentContextCategory.APP_USAGE_SUMMARY.wireValue in effectiveCategories && !usageConsentActive) return false
        if (AgentContextCategory.LOCATION_SUMMARY.wireValue in effectiveCategories &&
            !locationPanelCoordinator.consentStore.isLocationSummarySharingEnabled()
        ) return false
        return true
    }

    private fun isAgentContextVisible(): Boolean {
        if (!agentConsentMigrationReady.get() || revocationBarrier.isAgentBlocked()) return false
        val mandate = agentMandateStore.current()
        val now = clock.nowMillis()
        if (mandate.revoked || mandate.suspended || now >= mandate.expiresAtUtcMillis) return false
        val dates = agentContextCoordinator.consentDates()
        val effectiveCategories = agentContextCoordinator.effectiveMandate(mandate).sharedDataCategories
        return effectiveCategories.all { dates[it]?.let { at -> at in 1L..now } == true }
    }

    private fun canExplicitOptInClearBarrier(): Boolean {
        val mandate = agentMandateStore.current()
        return capabilities[CAP_AGENT] == true && !mandate.revoked && !mandate.suspended &&
            clock.nowMillis() < mandate.expiresAtUtcMillis
    }

    private fun blockAgentForRevocation(operation: String, category: String? = null): Boolean {
        val stored = synchronized(proactiveAuthorizationEffectLock) {
            agentRequestGeneration.incrementAndGet()
            proactiveEffectGeneration.incrementAndGet()
            if (category == null) revocationBarrier.block() else revocationBarrier.blockCategory(category)
        }
        cancelProactiveWorkAndVisibleNotifications()
        return stored.also {
            if (!stored) recordPersistenceFailure(operation, PersistFailureKind.STORAGE_IO)
        }
    }

    /** Mutate both epoch components under the authority lock; cancellation stays outside the lock. */
    private fun invalidateProactiveAuthorizationNow(invalidateAgentRequests: Boolean = false) {
        synchronized(proactiveAuthorizationEffectLock) {
            if (invalidateAgentRequests) agentRequestGeneration.incrementAndGet()
            proactiveEffectGeneration.incrementAndGet()
        }
        cancelProactiveWorkAndVisibleNotifications()
    }

    private inline fun <T> withProactiveAuthorizationClose(
        invalidateAgentRequests: Boolean = false,
        action: () -> T,
    ): T {
        val wasAuthorizationCommitInProgress = agentAuthorizationCommitInProgress.getAndSet(true)
        try {
            invalidateProactiveAuthorizationNow(invalidateAgentRequests)
            return action()
        } finally {
            if (!wasAuthorizationCommitInProgress) agentAuthorizationCommitInProgress.set(false)
        }
    }

    private fun cancelProactiveWorkAndVisibleNotifications() {
        runCatching { ProactiveCoachWorkScheduler.cancel(appContext) }
        runCatching { proactiveNotificationPublisher.cancelPublishedProactiveNotifications() }
    }

    private fun captureProactivePublicationEpoch(): ProactivePublicationEpoch =
        synchronized(proactiveAuthorizationEffectLock) {
            ProactivePublicationEpoch(
                capabilityGeneration = proactiveEffectGeneration.get(),
                agentRequestGeneration = agentRequestGeneration.get(),
            )
        }

    private fun isProactivePublicationAuthorized(
        authorization: ProactivePublicationAuthorization,
    ): Boolean = synchronized(proactiveAuthorizationEffectLock) {
        runCatching {
            authorization.capabilityGeneration == proactiveEffectGeneration.get() &&
                authorization.agentRequestGeneration == agentRequestGeneration.get() &&
                isAgentRequestAllowed() && isProactiveCoachEnabled() &&
                authorization.triggers.isNotEmpty() &&
                authorization.triggers.all(proactiveConsentSnapshot()::allows)
        }.getOrDefault(false)
    }

    private fun runIfProactiveAuthorizationCurrent(
        authorization: ProactivePublicationAuthorization,
        effect: () -> Unit,
    ): Boolean = synchronized(proactiveAuthorizationEffectLock) {
        if (!isProactivePublicationAuthorized(authorization)) return@synchronized false
        effect()
        true
    }

    private fun recordPersistenceFailure(operation: String, reason: PersistFailureKind) {
        persistenceDiagnostics.record(operation, reason, clock.nowMillis())
    }

    private fun isProactiveCoachEnabled(): Boolean =
        CapabilityRules.mayRunProactiveCoach(capabilities, isAgentEnabled) && !isEmergencyPaused

    private fun currentMandateZoneId(): ZoneId = ZoneId.of(agentMandateStore.current().focusWindow.timezone)

    /** Last controller boundary before the publisher's final Android gate. */
    private fun showProactiveNotificationIfStillAllowed(
        notification: ProactiveNotification,
        authorization: ProactivePublicationAuthorization,
    ) {
        check(authorization.triggers.map { it.source }.toSet() == notification.triggerSources &&
            isProactivePublicationAuthorized(authorization)
        ) { "Proactive consent changed before notification" }
        proactiveNotificationAttemptObserverForTesting?.invoke()
        proactiveNotificationPublisher.show(notification) {
            isProactivePublicationAuthorized(authorization)
        }
    }

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
        val grants = agentContextCoordinator.effectiveMandate(mandate).sharedDataCategories
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
        synchronized(proactiveDrainLock) {
            pendingProactiveEvents.addAll(batch)
            scheduleProactiveEventDrain()
        }
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
            var workerWaiters = emptyList<CompletableFuture<ProactiveRunResult>>()
            try {
                val snapshot = synchronized(proactiveDrainLock) {
                    val waiters = buildList {
                        while (true) add(proactiveWorkerWaiters.poll() ?: break)
                    }
                    val events = buildList {
                        while (true) add(pendingProactiveEvents.poll() ?: break)
                    }
                    waiters to events
                }
                workerWaiters = snapshot.first
                val eventBatch = snapshot.second
                var runRequired = workerWaiters.isNotEmpty()
                if (eventBatch.isNotEmpty() && isProactiveCoachEnabled()) {
                    val recorded = proactiveCoachCoordinator.recordAll(eventBatch)
                    runRequired = runRequired || recorded.any {
                        it == dev.atlas.coach.coach.proactive.CoachTriggerRecordStatus.RECORDED
                    }
                }
                if (runRequired) {
                    val decision = if (proactiveWorkEligible()) {
                        runCatching { proactiveCoachCoordinator.runDue() }
                            .getOrElse { ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED) }
                    } else {
                        ProactiveCoachWorkScheduler.cancel(appContext)
                        ProactiveRunResult(ProactiveRunStatus.CONSENT_REVOKED)
                    }
                    workerWaiters.forEach { it.complete(decision) }
                }
            } catch (_: Exception) {
                workerWaiters.forEach { it.complete(ProactiveRunResult(ProactiveRunStatus.PERSISTENCE_FAILED)) }
            } finally {
                proactiveDrainScheduled.set(false)
                if (pendingProactiveEvents.isNotEmpty() || proactiveWorkerWaiters.isNotEmpty()) scheduleProactiveEventDrain()
            }
        }, PROACTIVE_EVENT_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun queueProactivePurgeRevoked() {
        syncWorker.execute { runCatching { proactiveCoachCoordinator.purgeRevoked() } }
    }

    private fun queueProactiveDisableAndPurge() {
        syncWorker.execute { runCatching { proactiveCoachCoordinator.disableAndPurgePending() } }
    }

    private fun requestProactivePlan(
        request: ProactivePlanRequest,
        authorization: ProactivePublicationAuthorization,
    ): ProactivePlanResponse? {
        if (authorization.triggers != request.triggers || !isProactivePublicationAuthorized(authorization)) return null
        val client = agentApiClient ?: return null
        val mandate = agentMandateStore.current()
        val context = agentContextCoordinator.assembleCurrent()
        val requestStillAuthorized = { agentApiClient === client && isProactivePublicationAuthorized(authorization) }
        val response = try {
            proactivePlanTransportForTesting?.invoke(request, requestStillAuthorized) ?: client.plan(
                conversationId = agentConversationId ?: UUID.randomUUID().toString().also { agentConversationId = it },
                mandate = mandate,
                context = context,
                request = request,
                allowSend = requestStillAuthorized,
            )
        } catch (_: Exception) {
            return null
        }
        afterProactivePlanResponseForTesting?.invoke()
        if (!requestStillAuthorized() || !applyProactivePlanResponse(response, client, authorization)) return null
        val localProposalId = response.proposals.firstNotNullOfOrNull { proposal ->
            val entry = agentInboxRepository.get(proposal.proposalId) ?: return@firstNotNullOfOrNull null
            entry.id.takeIf {
                entry.proposal.state in setOf(ProposalState.PROPOSED, ProposalState.PENDING_CONFIRMATION) &&
                    entry.proposal.expiresAtUtcMillis > clock.nowMillis()
            }
        }
        val notificationBody = response.reply.trim().take(ProactiveNotificationContent.MAX_BODY_LENGTH)
            .ifBlank { "Ouvrez ATLAS pour consulter votre prochaine étape." }
        return ProactivePlanResponse(
            notification = ProactiveNotificationContent("Votre plan ATLAS est prêt", notificationBody),
            localProposalId = localProposalId,
        )
    }

    private fun applyProactivePlanResponse(
        response: dev.atlas.coach.sync.agent.coach.AgentChatResponse,
        client: AgentApiClient,
        authorization: ProactivePublicationAuthorization,
    ): Boolean = runOnMainAndWait {
        if (agentApiClient !== client || !isProactivePublicationAuthorized(authorization)) return@runOnMainAndWait false
        agentPlanReply = response.reply
        agentStatus = response.status
        agentStatusMessage = if (response.status == AgentStatus.CONNECTED) "Agent connecté"
            else "Agent indisponible — fonctions locales actives"
        if (!runIfProactiveAuthorizationCurrent(authorization) {
                agentInboxRepository.mergePulled(response.proposals, null)
            }
        ) return@runOnMainAndWait false
        if (!runIfProactiveAuthorizationCurrent(authorization) {
                recordBackendContactSucceeded(inboxAlreadyMarked = true)
            }
        ) return@runOnMainAndWait false
        if (!dispatchPreauthorizedInbox(authorization)) return@runOnMainAndWait false
        if (!runIfProactiveAuthorizationCurrent(authorization) { persist() }) return@runOnMainAndWait false
        if (!isProactivePublicationAuthorized(authorization) || agentApiClient !== client) return@runOnMainAndWait false
        syncWorker.execute {
            sendPendingAgentAcks(client, authorization)
        }
        true
    }

    /** Direct entry for instrumentation; production scheduling still uses the debounced drain. */
    internal fun recordProactiveTriggerForTesting(trigger: CoachTrigger) = proactiveCoachCoordinator.record(trigger)

    /** Direct entry for instrumentation; runs the real coordinator, scheduler and transport path. */
    internal fun runProactiveDueForTesting(): ProactiveRunResult = proactiveCoachCoordinator.runDue()

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

    private fun dispatchPreauthorizedInbox(
        authorization: ProactivePublicationAuthorization? = null,
    ): Boolean {
        val authorizationIsCurrent = {
            if (authorization == null) isAgentRequestAllowed()
            else isProactivePublicationAuthorized(authorization)
        }
        fun runWhileAuthorized(effect: () -> Unit): Boolean = synchronized(proactiveAuthorizationEffectLock) {
            if (!authorizationIsCurrent()) return@synchronized false
            effect()
            true
        }

        if (!runWhileAuthorized { persistOrThrow("agent.inbox") }) {
            latestMessage = "Impossible d'enregistrer les propositions; aucune action automatique n'a été exécutée."
            touch()
            return false
        }
        try {
            agentInboxRepository.visible().forEach { entry ->
                if (agentMandate.mode == AgentMode.PREAUTHORIZED) {
                    if (!runWhileAuthorized { agentDispatcher.dispatchPreauthorized(entry.id) }) return false
                } else if (!authorizationIsCurrent()) {
                    return false
                }
            }
            if (!runWhileAuthorized { persistOrThrow("agent.inbox") }) return false
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

    private fun sendPendingAgentAcks(
        client: AgentApiClient,
        authorization: ProactivePublicationAuthorization? = null,
    ) {
        val proactiveAuthorizationAllowed: (() -> Boolean)? = authorization?.let { captured ->
            { agentApiClient === client && isProactivePublicationAuthorized(captured) }
        }
        val authorizationIsCurrent = proactiveAuthorizationAllowed ?: { isAgentRequestAllowed() }
        if (!authorizationIsCurrent()) return
        agentInboxRepository.pendingAcks().forEach { entry ->
            if (!authorizationIsCurrent()) return
            try {
                if (entry.proposal.state == ProposalState.EXECUTED) {
                    try {
                        if (!authorizationIsCurrent()) return
                        if (authorization != null) proactiveAckAttemptObserverForTesting?.invoke()
                        client.acknowledge(
                            entry.id,
                            "accepted",
                            null,
                            clock.nowMillis(),
                            authorizationAllowed = proactiveAuthorizationAllowed,
                        )
                    } catch (error: AgentApiException) {
                        if (error.statusCode != 409) throw error
                    }
                    if (!authorizationIsCurrent()) return
                    if (authorization != null) proactiveAckAttemptObserverForTesting?.invoke()
                    client.acknowledge(
                        entry.id,
                        "executed",
                        entry.effect,
                        clock.nowMillis(),
                        authorizationAllowed = proactiveAuthorizationAllowed,
                    )
                } else {
                    if (!authorizationIsCurrent()) return
                    if (authorization != null) proactiveAckAttemptObserverForTesting?.invoke()
                    client.acknowledge(
                        entry.id,
                        entry.proposal.state.wireValue,
                        entry.effect,
                        clock.nowMillis(),
                        authorizationAllowed = proactiveAuthorizationAllowed,
                    )
                }
                if (!authorizationIsCurrent()) return
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
        val actionType = agentInboxRepository.get(proposalId)?.proposal?.actionType
        val result = agentDispatcher.accept(proposalId)
        latestMessage = when (result.status) {
            dev.atlas.coach.sync.agent.dispatch.DispatchStatus.EXECUTED ->
                if (result.effect.contains("planifi", ignoreCase = true)) "Action planifiée" else result.effect
            dev.atlas.coach.sync.agent.dispatch.DispatchStatus.REFUSED -> result.effect
            else -> result.effect
        }
        val focusStartCommitted = result.status == dev.atlas.coach.sync.agent.dispatch.DispatchStatus.EXECUTED &&
            actionType == AgentActionType.FOCUS_START.wireValue
        if (!focusStartCommitted) persist()
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
        startAgentProposedFocus(sessionProposalId, taskTitle, config, goalId = null)
    }

    fun startAgentProposedFocus(
        sessionProposalId: String,
        taskTitle: String?,
        config: ReefPomodoroConfig?,
        goalId: String?,
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
        val previousGoalId = acceptedAgentFocusGoalId.get()
        acceptedAgentFocusGoalId.set(goalId)
        try {
            acceptAgentProposal(start.proposalId)
        } finally {
            if (previousGoalId == null) acceptedAgentFocusGoalId.remove()
            else acceptedAgentFocusGoalId.set(previousGoalId)
        }
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
            clearLastSharedContextPayload()
            queueProactiveDisableAndPurge()
            synchronized(proactiveAuthorizationEffectLock) {
                capabilities = capabilities + (CAP_AGENT to false)
                agentMandateStore.revoke()
            }
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
            refreshProactiveCoachWork()
        }
    }

    fun enableAgent() {
        agentAuthorizationCommitInProgress.set(true)
        val previousCapability = capabilities[CAP_AGENT] == true
        val previousMandate = agentMandateStore.current()
        try {
            if (previousMandate.revoked || previousMandate.suspended) {
                agentContextCoordinator.resetCategoryConsents(previousMandate.sharedDataCategories)
            }
            synchronized(proactiveAuthorizationEffectLock) {
                capabilities = capabilities + (CAP_AGENT to true)
                if (previousMandate.revoked || previousMandate.suspended) {
                    agentMandateStore.replace(previousMandate.copy(revoked = false, suspended = false))
                    agentRequestGeneration.incrementAndGet()
                }
            }
            persistOrThrow("agent.enable")
            val eligible = canExplicitOptInClearBarrier()
            val barrierWriteSucceeded = if (eligible) revocationBarrier.clearAfterDurableOptIn(true) else true
            if (!barrierWriteSucceeded) {
                recordPersistenceFailure("agent.enable", PersistFailureKind.STORAGE_IO)
                latestMessage = "Activation enregistrée, mais l'agent reste bloqué car la barrière locale n'a pas pu être levée."
            } else if (!eligible) {
                latestMessage = "Agent activé localement; le mandat est encore suspendu ou expiré."
            } else if (revocationBarrier.isAgentBlocked()) {
                latestMessage = "Agent activé, mais une révocation générale reste en attente."
            } else if (revocationBarrier.blockedCategories().isNotEmpty()) {
                latestMessage = "Agent activé; les catégories retirées restent exclues jusqu'à leur nouvelle activation."
            } else {
                latestMessage = "Agent réactivé en mode propositions seulement."
            }
            agentStatus = AgentStatus.UNAVAILABLE
            touch()
        } catch (_: Exception) {
            synchronized(proactiveAuthorizationEffectLock) {
                capabilities = capabilities + (CAP_AGENT to previousCapability)
                agentMandateStore.replace(previousMandate)
                agentRequestGeneration.incrementAndGet()
            }
            latestMessage = "Impossible d'enregistrer la réactivation. L'agent reste bloqué."
            touch()
        } finally {
            agentAuthorizationCommitInProgress.set(false)
            refreshAgentApiRuntime()
            refreshProactiveCoachWork()
        }
    }

    fun suspendAgentMandate() {
        val barrierStored = blockAgentForRevocation("agent.suspend")
        agentAuthorizationCommitInProgress.set(true)
        try {
            clearLastSharedContextPayload()
            synchronized(proactiveAuthorizationEffectLock) {
                agentMandateStore.replace(agentMandateStore.current().copy(suspended = true))
            }
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
            refreshProactiveCoachWork()
        }
    }

    fun resumeAgentMandate() {
        agentAuthorizationCommitInProgress.set(true)
        val previous = agentMandateStore.current()
        try {
            agentContextCoordinator.resetCategoryConsents(previous.sharedDataCategories)
            synchronized(proactiveAuthorizationEffectLock) {
                agentMandateStore.replace(previous.copy(suspended = false, revoked = false))
                agentRequestGeneration.incrementAndGet()
            }
            persistOrThrow("agent.resume")
            val eligible = canExplicitOptInClearBarrier()
            val barrierWriteSucceeded = if (eligible) revocationBarrier.clearAfterDurableOptIn(true) else true
            if (!barrierWriteSucceeded) {
                recordPersistenceFailure("agent.resume", PersistFailureKind.STORAGE_IO)
                latestMessage = "Mandat enregistré, mais l'agent reste bloqué car la barrière locale n'a pas pu être levée."
            } else if (eligible && !revocationBarrier.isAgentBlocked()) {
                latestMessage = if (revocationBarrier.blockedCategories().isEmpty()) {
                    "Mandat réactivé en mode propositions seulement."
                } else {
                    "Mandat réactivé; les catégories retirées restent exclues jusqu'à leur nouvelle activation."
                }
            } else {
                latestMessage = "Mandat enregistré, mais l'agent reste bloqué."
            }
            touch()
        } catch (_: Exception) {
            synchronized(proactiveAuthorizationEffectLock) {
                agentMandateStore.replace(previous)
                agentRequestGeneration.incrementAndGet()
            }
            latestMessage = "Impossible d'enregistrer la réactivation du mandat. L'agent reste bloqué."
            touch()
        } finally {
            agentAuthorizationCommitInProgress.set(false)
            refreshAgentApiRuntime()
            refreshProactiveCoachWork()
        }
    }

    fun revokeAgentMandate() {
        val barrierStored = blockAgentForRevocation("agent.revoke")
        agentAuthorizationCommitInProgress.set(true)
        try {
            clearLastSharedContextPayload()
            queueProactiveDisableAndPurge()
            synchronized(proactiveAuthorizationEffectLock) {
                agentMandateStore.revoke()
            }
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
            refreshProactiveCoachWork()
        }
    }

    fun setAgentMode(mode: AgentMode) = updateAgentMandate { it.copy(mode = mode) }

    fun setAgentActionAllowed(action: String, allowed: Boolean) = updateAgentMandate { mandate ->
        mandate.copy(allowedActions = if (allowed) mandate.allowedActions + action else mandate.allowedActions - action)
    }

    fun setAgentSharedCategory(category: String, allowed: Boolean) {
        require(category in AgentContextCategory.wireValueSet) { "Unknown context category" }
        val wasAuthorizationCommitInProgress = if (!allowed) {
            agentAuthorizationCommitInProgress.getAndSet(true)
        } else {
            false
        }
        try {
            if (!allowed) {
                blockAgentForRevocation("agent.category_consent", category)
                purgeLastSharedContextCategory(category)
            }
            updateAgentMandate(
                operation = "agent.category_consent",
                explicitOptIn = allowed,
                revocation = !allowed,
                optInCategory = category,
            ) { mandate ->
                val next = mandate.copy(
                    sharedDataCategories = if (allowed) mandate.sharedDataCategories + category else mandate.sharedDataCategories - category,
                )
                agentContextCoordinator.setCategoryConsent(
                    category,
                    allowed,
                    resetIncludeExistingGoals = category == AgentContextCategory.GOALS.wireValue,
                )
                if (!allowed) queueProactivePurgeRevoked()
                if (!allowed && category == AgentContextCategory.LOCATION_SUMMARY.wireValue) {
                    locationPanelCoordinator.setCoachSummaryConsent(false)
                }
                next
            }
        } finally {
            if (!allowed && !wasAuthorizationCommitInProgress) {
                agentAuthorizationCommitInProgress.set(false)
            }
        }
    }

    fun setIncludeExistingAgentGoals(enabled: Boolean) {
        if (enabled && AgentContextCategory.GOALS.wireValue !in agentContextCoordinator.preview().authorizedCategories) {
            latestMessage = "Activez d'abord le partage de la catégorie Objectifs."
            touch()
            return
        }
        try {
            agentContextCoordinator.setIncludeExistingGoals(enabled)
            latestMessage = if (enabled) {
                "Choix enregistré. Les objectifs actifs existants pourront apparaître dans le prochain envoi."
            } else {
                "Choix enregistré. Seuls les objectifs créés ou modifiés depuis le consentement seront inclus."
            }
            touch()
        } catch (_: Exception) {
            latestMessage = "Impossible d'enregistrer ce choix. Les objectifs actifs existants restent exclus."
            touch()
        }
    }

    fun deleteLastContextDisclosureHistory() {
        val previousAudit = lastContextDisclosureAuditSnapshot
        clearLastSharedContextPayload()
        lastContextDisclosureAuditSnapshot = null
        try {
            persistOrThrow("agent.context_audit_delete")
            latestMessage = "Historique local du contexte supprimé. Une copie déjà reçue par le backend n'est pas supprimée."
        } catch (_: Exception) {
            lastContextDisclosureAuditSnapshot = previousAudit
            latestMessage = "Impossible de supprimer durablement l'historique local. Une copie déjà reçue par le backend n'est pas supprimée."
        }
        touch()
    }

    private fun purgeLastSharedContextCategory(category: String) {
        lastSharedAgentContextJsonSnapshot = LastContextDisclosureAudit.purgePayloadCategory(
            lastSharedAgentContextJsonSnapshot,
            category,
        )
    }

    private fun clearLastSharedContextPayload() {
        lastSharedAgentContextJsonSnapshot = LastContextDisclosureAudit.purgeAllPayloadValues(
            lastSharedAgentContextJsonSnapshot,
        )
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
        val wasAuthorizationCommitInProgress = agentAuthorizationCommitInProgress.getAndSet(true)
        val current = agentMandateStore.current()
        try {
            val updated = transform(current)
            replaceAgentMandate(updated, refreshRuntime = false)
            try {
                persistOrThrow(operation)
            } catch (error: Exception) {
                if (!revocation) replaceAgentMandate(current, refreshRuntime = false)
                latestMessage = if (revocation) {
                    if (revocationBarrier.isAgentBlocked()) {
                        "Agent bloqué dans cette session; stockage en échec, le retrait n'est pas confirmé."
                    } else {
                        "Cette catégorie reste exclue dans cette session; son retrait n'a pas pu être enregistré durablement."
                    }
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
                latestMessage = "Consentement enregistré, mais cette catégorie reste exclue car la barrière locale n'a pas pu être levée."
            } else if (revocation && revocationBarrier.isAgentBlocked()) {
                latestMessage = "Autorisation retirée durablement; l'agent reste bloqué jusqu'à une nouvelle activation explicite."
            } else if (revocation && optInCategory != null) {
                latestMessage = "Autorisation retirée durablement; cette catégorie reste exclue jusqu'à une nouvelle activation explicite."
            } else if (explicitOptIn && revocationBarrier.isAgentBlocked()) {
                latestMessage = "Consentement enregistré, mais l'agent reste bloqué par une révocation générale en attente."
            } else if (explicitOptIn && revocationBarrier.blockedCategories().isNotEmpty()) {
                latestMessage = "Consentement enregistré; les autres catégories retirées restent exclues jusqu'à leur réactivation."
            } else {
                latestMessage = "Mandat enregistré · ${agentMandate.version}."
            }
            touch()
        } catch (error: Exception) {
            latestMessage = if (error is PersistOperationException) {
                if (revocationBarrier.isAgentBlocked()) {
                    "Impossible d'enregistrer le changement; l'agent reste bloqué et le retrait n'est pas confirmé."
                } else if (revocation) {
                    "Cette catégorie reste exclue dans cette session; le retrait n'a pas pu être enregistré durablement."
                } else {
                    "Impossible d'enregistrer la modification du mandat."
                }
            } else {
                "Modification du mandat invalide."
            }
            touch()
        } finally {
            if (!wasAuthorizationCommitInProgress) agentAuthorizationCommitInProgress.set(false)
            refreshAgentApiRuntime()
        }
    }

    private fun replaceAgentMandate(mandate: AgentMandate, refreshRuntime: Boolean = true) {
        synchronized(proactiveAuthorizationEffectLock) {
            agentMandateStore.replace(mandate.copy(id = agentMandateStore.current().id))
            agentRequestGeneration.incrementAndGet()
        }
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

    private fun isAllowedAgentBackendUrl(value: String): Boolean {
        if (!isAllowedBackendUrl(value)) return false
        val debuggable = appContext.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        return BackendUrlPolicy.isAllowedAgentBackendUrl(value, debuggable)
    }

    /**
     * Plan-revision client for the explicit coach flow. Proposal-only: the
     * capability and a live mandate are rechecked on every request.
     */
    /** Read-only sport projection; fixtures are used only when the user explicitly asks for demo mode. */
    fun selectSportDemoMode(enabled: Boolean) {
        sportDemoMode = enabled
        sportMessage = null
        if (enabled) sportPlanState = null else refreshSportPlan()
    }

    fun refreshSportPlan() {
        if (sportDemoMode || sportBusy) return
        val base = syncBaseUrl.trim().trimEnd('/')
        if (base.isBlank() || !isAllowedAgentBackendUrl(base)) {
            sportPlanState = SportPlanUiState(plan = null, syncState = SyncState.UNAVAILABLE)
            sportMessage = "Backend non configuré; la séance sportive reste indisponible."
            return
        }
        val token = try {
            syncTokenStore.token()?.trim()?.takeIf(String::isNotEmpty)
        } catch (_: Exception) {
            null
        }
        if (token == null) {
            sportPlanState = SportPlanUiState(plan = null, syncState = SyncState.UNAVAILABLE)
            sportMessage = "Jeton backend requis; aucune séance n'est inventée."
            return
        }
        val zone = ZoneId.systemDefault()
        val localDate = LocalDate.now(zone).toString()
        sportBusy = true
        sportMessage = null
        syncWorker.execute {
            val state = SportPlanClient().fetch(base, token, localDate, zone.id)
            mainHandler.post {
                sportPlanState = state
                sportBusy = false
                sportMessage = when (state.syncState) {
                    SyncState.FRESH -> null
                    SyncState.STALE -> "Données sportives périmées; aucune performance n'est inventée."
                    SyncState.UNAVAILABLE -> "Sport indisponible; la dernière projection connue peut manquer."
                    SyncState.MISMATCH -> "Réponse sportive incohérente; elle est refusée."
                }
            }
        }
    }

    fun planRevisionClient(): PlanRevisionProposalClient? {
        val requestAllowed = {
            capabilities[CAP_PLAN_REVISION] == true &&
                capabilities[CAP_AGENT] == true &&
                !revocationBarrier.isAgentBlocked() &&
                !agentMandateStore.current().let { mandate ->
                    mandate.revoked || mandate.suspended || clock.nowMillis() >= mandate.expiresAtUtcMillis
                }
        }
        when (relaySettings.mode) {
            RelayTransportMode.RELAY -> return relaySettings.relayPlanRevisionClient(requestAllowed)
            RelayTransportMode.OFFLINE -> return null
            RelayTransportMode.DIRECT -> Unit
        }
        if (syncTokenStore.token() == null) return null
        if (syncBaseUrl.isBlank() || !isAllowedAgentBackendUrl(syncBaseUrl)) return null
        return PlanRevisionApiClient(
            baseUrl = syncBaseUrl,
            tokenProvider = syncTokenStore,
            requestAllowed = requestAllowed,
        )
    }

    val planRevisionMandate: AgentMandate get() = agentMandateStore.current()

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
        val previousCoachState = if (choice == DirectiveChoice.START) coach.snapshotState() else null
        try {
            val result = coach.respond(activeDirective.id, choice)
            directiveSnapshot = coach.currentDirective()
            latestMessage = result.message
            if (choice == DirectiveChoice.START) {
                val seconds = ((result.requestedDurationMinutes ?: 10) * 60L).coerceAtLeast(1L)
                val committed = startFocusTransaction(
                    plan = FocusPlan.custom(seconds).copy(presetId = "directive-" + activeDirective.id),
                    directiveId = activeDirective.id,
                    goalId = null,
                    successMessage = result.message,
                )
                if (!committed) {
                    previousCoachState?.let(coach::restoreState)
                    directiveSnapshot = coach.currentDirective()
                    return
                }
                runCatching {
                    enqueueSyncEvent(
                        SyncEventType.DIRECTIVE_RESPONDED,
                        SyncDataCategory.COACH_INTERACTION,
                        mapOf("choice" to choice.contractValue),
                    )
                }
                runCatching { sendRemoteCoachResponse(choice) }
                return
            }
            enqueueSyncEvent(
                SyncEventType.DIRECTIVE_RESPONDED,
                SyncDataCategory.COACH_INTERACTION,
                mapOf("choice" to choice.contractValue),
            )
            sendRemoteCoachResponse(choice)
        } catch (error: IllegalArgumentException) {
            latestMessage = error.message ?: "Cette réponse n'est pas disponible."
        }
        persist()
    }

    override fun setHomeDirective(proposal: ProposalV2): AgentActionOutcome {
        val next = agentHomeStateSnapshot.withRemoteDirective(proposal)
        return persistAgentAction(
            proposal = proposal,
            successMessage = "Directive HOME enregistrée localement.",
            overrides = mapOf("agentHomeState" to next.toJson().toString()),
            publish = { agentHomeStateSnapshot = next },
        )
    }

    override fun reorderHomeCards(proposal: ProposalV2): AgentActionOutcome {
        val requested = (proposal.parameters["order"] as List<*>).filterIsInstance<String>()
        val completed = requested + AgentHomeState.DEFAULT_CARD_ORDER.filterNot(requested::contains)
        val next = agentHomeStateSnapshot.copy(cardOrder = completed)
        return persistAgentAction(
            proposal = proposal,
            successMessage = "Ordre des cartes HOME enregistré; les autres cartes restent visibles.",
            overrides = mapOf("agentHomeState" to next.toJson().toString()),
            publish = { agentHomeStateSnapshot = next },
        )
    }

    override fun proposeFocus(proposal: ProposalV2): AgentActionOutcome {
        val previous = agentHomeStateSnapshot.focusProposal
        if (previous?.state in setOf(AgentFocusCardState.PLANNED, AgentFocusCardState.RUNNING, AgentFocusCardState.PAUSED)) {
            return AgentActionOutcome(false, "Une session Focus agent est déjà planifiée ou active; annulez-la avant de la remplacer.")
        }
        val card = AgentFocusProposalCard(
            proposalId = proposal.proposalId,
            label = proposal.parameters["label"] as? String ?: "Session proposée",
            focusMinutes = (proposal.parameters["focus_minutes"] as Number).toInt(),
            breakMinutes = (proposal.parameters["break_minutes"] as Number).toInt(),
            cycles = (proposal.parameters["cycles"] as Number).toInt(),
            longBreakMinutes = 15,
            longBreakEveryCycles = minOf(4, (proposal.parameters["cycles"] as Number).toInt()),
        )
        val next = agentHomeStateSnapshot.copy(
            focusProposal = card,
            cancelledFocusProposalIds = previous?.let {
                agentHomeStateSnapshot.cancelledFocusProposalIds + it.proposalId
            } ?: agentHomeStateSnapshot.cancelledFocusProposalIds,
        )
        val retiredRoutineId = previous?.routineId
        val nextRoutines = focusRoutineRepository.list()
            .filterNot { it.id == retiredRoutineId }
            .map(FocusRoutine::toMap)
        return persistAgentAction(
            proposal = proposal,
            successMessage = "Session proposée enregistrée localement.",
            overrides = mapOf(
                "agentHomeState" to next.toJson().toString(),
                "focusRoutines" to nextRoutines,
            ),
            publish = {
                focusRoutinesSnapshot = focusRoutineRepository.list().filterNot { it.id == retiredRoutineId }
                agentHomeStateSnapshot = next
                retiredRoutineId?.let { routineAlarms.cancel(it); focusRoutineRepository.delete(it) }
                retireReplacedFocusProposal(previous?.proposalId)
                focusRoutinesSnapshot = focusRoutineRepository.list()
            },
        )
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
        val next = agentHomeStateSnapshot.copy(
            focusProposal = card,
            cancelledFocusProposalIds = previous?.let {
                agentHomeStateSnapshot.cancelledFocusProposalIds + it.proposalId
            } ?: agentHomeStateSnapshot.cancelledFocusProposalIds,
        )
        val retiredRoutineId = previous?.routineId
        val nextRoutines = (focusRoutineRepository.list().filterNot { it.id == retiredRoutineId } + routine)
            .map(FocusRoutine::toMap)
        return persistAgentAction(
            proposal = proposal,
            successMessage = "Session planifiée avec une alarme inexacte; le démarrage demandera une confirmation.",
            overrides = mapOf(
                "agentHomeState" to next.toJson().toString(),
                "focusRoutines" to nextRoutines,
            ),
            rollbackPreparation = {
                focusRoutineRepository.delete(routine.id)
                focusRoutinesSnapshot = focusRoutineRepository.list()
            },
            publish = {
                agentHomeStateSnapshot = next
                retiredRoutineId?.let { routineAlarms.cancel(it); focusRoutineRepository.delete(it) }
                retireReplacedFocusProposal(previous?.proposalId)
                focusRoutinesSnapshot = focusRoutineRepository.list()
                routineAlarms.schedule(routine, ZonedDateTime.now(zone), zone)
            },
        )
    }

    override fun startFocus(proposal: ProposalV2): AgentActionOutcome =
        synchronized(goalFocusTransactionLock) { startAgentFocusTransaction(proposal) }

    private fun startAgentFocusTransaction(proposal: ProposalV2): AgentActionOutcome {
        val ref = proposal.parameters.getValue("session_ref") as String
        val card = agentHomeStateSnapshot.focusProposal?.takeIf {
            it.proposalId == ref && it.state !in setOf(AgentFocusCardState.CANCELLED, AgentFocusCardState.FINISHED)
        } ?: return AgentActionOutcome(false, "Session proposée absente ou annulée.")
        if (card.continuous) {
            return AgentActionOutcome(false, "Une session Focus continue n'est pas autorisée par un mandat à durée bornée.")
        }
        val mandate = agentMandateStore.current()
        val focusWindow = mandate.focusWindow
        val zone = ZoneId.of(focusWindow.timezone)
        val now = clock.nowMillis()
        val scheduled = Instant.ofEpochMilli(now).atZone(zone)
        val sessionMinutes = focusSessionDurationMinutes(
            focusMinutes = card.focusMinutes.toLong(),
            breakMinutes = card.breakMinutes.toLong(),
            cycles = card.cycles.toLong(),
            longBreakMinutes = card.longBreakMinutes.toLong(),
            longBreakEveryCycles = card.longBreakEveryCycles.toLong(),
            continuous = card.continuous,
        )
        if (sessionMinutes > focusWindow.maxMinutesPerSession.toLong()) {
            return AgentActionOutcome(false, "Durée totale supérieure à la borne du mandat.")
        }
        if (scheduled.dayOfWeek.value !in focusWindow.days) {
            return AgentActionOutcome(false, "Jour hors fenêtre du mandat.")
        }
        val start = LocalTime.parse(focusWindow.start)
        val end = LocalTime.parse(focusWindow.end)
        if (!focusTimeInWindow(scheduled.toLocalTime(), start, end)) {
            return AgentActionOutcome(false, "Heure hors fenêtre du mandat.")
        }
        if (!focusPlanEndWithinWindow(scheduled, sessionMinutes, start, end)) {
            return AgentActionOutcome(false, "Fin du plan hors fenêtre du mandat.")
        }
        val active = focusEngine.currentSession()
        if (active?.status in setOf(FocusStatus.RUNNING, FocusStatus.PAUSED)) {
            return AgentActionOutcome(false, "Une autre session Focus est déjà active.")
        }
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
        val preparedStart = try {
            focusEngine.prepareStart(
                plan = plan,
                directiveId = card.proposalId,
                sessionId = UUID.randomUUID().toString(),
                startedAtUtcMillis = now,
                startedAtMonotonicMillis = clock.monotonicMillis(),
            )
        } catch (error: Exception) {
            return AgentActionOutcome(false, error.message ?: "Impossible de préparer la session Focus.")
        }
        val goalId = acceptedAgentFocusGoalId.get()
        val preparedGoalLink = try {
            goalId?.let { goalPanelCoordinator.prepareFocusLink(it, preparedStart.session.id, now) }
        } catch (error: Exception) {
            return AgentActionOutcome(false, error.message ?: "Impossible de lier la session à cet objectif.")
        }
        val terminalSession = focusEngine.currentSession()?.takeIf {
            it.status == FocusStatus.FINISHED || it.status == FocusStatus.CANCELLED
        }
        val nextHistory = terminalSession?.let { terminal ->
            (listOf(terminal) + focusHistoryEntries.filterNot { it.id == terminal.id }).take(MAX_FOCUS_HISTORY)
        } ?: focusHistoryEntries
        val nextHome = agentHomeStateSnapshot.copy(focusProposal = card.copy(state = AgentFocusCardState.RUNNING))
        val overrides = buildMap<String, Any?> {
            put("focus", preparedStart.session.toMap())
            put("focusHistory", nextHistory.map(FocusSession::toMap))
            put("agentHomeState", nextHome.toJson().toString())
            preparedGoalLink?.let { put("goals", it.goalsSubdocument) }
        }
        return persistAgentAction(
            proposal = proposal,
            successMessage = "Focus démarré localement sans ouvrir d'activité.",
            overrides = overrides,
            preflight = {
                if (preparedGoalLink != null &&
                    goalPanelCoordinator.repository.all() != preparedGoalLink.expectedRecords
                ) {
                    throw IllegalStateException("L'objectif a changé pendant la préparation. Réessayez le démarrage.")
                }
            },
            publish = {
                focusHistoryEntries = nextHistory
                focusSnapshot = focusEngine.publishPreparedStart(preparedStart)
                if (preparedGoalLink != null) {
                    goalPanelCoordinator.publishCommittedFocusLink(preparedGoalLink)
                    goalsSubdocument = preparedGoalLink.goalsSubdocument
                }
            },
            afterCommit = {
                if (capabilities[CAP_FOCUS_NOTIFICATIONS] == true) {
                    runCatching { phaseAlarms.schedule(preparedStart.session) }
                }
                runCatching { enqueueSyncEvent(SyncEventType.FOCUS_STARTED, SyncDataCategory.FOCUS) }
            },
            afterEffects = { agentHomeStateSnapshot = nextHome },
        )
    }

    override fun pauseFocus(proposal: ProposalV2): AgentActionOutcome {
        val active = focusEngine.currentSession() ?: return AgentActionOutcome(false, "Aucune session Focus active.")
        if (active.status != FocusStatus.RUNNING) return AgentActionOutcome(false, "La session Focus n'est pas en cours.")
        val ref = proposal.parameters.getValue("session_ref") as String
        if ((active.directiveId ?: active.id) != ref) return AgentActionOutcome(false, "La référence ne correspond pas à la session active.")
        val paused = active.copy(
            status = FocusStatus.PAUSED,
            updatedAtUtcMillis = clock.nowMillis(),
        )
        val nextHome = agentHomeStateSnapshot.focusProposal?.takeIf { it.proposalId == ref }?.let { card ->
            agentHomeStateSnapshot.copy(focusProposal = card.copy(state = AgentFocusCardState.PAUSED))
        } ?: agentHomeStateSnapshot
        return persistAgentAction(
            proposal = proposal,
            successMessage = "Session Focus mise en pause.",
            overrides = mapOf(
                "focus" to paused.toMap(),
                "agentHomeState" to nextHome.toJson().toString(),
            ),
            publish = {
                focusSnapshot = focusEngine.restore(paused)
                agentHomeStateSnapshot = nextHome
                phaseAlarms.cancel(active.id)
                focusNotifications.cancel(active.id)
            },
        )
    }

    override fun suggestHabit(proposal: ProposalV2): AgentActionOutcome {
        val name = proposal.parameters.getValue("name") as String
        val schedule = proposal.parameters.getValue("schedule") as String
        val next = (agentSuggestionsSnapshot + "Habitude proposée : $name · $schedule").takeLast(100)
        return persistAgentAction(
            proposal = proposal,
            successMessage = "Suggestion d'habitude enregistrée; aucune habitude n'a été créée sans confirmation.",
            overrides = mapOf("agentSuggestions" to next),
            publish = { agentSuggestionsSnapshot = next },
        )
    }

    override fun proposeAppPolicy(proposal: ProposalV2): AgentActionOutcome {
        val packageRef = proposal.parameters.getValue("package_ref") as String
        val minutes = proposal.parameters.getValue("daily_limit_minutes") as Number
        val next = (agentSuggestionsSnapshot +
            "Politique proposée pour $packageRef : ${minutes.toInt()} min/jour · aucune règle modifiée.").takeLast(100)
        return persistAgentAction(
            proposal = proposal,
            successMessage = "Politique suggérée localement; elle n'est pas encore appliquée.",
            overrides = mapOf("agentSuggestions" to next),
            publish = { agentSuggestionsSnapshot = next },
        )
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
        val next = agentHomeStateSnapshot.copy(contentRecommendation = AgentContentCard(contentId, url, title))
        return persistAgentAction(
            proposal = proposal,
            successMessage = "Contenu recommandé sur HOME; son ouverture reste manuelle.",
            overrides = mapOf("agentHomeState" to next.toJson().toString()),
            publish = { agentHomeStateSnapshot = next },
        )
    }

    private fun persistAgentAction(
        proposal: ProposalV2,
        successMessage: String,
        overrides: Map<String, Any?>,
        rollbackPreparation: () -> Unit = {},
        preflight: () -> Unit = {},
        publish: () -> Unit,
        afterCommit: () -> Unit = {},
        afterEffects: () -> Unit = {},
    ): AgentActionOutcome {
        val operation = "agent.action.${proposal.actionType}"
        val candidate = try {
            stateMap() + overrides + mapOf(
                "latestMessage" to successMessage,
                "appliedLocalAgentEffectKeys" to (appliedLocalAgentEffectKeys + proposal.idempotencyKey).toList(),
            )
        } catch (error: Exception) {
            recordPersistenceFailure(operation, persistenceFailureKind(error))
            return failedAgentAction(rollbackPreparation)
        }
        try {
            preflight()
        } catch (error: Exception) {
            val message = error.message ?: "L'état a changé avant l'enregistrement de l'action."
            latestMessage = message
            touch()
            return AgentActionOutcome(applied = false, effect = message)
        }
        try {
            persistOrThrow(operation, stateOverride = candidate)
        } catch (_: PersistOperationException) {
            return failedAgentAction(rollbackPreparation)
        }
        runCatching(publish)
        appliedLocalAgentEffectKeys += proposal.idempotencyKey
        runCatching(afterCommit)
        runCatching(afterEffects)
        latestMessage = successMessage
        touch()
        return AgentActionOutcome(true, successMessage)
    }

    private fun failedAgentAction(rollbackPreparation: () -> Unit): AgentActionOutcome {
        runCatching(rollbackPreparation)
        runCatching {
            focusSnapshot = focusEngine.currentSession()
            refreshDomainSnapshots()
        }
        val message = "Impossible d'enregistrer l'état local. L'action n'a pas été appliquée et reste en réconciliation."
        latestMessage = message
        touch()
        return AgentActionOutcome(applied = false, effect = message, requiresReconciliation = true)
    }

    private fun retireReplacedFocusProposal(proposalId: String?) {
        if (proposalId == null) return
        runCatching {
            agentInboxRepository.get(proposalId)
                ?.takeIf { !it.proposal.state.terminal }
                ?.let { agentInboxRepository.update(proposalId, ProposalState.CANCELLED, "Session proposée remplacée localement") }
        }
    }

    override fun reconcile(proposal: ProposalV2, idempotencyKey: String): AgentEffectReconciliation {
        if (proposal.idempotencyKey != idempotencyKey) return AgentEffectReconciliation.UNKNOWN
        if (idempotencyKey in appliedLocalAgentEffectKeys) return AgentEffectReconciliation.APPLIED
        // The business state and this marker share one root commit; alarms and notifications
        // are scheduled only after that commit, so a missing marker proves no action was applied.
        return when (AgentActionType.parse(proposal.actionType)) {
            AgentActionType.HOME_SET_DIRECTIVE,
            AgentActionType.HOME_REORDER_CARDS,
            AgentActionType.HABIT_SUGGEST,
            AgentActionType.APP_POLICY_PROPOSE,
            AgentActionType.APP_POLICY_ADJUST,
            AgentActionType.FOCUS_PROPOSE,
            AgentActionType.FOCUS_PLAN,
            AgentActionType.FOCUS_START,
            AgentActionType.FOCUS_PAUSE,
            AgentActionType.CONTENT_RECOMMEND -> AgentEffectReconciliation.NOT_APPLIED
            null -> AgentEffectReconciliation.UNKNOWN
        }
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
        val reefConfig = when (preset) {
            FocusPreset.POMODORO_25_5 -> ReefPomodoroConfig.DEFAULT
            FocusPreset.VARIANT_10_2 -> ReefPomodoroConfig(10, 2, 15, 4)
        }.toPomodoroConfig()
        startFocus(reefConfig.toFocusPlan(preset.presetId))
    }

    /** Stable host API for preset, custom and deterministic integration-test plans. */
    fun startFocus(plan: FocusPlan) {
        startFocus(plan, goalId = null)
    }

    fun startFocus(plan: FocusPlan, goalId: String?) {
        startFocusTransaction(plan, directive.id, goalId, successMessage = focusStartMessage(plan))
    }

    private fun focusStartMessage(plan: FocusPlan): String =
        if (plan.continuous) {
            "Focus continu démarré · " + plan.workSeconds + "s de concentration par cycle."
        } else {
            "Focus démarré · " + plan.totalWorkCycles + " cycle" +
                (if (plan.totalWorkCycles == 1) "" else "s") + " au total."
        }

    private fun startFocusTransaction(
        plan: FocusPlan,
        directiveId: String?,
        goalId: String?,
        successMessage: String,
    ): Boolean = synchronized(goalFocusTransactionLock) {
        if (capabilities[CAP_FOCUS] != true) {
            latestMessage = "Le focus est désactivé dans les paramètres."
            touch()
            return@synchronized false
        }
        val previousFocus = focusEngine.currentSession()
        if (previousFocus?.status in setOf(FocusStatus.RUNNING, FocusStatus.PAUSED)) {
            latestMessage = "Une session Focus est déjà active."
            touch()
            return@synchronized false
        }

        val startedAtUtcMillis = clock.nowMillis()
        val preparedStart = try {
            focusEngine.prepareStart(
                plan = plan,
                directiveId = directiveId,
                sessionId = UUID.randomUUID().toString(),
                startedAtUtcMillis = startedAtUtcMillis,
                startedAtMonotonicMillis = clock.monotonicMillis(),
            )
        } catch (error: Exception) {
            latestMessage = error.message ?: "Impossible de préparer la session Focus."
            touch()
            return@synchronized false
        }
        val preparedGoalLink = try {
            goalId?.let {
                goalPanelCoordinator.prepareFocusLink(it, preparedStart.session.id, startedAtUtcMillis)
            }
        } catch (error: Exception) {
            latestMessage = error.message ?: "Impossible de lier la session à cet objectif."
            touch()
            return@synchronized false
        }
        val terminalFocus = previousFocus?.takeIf {
            it.status == FocusStatus.FINISHED || it.status == FocusStatus.CANCELLED
        }
        val nextHistory = terminalFocus?.let { terminal ->
            (listOf(terminal) + focusHistoryEntries.filterNot { it.id == terminal.id })
                .take(MAX_FOCUS_HISTORY)
        } ?: focusHistoryEntries
        val candidate = try {
            stateMap() + mapOf(
                "focus" to preparedStart.session.toMap(),
                "focusHistory" to nextHistory.map(FocusSession::toMap),
                "goals" to (preparedGoalLink?.goalsSubdocument ?: goalsSubdocument),
                "latestMessage" to successMessage,
            )
        } catch (error: Exception) {
            val reason = persistenceFailureKind(error)
            recordPersistenceFailure("focus.start", reason)
            latestMessage = "Impossible de préparer l'état local. La session n'a pas démarré."
            touch()
            return@synchronized false
        }

        // The Goal candidate must still describe the exact repository state immediately before commit.
        if (preparedGoalLink != null &&
            goalPanelCoordinator.repository.all() != preparedGoalLink.expectedRecords
        ) {
            latestMessage = "L'objectif a changé pendant la préparation. Réessayez le démarrage."
            touch()
            return@synchronized false
        }
        val focusBeforeCommit = focusEngine.currentSession()
        if (focusBeforeCommit?.status in setOf(FocusStatus.RUNNING, FocusStatus.PAUSED)) {
            latestMessage = "Une session Focus est déjà active."
            touch()
            return@synchronized false
        }
        try {
            persistOrThrow("focus.start", stateOverride = candidate)
        } catch (_: PersistOperationException) {
            latestMessage = "Focus non démarré. Aucun lien à l'objectif n'a été enregistré."
            touch()
            return@synchronized false
        }

        // S3 candidates are assignment-only: publish the committed state before any external effect.
        focusHistoryEntries = nextHistory
        focusSnapshot = focusEngine.publishPreparedStart(preparedStart)
        if (preparedGoalLink != null) {
            goalPanelCoordinator.publishCommittedFocusLink(preparedGoalLink)
            goalsSubdocument = preparedGoalLink.goalsSubdocument
        }
        if (capabilities[CAP_FOCUS_NOTIFICATIONS] == true) runCatching { phaseAlarms.schedule(preparedStart.session) }
        runCatching { enqueueSyncEvent(SyncEventType.FOCUS_STARTED, SyncDataCategory.FOCUS) }
        latestMessage = successMessage
        touch()
        runCatching { navigationState.navigate(AtlasScreenId.FOCUS) }
        true
    }

    fun startFocus(config: ReefPomodoroConfig) {
        startFocus(config, goalId = null)
    }

    @JvmName("startFocusForGoal")
    fun startFocus(config: ReefPomodoroConfig, goalId: String?) {
        val plan = config.toPomodoroConfig().toFocusPlan(
            "reef-pomodoro-${config.focusMinutes}-${config.shortBreakMinutes}-${config.longBreakMinutes}-${config.cycles}",
        )
        val message = if (config.continuous) {
            "Pomodoro démarré · " + config.focusMinutes + " min de concentration, " +
                "pause courte de " + config.shortBreakMinutes + " min · mode continu."
        } else {
            "Pomodoro démarré · " + config.focusMinutes + " min de concentration, " +
                "pause courte de " + config.shortBreakMinutes + " min · " +
                plan.totalWorkCycles + " cycles de travail au total."
        }
        startFocusTransaction(plan, directive.id, goalId, message)
    }

    fun startFocus(config: ReefPomodoroConfig, taskTitle: String) {
        startFocus(config, taskTitle, goalId = null)
    }

    fun startFocus(config: ReefPomodoroConfig, taskTitle: String, goalId: String?) {
        val normalizedTitle = taskTitle.trim()
        if (normalizedTitle.isEmpty() || normalizedTitle.length > FocusPlan.MAX_TASK_TITLE_LENGTH) {
            latestMessage = "Le titre de tâche doit contenir entre 1 et ${FocusPlan.MAX_TASK_TITLE_LENGTH} caractères."
            touch()
            return
        }
        val plan = config.toPomodoroConfig().toFocusPlan(
            "pomodoro-${config.focusMinutes}-${config.shortBreakMinutes}-${config.longBreakMinutes}-${config.cycles}",
        ).copy(taskTitle = normalizedTitle)
        val message = if (config.continuous) {
            "Pomodoro démarré · " + normalizedTitle + " · " + config.focusMinutes + " min par cycle, mode continu."
        } else {
            "Pomodoro démarré · " + normalizedTitle + " · " + config.focusMinutes +
                " min par cycle · " + plan.totalWorkCycles + " cycles."
        }
        startFocusTransaction(plan, directive.id, goalId, message)
    }

    fun startDemoFocus() {
        if (capabilities[CAP_FOCUS] != true) {
            latestMessage = "Le focus est désactivé dans les paramètres."
            touch()
            return
        }
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
        refreshProactiveCoachWork()
        relaySettings.onAppResumed()
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
        withProactiveAuthorizationClose(invalidateAgentRequests = true) {
            runCatching { UsageObservationWorkScheduler.cancel(appContext) }
            usageObserver.revokeConsent()
            queueProactivePurgeRevoked()
            pendingUsagePreferences.edit().remove(KEY_PENDING_USAGE_PACKAGES).apply()
            policy = policy.copy(allowlistedPackageNames = emptySet())
            capabilities = capabilities + (CAP_APP_TRACKING to false)
            latestMessage = message
            persist()
        }
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
        withProactiveAuthorizationClose {
            val now = clock.nowMillis()
            AndroidEmergencyPauseStore.pauseForThirtyMinutes(appContext, now)
            policy = policy.withEmergencyPause(now)
            locationPanelCoordinator.onEmergencyPause()
            queueProactiveDisableAndPurge()
            cancelPendingAgentWork("Pause d'urgence locale; action annulée")
            latestMessage = "Pause d'urgence active pendant 30 minutes."
            persist()
            refreshProactiveCoachWork()
        }
    }

    fun endEmergencyPause() {
        AndroidEmergencyPauseStore.clear(appContext)
        policy = policy.copy(pauseUntil = null)
        latestMessage = "La pause d'urgence est terminée."
        persist()
        refreshProactiveCoachWork()
    }

    fun setCapability(name: String, enabled: Boolean) {
        val invalidatesProactive = !enabled && capabilities[name] == true &&
            name in PROACTIVE_CONTEXT_CLOSING_CAPABILITIES && name != CAP_APP_TRACKING
        if (invalidatesProactive) {
            withProactiveAuthorizationClose { setCapabilityAfterInvalidation(name, enabled) }
        } else {
            setCapabilityAfterInvalidation(name, enabled)
        }
    }

    private fun setCapabilityAfterInvalidation(name: String, enabled: Boolean) {
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
        if (name == CAP_AGENT_PROACTIVE) refreshProactiveCoachWork()
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
    private fun persist(
        operation: String = "state",
        durable: Boolean = true,
        stateOverride: Map<String, Any?>? = null,
    ): PersistResult {
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
            val snapshot = stateOverride ?: try {
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
    private fun persistOrThrow(
        operation: String,
        durable: Boolean = true,
        stateOverride: Map<String, Any?>? = null,
    ): PersistResult {
        val result = persist(operation, durable, stateOverride)
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
            KEY_LAST_CONTEXT_DISCLOSURE_AUDIT to lastContextDisclosureAuditSnapshot?.toMap(),
            KEY_AGENT_INCLUDE_EXISTING_GOALS to agentContextCoordinator.includesExistingGoals(),
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
        const val CAP_PLAN_REVISION = "planRevision"
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
        private val PROACTIVE_CONTEXT_CLOSING_CAPABILITIES = setOf(
            CAP_AGENT_PROACTIVE,
            CAP_APP_TRACKING,
            CAP_USAGE_BACKGROUND,
            CAP_FOCUS,
            CAP_FOCUS_ROUTINES,
            CAP_HABITS,
            CAP_HABIT_REMINDERS,
            CAP_CONTENT,
            CAP_COACH,
            CAP_LOCATION,
            CAP_MOBILITY,
        )
        const val FOCUS_CHECKPOINT_INTERVAL_MILLIS = 20_000L
        const val MAX_FOCUS_HISTORY = 100
        const val MAX_FOCUS_ROUTINES = 100
        const val MAX_AGENT_MESSAGE_LENGTH = 2_000
        const val MAX_AGENT_CONVERSATION_MESSAGES = 80
        const val PROACTIVE_PROPOSAL_UNAVAILABLE_MESSAGE =
            "Cette proposition n'est plus disponible (expirée ou retirée). Aucune action n'a été effectuée."
        private const val MAX_AGENT_CONTENT_ACTIVITY = 200
        private const val KEY_AGENT_CONTEXT_CONSENT_DATES = "agentContextConsentAt"
        private const val KEY_LAST_CONTEXT_DISCLOSURE_AUDIT = "lastAgentContextDisclosureAudit"
        private const val KEY_AGENT_INCLUDE_EXISTING_GOALS = "agentIncludeExistingGoals"
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
                CAP_PLAN_REVISION to false,
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
