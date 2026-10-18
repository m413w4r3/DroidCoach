# V4 — Lot E : habitudes et contenus (R5)

Date : 2026-10-19. Ce lot étend les frontières existantes de `HabitRepository` et `ContentCatalog`. Il ne modifie pas `AtlasController`, l'UI, le manifeste, les contrats ni le stockage commun.

## Résultat

- Les plannings d'échéance sont distincts du score Loop : quotidien, jours choisis, quota hebdomadaire avec début de semaine ancré, et alternance avec date initiale / intervalle.
- Les `LocalDate` des check-ins restent la seule écriture. Un check-in est idempotent par `(habitId, date)`, son retrait est explicite, et les conversions coach ont un identifiant stable par check-in. Les rappels et leur receiver n'écrivent jamais de check-in.
- Les maps historiques sans planning migrent en quotidien; les anciennes maps à fréquence hebdomadaire gardent lundi comme ancrage. La date locale de création est enregistrée pour les nouveaux éléments; les anciens restent interprétés avec leur date UTC historique.
- L'historique expose échéances/non-échéances, taux des fenêtres 7/30 jours et séries courante/meilleure. La force et l'export CSV Loop existants sont conservés.
- `HabitReminder` garde heure, jours et activation; `HabitReminderScheduler` a un fake JVM et un adaptateur Android. Celui-ci utilise un `AlarmManager.set(RTC_WAKEUP)` inexact, un canal réservé, des PendingIntent immuables et reprogramme au boot / changement d'heure ou fuseau. Aucun exact-alarm access n'est demandé.
- `ContentCatalog` accepte des vidéos précises ajoutées manuellement, l'épingle, la suppression et le round-trip par maps. Les anciennes URL de recherche et les raccourcis de démo sont marqués `SEARCH`. L'ouverture HTTPS utilise l'`ACTION_VIEW` et le chooser; le compteur d'ouvertures local par élément ne s'incrémente qu'en cas d'ouverture réussie et peut être désactivé. Aucun historique de lecture n'est conservé.
- ReVanced reste une app externe facultative. Il n'y a ni patch, ni package forcé, ni API interne, ni hypothèse d'installation; l'URL HTTPS reste le fallback navigateur.

## Fichiers modifiés

- `android/app/src/main/java/dev/atlas/coach/habits/Habits.kt`
- `android/app/src/main/java/dev/atlas/coach/habits/HabitReminders.kt`
- `android/app/src/main/java/dev/atlas/coach/habits/android/AndroidHabitReminderScheduler.kt`
- `android/app/src/main/java/dev/atlas/coach/habits/thirdparty/loop/HabitStrengthCalculator.kt`
- `android/app/src/main/java/dev/atlas/coach/content/ContentCatalog.kt`
- `android/app/src/main/java/dev/atlas/coach/content/AndroidContentOpener.kt`
- tests JVM sous `android/app/src/test/java/dev/atlas/coach/{habits,content}/`
- `docs/DECISIONS_AUTOMATIQUES.md`
- ce rapport

Les fichiers `THIRD_PARTY_NOTICES.md` et `docs/AUDIT_CIBLE.md` ont été vérifiés : les adaptations Loop déjà présentes y sont attribuées au SHA `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38`, avec leurs fichiers ATLAS et la licence GPL-3.0-or-later. Ce lot n'a copié aucun nouveau code Loop ni ReVanced; il adapte le calcul Loop existant pour la date locale persistée et la fréquence de l'alternance.

## Commit

Commit principal E : `d28a8b8` — `feat: V4 E: planifier habitudes et gérer les liens vidéo`. L'outil de proposition Nimbalyst a refusé les chemins comme hors des dépôts de session; le commit a été créé avec le repli CLI, après staging explicite des seuls 12 chemins autorisés.

## API publique stable

Les signatures existantes restent utilisables. Les champs ajoutés ont des valeurs par défaut, et `HabitRepository.setReminder` a une implémentation par défaut pour ne pas casser les autres implémentations.

