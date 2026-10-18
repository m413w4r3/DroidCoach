# V4 — Lot C : Focus, alarmes et routines

Date : 2026-10-19
Branche de départ : `feat/atlas-v4-android17`, HEAD initial `c2cba5a636aebb24d9abed97ee4c7e01df9ae749`
Périmètre demandé : moteur Focus, planification de notifications, API routines et tests JVM. Aucun AVD n'a été utilisé.

## Résultat

Le moteur accepte maintenant un nombre total de cycles distinct de la fréquence des pauses longues. Les nouveaux plans sont finis par défaut (4 cycles) et finissent à la fin du dernier travail. `continuous=true` conserve la répétition. Les maps anciennes qui ne contiennent pas `totalWorkCycles` sont lues comme des sessions continues, sans remettre le temps ou les cycles à zéro.

`PhaseSchedule` calcule paresseusement les frontières à partir d'un `FocusSession` persisté. Les adaptateurs Android planifient une seule alarme RTC inexacte à la fois, avancent le même `FocusEngine` à réception, enregistrent le nouveau snapshot à la frontière et replanifient. L'adaptateur de routine émet une notification consultative; il ne lance jamais Focus depuis l'arrière-plan. Le refus des notifications ne bloque pas le timer local.

Les API de routines couvrent création, édition, suppression, activation, désactivation, fenêtres actives, prochain déclenchement et propositions. Le fuseau est injecté dans les fonctions de calcul. La fenêtre DST utilise les règles de `java.time.atZone`: une heure inexistante avance au premier instant valide; une heure répétée choisit le premier offset. Le changement de fuseau relance le calcul à partir des maps locales sauvegardées.

`FocusSessionLinker` représente le lien directive mock / projet facultatif / habitude / entrée journal sous une clé stable `sessionId`. Il exige la condition choisie et une confirmation explicite pour une habitude. Le store expose des maps afin que l'intégrateur persiste ce registre dans le document local existant; il ne crée pas un second stockage des habitudes.

## Fichiers modifiés par C

- `android/app/src/main/java/dev/atlas/coach/focus/Clock.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/FocusEngine.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/FocusModels.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/FocusRepository.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/FocusSessionLinking.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/PhaseSchedule.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/ReefTimerState.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/android/AndroidFocusNotifications.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/android/AndroidPhaseAlarmScheduler.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/android/AndroidRoutineProposalScheduler.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/android/FocusPhaseAlarmReceiver.kt`
- `android/app/src/main/java/dev/atlas/coach/routines/FocusRoutine.kt`
- `android/app/src/main/java/dev/atlas/coach/routines/FocusRoutineRepository.kt`
- `android/app/src/test/java/dev/atlas/coach/focus/FocusEngineTest.kt`
- `android/app/src/test/java/dev/atlas/coach/focus/FocusSessionLinkingTest.kt`
- `android/app/src/test/java/dev/atlas/coach/routines/FocusRoutineTest.kt`
- `docs/DECISIONS_AUTOMATIQUES.md`
- `docs/v4/lots/C.md`

Aucun fichier de `rules/**`, `usage/**`, `habits/**`, `content/**`, `launcher/**`, manifeste, UI, `AtlasController.kt` ou Gradle n'a été modifié par C. D'autres changements `rules/**` et `usage/**` sont apparus dans le worktree pendant cette session et sont laissés intacts.

## Vérifications et preuve

Inventaire observé : OpenJDK 21.0.12.1, Gradle wrapper 9.6.0, AGP 9.4.1, compileSdk 37.2, targetSdk 35. Le répertoire `.codegraph/` est absent; les emplacements de code ont été vérifiés par lecture et `rg` après les documents obligatoires.

Commande demandée, lancée depuis `android/` :

```text
./gradlew :app:testDebugUnitTest
```

Résultats des deux lancements, consignés dans l'ordre :

