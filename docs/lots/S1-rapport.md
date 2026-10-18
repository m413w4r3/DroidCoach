# Rapport S1 — Domaine focus + règles

## Résultat

Le domaine Focus et l’évaluateur de règles sont implémentés en Kotlin pur, sans classe Android, réseau, LLM ni dépendance Gradle ajoutée. Les `.gitkeep` de `focus/` et `rules/` ont été supprimés. L’état et les contrats sont locaux ; aucune observation ou ouverture réelle d’application n’est faite.

## API publique

### Focus — `dev.atlas.coach.focus`

- `interface Clock { fun nowMillis(): Long; fun monotonicMillis(): Long }` : horloge UTC epoch et temps monotone.
- `FakeClock(startUtcMillis: Long = 0L, startMonotonicMillis: Long = 0L)` : horloge de démonstration/test, avec `advanceMillis`, `advanceWallMillis`, `setWallMillis` et `advanceMonotonicMillis`.
- `FocusPhase`: `WORK`, `SHORT_BREAK`, `LONG_BREAK` ; `FocusStatus`: `IDLE`, `RUNNING`, `PAUSED`, `FINISHED`, `CANCELLED`.
- `FocusPreset.POMODORO_25_5` et `FocusPreset.VARIANT_10_2`; les plans utilisent 15 minutes de pause longue toutes les 4 phases de travail.
- `FocusPlan(presetId, workSeconds, shortBreakSeconds, longBreakSeconds = 900, longBreakEveryCycles = 4)`, `FocusPlan.fromPreset(...)`, `FocusPlan.custom(workSeconds)` et `scaled(factor)`.
- `FocusSession` porte l’ID, la directive, la phase, l’état, les horodatages UTC, le temps restant, les cycles et les durées. `toMap()` / `FocusSession.fromMap(...)` fournissent un format primitif sans sérialiseur.
- `FocusEngine(clock, idFactory)` expose `start(preset, directiveId)`, `start(plan, directiveId)`, `start(durationSeconds, directiveId)`, `currentSession()`, `snapshot()`, `pause(id)`, `resume(id)`, `finish(id)`, `cancel(id)` et `restore(FocusSession | Map<String, Any?>)`.
- `FocusRepository` est synchrone :

  ```kotlin
  fun start(durationSeconds: Long, directiveId: String? = null): FocusSession
  fun pause(id: String): FocusSession?
  fun resume(id: String): FocusSession?
  fun finish(id: String): FocusSession?
  fun cancel(id: String): FocusSession?
  ```

- `InMemoryFocusRepository(clock, idFactory)` implémente ce contrat et expose aussi `currentSession()`, `snapshot()` et `restore(...)` pour faciliter le raccordement local.

Les actions répétées sur une session active ou terminale sont sans erreur et sans effet supplémentaire. `start` sur une session RUNNING/PAUSED renvoie la session existante ; après FINISHED/CANCELLED, un nouvel appel démarre une nouvelle session. Un ID inconnu renvoie `null` pour les actions ciblées.

### Règles — `dev.atlas.coach.rules`

- `AppOpenEvent(eventId: String, packageName: String, timestampUtc: Long)`.
- `QuietHours(start: LocalTime, end: LocalTime)` : début inclus, fin exclue ; gère les intervalles qui traversent minuit.
- `LocalPolicy` configure `ruleId`, seuil (4), fenêtre (25 min), cooldown (15 min), heures calmes, `pauseUntil`, packages vitaux et durée de pause d’urgence (30 min). `withEmergencyPause(nowUtc)` produit le `pauseUntil` correspondant.
- `RuleEvaluator(zoneId: ZoneId, initialState)` fournit `evaluate(event, policy): Intervention?`, `snapshot()` et `restore(state)`. Le fuseau est injecté ; les compteurs et cooldowns sont séparés par règle et package. `RuleEvaluatorState` expose les event IDs traités, événements récents, cooldowns et interventions émises.
- `AppEventSimulator(clock, eventIdFactory).generate(packageName, count, intervalSeconds, firstTimestampUtc)` génère des événements synthétiques espacés à partir de l’horloge.

Une intervention est une proposition locale (`Intervention`) : elle ne bloque jamais Android. Après intervention, le compteur de cette paire règle/package est vidé. Les ouvertures durant le cooldown, la pause, les heures calmes ou concernant un package vital sont dédupliquées, mais ne remplissent pas le seuil suivant.

## Décisions et limites