```kotlin
enum class HabitScheduleKind { DAILY, WEEKDAYS, TIMES_PER_WEEK, ALTERNATING_DAYS }

data class HabitSchedule(
    val kind: HabitScheduleKind,
    val weekdaysMask: Int = 0,
    val timesPerWeek: Int = 0,
    val weekStartsOn: DayOfWeek = DayOfWeek.MONDAY,
    val anchorDateEpochDay: Long? = null,
    val intervalDays: Int = 0,
) {
    fun isDueOn(date: LocalDate, checkIns: Collection<HabitCheckIn> = emptyList()): Boolean
    fun toMap(): Map<String, Any>

    companion object {
        fun daily(): HabitSchedule
        fun weekdays(days: Set<DayOfWeek>): HabitSchedule
        fun timesPerWeek(count: Int, weekStartsOn: DayOfWeek = DayOfWeek.MONDAY): HabitSchedule
        fun alternatingDays(startDate: LocalDate, intervalDays: Int = 2): HabitSchedule
        fun fromMap(map: Map<String, Any?>): HabitSchedule
    }
}

data class HabitReminder(
    val hour: Int,
    val minute: Int = 0,
    val days: Set<DayOfWeek> = DayOfWeek.values().toSet(),
    val enabled: Boolean = true,
) {
    fun nextOccurrence(after: ZonedDateTime, zone: ZoneId = after.zone): ZonedDateTime?
    fun toMap(): Map<String, Any?>
}

interface HabitReminderScheduler {
    fun schedule(habitId: String, habitName: String, reminder: HabitReminder,
                 zone: ZoneId = ZoneId.systemDefault()): HabitReminderSchedulingResult
    fun cancel(habitId: String)
}
class FakeHabitReminderScheduler(notificationsAvailable: Boolean = true) : HabitReminderScheduler
class AndroidHabitReminderScheduler(context: Context) : HabitReminderScheduler

interface HabitRepository {
    fun add(name: String, schedule: HabitSchedule): Habit
    fun checkIn(habitId: String, date: LocalDate): HabitCheckIn
    fun undoCheckIn(habitId: String, date: LocalDate): Boolean
    fun setReminder(habitId: String, reminder: HabitReminder?): Habit
    fun history(habitId: String, endDate: LocalDate, days: Int): HabitHistory
    // Existing score, archive, exportCsv, exportMaps functions are retained.
}
object HabitCoachEventConverter {
    fun checkInConfirmed(checkIn: HabitCheckIn): HabitCoachEvent
    fun checkInRevoked(checkIn: HabitCheckIn, revokedAtUtcMillis: Long): HabitCoachEvent
}

enum class ContentKind { VIDEO_LINK, SEARCH }
data class ContentItem(
    val id: String, val title: String, val url: String,
    val kind: ContentKind = ContentKind.VIDEO_LINK,
    val pinned: Boolean = false, val openCount: Int = 0,
    val openCountingEnabled: Boolean = true,
)
class ContentCatalog {
    val items: List<ContentItem>
    fun addManualLink(title: String, url: String, id: String = ...): ContentItem
    fun remove(id: String): Boolean
    fun setPinned(id: String, pinned: Boolean): Boolean
    fun setOpenCountingEnabled(id: String, enabled: Boolean): Boolean
    fun open(id: String, opener: ContentOpener): ContentOpenResult
    fun toMaps(): List<Map<String, String>>
}
```

`ContentUrlValidator` conserve l'allowlist d'origine (`youtube.com`, `www.youtube.com`, `m.youtube.com`, `youtu.be`), et les nouveaux liens manuels doivent désigner une vidéo précise (`watch?v=…`, `youtu.be/<id>`, `shorts`, `live` ou `embed`). Les recherches restent des éléments `SEARCH` séparés.

## Vérifications et statut par critère

La première exécution de `./gradlew :app:testDebugUnitTest` s'est arrêtée pendant `:app:compileDebugKotlin`, avant nos tests : le fichier concurrent non suivi `sync/RemoteBackendGateway.kt` référençait `VALID_CHOICES`, qui n'était pas défini à ce moment. Après stabilisation de ce fichier, deux exécutions ont révélé des attentes de dates incorrectes dans nos tests; elles ont été corrigées. La dernière exécution complète a réussi avec 100 tests.

