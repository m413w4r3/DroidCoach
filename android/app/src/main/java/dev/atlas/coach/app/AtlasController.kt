package dev.atlas.coach.app

import android.content.Context
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
import dev.atlas.coach.focus.ReefPomodoroConfig
import dev.atlas.coach.habits.Habit
import dev.atlas.coach.habits.HabitLinkPolicy
import dev.atlas.coach.habits.HabitSchedule
import dev.atlas.coach.habits.InMemoryHabitRepository
import dev.atlas.coach.rules.AppEventSimulator
import dev.atlas.coach.rules.AppOpenEvent
import dev.atlas.coach.rules.Intervention
import dev.atlas.coach.rules.LocalPolicy
import dev.atlas.coach.rules.QuietHours
import dev.atlas.coach.rules.RuleEvaluator
import dev.atlas.coach.routines.FocusRoutine
import dev.atlas.coach.routines.RoutineEvaluator
import dev.atlas.coach.routines.RoutineSchedule
import java.time.LocalDate
import java.time.ZoneId
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID
import org.json.JSONObject

/** Owns the small, synchronous local graph used by the Compose demo. */
class AtlasController(context: Context) {
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
    private val clock = object : Clock {
        override fun nowMillis(): Long = System.currentTimeMillis()
        override fun monotonicMillis(): Long = SystemClock.elapsedRealtime()
    }
    private val focusEngine = FocusEngine(clock)
    private var focusSnapshot by mutableStateOf<FocusSession?>(null)
    private var focusHistoryEntries by mutableStateOf(
        restored["focusHistory"].asList().mapNotNull { it.asMap()?.let { values -> runCatching { FocusSession.fromMap(values) }.getOrNull() } }
            .filter { it.status in setOf(dev.atlas.coach.focus.FocusStatus.FINISHED, dev.atlas.coach.focus.FocusStatus.CANCELLED) }
            .sortedByDescending { it.createdAtUtcMillis }
            .take(MAX_FOCUS_HISTORY),
    )
    private var focusRoutinesSnapshot by mutableStateOf(
        restored["focusRoutines"].asList().mapNotNull { it.asMap()?.let { values -> runCatching { FocusRoutine.fromMap(values) }.getOrNull() } }.take(MAX_FOCUS_ROUTINES),
    )
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
    private var directiveSnapshot by mutableStateOf(initialDirective)
    private var habitsSnapshot by mutableStateOf<List<Habit>>(emptyList())
    private var checkedHabitIdsSnapshot by mutableStateOf<Set<String>>(emptySet())
    private var journalSnapshot by mutableStateOf<List<CoachJournalEntry>>(emptyList())
    private var interventionsSnapshot by mutableStateOf<List<Intervention>>(emptyList())
    private var coachPausedSnapshot by mutableStateOf(false)
    private var catalog = restored["content"].asList().mapNotNull { it.asMap() }
        .takeIf { it.isNotEmpty() }?.let(ContentCatalog::fromMaps) ?: ContentCatalog.demo()
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
        restored["focus"].asMap()?.let { saved ->
            runCatching { focusEngine.restore(FocusSession.fromMap(saved)) }
        }
        focusSnapshot = focusEngine.currentSession()
        if (focusEngine.currentSession()?.status in setOf(dev.atlas.coach.focus.FocusStatus.RUNNING, dev.atlas.coach.focus.FocusStatus.PAUSED)) {
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
        when {
            recoveryReason != null && persistenceAllowed -> persist()
            recoveryReason != null -> touch()
            stateReadResult == StateReadResult.Missing -> persist()
        }
    }

