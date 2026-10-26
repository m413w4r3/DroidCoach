# Contrat S3 — liaison Objectif → Focus V6.2.2

**État :** analyse et contrat proposés avant développement. Les signatures ci-dessous sont à geler avant le travail S3/S5.  
**Référence :** `feat/atlas-v6-2-2` · HEAD initial `bf647cc18a142dd0f5cd5e68b21c36d511014388`.  
**Périmètre de ce livrable :** contrat Focus/Goals, chemins d’appel, tests existants et risques C1–C4. Aucun fichier source ou test n’est modifié ici.

## 1. Décisions de modèle à préserver

- `GoalRecord.linkedFocusSessionIds` reste l’unique source de vérité du lien. Le schéma du sous-document Goals reste `schema_version = 1`.
- Un nouveau démarrage peut relier la session à **zéro ou un** objectif principal, choisi explicitement par l’utilisateur. Le démarrage agent sans sélection locale reste libre.
- Ne pas ajouter `goalId` à `FocusSession`, à ses maps ou au protocole OpenClaw V2. Ne pas ajouter de table, de dépôt ou de calculateur de progression.
- `GoalProgressCalculator` demeure le seul calculateur. L’écran lit sa projection existante via `GoalPanelCoordinator.entries()`.
- Le lien manuel historique d’une session déjà terminée reste accessible par `GoalPanelCoordinator.linkFocus()`. Il n’attribue aucun objectif aux anciennes sessions automatiquement.
- `FocusSessionLinker` / `FocusJournalLink` concernent le journal et les habitudes. Ce mécanisme reste distinct du lien Goal et n’est pas réutilisé.

Sources constatées : [GoalRecord.kt](../../android/app/src/main/java/dev/atlas/coach/goals/GoalRecord.kt), [GoalProgressCalculator.kt](../../android/app/src/main/java/dev/atlas/coach/goals/GoalProgressCalculator.kt), [FocusSessionLinking.kt](../../android/app/src/main/java/dev/atlas/coach/focus/FocusSessionLinking.kt), [GoalPanelCoordinator.kt](../../android/app/src/main/java/dev/atlas/coach/app/GoalPanelCoordinator.kt).

## 2. Contrat des préparations pures

### FocusEngine

Forme proposée pour la signature gelée :

```kotlin
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

fun FocusEngine.publishPreparedStart(
    prepared: PreparedFocusStart,
): FocusSession
```

`prepareStart` construit la `FocusSession` candidate en phase `WORK`, statut `RUNNING`, avec `remainingMillis = plan.workSeconds * 1_000`, et conserve l’instant monotone de départ. Il valide les entrées mais ne lit ni l’horloge ni le générateur d’identifiants, ne modifie aucun champ de l’engine et ne déclenche aucune alarme, notification, écriture, navigation ou synchronisation. L’identifiant et les deux instants lui sont fournis afin que cette préparation soit déterministe.

`publishPreparedStart` ne fait que publier la session candidate dans l’état mémoire de l’engine (`session`, temps restant de phase et origine monotone). Il ne persiste rien et n’émet aucun autre effet. La méthode historique `FocusEngine.start(...)` peut garder son comportement pratique de wrapper `prepareStart` + `publishPreparedStart` pour `FocusRepository` et les tests unitaires ; le contrôleur transactionnel ne doit pas l’appeler avant le commit.

Avant la préparation, le contrôleur rafraîchit l’état courant par le chemin existant et refuse un état `RUNNING` ou `PAUSED`. Cette vérification doit précéder la génération de la candidate. Les actions de démarrage doivent être sérialisées afin que deux taps simultanés ne préparent pas deux commits concurrents. La candidate doit aussi avoir un ID non vide et non déjà utilisé par la session courante ou l’historique conservé.

Source constatée : [FocusEngine.kt](../../android/app/src/main/java/dev/atlas/coach/focus/FocusEngine.kt), [FocusModels.kt](../../android/app/src/main/java/dev/atlas/coach/focus/FocusModels.kt).

### GoalRepository et GoalPanelCoordinator

Forme proposée pour la signature gelée :

```kotlin
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

fun GoalRepository.publishCommittedFocusLink(
    prepared: PreparedFocusLink,
): GoalRecord
```

`GoalPanelCoordinator` expose les mêmes deux opérations comme façade, en déléguant au dépôt. La date vient du `Clock` contrôlé du contrôleur et est passée en argument ; la préparation ne lit pas l’horloge.