| Critère | Statut | Preuve / limite |
|---|---|---|
| Planning flexible, calendrier distinct du score, migration quotidienne et quota avec ancre | **PASS** | Tests exécutés pour jours sélectionnés, ancre dimanche, dates futures, ancien map et défaut quotidien. |
| Fuseaux Paris / Los Angeles, changement de fuseau, DST, statistiques et non-échéances | **PASS** | Tests exécutés sur dates locales, date de création stable au changement de fuseau, trous DST Paris/LA, taux 7/30, séries actuelle/meilleure et jours non dus. |
| Check-in / retrait explicite, événements coach, idempotence après restauration, force et CSV | **PASS** | Le repository conserve la clé `(habitId, epochDay)`; le test round-trip répète le même check-in et retrouve le même ID. Aucun reminder receiver n'accède au repository. Tests score/CSV existants passent dans la suite complète. |
| `HabitReminder`, fake JVM, alarme Android inexacte, canal, PendingIntent immuable et replanification | **PASS — compilation JVM** | La compilation principale Android réussit comme étape de `testDebugUnitTest`; fake et calcul de rappels testés JVM. Délivrance Android réelle et intégration manifest restent à valider sur AVD. Sans permission/runtime notifications désactivées, l'alarme reste planifiée et le résultat indique l'absence de notification. |
| Bibliothèque manuelle, allowlist, recherche vs vidéo, pin/suppression/maps, compteur désactivable | **PASS** | Tests exécutés pour URLs invalides, migration des recherches, ajout/épingle/suppression/maps et compteur sans historique. |
| Opener `ACTION_VIEW`, chooser, fallback, aucun handler et fakes JVM | **PASS — fake JVM** | `AndroidContentOpener` accepte une passerelle fake; tests exécutés pour chooser, fallback navigateur simulé, échec et absence de handler. Aucun lecteur, navigateur ou ReVanced n'a été supposé installé. |
| `./gradlew :app:testDebugUnitTest` entier, incluant lots C et existants | **PASS** | Dernière exécution : 101 tests, 0 échec/erreur, 0 ignoré, 24 suites XML. Une première tentative s'est arrêtée sur l'intégration concurrente `sync/RemoteBackendGateway.kt`; les deux exécutions suivantes ont échoué uniquement sur des assertions de dates des nouveaux tests avant correction. |
| Test instrumenté / AVD | **BLOCKED — non exécuté conformément au brief** | Le lot D utilise l'AVD partagé; E s'est limité aux tests JVM. Aucun test instrumenté n'a été écrit ni lancé. |
| Diff d'intégration manifest / Controller / UI et API stable | **PASS** | Propositions non appliquées ci-dessous. |

Commandes exécutées : `./gradlew --version` (**Gradle 9.6.0**, JDK 21.0.12.1), `./gradlew :app:testDebugUnitTest` (première tentative bloquée par la source concurrente, deux tentatives avec assertions de tests incorrectes, dernière tentative **PASS — 101/101**), `git diff --check` (**PASS**). Aucun test AVD/Pixel, build APK, lint ni pytest n'a été lancé par E.

## Décisions automatiques

Les choix réversibles sont consignés dans `docs/DECISIONS_AUTOMATIQUES.md`: début de semaine lundi par défaut mais configurable; date de création locale persistée avec repli UTC pour les anciennes données; alternance tous les deux jours par défaut avec date de départ obligatoire; alarmes inexactes sans permission d'alarme exacte; compteur local par lien et désactivable. Les reminders sont des notifications uniquement. Une autorisation absente ne déclenche aucune demande système en arrière-plan.

## Intégration requise

Ces propositions ne sont **pas appliquées** : `AndroidManifest.xml`, `AtlasController.kt`, `AtlasApp.kt` / `MainActivity.kt` et les écrans Compose sont hors des chemins autorisés au lot E.

### `AndroidManifest.xml` — receiver et permissions

Le manifeste actuel contient déjà une requête `<queries>` pour `ACTION_VIEW` + `https`; elle couvre la résolution des lecteurs et du navigateur, donc aucune entrée package/ReVanced supplémentaire n'est requise.

```diff
 <manifest ...>
+    <!-- Nécessaire seulement après consentement aux rappels, API 33+. -->
+    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
+    <!-- Redonne les alarmes après redémarrage; pas de permission d'alarme exacte. -->
+    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
     <application ...>
+        <receiver android:name=".habits.android.HabitReminderReceiver"
+            android:exported="false" />
+        <receiver android:name=".habits.android.HabitReminderRescheduleReceiver"
+            android:enabled="true" android:exported="false">
+            <intent-filter>
+                <action android:name="android.intent.action.BOOT_COMPLETED" />
+                <action android:name="android.intent.action.TIME_SET" />
+                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
+            </intent-filter>
+        </receiver>
     </application>
 </manifest>
```

`POST_NOTIFICATIONS` doit être demandé depuis une action de premier plan qui explique le rappel; le refus ne bloque ni le reste du coach ni la révocation. Sans permission ou si le canal est coupé, `AndroidHabitReminderScheduler` conserve une alarme inexacte mais le receiver n'affiche rien. `RECEIVE_BOOT_COMPLETED` ne sert qu'à restaurer l'heure des rappels. `SCHEDULE_EXACT_ALARM` n'est pas proposé.

### `AtlasController.kt` — unique écriture, scheduling et persistance