    val directive: Directive get() = directiveSnapshot
    fun directiveTitle(id: String?): String = id?.let { coach.findDirective(it)?.title } ?: "focus libre"
    val focusSession: FocusSession? get() = focusSnapshot
    val focusHistory: List<FocusSession> get() = focusHistoryEntries
    val focusRoutines: List<FocusRoutine> get() = focusRoutinesSnapshot
    val activeHabits: List<Habit> get() = habitsSnapshot
    val checkedHabitIds: Set<String> get() = checkedHabitIdsSnapshot
    val coachJournal: List<CoachJournalEntry> get() = journalSnapshot
    val interventions: List<Intervention> get() = interventionsSnapshot
    val contentItems: List<ContentItem> get() = catalog.items
    val isCoachPaused: Boolean get() = coachPausedSnapshot
    val isEmergencyPaused: Boolean get() = policy.pauseUntil?.let { it > clock.nowMillis() } == true
    val quietHoursEnabled: Boolean get() = policy.quietHours != null

    fun navigate(destination: String) {
        screen = destination
        touch()
    }

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
            if (choice == DirectiveChoice.START) {
                archiveCurrentTerminalFocus()
                val seconds = ((result.requestedDurationMinutes ?: 10) * 60L).coerceAtLeast(1L)
                focusEngine.start(FocusPlan.custom(seconds).copy(presetId = "directive-${activeDirective.id}"), activeDirective.id)
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
        val plan = reefConfig.toFocusPlan(preset.presetId)
        archiveCurrentTerminalFocus()
        focusEngine.start(plan, directive.id)
        latestMessage = if (preset == FocusPreset.POMODORO_25_5) "Session 25/5 démarrée." else "Session 10/2 démarrée."
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
        archiveCurrentTerminalFocus()
        focusEngine.start(plan, directive.id)
        latestMessage = "Pomodoro ${config.focusMinutes}/${config.shortBreakMinutes} démarré · pause longue toutes les ${config.cycles} phases."
        screen = SCREEN_FOCUS
        persist()
    }

    fun startDemoFocus() {
        if (capabilities[CAP_FOCUS] != true) {
            latestMessage = "Le focus est désactivé dans les paramètres."
            touch()
            return
        }
        archiveCurrentTerminalFocus()
        focusEngine.start(FocusPlan.custom(20).copy(presetId = "demo-20-seconds"), directive.id)
        latestMessage = "Session démo de 20 secondes démarrée."
        screen = SCREEN_FOCUS
        persist()
    }

    fun tickFocus() {
        if (!CapabilityRules.mayTickFocus(capabilities, focusSnapshot?.status)) return
        val previous = focusSnapshot
        val current = focusEngine.currentSession() ?: return
        focusSnapshot = current
        val transitioned = previous?.id != current.id || previous?.phase != current.phase || previous?.status != current.status
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
        if (session.status.name == "RUNNING") focusEngine.pause(session.id) else if (session.status.name == "PAUSED") focusEngine.resume(session.id)
        focusSnapshot = focusEngine.currentSession()
        persist()
    }

    fun cancelFocus() {
        focusSession?.let { focusEngine.cancel(it.id)?.let(::archiveFocusSession) }
        focusSnapshot = focusEngine.currentSession()
        latestMessage = "Session annulée."
        persist()
    }

    fun finishFocus() {
        focusSession?.let { focusEngine.finish(it.id)?.let(::archiveFocusSession) }
        focusSnapshot = focusEngine.currentSession()
        latestMessage = "Session terminée. Voulez-vous la relier à une habitude ?"
        persist()
    }

    fun confirmHabitLink(habitId: String) {
        if (!CapabilityRules.mayConfirmHabitLink(capabilities)) {
            latestMessage = "Les habitudes sont désactivées dans les paramètres."
            touch()
            return
        }
        val session = focusSession ?: return
        habitLinkPolicy.confirmLink(session.id, habitId, LocalDate.now())
        latestMessage = "Habitude cochée après votre confirmation."
        persist()
    }