- API synchrone, car S1 n’a ni coroutines ni dépendance de persistance. `InMemoryFocusRepository` et l’état du moteur disparaissent avec le processus tant que S3 ne les persiste pas.
- En fonctionnement, le temps écoulé vient de `monotonicMillis()`. `snapshot()` sauvegarde `updatedAtUtcMillis` et `remainingSeconds`; le mapping contient uniquement des valeurs primitives/nullables.
- À la restauration d’une session RUNNING, le temps écoulé est recalculé depuis l’horloge UTC, plafonné à 24 heures. Si l’horloge murale a reculé ou est égale au checkpoint, aucun temps n’est déduit. Une session PAUSED ne consomme pas le temps mural.
- Le mode accéléré est fourni par `FocusPlan.scaled(factor)` ou par des durées de plan exprimées en secondes. Il n’y a ni boucle de notification ni alerte.
- La déduplication conserve tous les `eventId` dans l’état : S3 devra décider d’une rétention si le stockage est durable et de longue durée, en tenant compte de l’idempotence recherchée.
- `AppEventSimulator` ne lit ni ne lance d’application. Il n’y a pas d’intégration `UsageStatsManager` dans S1.
- Aucune dépendance ne semble indispensable au périmètre ; aucun fichier Gradle n’a été modifié.

## Fichiers

Créés :

- `android/app/src/main/java/dev/atlas/coach/focus/Clock.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/FocusEngine.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/FocusModels.kt`
- `android/app/src/main/java/dev/atlas/coach/focus/FocusRepository.kt`
- `android/app/src/main/java/dev/atlas/coach/rules/AppEventSimulator.kt`
- `android/app/src/main/java/dev/atlas/coach/rules/RuleEvaluator.kt`
- `android/app/src/main/java/dev/atlas/coach/rules/RuleModels.kt`
- `android/app/src/test/java/dev/atlas/coach/focus/FocusEngineTest.kt`
- `android/app/src/test/java/dev/atlas/coach/rules/RuleEvaluatorTest.kt`

Supprimés : `android/app/src/main/java/dev/atlas/coach/focus/.gitkeep` et `android/app/src/main/java/dev/atlas/coach/rules/.gitkeep`.

## Commandes et résultats observés

Environnement relevé : OpenJDK `21.0.12.1`; le dépôt n’est pas initialisé avec Git. Aucun appareil réel n’a été utilisé.

Commande demandée, lancée depuis `android/` :

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.atlas.coach.focus.*' --tests 'dev.atlas.coach.rules.*'
```

La première exécution a terminé avec `18 tests completed, 1 failed` : l’assertion du test de doublon attendait à tort zéro intervention après quatre IDs distincts. Après correction de l’assertion, la même commande a réussi. Elle a été relancée après les derniers ajustements et a terminé par `BUILD SUCCESSFUL`.

Les sorties XML sont partagées avec les autres lots dans `app/build/`; une exécution concurrente les avait remplacées par les rapports S2. J’ai relancé le filtre S1 et relu les XML immédiatement après. Résultat final : **18 tests, 0 échec, 0 erreur, 0 ignoré** — 7 tests Focus et 11 tests Rules. Les scénarios demandés (cycles 25/5 et 10/2, pause longue, idempotence, restauration et accélération, seuil/cooldown/doublons, fenêtres, heures calmes et fuseau, pause, exceptions vitales, simulateur) sont couverts.

Pas de `clean`, pas d’`assembleDebug`, pas d’essai sur émulateur ou Pixel réel : seule la commande de tests unitaires demandée a été exécutée.

## Raccordement attendu pour S3

1. Implémenter dans la couche Android un `Clock` utilisant l’heure UTC système et une source monotone telle que `SystemClock.elapsedRealtime()` ; conserver S1 indépendant du framework.
2. Choisir une persistance locale et enregistrer `repository.snapshot()?.toMap()` ; à la reprise, appeler `restore(map)` avant d’afficher l’état. Le mapping du timer n’impose aucune bibliothèque.
3. Instancier `RuleEvaluator` avec le fuseau local choisi et persister/restaurer `snapshot()` selon le format du stockage S3.
4. Raccorder les événements simulés via `AppEventSimulator` pour VM-1 ; ne pas présenter ces événements comme une observation réelle d’applications.
5. Appeler ces APIs de façon synchrone depuis l’adaptateur/UI et présenter `Intervention` comme proposition. Aucune notification ou action Android n’est fournie par S1.