1. Première tentative : **FAIL avant les tests** à `:app:compileDebugKotlin`; le compilateur signalait alors `getRoleHolders` dans `usage/android/AndroidProtectedPackages.kt:28:70` et `instanceId` dans `usage/android/AndroidUsageStatsEventSource.kt:43:88`. Ces fichiers sont réservés au lot D et n'ont pas été modifiés par C.
2. Nouvelle tentative après l'évolution concurrente de `usage/**` : **BUILD SUCCESSFUL**. Les 18 rapports XML de `testDebugUnitTest` comptent **76 tests, 0 échec, 0 erreur, 0 ignoré**. `compileDebugKotlin`, `compileDebugUnitTestKotlin` et `testDebugUnitTest` ont tous terminé.

Les rapports incluent `FocusEngineTest` (15), `FocusSessionLinkingTest` (3), `ReefTimerAdaptationTest` (3), `FocusRoutineTest` (7), ainsi que le reste de la suite. Aucun émulateur n'a été interrogé ou démarré, conformément au brief; la compilation Kotlin de l'implémentation Android passe, mais sa recette Android reste à faire par l'intégrateur.

La provenance Reef préexistante couvre déjà `ReefTimerState.kt` et `routines/FocusRoutine.kt` dans `THIRD_PARTY_NOTICES.md`, SHA `044358fb26bf11aa85dddadf919106ca4053d926`, MIT avec attribution, ainsi que dans `docs/AUDIT_CIBLE.md`. Les changements C prolongent ces adaptations déjà déclarées; aucune source nouvelle ni dépendance tierce n'a été copiée/ajoutée.

## Décisions automatiques

- Nouveau `FocusPlan`: `totalWorkCycles=4`, `continuous=false`; `longBreakEveryCycles` reste distinct. L'arrêt automatique survient à la fin du travail final, sans pause post-finale.
- Ancienne map sans `totalWorkCycles`: `totalWorkCycles=Int.MAX_VALUE`, `continuous=true`; les autres champs, durée restante et cycle terminé restent inchangés.
- Le moteur utilise le temps monotone pendant une exécution; à restauration, le temps mural UTC avance au maximum de 24 heures comme avant. Un recul de l'horloge murale n'ajoute pas de temps.
- Android planifie une frontière à la fois avec `AlarmManager.set(RTC_WAKEUP, ...)`, sans `SCHEDULE_EXACT_ALARM`; le receiver reconstitue et sauvegarde le même état Focus au passage de phase.
- Si `POST_NOTIFICATIONS` manque ou est refusée, le moteur reste fonctionnel et l'adaptateur renvoie un état de repli. La demande de permission est déclenchée par une action UI au premier plan, jamais depuis le receiver.
- Le mandat routine `preauthorizedStart` est faux par défaut. L'adaptateur livré reste consultatif même lorsqu'un mandat est enregistré; l'exécution automatique n'est pas intégrée.
- Les horaires non récurrents nouveaux requièrent `startDate`. Une ancienne map `isRecurring=false` sans date est migrée comme récurrente pour préserver son comportement historique.

## Statut par critère