`prepareFocusLink` travaille sur une copie de l’état courant et refuse avant écriture si l’objectif est absent, inactif ou différent de `FOCUS_MINUTES`, si l’ID de session est invalide, ou si un autre objectif actif revendique déjà cet ID. Si le même ID est déjà dans le set du même objectif, la préparation est idempotente. Elle produit le `GoalRecord` modifié (`updatedAt = maxOf(goal.updatedAt, linkedAtUtcMillis)`) et son sous-document versionné complet, sans appeler `persistSubdocument` ni modifier le cache `records`.

`publishCommittedFocusLink` ne fait qu’installer `candidateRecords` dans le cache du dépôt après confirmation du commit racine. Il ne rappelle jamais le callback de persistance. L’opération Focus/Goals est sérialisée par le contrôleur de la préparation jusqu’à la publication ; ainsi la candidate publiée correspond à l’état qui vient d’être committé. Le contrôleur met également à jour son miroir `goalsSubdocument` avec la candidate après ce commit, sans callback d’écriture.

Source constatée : [GoalRepository.kt](../../android/app/src/main/java/dev/atlas/coach/goals/GoalRepository.kt), [GoalPanelCoordinator.kt](../../android/app/src/main/java/dev/atlas/coach/app/GoalPanelCoordinator.kt).

## 3. Transaction racine et ordre des effets

Les préparations Focus et Goal produisent **un seul document racine candidat**, sans publication préalable :

```text
stateMap()
  + focus = preparedStart.session.toMap()
  + focusHistory = historique candidat avec l’ancienne session terminale si nécessaire
  + goals = preparedLink.goalsSubdocument, ou le sous-document courant si goalId == null
  + latestMessage = message final du chemin de démarrage
```

`focusHistory` est calculé sur une copie : dédupliquer l’ID, placer la session terminale précédente en tête, trier comme le chemin existant et limiter à `MAX_FOCUS_HISTORY`. Ne pas appeler `archiveCurrentTerminalFocus()` avant le commit puisqu’il publie une mutation mémoire. Pour `goalId == null`, conserver le sous-document Goals inchangé. Le message final du preset/Pomodoro est construit avant la transaction ; les wrappers ne font pas un second `persist()` pour le remplacer. La navigation reste mémoire seulement : ne pas ajouter de champ de navigation au schéma racine.

Appeler exactement une fois `persistOrThrow(operation, stateOverride = rootCandidate)`. Le code actuel de `persistOrThrow` passe par `persist()` ; transmettre `stateOverride` garantit qu’il écrit cette candidate et ne reconstruit pas un autre état racine. Aucun appel tolérant `persist()` ne peut annoncer le démarrage comme réussi.

En cas d’échec de l’écriture : aucune publication de `PreparedFocusStart`, aucune modification du cache Goal ou de `goalsSubdocument`, aucun archivage mémoire de l’ancienne session, aucune alarme, aucun événement `FOCUS_STARTED` et aucune navigation vers Focus. L’interface peut publier un message d’échec en mémoire, sans garder la session candidate comme session active.

Après le commit réussi seulement, effectuer les effets dans cet ordre :

1. Publier la session par `FocusEngine.publishPreparedStart()` et publier l’historique terminal candidat en mémoire.
2. Publier `PreparedFocusLink` dans `GoalRepository`, puis mettre à jour le miroir `goalsSubdocument` déjà committé. Aucune seconde écriture.
3. Planifier l’alarme de phase si `CAP_FOCUS_NOTIFICATIONS` l’autorise.
4. Émettre l’événement local `FOCUS_STARTED` par le chemin sync existant.
5. Publier le message, naviguer vers `AtlasScreenId.FOCUS` et appeler `touch()` pour l’UI.

Une panne d’alarme ou d’outbox après le commit ne doit pas annuler en mémoire ou sur disque la session déjà démarrée. Le chemin de restauration existant doit pouvoir replanifier l’alarme autorisée. Toute relance d’effet doit rester idempotente par l’ID de session ; elle ne relance pas la transaction racine.

### Compatibilité des chemins de démarrage

Tous les démarrages locaux convergent vers la même préparation, composition de candidate et publication post-commit :

- `startFocus(FocusPreset)` convertit le preset puis délègue.
- `startFocus(FocusPlan, goalId)` est l’entrée transactionnelle. Signature proposée :

  ```kotlin
  @JvmOverloads
  fun startFocus(plan: FocusPlan, goalId: String? = null)
  ```

  L’overload JVM sans `goalId` préserve les appelants Java ; les appelants Kotlin actuels qui font `startFocus(plan)` restent valides.
