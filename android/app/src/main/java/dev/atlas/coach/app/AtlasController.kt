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
import dev.atlas.coach.data.AndroidStateBackend
import dev.atlas.coach.data.AtlasStateRecovery
import dev.atlas.coach.data.LocalStateStore
import dev.atlas.coach.data.RecoveredAtlasState
import dev.atlas.coach.data.StateReadResult
import dev.atlas.coach.data.localPolicyFromStorage
import dev.atlas.coach.data.ruleEvaluatorStateFromStorage
import dev.atlas.coach.data.toStorageMap
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
import dev.atlas.coach.usage.android.usageAccessSettingsIntent as systemUsageAccessSettingsIntent
import dev.atlas.coach.rules.AppEventSimulator
import dev.atlas.coach.rules.AppOpenEvent
import dev.atlas.coach.rules.Intervention
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.QuietHours
import dev.atlas.coach.rules.RuleEvaluator
import dev.atlas.coach.routines.FocusRoutine
import dev.atlas.coach.routines.InMemoryFocusRoutineRepository
import dev.atlas.coach.routines.RoutineEvaluator
import dev.atlas.coach.routines.RoutineSchedule
import dev.atlas.coach.sync.AndroidSyncStorage
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID
import java.net.URI
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Owns the small, synchronous local graph used by the Compose demo. */
class AtlasController(context: Context, private val clock: Clock = object : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
}) {
    private val appContext = context.applicationContext
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
    private val effectiveConsent = AtomicReference(SyncConsent.DISABLED)
    @Volatile private var remoteBackend: RemoteBackendGateway? = null
    @Volatile private var syncRetryFuture: ScheduledFuture<*>? = null
    @Volatile private var coachGateway: CoachGateway = LocalMockCoachGateway()
    @Volatile private var syncGateway: SyncGateway = LocalMockSyncGateway()
    private val phaseAlarms = AndroidPhaseAlarmScheduler(appContext)
    private val focusNotifications = AndroidFocusNotifications(appContext)
    private val routineAlarms = AndroidRoutineProposalScheduler(appContext)
    private val habitReminderScheduler = AndroidHabitReminderScheduler(appContext)
    private val store = LocalStateStore(AndroidStateBackend(context))
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
    private var habitLinkPolicy: HabitLinkPolicy
    private var lastSimulatedEvent: AppOpenEvent? = runCatching {
        restored["lastSimulatedEvent"].asMap()?.let(::eventFromMap)
    }.getOrNull()
    private var lastFocusCheckpointAtElapsed = 0L

    var revision by mutableIntStateOf(0)
        private set
    var screen by mutableStateOf(SCREEN_HOME)
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
    var launcherPinnedPackages by mutableStateOf(restored["launcherPins"].asList().filterIsInstance<String>().toSet())
        private set
    var historyDays by mutableIntStateOf(7)
        private set
    var simulationMessage by mutableStateOf(restored["simulationMessage"] as? String)
        private set
    var lastSimulationInterventions by mutableIntStateOf(
        (restored["lastSimulationInterventions"] as? Number)?.toInt() ?: 0,
    )
        private set

    init {
        policy = AndroidEmergencyPauseStore.apply(appContext, policy, clock.nowMillis())
        restored["focus"].asMap()?.let { saved ->
            runCatching { focusEngine.restore(FocusSession.fromMap(saved)) }
        }
        focusSnapshot = focusEngine.currentSession()
        if (focusSnapshot?.status == FocusStatus.RUNNING && capabilities[CAP_FOCUS_NOTIFICATIONS] == true) {
            focusSnapshot?.let(phaseAlarms::schedule)
        }
        if (focusSnapshot?.status in setOf(FocusStatus.RUNNING, FocusStatus.PAUSED)) {
            screen = SCREEN_FOCUS
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
        when {
            recoveryReason != null && persistenceAllowed -> persist()
            recoveryReason != null -> touch()
            stateReadResult == StateReadResult.Missing -> persist()
        }
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

    val canLinkCompletedFocusToHabit: Boolean
        get() = focusSession?.let { FocusHabitLinkCondition.AllWorkCyclesCompleted.isSatisfiedBy(it) } == true

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
                navigate(SCREEN_ROUTINES)
                latestMessage = "Proposition de routine ouverte. Démarrez-la explicitement si vous le souhaitez."
            }
            intent.getStringExtra("atlas_open_focus_session") != null -> {
                navigate(SCREEN_FOCUS)
                latestMessage = "La phase Focus est terminée."
            }
        }
        touch()
    }

    fun navigate(destination: String) {
        screen = destination
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

    private fun isAllowedBackendUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
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
                screen = SCREEN_FOCUS
            }
        } catch (error: IllegalArgumentException) {
            latestMessage = error.message ?: "Cette réponse n'est pas disponible."
        }
        persist()
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
        screen = SCREEN_FOCUS
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
        focusSnapshot = focusEngine.currentSession()
        latestMessage = "Session annulée."
        persist()
    }

    fun finishFocus() {
        focusSession?.let { phaseAlarms.cancel(it.id); focusNotifications.cancel(it.id) }
        focusSession?.let { focusEngine.finish(it.id)?.let(::archiveFocusSession) }
        focusSnapshot = focusEngine.currentSession()
        if (focusSnapshot?.status == FocusStatus.FINISHED) {
            enqueueSyncEvent(
                SyncEventType.FOCUS_COMPLETED,
                SyncDataCategory.FOCUS,
                mapOf("completed_work_cycles" to (focusSnapshot?.completedWorkCycles ?: 0)),
            )
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
        if (capabilities[CAP_LAUNCHER_HOME] != true || packageName.isBlank()) return
        launcherPinnedPackages = if (packageName in launcherPinnedPackages) {
            launcherPinnedPackages - packageName
        } else {
            launcherPinnedPackages + packageName
        }
        latestMessage = if (packageName in launcherPinnedPackages) "Application épinglée à l'accueil ATLAS." else "Application retirée des épingles ATLAS."
        persist()
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
        val result = catalog.open(id, AndroidContentOpener(context))
        if (result.status in setOf(
                dev.atlas.coach.content.ContentOpenStatus.OPENED_CHOOSER,
                dev.atlas.coach.content.ContentOpenStatus.OPENED_DIRECT_FALLBACK,
            )
        ) enqueueSyncEvent(SyncEventType.CONTENT_OPENED, SyncDataCategory.CONTENT)
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
            return
        }
        if (pending.isNotEmpty()) {
            pendingUsagePreferences.edit().remove(KEY_PENDING_USAGE_PACKAGES).apply()
            latestMessage = "Accès à l'utilisation non accordé. Aucune observation n'est active."
            touch()
        }
        if (usageConsentActive) {
            revokeUsageObservation("Accès à l'utilisation a été retiré. L'observation est arrêtée et son historique a été purgé.")
        }
    }

    fun usageAccessSettingsIntent(): Intent = systemUsageAccessSettingsIntent()

    fun pollUsageObservation() {
        if (!usageConsentActive) return
        if (!usageAccessStatus.isAllowed()) {
            revokeUsageObservation("Accès à l'utilisation n'est plus autorisé. L'observation est arrêtée et son historique a été purgé.")
            return
        }
        val result = usageObserver.poll(clock.nowMillis())
        if (result.status == UsagePollStatus.PERMISSION_REQUIRED) {
            revokeUsageObservation("Accès à l'utilisation n'est plus autorisé. L'observation est arrêtée et son historique a été purgé.")
        } else if (result.interventions.isNotEmpty()) {
            latestMessage = "Une proposition de pause est disponible dans la notification. ATLAS n'a pas bloqué l'application."
            touch()
        }
    }

    fun revokeUsageObservation(message: String = "Observation désactivée. L'historique et les données de déduplication ont été supprimés.") {
        usageObserver.revokeConsent()
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
            latestMessage = "Observation activée pour ${selected.size} application(s) sélectionnée(s). Les ouvertures sont lues uniquement quand ATLAS est visible."
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
        if (name == CAP_APP_TRACKING) {
            if (enabled) {
                latestMessage = "L'observation ne peut être activée que depuis l'écran Observation, après choix des apps et vérification d'Android."
                touch()
            } else {
                revokeUsageObservation()
            }
            return
        }
        capabilities = capabilities + (name to enabled)
        if (name == CAP_LAUNCHER_HOME && !enabled && screen == SCREEN_LAUNCHER) {
            screen = SCREEN_HOME
            latestMessage = "Accueil launcher désactivé; l'accueil ATLAS reste accessible."
        }
        if (name == CAP_FOCUS && !enabled) {
            val current = focusEngine.currentSession()
            if (current != null && CapabilityRules.mustPauseFocusOnDisable(enabled, current.status)) {
                focusSnapshot = focusEngine.pause(current.id)
                phaseAlarms.cancel(current.id)
                focusNotifications.cancel(current.id)
                latestMessage = "Focus désactivé. La session a été mise en pause; réactivez Focus pour la reprendre."
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
        navigate(SCREEN_FOCUS_STATS)
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

    private fun persist(durable: Boolean = true) {
        focusSnapshot = focusEngine.currentSession()
        refreshDomainSnapshots()
        if (!persistenceAllowed) {
            touch()
            return
        }
        try {
            store.write(stateMap(), durable = durable)
            lastFocusCheckpointAtElapsed = clock.monotonicMillis()
        } catch (_: Exception) {
            latestMessage = "Impossible d'enregistrer l'état local. La session reste disponible en mémoire."
        }
        touch()
    }

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
        const val SCREEN_HOME = "Accueil"
        const val SCREEN_FOCUS = "Focus"
        const val SCREEN_HABITS = "Habitudes"
        const val SCREEN_LIBRARY = "Bibliothèque"
        const val SCREEN_COACH = "Coach"
        const val SCREEN_SETTINGS = "Paramètres"
        const val SCREEN_SIMULATOR = "Simulateur de friction"
        const val SCREEN_DIAGNOSTICS = "Diagnostic avancé / développeur"
        const val SCREEN_OBSERVATION = "Observation"
        const val SCREEN_LAUNCHER = "Accueil launcher"
        const val SCREEN_ABOUT = "À propos / Licences"
        const val SCREEN_FOCUS_STATS = "Statistiques de focus"
        const val SCREEN_ROUTINES = "Routines de focus"
        const val SCREEN_SYNC_SETTINGS = "Partage backend"
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
        const val FOCUS_CHECKPOINT_INTERVAL_MILLIS = 20_000L
        const val MAX_FOCUS_HISTORY = 100
        const val MAX_FOCUS_ROUTINES = 100
        private const val KEY_PENDING_USAGE_PACKAGES = "pending_allowlist"

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