    fun addHabit(name: String) {
        if (capabilities[CAP_HABITS] != true) {
            latestMessage = "Les habitudes sont désactivées dans les paramètres."
            touch()
            return
        }
        try {
            habits.add(name, HabitSchedule.daily())
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
            latestMessage = "Habitude validée pour aujourd'hui."
        }
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

    fun addFocusRoutine(name: String, start: String, end: String, weekdaysOnly: Boolean): Boolean {
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
        val routine = FocusRoutine.create(normalizedName, schedule)
        focusRoutinesSnapshot = (focusRoutinesSnapshot + routine).takeLast(MAX_FOCUS_ROUTINES)
        latestMessage = "Routine locale ajoutée. Elle proposera un focus pendant sa plage horaire."
        persist()
        return true
    }

    fun deleteFocusRoutine(id: String) {
        focusRoutinesSnapshot = focusRoutinesSnapshot.filterNot { it.id == id }
        persist()
    }

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
        return "${history.completedDueDays}/${history.dueDays} jours · force ${strength}% · série ${history.currentStreak}"
    }

    fun exportHabits(): String = habits.exportCsv()

    fun openContent(id: String, context: Context): ContentOpenResult {
        if (capabilities[CAP_CONTENT] != true) {
            return ContentOpenResult(dev.atlas.coach.content.ContentOpenStatus.INVALID_CONTENT, "La bibliothèque est désactivée dans les paramètres.")
                .also { latestMessage = it.message; touch() }
        }
        val result = catalog.open(id, AndroidContentOpener(context))
        latestMessage = result.message
        touch()
        return result
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
        events.forEach { event -> if (ruleEvaluator.evaluate(event, policy) != null) emitted++ }
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
            val duplicateResult = ruleEvaluator.evaluate(event, policy)
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
        val emitted = events.count { ruleEvaluator.evaluate(it, policy) != null }
        lastSimulationInterventions = emitted
        simulationMessage = "4 nouveaux événements · $emitted proposition${if (emitted == 1) "" else "s"} pendant la période d'attente."
        persist()
    }

    fun toggleQuietHours() {
        policy = policy.copy(quietHours = if (policy.quietHours == null) QuietHours(LocalTime.of(22, 0), LocalTime.of(7, 0)) else null)
        persist()
    }

    fun setEmergencyPause() {
        policy = policy.withEmergencyPause(clock.nowMillis())
        latestMessage = "Pause d'urgence active pendant 30 minutes."
        persist()
    }

    fun endEmergencyPause() {
        policy = policy.copy(pauseUntil = null)
        latestMessage = "La pause d'urgence est terminée."
        persist()
    }

    fun setCapability(name: String, enabled: Boolean) {
        capabilities = capabilities + (name to enabled)
        if (name == CAP_LAUNCHER_HOME && !enabled && screen == SCREEN_LAUNCHER) {
            screen = SCREEN_HOME
            latestMessage = "Accueil launcher désactivé; l'accueil ATLAS reste accessible."
        }
        if (name == CAP_FOCUS && !enabled) {
            val current = focusEngine.currentSession()
            if (current != null && CapabilityRules.mustPauseFocusOnDisable(enabled, current.status)) {
                focusSnapshot = focusEngine.pause(current.id)
                latestMessage = "Focus désactivé. La session a été mise en pause; réactivez Focus pour la reprendre."
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
        habitsSnapshot = habits.list()
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
        const val SCREEN_LAUNCHER = "Accueil launcher"
        const val SCREEN_ABOUT = "À propos / Licences"
        const val SCREEN_FOCUS_STATS = "Statistiques de focus"
        const val SCREEN_ROUTINES = "Routines de focus"
        const val CAP_FOCUS = "focus"
        const val CAP_HABITS = "habits"
        const val CAP_CONTENT = "content"
        const val CAP_COACH = "coach"
        const val CAP_SIMULATED_RULES = "simulatedRules"
        const val CAP_APP_TRACKING = "appTrackingConsent"
        const val CAP_LAUNCHER_HOME = "launcherHome"
        const val CAP_FOCUS_ROUTINES = "focusRoutines"
        const val FOCUS_CHECKPOINT_INTERVAL_MILLIS = 20_000L
        const val MAX_FOCUS_HISTORY = 100
        const val MAX_FOCUS_ROUTINES = 100

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

private fun Any?.asMap(): Map<String, Any?>? = (this as? Map<*, *>)
    ?.entries?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }?.toMap()

private fun Any?.asList(): List<*> = this as? List<*> ?: emptyList<Any?>()