- `startFocus(ReefPomodoroConfig)` et `startFocus(ReefPomodoroConfig, taskTitle)` convertissent/valident leur plan puis délèguent à la même transaction. Ils ne persisteront pas un second message de succès.
- `startDemoFocus()` fournit son plan de 20 secondes à la même transaction, sans archivage préalable.
- `startFocusRoutine(id)` continue de passer par le chemin Pomodoro commun.
- `respond(DirectiveChoice.START)` est un chemin actuel qui appelle directement `focusEngine.start`; le faire converger vers le démarrage sans objectif, sans sélection implicite.

Tous ces chemins vérifient `CAP_FOCUS` et l’absence d’une session `RUNNING`/`PAUSED`. Aucun ne démarre si la capability est désactivée. Une absence de `goalId` signifie explicitement session libre.

## 4. Frontière de la transaction agent existante

Le démarrage agent reste derrière `MandateVerifier`, `ActionDispatcher`, le journal d’effets et `persistAgentAction`. Son écriture racine ajoute ensemble l’état métier et `appliedLocalAgentEffectKeys`; après succès, son callback `publish` publie Focus/HOME, puis programme l’alarme et émet `FOCUS_STARTED`. En échec, `failedAgentAction` garde la voie de réconciliation : la candidate n’est pas publiée et aucun ACK terminal ne doit annoncer l’effet appliqué.

Le raccordement utilise `prepareStart` pour produire la session et le même bloc mémoire post-commit pour publier Focus/alarme/événement. Il **ne passe pas** par une fonction de démarrage qui appelle elle-même `persistOrThrow` : ce serait un double commit et pourrait séparer la session du marqueur d’idempotence agent.

`AgentActionPort.startFocus(proposal)` reçoit par défaut `goalId = null`. Si l’UI du parcours d’acceptation expose aussi un choix local explicite, ce choix doit être un argument/contexte local au contrôleur et entrer dans la candidate Goals du commit `persistAgentAction` existant. Il ne va ni dans `ProposalV2.parameters`, ni dans le journal/protocole, ni dans `FocusSession`. Si aucun choix utilisateur n’est fourni, ne pas créer de lien.

`startAgentProposedFocus(...)` peut continuer de sauvegarder la modification utilisateur de la carte avant acceptation, comme aujourd’hui. Le commit de démarrage demeure celui du dispatch `focus.start`, où le nouvel état de session et l’éventuelle Goals candidate sont associés au même marqueur agent.

Sources constatées : [AtlasController.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt), [ActionDispatcher.kt](../../android/app/src/main/java/dev/atlas/coach/sync/agent/dispatch/ActionDispatcher.kt), [AgentContractsTest.kt](../../android/app/src/test/java/dev/atlas/coach/sync/AgentContractsTest.kt).

## 5. Règle unique de progression — travail, pauses, Pomodoro

Pour un objectif `FOCUS_MINUTES`, la progression est dérivée des IDs explicites du set `linkedFocusSessionIds` et des sessions `FINISHED` seulement. `GoalProgressCalculator.completedWorkSeconds()` applique :

- les cycles de travail entièrement terminés, multipliés par `workSeconds` ;
- plus le temps de travail effectivement écoulé dans la phase `WORK` si une session est terminée manuellement au milieu du travail ;
- zéro pour les statuts `RUNNING`, `PAUSED` et `CANCELLED`.

Le temps `SHORT_BREAK`/`LONG_BREAK` n’est jamais du travail et n’ajoute aucune minute. Le temps passé en pause n’est pas mesuré par le chronomètre monotone et ne peut pas être crédité. La conversion en minutes est un plancher **après somme des secondes liées** : deux fractions issues de sessions finies peuvent donc cumuler une minute, sans arrondi par session. Les IDs liés sont des `Set`; le calculateur sélectionne la dernière version de chaque ID et, à timestamp égal, traite l’annulation de façon conservatrice. Recalculer la progression n’écrit ni n’incrémente un compteur.

Conséquences de l’exemple demandé : objectif actif `0 / 120` avant et juste après démarrage, toujours `0 / 120` durant `RUNNING` ou `PAUSED`, puis `25 / 120` une fois les 25 minutes de travail réellement terminées. Une terminaison manuelle en milieu de phase crédite uniquement les secondes de travail réellement écoulées ; une annulation ne crédite rien. Après recréation, la même paire GoalRecord + historique produit la même progression.