| Critère | Statut | Preuve / limite |
|---|---|---|
| Cycles finis/continu, pause longue, annulation, restauration et migration des anciennes maps | **PASS** | `:app:testDebugUnitTest` réussi; 15 tests `FocusEngineTest` couvrent transitions, reprises, horloge et cycle final. |
| Planification pure et fakes de phase | **PASS** | Calcul paresseux depuis le snapshot; frontières de fin, prochaine phase et mode continu compilent/testent dans la suite de 76 tests. |
| Adaptateurs Android notification/alarmes, action ouvrir, annulation, reboot et changement d'heure/fuseau | **PASS — compilation** | `compileDebugKotlin` passe; alarmes `set(RTC_WAKEUP)` inexactes, PendingIntents immuables, permission refusée représentée. **BLOCKED — exécution AVD** par la contrainte du lot. |
| Routines CRUD, `nextTrigger`, jours, fuseau injecté, DST, simultanéité et désactivation immédiate | **PASS** | 7 tests `FocusRoutineTest`; calculs restent purs, fakes de proposition et d'alarme fournis. |
| Adaptateur Android de notification routine et replanification au reboot | **PASS — compilation** | Inclus dans `compileDebugKotlin`; la délivrance Android réelle n'a pas été testée sur AVD. |
| Lien session/directive/projet/habitude/journal, condition et idempotence par `sessionId` | **PASS — API pure** | 3 tests `FocusSessionLinkingTest`; maps sérialisables et store restaurable. **BLOCKED — intégration Controller** car ce fichier est réservé à l'orchestrateur. |
| Rapport et propositions aux fichiers interdits | **PASS** | API publique, diff Manifest/Controller/UI, demandes de permission et points de raccordement décrits ci-dessous; aucun de ces fichiers n'a été changé. |
| Recette Android 17 / permission refusée / reboot / retour depuis notification | **BLOCKED** | Explicitement exclue du lot C; sera exécutée par l'intégrateur sur l'AVD partagé. |

## Limites et travaux restants

1. Relancer `cd android && ./gradlew :app:testDebugUnitTest` après toute modification intégrée; le dernier run de ce lot passe avec 76 tests, 0 échec. L'intégrateur ajoutera son SHA de livraison au bilan global.
2. Intégrer les diffs ci-dessous, puis vérifier permission refusée/accordée, transitions en arrière-plan, arrêt forcé, reboot, réglage d'heure/fuseau, routine désactivée et retour par notification sur l'AVD Android 17.
3. Le receiver de phase met à jour la racine JSON existante une fois par frontière (pas chaque seconde). Les alarmes Android restent inexactes et peuvent arriver tard; une notification de phase intermédiaire peut être regroupée si l'appareil est hors tension ou fortement retardé.
4. Le store d'idempotence `FocusJournalLinkStore` fourni est en mémoire pour les tests. L'hôte doit sérialiser `all().map(FocusJournalLink::toMap)` dans son document JSON et le restaurer avant de traiter un lien; ne pas utiliser un store neuf à chaque clic.
5. Le code livré ne demande pas la permission de notification. Il expose le résultat de permission à l'appelant; le flux Activity doit expliquer et demander la permission, puis proposer Réglages si elle a déjà été refusée.
6. Le mandat préautorisé est persisté et visible dans l'API, mais aucune routine n'auto-démarre Focus dans ce lot.
7. L'état `README_APPLIQUER.md` était déjà non suivi au début; il reste hors du commit C. Gradle a généré le cache local `android/.kotlin/`; il n'est pas ajouté au commit.

## Intégration requise

Les fichiers suivants sont réservés à l'orchestrateur : `AndroidManifest.xml`, `AtlasController.kt`, `ReefPomodoroSetup.kt`, `FocusRoutinesScreen.kt`, `AtlasApp.kt` / `MainActivity.kt`. Les extraits ci-dessous sont des propositions uniquement; aucun n'a été appliqué.

### API publique stable

