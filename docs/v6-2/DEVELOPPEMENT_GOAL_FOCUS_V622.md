# Développement S3 — Goal–Focus V6.2.2

**État :** tranche métier implémentée et validée le 20 octobre 2026.
**Références :** [contrat gelé](CONTRAT_GOAL_FOCUS_V622.md) · [plan d’intégration S5](INTEGRATION_V622.md).
**Branche :** `feat/atlas-v6-2-2` · aucun commit, push ou merge.

## API livrée

Dans `dev.atlas.coach.focus` :

```kotlin
@ConsistentCopyVisibility
data class PreparedFocusStart internal constructor(
    val session: FocusSession,
    internal val phaseStartedMonotonicMillis: Long,
)

fun FocusEngine.prepareStart(
    plan: FocusPlan,
    directiveId: String?,
    sessionId: String,
    startedAtUtcMillis: Long,
    startedAtMonotonicMillis: Long,
): PreparedFocusStart

fun FocusEngine.publishPreparedStart(prepared: PreparedFocusStart): FocusSession
```

`prepareStart` vérifie l’ID et la date UTC, construit la session `RUNNING` en phase `WORK` et capture l’origine monotone fournie. Il ne consulte ni horloge ni générateur d’ID et ne touche pas au moteur. `publishPreparedStart` installe la session, le temps restant et l’origine monotone par affectations seulement. Le wrapper métier `start()` garde sa compatibilité et passe désormais par ces opérations.

Dans `dev.atlas.coach.goals` :

```kotlin
@ConsistentCopyVisibility
data class PreparedFocusLink internal constructor(
    val goalId: String,
    val focusSessionId: String,
    val linkedGoal: GoalRecord,
    val goalsSubdocument: Map<String, Any?>,
    internal val expectedRecords: List<GoalRecord>,
    internal val candidateRecords: List<GoalRecord>,
)

fun GoalRepository.prepareFocusLink(
    goalId: String,
    focusSessionId: String,
    linkedAtUtcMillis: Long,
): PreparedFocusLink

fun GoalRepository.publishCommittedFocusLink(prepared: PreparedFocusLink): GoalRecord
```

`GoalPanelCoordinator` expose les deux façades de même signature et délègue au dépôt. La préparation refuse un objectif absent, inactif ou d’une autre métrique, un ID vide et un ID déjà réclamé par un autre objectif actif. Une répétition du même lien au même objectif est idempotente. Le sous-document conserve `schema_version = 1`.

Les records, sets, listes et maps des snapshots sont copiés et exposés en collections non modifiables. `expectedRecords` est `internal`, donc accessible à S5 dans le même module pour comparer la candidate à `repository.all()` **avant** toute écriture. Après le commit racine, la publication du dépôt installe directement la liste candidate immuable par affectation de référence, sans sérialisation, callback ou validation.

## Effets et frontière S5

Ces API préparent les éléments métier; elles ne réalisent pas le commit racine. S5 doit, sous son verrou commun Focus/Goals, vérifier l’état Focus et comparer `expectedRecords` aux records actuels avant l’écriture. La composition attendue reste `stateMap()` + session Focus + historique terminal candidat + Goals candidate (inchangé si aucune sélection) + message final, puis un unique `persistOrThrow(..., stateOverride = candidate)`.

Après le retour réussi seulement, S5 publie Focus et historique, installe le lien Goal et le miroir `goalsSubdocument`, puis programme l’alarme, émet `FOCUS_STARTED`, publie le message et navigue. Le démarrage agent conserve son `persistAgentAction` existant et consomme `prepareStart` sans second commit. Ces raccordements contrôleur, le verrou des sept mutations Goals, le rejet d’une candidate obsolète et les effets après commit restent à faire par S5.

## Progression et couverture C1–C4

`GoalProgressCalculator` n’a pas été modifié. Les nouveaux tests emploient `FakeClock` et le vrai `FocusEngine` pour couvrir :