Dans l’écran : choix d’objectif avant le départ parmi les seuls objectifs actifs `FOCUS_MINUTES`; aucun sélecteur si la liste est vide. Avec un seul candidat, le montrer comme sélection visible avant le tap Démarrer. Avec plusieurs, valeur initiale « Sans objectif ». Pendant le Focus lié, afficher le titre et la durée prévue. Après terminaison, afficher les minutes validées et le `currentValue / targetValue` issu de `GoalProgressCalculator`, sans calcul UI parallèle. Le lien manuel des sessions historiques conserve ses options actuelles (sessions terminées uniquement).

## 6. Revue des tests existants

Tests métier déjà présents et limites de leur couverture :

- [FocusEngineTest.kt](../../android/app/src/test/java/dev/atlas/coach/focus/FocusEngineTest.kt) couvre transitions Pomodoro, cycles, reprise par temps mural borné, temps monotone, pause/reprise/fin/annulation et alarmes simulées. Il ne couvre pas encore une préparation sans mutation suivie d’une publication après commit.
- [GoalProgressCalculatorTest.kt](../../android/app/src/test/java/dev/atlas/coach/goals/GoalProgressCalculatorTest.kt) vérifie les sessions terminées liées, le temps partiel, les doublons, les annulations, les sessions non finies/non liées, l’arrondi après somme, les métriques habitude/manuelle et la sérialisation.
- [GoalRepositoryTest.kt](../../android/app/src/test/java/dev/atlas/coach/goals/GoalRepositoryTest.kt) couvre migration v1, CRUD, règles de complétion et versions invalides ; il ne couvre pas de candidate Goal–Focus sans écriture ni publication sans callback.
- [GoalPersistenceTransactionTest.kt](../../android/app/src/test/java/dev/atlas/coach/goals/GoalPersistenceTransactionTest.kt) injecte une panne d’écriture pour les opérations du dépôt et vérifie cache/disque/recréation. Ce n’est pas le test d’une unique transaction racine Focus + Goals.
- [AtlasControllerInitialStateRecoveryTest.kt](../../android/app/src/test/java/dev/atlas/coach/app/AtlasControllerInitialStateRecoveryTest.kt) couvre la récupération/quarantaine et une création Goal après récupération, pas un démarrage Focus lié.
- [FocusSessionLinkingTest.kt](../../android/app/src/test/java/dev/atlas/coach/focus/FocusSessionLinkingTest.kt) porte sur le journal/habitude, pas sur `GoalRecord.linkedFocusSessionIds`.
- [ProposedFocusSessionTest.kt](../../android/app/src/test/java/dev/atlas/coach/app/ProposedFocusSessionTest.kt) teste les conversions de proposition et estimations, pas l’atomicité Goals.
- [FocusIntegrationInstrumentationTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/FocusIntegrationInstrumentationTest.kt) vérifie Pomodoro/notification avec `FakeClock`, sans Goal.
- [V6RealAgentE2eInstrumentationTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/app/V6RealAgentE2eInstrumentationTest.kt), [V62RealGatewayContextBridgeTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/app/V62RealGatewayContextBridgeTest.kt) et [V62OpenClawClientInstrumentationTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/app/V62OpenClawClientInstrumentationTest.kt) protègent les parcours agent/contexte existants ; ils ne démontrent pas encore le choix local d’objectif au démarrage.
- [ActionDispatcherTest.kt](../../android/app/src/test/java/dev/atlas/coach/sync/agent/dispatch/ActionDispatcherTest.kt), [AgentContractsTest.kt](../../android/app/src/test/java/dev/atlas/coach/sync/agent/AgentContractsTest.kt), [V621ReviewFindingsTest.kt](../../android/app/src/test/java/dev/atlas/coach/app/V621ReviewFindingsTest.kt) et les instrumentés d’échec de persistance restent les protections de la frontière Agent et de son idempotence.

Aucun test ciblé `GoalPanelCoordinator` n’a été trouvé sous `android/app/src/test`. Aucun de ces tests n’a été lancé dans cette phase.

### Tests à ajouter pour C1–C4

Avec `FakeClock` et des fixtures synthétiques uniquement :