```kotlin
data class FocusPlan(
    val presetId: String,
    val workSeconds: Long,
    val shortBreakSeconds: Long,
    val longBreakSeconds: Long = 15 * 60L,
    val longBreakEveryCycles: Int = 4,
    val totalWorkCycles: Int = 4,
    val continuous: Boolean = false,
)

fun FocusEngine.start(plan: FocusPlan, directiveId: String? = null): FocusSession
fun FocusEngine.pause(id: String): FocusSession?
fun FocusEngine.resume(id: String): FocusSession?
fun FocusEngine.finish(id: String): FocusSession?
fun FocusEngine.cancel(id: String): FocusSession?
fun FocusEngine.snapshot(): FocusSession?
fun FocusEngine.restore(saved: FocusSession): FocusSession
fun FocusSession.toMap(): Map<String, Any?>
fun FocusSession.Companion.fromMap(values: Map<String, Any?>): FocusSession

interface FocusRepository {
    fun start(plan: FocusPlan, directiveId: String? = null): FocusSession
    fun start(durationSeconds: Long, directiveId: String? = null): FocusSession
    fun pause(id: String): FocusSession?
    fun resume(id: String): FocusSession?
    fun finish(id: String): FocusSession?
    fun cancel(id: String): FocusSession?
}

object PhaseSchedule {
    fun transitions(session: FocusSession): Sequence<PhaseTransition>
    fun nextTransition(session: FocusSession): PhaseTransition?
}
interface PhaseNotifier {
    fun notify(transition: PhaseTransition): PhaseNotificationResult
    fun cancel(sessionId: String)
}
interface PhaseAlarmScheduler {
    fun schedule(session: FocusSession)
    fun cancel(sessionId: String)
}
class AndroidPhaseAlarmScheduler(context: Context) : PhaseAlarmScheduler
class AndroidFocusNotifications(context: Context) : PhaseNotifier, RoutineProposalNotifier
interface RoutineProposalNotifier { fun notify(proposal: RoutineEvaluator.Proposal): Boolean }
interface RoutineProposalScheduler {
    fun schedule(routine: FocusRoutine, after: ZonedDateTime, zone: ZoneId)
    fun cancel(routineId: String)
}

interface FocusRoutineRepository {
    fun list(): List<FocusRoutine>
    fun find(id: String): FocusRoutine?
    fun create(
        name: String, schedule: RoutineSchedule, focusMinutes: Int = 25,
        preauthorizedStart: Boolean = false,
    ): FocusRoutine
    fun update(routine: FocusRoutine): Boolean
    fun delete(id: String): Boolean
    fun setEnabled(id: String, enabled: Boolean): FocusRoutine?
    fun nextTrigger(id: String, after: ZonedDateTime, zone: ZoneId): ZonedDateTime?
    fun proposals(now: ZonedDateTime, zone: ZoneId = now.zone): List<RoutineEvaluator.Proposal>
}
object RoutineTimeCalculator {
    fun getSessionWindow(
        schedule: RoutineSchedule, now: ZonedDateTime, zone: ZoneId = now.zone,
    ): RoutineTimeCalculator.SessionWindow?
    fun nextTrigger(
        schedule: RoutineSchedule, after: ZonedDateTime, zone: ZoneId,
    ): ZonedDateTime?
}

interface FocusJournalLinkStore {
    fun find(sessionId: String): FocusJournalLink?
    fun all(): List<FocusJournalLink>
    fun insertIfAbsent(link: FocusJournalLink): Boolean
}
fun FocusJournalLink.toMap(): Map<String, Any?>
fun FocusJournalLink.Companion.fromMap(values: Map<String, Any?>): FocusJournalLink

fun FocusSessionLinker.link(
    session: FocusSession,
    condition: FocusHabitLinkCondition,
    projectId: String? = null,
    habitId: String? = null,
    habitConfirmed: Boolean = false,
    linkedAtUtcMillis: Long,
): FocusLinkResult
```

### `AndroidManifest.xml` — propositions de diff

```diff
--- AndroidManifest.xml
+++ AndroidManifest.xml
@@ -1,2 +1,4 @@
 <manifest xmlns:android="http://schemas.android.com/apk/res/android">
+    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
+    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
@@ -40,2 +42,10 @@
         </activity-alias>
+        <receiver android:name=".focus.android.FocusPhaseAlarmReceiver" android:exported="false" />
+        <receiver android:name=".focus.android.FocusRoutineProposalReceiver" android:exported="false" />
+        <receiver android:name=".focus.android.FocusRescheduleReceiver" android:enabled="true" android:exported="false">
+            <intent-filter>
+                <action android:name="android.intent.action.BOOT_COMPLETED" />
+                <action android:name="android.intent.action.TIME_SET" />
+                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
+            </intent-filter>
+        </receiver>
     </application>
 </manifest>
```