- démarrage à `0/120`, progression toujours nulle durant le travail non terminé, la pause de 10 minutes, les huit heures suspendues et la reprise ; la fin réelle après 25 minutes de travail donne `25/120` ; la recréation du coordinateur depuis le sous-document candidat et le même historique redonne `25/120` aux recalculs répétés ;
- annulation liée à zéro minute ; pause courte Pomodoro exclue du travail compté ; un lien vers une session historique finie reste disponible par `focusLinkOptions()` et `linkFocus()` ; une ligne historique dupliquée ne donne pas de double crédit ;
- préparations Focus et Goal sans publication ni écriture, publications post-commit sans écriture, ID déjà lié idempotent, et snapshots indépendants ;
- refus des objectifs absents, en pause, `MANUAL`, `HABIT_CHECKINS`, ainsi que des sessions déjà revendiquées par un autre objectif actif.

Le calcul existant compte les cycles de travail terminés et, pour une fin manuelle dans `WORK`, les secondes écoulées de cette phase. `RUNNING`, `PAUSED` et `CANCELLED` ne rapportent rien; les pauses courtes/longues ne sont jamais ajoutées. Le plancher minute s’applique après somme des secondes liées et le calculateur déduplique les versions par ID.

## Fichiers S3 et changements

Sources métier modifiées :

- `android/app/src/main/java/dev/atlas/coach/focus/FocusEngine.kt` — candidate déterministe, publication post-commit et conservation du wrapper `start()`.
- `android/app/src/main/java/dev/atlas/coach/goals/GoalRepository.kt` — candidates Focus–Goal, copie d’état, validation métier avant écriture et installation post-commit par affectation.
- `android/app/src/main/java/dev/atlas/coach/app/GoalPanelCoordinator.kt` — façades métier.

Tests modifiés ou ajoutés :

- `android/app/src/test/java/dev/atlas/coach/focus/FocusEngineTest.kt`;
- `android/app/src/test/java/dev/atlas/coach/goals/GoalRepositoryTest.kt`;
- `android/app/src/test/java/dev/atlas/coach/goals/GoalProgressCalculatorTest.kt`;
- nouveau `android/app/src/test/java/dev/atlas/coach/app/GoalPanelCoordinatorFocusLinkTest.kt`.

`FocusModels.kt`, `GoalRecord.kt`, `GoalProgressCalculator.kt`, `AtlasController.kt`, l’UI, `FocusSession` sérialisée et le protocole n’ont reçu aucune modification S3. Aucun champ `goalId` n’a été ajouté au modèle Focus ou au protocole. S5 reste propriétaire de `AtlasController.kt`, `AtlasScreens.kt`, `FocusRoutinesScreen.kt`, de ses tests de transaction et du raccordement UI.

## Commandes et résultats

Depuis `android/` :

| Commande | Résultat |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | PASS final : 297 tests, 0 échec, 0 erreur, 0 ignoré (56 rapports XML). La première passe a détecté une assertion erronée dans un test ajouté; elle a été corrigée avant la passe finale. |
| `./gradlew :app:assembleDebug` | PASS : assemblage `debug` complet. |

Depuis la racine : `git diff --check` — PASS. La compilation de production et des tests Kotlin est incluse dans `testDebugUnitTest`; l’APK Debug a aussi été assemblée.

## Limites restantes

- Aucun test instrumenté ou appareil n’a été utilisé par S3.
- L’atomicité du document racine, la restauration après échec d’écriture, les alarmes/événements, les doubles taps, la sérialisation de toutes les mutations Goals, les wrappers controller et la transaction agent restent à vérifier par S5; les tests domaine ne prouvent pas ces effets d’intégration.
- La sélection locale d’objectif dans l’UI n’est pas implémentée dans cette tranche.
- Les changements partagés S1 (tests instrumentés localisation, script et ses preuves/docs) sont restés intacts.