1. Préparer un Focus ne change ni `FocusEngine.snapshot()` ni le statut ; publier la même candidate après commit la rend `RUNNING` avec le temps monotone attendu.
2. Préparer un Goal–Focus ne change ni `GoalRepository.all()` ni les écritures capturées ; la candidate contient l’ID dans `linkedFocusSessionIds` et le schéma v1 ; la publication committée met à jour le cache sans écriture.
3. Refuser objectif absent, inactif/paused/completed, `MANUAL` ou autre métrique ; refuser un ID déjà revendiqué par un autre objectif actif ; une préparation libre ne modifie aucun Goal.
4. Objectif 120 min : `0/120` avant démarrage, après démarrage, après pause et après reprise tant que la session n’est pas finie ; `25/120` après terminaison réelle ; toujours `25/120` après recréation et recalculs répétés.
5. Annulation liée : progression nulle ; session sans lien : aucun Goal changé ; fin en pause Pomodoro : seules les phases travail terminées comptent ; fin au milieu du travail : seules les secondes de travail écoulées sont ajoutées.
6. Deux sessions avec fractions de secondes/minutes : vérifier le plancher après agrégation et l’absence de double crédit par ID ou par retry.
7. Double tap / appel concurrent : un seul ID, un seul commit de démarrage, une seule alarme et un seul événement `FOCUS_STARTED`.
8. Échec injecté de l’écriture racine initiale : aucune session active, aucune Goals candidate en cache ou dans `goalsSubdocument`, aucun message de succès, aucune alarme et aucun événement de démarrage ; état identique après recréation.
9. Rejouer les démarrages preset, plan direct, Pomodoro config, config + titre, demo, routine et `respond(START)` pour prouver qu’ils utilisent le même chemin sans double écriture.
10. Rejouer l’acceptation agent `focus.start` avec et sans sélection locale : mandat/journal/`appliedLocalAgentEffectKeys` et Focus restent dans le commit agent existant ; sans sélection, aucun lien ; échec racine = pas de publication ni d’ACK terminal ; aucun champ Goal dans la proposition ou le modèle Focus.
11. Données V6.2.1 : restaurer sans migration destructive, aucun lien attribué à une ancienne session, l’option de lien manuel reste utilisable pour une session terminée.

Le test de panne du contrôleur est réalisable avec le point d’injection `stateBackendOverride` du constructeur d’`AtlasController`; les alarmes et événements doivent être observables par les seams disponibles ou des doubles de test ajoutés au seul périmètre S5.

## 7. Risques par section du prompt maître

### C1 — modèle

- La sélection auto-préremplie d’un unique objectif n’est conforme que si son nom reste clairement visible avant le tap de démarrage ; une valeur cachée ou tirée du titre `taskTitle` n’est pas un choix utilisateur.
- Un ID Focus réutilisé ou déjà revendiqué pourrait créditer une vieille session ; générer un ID neuf et vérifier l’absence de collision avant la transaction.
- Le chemin manuel historique accepte actuellement un lien vers une session terminée et stocke des IDs dans un set. Ne pas supprimer cette fonction ni réattribuer automatiquement les sessions préexistantes.

### C2 — transaction

- Aujourd’hui le chemin direct appelle `focusEngine.start()` avant `persist()` tolérant. Une panne peut donc laisser un Focus démarré uniquement en mémoire. Il faut remplacer ce séquencement pour les démarrages concernés.
- `stateMap()` appelle `focusEngine.snapshot()`, qui synchronise le moteur en cours ; la nouvelle session candidate ne doit pas être installée avant sa sérialisation. Composer le root map une fois et le transmettre à `persistOrThrow(stateOverride=...)`.
- Les wrappers Pomodoro et avec titre font actuellement un second `persist()` pour remplacer le message générique. Construire le message définitif avant le seul commit.
- `FocusEngine.start()` renvoie silencieusement la session déjà active si elle est `RUNNING`/`PAUSED`; le contrôleur actuel peut ensuite tout de même émettre alarme/événement de nouveau départ. Le nouveau préflight et la sérialisation des taps doivent refuser le double départ.
- Le chemin actuel `respond(START)` et le démarrage agent `focus.start` contournent la méthode directe générique. Les faire converger vers la préparation commune sans leur faire prendre un second commit.
- Après le commit, une alarme système n’est pas atomique avec le stockage. L’ordre imposé garantit « jamais alarme avant état durable » ; l’état durable est restauré si la planification OS échoue.

### C3 — interface