`POST_NOTIFICATIONS` est une permission runtime à demander depuis une action explicite au premier plan (API 33+). `RECEIVE_BOOT_COMPLETED` restaure les alarmes locales. Aucun besoin démontré pour `SCHEDULE_EXACT_ALARM`; il n'est pas proposé.

### `AtlasController.kt` — démarrage, transitions et annulation

```diff
--- AtlasController.kt
+++ AtlasController.kt
@@ -27,6 +27,12 @@
 import dev.atlas.coach.focus.Clock
 import dev.atlas.coach.focus.FocusEngine
+import dev.atlas.coach.focus.FocusStatus
+import dev.atlas.coach.focus.PhaseNotificationResult
+import dev.atlas.coach.focus.PhaseTransition
+import dev.atlas.coach.focus.android.AndroidFocusNotifications
+import dev.atlas.coach.focus.android.AndroidPhaseAlarmScheduler
+import dev.atlas.coach.focus.android.AndroidRoutineProposalScheduler
 import dev.atlas.coach.focus.FocusPlan
@@ -53,3 +59,6 @@
 /** Owns the small, synchronous local graph used by the Compose demo. */
 class AtlasController(context: Context) {
+    private val phaseAlarms = AndroidPhaseAlarmScheduler(context)
+    private val focusNotifications = AndroidFocusNotifications(context)
+    private val routineAlarms = AndroidRoutineProposalScheduler(context)
     private val store = LocalStateStore(AndroidStateBackend(context))
@@ -150,2 +159,4 @@
         focusSnapshot = focusEngine.currentSession()
+        focusSnapshot?.takeIf { it.status == FocusStatus.RUNNING }?.let(phaseAlarms::schedule)
+        routineAlarms.rescheduleFromStoredState()
         if (focusEngine.currentSession()?.status in setOf(FocusStatus.RUNNING, FocusStatus.PAUSED)) {
@@ -239,4 +250,5 @@
         val plan = reefConfig.toFocusPlan(preset.presetId)
         archiveCurrentTerminalFocus()
-        focusEngine.start(plan, directive.id)
+        val started = focusEngine.start(plan, directive.id)
+        phaseAlarms.schedule(started)
         latestMessage = if (preset == FocusPreset.POMODORO_25_5) "Session 25/5 démarrée." else "Session 10/2 démarrée."
@@ -253,6 +265,7 @@
         val plan = config.toPomodoroConfig().toFocusPlan(
             "reef-pomodoro-${config.focusMinutes}-${config.shortBreakMinutes}-${config.longBreakMinutes}-${config.cycles}",
         )
         archiveCurrentTerminalFocus()
-        focusEngine.start(plan, directive.id)
+        val started = focusEngine.start(plan, directive.id)
+        phaseAlarms.schedule(started)
         latestMessage = "Pomodoro ${config.focusMinutes}/${config.shortBreakMinutes} démarré · pause longue toutes les ${config.cycles} phases."
@@ -279,9 +292,22 @@
         val current = focusEngine.currentSession() ?: return
         focusSnapshot = current
         val transitioned = previous?.id != current.id || previous?.phase != current.phase || previous?.status != current.status
+        if (transitioned && previous != null && previous.status == FocusStatus.RUNNING &&
+            (previous.phase != current.phase || current.status == FocusStatus.FINISHED)) {
+            val result = focusNotifications.notify(
+                PhaseTransition(
+                    current.id, 0, current.updatedAtUtcMillis, previous.phase,
+                    current.phase.takeIf { current.status == FocusStatus.RUNNING }, current.completedWorkCycles,
+                ),
+            )
+            if (result != PhaseNotificationResult.POSTED) latestMessage = "Phase terminée; activez les notifications pour recevoir les alertes hors écran."
+        }
+        if (transitioned && current.status == FocusStatus.RUNNING) phaseAlarms.schedule(current)
+        if (transitioned && current.status != FocusStatus.RUNNING) phaseAlarms.cancel(current.id)
+        if (transitioned && current.status == FocusStatus.FINISHED) archiveFocusSession(current)
         val elapsed = clock.monotonicMillis()
@@
         if (session.status.name == "RUNNING") focusEngine.pause(session.id) else if (session.status.name == "PAUSED") focusEngine.resume(session.id)
         focusSnapshot = focusEngine.currentSession()
+        focusSnapshot?.let { current ->
+            if (current.status == FocusStatus.RUNNING) phaseAlarms.schedule(current)
+            else { phaseAlarms.cancel(current.id); focusNotifications.cancel(current.id) }
+        }
         persist()
@@
     fun cancelFocus() {
+        focusSession?.let { phaseAlarms.cancel(it.id); focusNotifications.cancel(it.id) }
         focusSession?.let { focusEngine.cancel(it.id)?.let(::archiveFocusSession) }
@@
     fun finishFocus() {
+        focusSession?.let { phaseAlarms.cancel(it.id); focusNotifications.cancel(it.id) }
         focusSession?.let { focusEngine.finish(it.id)?.let(::archiveFocusSession) }
@@
         val routine = FocusRoutine.create(normalizedName, schedule)
         focusRoutinesSnapshot = (focusRoutinesSnapshot + routine).takeLast(MAX_FOCUS_ROUTINES)
+        routineAlarms.schedule(routine, ZonedDateTime.now(), ZoneId.systemDefault())
@@
     fun deleteFocusRoutine(id: String) {
+        routineAlarms.cancel(id)
         focusRoutinesSnapshot = focusRoutinesSnapshot.filterNot { it.id == id }


Le même ajout de planification s'applique aux deux overloads de `startFocus`. En cas de désactivation de la capability Focus, annuler l'alarme après la mise en pause. À l'activation/édition d'une routine, programmer depuis `ZoneId.systemDefault()`; à sa désactivation/suppression, annuler immédiatement. À une transition visible, notifier avant de replanifier/canceller l'alarme afin que le premier plan ne supprime pas la notification de phase. Exploiter le `PhaseNotificationResult` pour afficher le repli de permission. Les hooks de démarrage et les transitions ne créent pas un autre timer : `FocusEngine.restore` et la map `focus` existante restent la source de vérité.

### UI minimale

Dans `ReefPomodoroSetup.kt`, distinguer fréquence de pause longue et nombre total de travaux; conserver une bascule de mode continu :

```diff
--- ReefPomodoroSetup.kt
+++ ReefPomodoroSetup.kt
@@ -13,9 +13,11 @@
 import androidx.compose.material3.Button
 import androidx.compose.material3.MaterialTheme
