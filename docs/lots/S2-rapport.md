# Rapport du lot S2 — Habitudes, coach et catalogue de contenu

## Résumé

S2 livre des APIs synchrones en Kotlin pur pour les habitudes, les directives et le catalogue de contenu. Les dépôts fournis sont en mémoire ; les entités utiles exposent `toMap()` / `fromMap()` (ou `toMaps()` / `fromMaps()`) afin que S3 puisse les brancher sur sa persistance. Aucun fichier Gradle ni dépendance n’a été modifié. L’unique classe utilisant Android est l’adaptateur d’ouverture de lien.

## API publique

### Habitudes (`dev.atlas.coach.habits`)

```kotlin
fun interface HabitClock { fun nowUtcMillis(): Long }

data class HabitSchedule(
    val kind: HabitScheduleKind,
    val weekdaysMask: Int = 0,
    val timesPerWeek: Int = 0,
)
HabitSchedule.daily()
HabitSchedule.weekdays(days: Set<DayOfWeek>)
HabitSchedule.timesPerWeek(count: Int)

interface HabitRepository {
    fun list(): List<Habit>
    fun add(name: String, schedule: HabitSchedule): Habit
    fun checkIn(habitId: String, date: LocalDate): HabitCheckIn
    fun undoCheckIn(habitId: String, date: LocalDate): Boolean
    fun history(habitId: String, endDate: LocalDate, days: Int): HabitHistory
    fun checkIns(habitId: String): List<HabitCheckIn>
    fun archive(habitId: String): Habit
    fun exportCsv(): String
    fun exportMaps(): List<Map<String, Any?>>
}

class InMemoryHabitRepository(clock: HabitClock, newId: () -> String)

class HabitLinkPolicy(habits: HabitRepository, preEngagedHabitIds: Set<String> = emptySet()) {
    fun setPreEngagement(habitId: String, enabled: Boolean)
    fun confirmLink(sessionId: String, habitId: String, date: LocalDate): HabitCheckIn
    fun onSessionCompleted(sessionId: String, date: LocalDate): List<HabitCheckIn>
}
```

`Habit`, `HabitCheckIn`, `HabitSchedule` et la configuration de `HabitLinkPolicy` s’exportent en maps. Les check-ins sont uniques par `(habitId, date)` ; un appel répété renvoie le check-in existant. `undoCheckIn` indique si un élément a été retiré. L’historique accepte une fenêtre inclusive de 7 ou 30 jours et expose, pour chaque date, `DUE`, `DONE` ou `NOT_DUE`, le taux de réalisation et la série courante.

### Coach (`dev.atlas.coach.coach`)

```kotlin
fun interface CoachClock { fun nowUtcMillis(): Long }

data class Directive(
    val id: String,
    val title: String,
    val reason: String,
    val projectId: String? = null,
    val estimatedMinutes: Int? = 10,
    val state: DirectiveState = DirectiveState.PENDING,
    val type: DirectiveType = DirectiveType.START_FOCUS_SESSION,
    val choices: List<DirectiveChoice> = DirectiveChoice.entries,
    val createdAtUtcMillis: Long,
    val expiresAtUtcMillis: Long,
    val requiresExplicitConfirmation: Boolean = false,
)

enum class DirectiveChoice { START, DISCUSS, ALTERNATIVE, POSTPONE, PAUSE }

interface CoachRepository {
    fun currentDirective(): Directive
    fun findDirective(id: String): Directive?
    fun respond(id: String, choice: DirectiveChoice): DirectiveResult
    fun journal(): CoachJournal
    fun listJournal(): List<CoachJournalEntry>
    fun exportJournalCsv(): String
    fun isPaused(): Boolean
}

class MockCoachRepository(clock: CoachClock, initialDirective: Directive, newId: () -> String)
```

Les noms de directive suivent le schéma autant que possible (`directive_id`, `type`, `title`, `reason`, `project_id`, `estimated_minutes`, `choices`, `requires_explicit_confirmation`). Les dates de map restent des millisecondes epoch UTC. Les maps de directive incluent la version de schéma `1` et les dates suffixées `_utc_ms` pour lever l’ambiguïté de format.

Les cinq choix produisent un résultat en français, une transition d’état et une entrée datée de `CoachJournal`. `START` fournit une demande de focus et sa durée ; le dépôt mock ne démarre pas lui-même de focus. `DISCUSS` renvoie un texte de discussion, `ALTERNATIVE` remplace la directive courante par une proposition déterministe de 5 min, `POSTPONE` décale l’échéance d’une heure, et `PAUSE` suspend les nouvelles réponses. Répéter le même couple `(directiveId, choix)` renvoie exactement le résultat précédent sans ajouter une seconde entrée au journal.

### Contenu (`dev.atlas.coach.content`)

```kotlin
data class ContentItem(val id: String, val title: String, val url: String)
object ContentUrlValidator { fun parse(rawUrl: String): URI; fun isValid(rawUrl: String): Boolean }
object ContentOpenRequestFactory { fun build(item: ContentItem): ContentOpenRequest }

interface ContentOpener { fun open(request: ContentOpenRequest): ContentOpenResult }
class FallbackContentOpener(gateway: ContentLaunchGateway) : ContentOpener
class AndroidContentOpener(context: Context) : ContentOpener

class ContentCatalog {
    fun find(id: String): ContentItem?
    fun open(id: String, opener: ContentOpener): ContentOpenResult
    fun toMaps(): List<Map<String, String>>
    companion object {
        fun create(items: List<ContentItem>): ContentCatalog
        fun demo(): ContentCatalog
        fun fromMaps(items: List<Map<String, Any?>>): ContentCatalog
    }
}
```