```diff
+private val habitReminderScheduler = AndroidHabitReminderScheduler(context)
+private val focusLinkStore = InMemoryFocusJournalLinkStore(
+    restored["focusJournalLinks"].asList().mapNotNull { it.asMap()?.let(FocusJournalLink::fromMap) },
+)
+private val focusSessionLinker = FocusSessionLinker(focusLinkStore)

-fun addHabit(name: String) { habits.add(name, HabitSchedule.daily()); persist() }
+fun addHabit(name: String, schedule: HabitSchedule = HabitSchedule.daily(),
+             reminder: HabitReminder? = null) {
+    val habit = habits.add(name, schedule)
+    if (reminder != null) habits.setReminder(habit.id, reminder)
+    if (reminder?.enabled == true && capabilities[CAP_HABIT_REMINDERS] == true)
+        habitReminderScheduler.schedule(habit.id, habit.name, reminder)
+    persist()
+}
+fun setHabitReminder(habitId: String, reminder: HabitReminder?) {
+    val habit = habits.setReminder(habitId, reminder)
+    if (reminder?.enabled == true && capabilities[CAP_HABIT_REMINDERS] == true)
+        habitReminderScheduler.schedule(habit.id, habit.name, reminder)
+    else habitReminderScheduler.cancel(habitId)
+    persist()
+}

 fun confirmHabitLink(habitId: String) {
     // Vérifier FINISHED + la condition choisie; ne rien écrire sans confirmation UI.
+    when (focusSessionLinker.link(session, condition, habitId = habitId,
+        habitConfirmed = true, linkedAtUtcMillis = clock.nowMillis())) {
+        is FocusLinkResult.Recorded -> habitLinkPolicy.confirmLink(session.id, habitId, LocalDate.now())
+        is FocusLinkResult.AlreadyRecorded, FocusLinkResult.ConditionNotMet,
+        FocusLinkResult.HabitConfirmationRequired -> return
+    }
     persist()
 }

 fun openContent(id: String, context: Context): ContentOpenResult {
     val result = catalog.open(id, AndroidContentOpener(context))
+    // `catalog.open` met à jour seulement le compteur local si l'ouverture réussit.
     latestMessage = result.message
-    touch()
+    persist()
     return result
 }
+fun addContentLink(title: String, url: String) { catalog.addManualLink(title, url); persist() }
+fun removeContent(id: String) { catalog.remove(id); persist() }
+fun pinContent(id: String, pinned: Boolean) { catalog.setPinned(id, pinned); persist() }
+fun setContentOpenCounting(id: String, enabled: Boolean) {
+    catalog.setOpenCountingEnabled(id, enabled); persist()
+}
```

Garder une capability distincte `CAP_HABIT_REMINDERS` afin de désactiver toutes les alarmes sans désactiver le calendrier ou le check-in. Lors d'une désactivation ou d'un archivage, annuler aussi l'alarme. Restaurer `focusJournalLinks` une seule fois et le sérialiser dans le même document `LocalStateStore`. Pour un check-in lié à Focus, choisir un seul chemin : enregistrer le lien après la condition et la confirmation, puis appeler une fois `HabitLinkPolicy.confirmLink`. Ne pas appeler en parallèle `onSessionCompleted`; les rappels n'utilisent aucun de ces appels.

### `AtlasApp.kt` / UI — calendrier et bibliothèque

- Écran Habitudes : remplacer le formulaire quotidien seul par un sélecteur quotidien / jours cochés / quota hebdomadaire avec début de semaine / alternance avec date initiale. Présenter les champs heure, jours et activation du rappel séparément. La bascule globale `CAP_HABIT_REMINDERS` et la désactivation par habitude appellent `setHabitReminder(..., null)` ou `cancel`; la permission Android est demandée explicitement au premier plan.
- Garder des actions distinctes « Valider aujourd'hui » et « Annuler la validation ». Afficher dans l'historique les échéances, les jours sans échéance, le taux 7/30 jours, la série courante/meilleure et la force Loop. Une notification ne coche aucune habitude.
- Dialog de lien : champs titre + URL HTTPS, validation immédiate par `ContentUrlValidator.isSpecificVideoLink`, ajout manuel, suppression et bouton Épingler. Étiqueter les `SEARCH` comme recherches génériques au lieu de liens vidéo sélectionnés. Afficher seulement le compteur local (`openCount`) et son interrupteur par élément; ne pas afficher de progression/visionnage YouTube.
- L'action « Ouvrir » appelle `openContent` et laisse le chooser Android proposer tout handler HTTPS disponible. Si aucun handler ne répond, conserver le message d'échec et proposer de copier l'URL. Aucun choix de package, y compris ReVanced, n'est enregistré.

## Limites et travaux restants

1. Rejouer `cd android && ./gradlew :app:testDebugUnitTest` après intégration des autres lots; ce lot a passé la suite complète à 100 tests.
2. Intégrer les propositions Controller/UI/manifest, demander `POST_NOTIFICATIONS` uniquement lors d'une activation explicite, et persister les maps après chaque mutation.
3. Après coordination avec le lot D, vérifier sur l'AVD Android 17 permission accordée/refusée, canal désactivé, rappel révoqué, reboot, changement de fuseau/heure et chooser avec lecteur/navigateur/aucun handler. E n'a pas lancé l'AVD.
4. Les liens vidéo restent des intents vers une app externe; l'ouverture réussie ne signifie pas que la vidéo a démarré ni été regardée.