- Le sélecteur ne doit pas inclure les objectifs `MANUAL`, `HABIT_CHECKINS` ou non actifs. Son absence quand aucun objectif n’est éligible évite une action sans effet.
- Le calcul de minutes doit rester dans le calculateur domaine ; la vue ne doit pas remplacer les secondes réellement travaillées par `plan.workSeconds` avant fin.
- Le focus actuel affiche cycle, tâche, état et possibilité de lien Habitude ; il n’affiche encore ni sélecteur Goal ni progression Goal sur la session terminée.

### C4 — métier / reprise

- La calculatrice compte un `FINISHED` partiel à partir de `remainingMillis` quand la phase finale est `WORK`, mais jamais les statuts non terminaux ni les pauses. Tester le cas « Terminer » après une pause pour éviter de créditer le temps suspendu.
- L’historique et la session courante peuvent contenir le même ID après restauration ; la déduplication et le choix de la version la plus récente doivent rester vérifiés.
- Les tests actuels valident séparément le dépôt Goal, le calculateur, le timer et la transaction agent ; aucun ne prouve encore une seule écriture racine ou l’absence d’alarme sur échec Focus+Goal.

## 8. Propriété exclusive des fichiers après gel

La propriété suivante est la proposition de raccordement S3/S5. Aucun fichier ne doit être modifié par les deux lots.

| Lot | Fichiers exclusifs |
| --- | --- |
| **S3 — Focus/Goals métier** | [FocusEngine.kt](../../android/app/src/main/java/dev/atlas/coach/focus/FocusEngine.kt), [GoalRepository.kt](../../android/app/src/main/java/dev/atlas/coach/goals/GoalRepository.kt), [GoalPanelCoordinator.kt](../../android/app/src/main/java/dev/atlas/coach/app/GoalPanelCoordinator.kt), [FocusEngineTest.kt](../../android/app/src/test/java/dev/atlas/coach/focus/FocusEngineTest.kt), [GoalRepositoryTest.kt](../../android/app/src/test/java/dev/atlas/coach/goals/GoalRepositoryTest.kt), [GoalProgressCalculatorTest.kt](../../android/app/src/test/java/dev/atlas/coach/goals/GoalProgressCalculatorTest.kt), nouveau `android/app/src/test/java/dev/atlas/coach/app/GoalPanelCoordinatorFocusLinkTest.kt`. |
| **S5 — contrôleur et UI** | [AtlasController.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt), [AtlasScreens.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt), [FocusRoutinesScreen.kt](../../android/app/src/main/java/dev/atlas/coach/app/FocusRoutinesScreen.kt), nouveau `android/app/src/test/java/dev/atlas/coach/app/GoalFocusStartTransactionTest.kt`, et un nouveau test Compose/instrumenté Focus si nécessaire. `AtlasApp.kt` n’entre dans cette liste que si le câblage actuel de `FocusScreen` doit réellement changer. |

S3 ne touche pas `AtlasController.kt`, l’UI, les tests contrôleur/Compose ou le protocole. S5 ne modifie pas les fichiers métier S3 ; une évolution de signature après gel repasse par le contrat et une attribution explicite, sans travail concurrent sur le même fichier. Ne pas changer `FocusModels.kt`, `GoalRecord.kt`, `GoalProgressCalculator.kt` ou `ActionDispatcher.kt` pour ajouter un champ ou un format Goal–Focus.

## 9. Baseline et limites de cette phase

La branche et le HEAD correspondent à la référence du prompt maître. L’arbre partagé avait déjà deux non-suivis avant ce livrable, [ORCHESTRATION_V622.md](ORCHESTRATION_V622.md) et [Prompt_Maitre_6.2.2.md](../../prompts/Prompt_Maitre_6.2.2.md) ; ils sont préexistants et préservés.

Inventaire en lecture seule : JDK/OpenJDK 21.0.12.1, Python 3.12.3, Gradle système 4.4.1 (ancien), wrapper présent sous `android/gradlew`, SDK Android `$HOME/Android/Sdk` avec plateformes 23–37.2 et Build Tools 30.0.3 à 37.0.0, ADB 34.0.4 (`/usr/bin/adb`), pytest 9.1.1 et `backend/.venv` présent. `sdkmanager` n’est pas sur le `PATH`. Cet inventaire ne prouve ni compileSdk effectif ni résultat de test.

Pour respecter la fenêtre S0, aucune commande de build ou de test n’a été lancée et aucun fichier source/test n’a été édité. Les tests cités sont un inventaire des fichiers présents, pas des résultats V6.2.2. Les validations métier listées en §6 restent à exécuter dans leurs lots après gel des signatures.