+import androidx.compose.material3.Switch
 import androidx.compose.material3.OutlinedButton
@@
 import androidx.compose.runtime.getValue
 import androidx.compose.runtime.mutableIntStateOf
+import androidx.compose.runtime.mutableStateOf
 import androidx.compose.runtime.remember
@@ -27,19 +29,26 @@
 @Composable
 internal fun ReefPomodoroSetup(onStart: (ReefPomodoroConfig) -> Unit) {
     var focus by remember { mutableIntStateOf(ReefPomodoroConfig.DEFAULT.focusMinutes) }
     var shortBreak by remember { mutableIntStateOf(ReefPomodoroConfig.DEFAULT.shortBreakMinutes) }
     var longBreak by remember { mutableIntStateOf(ReefPomodoroConfig.DEFAULT.longBreakMinutes) }
-    var cycles by remember { mutableIntStateOf(ReefPomodoroConfig.DEFAULT.cycles) }
+    var longBreakEvery by remember { mutableIntStateOf(ReefPomodoroConfig.DEFAULT.cycles) }
+    var totalCycles by remember { mutableIntStateOf(ReefPomodoroConfig.DEFAULT.totalWorkCycles) }
+    var continuous by remember { mutableStateOf(false) }

     Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
         Text("Pomodoro personnalisé", style = MaterialTheme.typography.titleMedium)
         DurationStepper("Concentration", focus, 1, 180) { focus = it }
         DurationStepper("Pause courte", shortBreak, 1, 60) { shortBreak = it }
         DurationStepper("Pause longue", longBreak, 1, 120) { longBreak = it }
-        DurationStepper("Cycles avant pause longue", cycles, 1, 12) { cycles = it }
+        DurationStepper("Pause longue toutes les", longBreakEvery, 1, 12) { longBreakEvery = it }
+        DurationStepper("Cycles de travail au total", totalCycles, 1, 100) { totalCycles = it }
+        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
+            horizontalArrangement = Arrangement.SpaceBetween) {
+            Text("Mode continu")
+            Switch(checked = continuous, onCheckedChange = { continuous = it })
+        }
         Button(
             modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
-            onClick = { onStart(ReefPomodoroConfig(focus, shortBreak, longBreak, cycles)) },
+            onClick = { onStart(ReefPomodoroConfig(focus, shortBreak, longBreak, longBreakEvery, totalCycles, continuous)) },
         ) { Text("Démarrer le Pomodoro") }
     }
 }