Le catalogue démo contient trois recherches YouTube génériques. La validation accepte uniquement HTTPS et les hôtes `youtube.com`, `www.youtube.com`, `m.youtube.com` et `youtu.be` ; elle rejette HTTP, les schémas actifs comme `javascript:`, les hôtes inconnus, les identifiants intégrés à l’URL, les fragments et les ports non standard. Le constructeur de requête fournit `android.intent.action.VIEW` et un `URI` testable sur JVM. L’adaptateur Android tente un chooser puis une ouverture directe ; le résultat expose aussi le cas sans application compatible.

## Décisions

- Identifiants générés par défaut : UUID ; ils restent fixes une fois enregistrés. Les horloges sont injectables par package et toutes les dates de stockage sont des millisecondes epoch UTC.
- Une date antérieure à la création d’une habitude n’est pas due et ne peut pas recevoir de check-in. L’historique utilise lundi comme début de semaine pour `N fois/semaine` : une date est due tant que le quota hebdomadaire n’a pas déjà été atteint avant cette date.
- La série ignore les jours non dus et n’est pas limitée à la fenêtre 7/30 jours affichée. Un jour dû non coché à la fin de la fenêtre n’interrompt pas immédiatement la série.
- Le lien focus/habitude n’est jamais implicite : `confirmLink` représente la confirmation explicite ; `onSessionCompleted` ne coche que les habitudes pré-engagées par l’utilisateur. La politique expose aussi sa configuration en map.
- Le mock coach accepte les choix déclarés par la directive, écrit un seul événement par couple directive/choix, et n’exécute aucune action Android. La pause interdit les nouvelles réponses ; une répétition idempotente de la réponse de pause reste lisible.
- Le catalogue est plafonné à trois éléments. La liste d’hôtes autorisés est volontairement stricte ; aucun code ne détecte ni ne mentionne un client vidéo particulier.
- L’ouverture Android est isolée dans `AndroidContentOpener.kt`. La logique de validation, la construction de requête et le fallback sont abstraits pour des tests JVM sans Robolectric.

## Fichiers créés et supprimés

- Créés :
  - `android/app/src/main/java/dev/atlas/coach/habits/Habits.kt`
  - `android/app/src/main/java/dev/atlas/coach/coach/Coach.kt`
  - `android/app/src/main/java/dev/atlas/coach/content/ContentCatalog.kt`
  - `android/app/src/main/java/dev/atlas/coach/content/AndroidContentOpener.kt`
  - `android/app/src/test/java/dev/atlas/coach/habits/HabitsTest.kt`
  - `android/app/src/test/java/dev/atlas/coach/habits/HabitLinkPolicyTest.kt`
  - `android/app/src/test/java/dev/atlas/coach/coach/CoachTest.kt`
  - `android/app/src/test/java/dev/atlas/coach/content/ContentCatalogTest.kt`
- Supprimés : les `.gitkeep` de `habits/`, `coach/` et `content/` en sources principales.
- `docs/lots/S2-rapport.md` est l’unique fichier de documentation ajouté pour S2.
- Le dépôt n’est pas initialisé avec Git ; aucune comparaison `git diff/status` n’était disponible. Aucune dépendance Gradle n’a été ajoutée.

## Commandes et résultats

Commande finale des tests, exécutée depuis `android/` :

```text
./gradlew :app:testDebugUnitTest --tests 'dev.atlas.coach.habits.*' --tests 'dev.atlas.coach.coach.*' --tests 'dev.atlas.coach.content.*'
BUILD SUCCESSFUL
CoachTest: 2 tests, 0 failure
ContentCatalogTest: 3 tests, 0 failure
HabitLinkPolicyTest: 1 test, 0 failure
HabitsTest: 2 tests, 0 failure
Total S2: 8 tests, 0 failure, 0 error, 0 skipped
```

Commande de compilation finale, depuis `android/` :

```text
./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 2s
APK : app/build/outputs/apk/debug/app-debug.apk
```

**Bilan final : 2 validations PASS** (tests ciblés et assemblage), **8 tests PASS / 0 FAIL / 0 erreur / 0 ignoré**. Historique des itérations du test ciblé : 3 PASS et 2 échecs transitoires corrigés. Une compilation des tests a été bloquée par une référence non résolue dans `rules/RuleEvaluatorTest.kt` (fichier S1) ; après une attente d’une minute et relance, ce problème avait disparu. L’itération suivante a échoué sur une fixture S2 qui cochait des dates antérieures à la création ; la fixture a été corrigée. La dernière exécution a confirmé les comptes depuis les rapports XML : 8/8.

## Limites et raccordement S3

- Les dépôts restent en mémoire : S3 doit charger/sauvegarder les maps, restaurer les directives et le journal, et persister la configuration des pré-engagements. Il doit appeler `onSessionCompleted` uniquement depuis le chemin d’une session effectivement terminée, et `confirmLink` après un consentement explicite.
- S3 doit câbler les écrans de création, check-in, annulation, historique/export, réponses de directive et journal. La demande de focus issue de `START` reste une proposition à raccorder au flux Focus.
- S3 doit fournir `AndroidContentOpener` à `ContentCatalog.open`. Le fallback chooser/direct et le message « aucune application » sont testés avec une passerelle JVM simulée ; l’envoi réel d’Intent n’a pas été instrumenté sur Android.
- Le catalogue contient des URLs de recherche publiques génériques, pas une sélection éditoriale de vidéos précises. Les liens externes nécessitent encore une validation visuelle dans l’interface intégrée.
- `assembleDebug` a réussi, mais aucun lancement de l’application ni test sur émulateur ou appareil réel n’a été effectué dans ce lot.