```

Dans `FocusScreen`, remplacer le libellé fixe `Cycle x / y` par `Cycle x · continu` si `timerState.continuous`; sinon afficher `Cycle x / totalWorkCycles`. Remplacer « sans notification ni service en arrière-plan » par un texte décrivant les notifications facultatives et l'état de permission. L'action de demande `POST_NOTIFICATIONS` reste au premier plan et explicite.

Dans `FocusRoutinesScreen`, ajouter un interrupteur actif, action « Modifier », `nextTrigger` localisé, et une action « Ouvrir » sur proposition. `FocusRoutineRepository.setEnabled(false)` retire immédiatement la routine des évaluations; l'appelant annule aussi son alarme. Une proposition notification ouvre l'Activity via PendingIntent immuable; l'Activity doit consommer `atlas_open_routine_id` / `atlas_open_focus_session` et ouvrir l'écran correspondant.

### Lien habitude et journal

Au lieu d'appeler `HabitLinkPolicy.confirmLink` sans contrôler l'état, l'intégrateur doit vérifier la capability, le `FocusLinkResult` et la confirmation. Persister le registre avec le même document local :

```diff
+ private val focusLinkStore = InMemoryFocusJournalLinkStore(
+     restored["focusJournalLinks"].asList().mapNotNull { it.asMap()?.let(FocusJournalLink::fromMap) },
+ )
+ private val focusSessionLinker = FocusSessionLinker(focusLinkStore)
@@ stateMap()
+ "focusJournalLinks" to focusLinkStore.all().map(FocusJournalLink::toMap),
@@ fun confirmHabitLink(habitId: String)
- habitLinkPolicy.confirmLink(session.id, habitId, LocalDate.now())
+ if (session.status != FocusStatus.FINISHED) return
+ when (focusSessionLinker.link(
+     session, FocusHabitLinkCondition.AllWorkCyclesCompleted,
+     habitId = habitId, habitConfirmed = true,
+     linkedAtUtcMillis = clock.nowMillis(),
+ )) {
+     is FocusLinkResult.Recorded -> habitLinkPolicy.confirmLink(session.id, habitId, LocalDate.now())
+     is FocusLinkResult.AlreadyRecorded -> return
+     FocusLinkResult.ConditionNotMet -> return
+     FocusLinkResult.HabitConfirmationRequired -> return
+ }
```

L'intégrateur doit restaurer les maps avec `FocusJournalLink.fromMap` et construire le store une seule fois. Lorsqu'un check-in est créé, conserver la même date locale et `sessionId` dans le registre; ne pas appeler `onSessionCompleted` et `confirmLink` tous les deux pour la même session.
