# Intégration V6.2.2 — tranche S5 C + B2 + correction S7-P1-01

**État au 21 octobre 2026 : C et B2 intégrés; S7-P1-01 est corrigé. La policy debug autorise exactement `127.0.0.1` en plus de l’exception existante, et les variants debug/release ont été vérifiés. La couverture ACK positive `accepted`→`executed` a été ajoutée à l’AndroidTest; tests JVM, lint et assemblages passent. Les deux méthodes ACK restent NOT_RUN jusqu’à S6 API34; Gate5 reste FAIL/P1 ACK jusqu’au runtime puis à la contre-revue finale S7. La paire S6 `ce10/893da1` reste figée; la paire S5 courante est sous `/tmp/atlas-v622-s5-ack-positive-final-20261021/`.**

Référence partagée : branche `feat/atlas-v6-2-2`, base `bf647cc18a142dd0f5cd5e68b21c36d511014388`. Contrats gelés : [Goal–Focus](CONTRAT_GOAL_FOCUS_V622.md), [notifications proactives](CONTRAT_NOTIFICATION_V622.md). API métier S3 : [développement Goal–Focus](DEVELOPPEMENT_GOAL_FOCUS_V622.md).

## État des gates et portée

- Gate 2 est accepté selon les preuves S1 : 30 tests JVM de sécurité, Agent 6/6 et Worker 2/2 instrumentés; résultats examinés. La correction source S1 a rétabli l’assertion spatiale historique de 25 km et durci la classification du blocage système. Sa recompilation est passée dans cette tranche; le rejeu instrumentation final reste à S6. La dernière recette S1 rapportée est `TEST_SYSTEM_BLOCKED` (code 77) après absence de fix GPS coarse, sans PASS d’acquisition.
- S3 a livré ses API gelées et a été accepté après revue source/tests, 297 tests JVM, contrôle XML 297/0/0/0 et `assembleDebug` PASS. Les fichiers métier S3 n’ont pas été modifiés par S5.
- La correction S7 de S2 est intégrée et les preuves S2 rapportent 315 tests JVM PASS. S5 utilise désormais le constructeur primaire `capturePublicationEpoch`; les tests scheduler/coordinator et publisher S2 sont conservés. S2 reste propriétaire de `proactive/**`, coordinator, publisher et de ses tests.
- Cette reprise n’a démarré aucun AVD et n’a exécuté aucun `am instrument`. Les parcours Compose Goal–Focus, les trois courses contrôleur et les scénarios publisher S2 compilent dans l’APK AndroidTest; aucun résultat runtime n’est déclaré. Leur exécution appartient à S6 après stabilisation, avec les recettes API34 et API37 prévues.

## Raccordements et garanties

### Démarrages Focus et mutation Goal

Le parcours UI part de `AtlasApp` / `FocusScreen` vers `FocusSessionScreen`. Les formulaires locaux, la configuration libre, le redémarrage après une session et la proposition Coach passent le Goal choisi au contrôleur. Aucun Goal n’est déduit du titre.

`AtlasController.startFocusTransaction` est l’unique préparation et transaction racine des départs locaux. Il refuse un Focus déjà RUNNING/PAUSED, prépare Focus via `FocusEngine.prepareStart`, prépare le lien via `GoalPanelCoordinator.prepareFocusLink`, puis construit une candidate contenant Focus, historique, sous-document Goals et message. Il compare `repository.all()` à `expectedRecords` après la préparation et juste avant l’écriture. Un changement concurrent annule le départ sans écriture. Le commit strict `persistOrThrow` est unique.

Après le commit unique, le contrôleur publie Focus, le Goal et son miroir `goalsSubdocument` en mémoire, sans callback ni second commit. Il demande ensuite l’alarme autorisée, émet l’événement `FOCUS_STARTED`, puis publie le message de succès, touche l’UI et navigue vers Focus. Les effets OS/sync sont protégés contre exception. Un échec du commit ne publie ni session ni lien et ne planifie pas d’alarme ou d’événement.

Le miroir Goals suit la candidate committée pour les prochains appels `stateMap()`. Les régressions locales et agent démarrent un lien, enchaînent pause/reprise/fin et une sauvegarde indépendante, relisent le document racine, puis vérifient le lien et `25/120` après recréation. La variante instrumentée agent vérifie également la clé d’idempotence après ces écritures.

Les sept mutations publiques Goal passent toutes dans `goalAction` sous le même `goalFocusTransactionLock` : création, mise à jour, changement d’état, complétion selon progression, confirmation manuelle et liens Focus/habitude. Le verrou couvre l’opération et sa persistance. Les tests injectent une écriture racine bloquée et vérifient que chacune attend le commit Focus.

Le démarrage `DirectiveChoice.START`, le preset, le plan, la configuration, le démarrage avec titre et la démo convergent vers la transaction locale. Les doubles demandes concurrentes ne produisent qu’une écriture de démarrage, une session, une alarme et un événement.

### Démarrage agent

`AgentActionPort.startFocus` prend le verrou commun et conserve `persistAgentAction` comme propriétaire de son commit. La candidate agent contient Focus, l’historique, Goals, la carte agent et `appliedLocalAgentEffectKeys` avec la clé d’idempotence existante. Le contrôle `expectedRecords` précède l’écriture. Après commit, les publications Focus/Goal/miroir précèdent l’alarme et l’outbox; la carte agent, le message de succès et le `touch()` suivent ces effets. L’alarme et l’outbox observent le root déjà commité.

Un Goal choisi dans l’écran est transmis localement pendant l’acceptation via le contexte temporaire existant; il n’est ajouté ni à `ProposalV2`, ni à `FocusSession`, ni au protocole. Après un `FOCUS_START` exécuté, `acceptAgentProposal` ne fait pas de second commit racine. L’AndroidTest agent contrôle une seule écriture, la clé déjà appliquée, l’absence de champ `goalId`, et le root persistant visible par l’alarme et l’outbox.

### Sélecteur et progression C3

Le sélecteur ne propose que les Goals actifs `FOCUS_MINUTES`. Sans candidat, aucun sélecteur n’est montré et la session reste libre. Avec un candidat, son nom est prérempli et le menu reste ouvrable, y compris l’option « Sans objectif ». Avec plusieurs candidats, la valeur initiale est « Sans objectif ». Un choix explicite libre reste libre si la liste des candidats change; aucun candidat n’est réattribué implicitement. Le choix reste local à l’écran. La projection en cours indique le lien et les minutes prévues sans crédit anticipé. Après terminaison, l’écran affiche les minutes validées de cette session via `GoalProgressCalculator.completedWorkSeconds()` et, si liée, la progression `currentValue / targetValue` issue de `GoalPanelCoordinator`; aucun calcul parallèle n’est fait dans Compose.

À la restauration, un Focus RUNNING est avancé avec l’horloge murale. Si sa phase/statut/progression change, l’état catch-up est persisté une fois pendant l’initialisation afin d’éviter de rejouer le même temps arrêté à chaque lancement.

## Couverture C4 et concurrence

| # | Scénario contractuel | Couverture |
|---:|---|---|
| 1 | Goal actif de 120 minutes | AndroidTest Compose `focusGoalSelectorOffersNoneOneAndManyAndCreditsOnlyFinishedLinkedWork` |
| 2 | Préparer un Focus lié de 25 minutes | Même parcours Compose : sélection explicite puis départ local et inspection du root |
| 3 | Avant le départ, progression 0/120 | Même parcours, projection/calculateur avant la session |
| 4 | Après le départ, progression toujours 0/120 | Même parcours, lien persisté et projection active sans crédit |
| 5 | En pause, progression toujours 0/120 | Même parcours, pause puis 8 heures contrôlées sans crédit |
| 6 | Après le vrai Focus terminé, 25/120 | Même parcours Compose, horloge avancée de 25 minutes puis bouton Terminer |
| 7 | Après recréation, 25/120 | Même parcours, nouveau contrôleur depuis le stockage local et vérification UI |
| 8 | Recalcul répété, toujours 25/120 | Même parcours et test JVM `controlledClockCreditsOnlyCompletedLinkedWorkAndRepeatedReadsDoNotDoubleCredit` |
| 9 | Session annulée : zéro crédit | JVM `unlinkedSessionAndCancelledLinkedSessionNeverChangeFocusGoalProgress` |
| 10 | Session libre : aucun changement Goal | Même test JVM, session terminée sans lien |
| 11 | Goal inactif refusé | JVM `inactiveAndManualGoalsAreRefusedWithoutStartingOrWritingFocus` |
| 12 | Goal `MANUAL` refusé | Même test JVM |
| 13 | Double départ concurrent : une session | JVM `concurrentDoubleTapStartRequestsCommitOnlyOneFocusSession`; asserts un commit, une session, une alarme et un événement |
| 14 | Échec initial : aucune session/lien/alarme/event | JVM `failedRootCommitPublishesNeitherFocusNorGoalLink` et AndroidTest `failedInitialCommitHasNoSessionGoalAlarmStartedEventOrRecreatedState`; root inchangé, alarme et outbox vides |
| 15 | Reprise après temps arrêté | AndroidTest `controllerRecreationAfterElapsedFocusPersistsOneCompletedCycleAndProgress`, root réel relu puis contrôleur recréé avec horloge avancée; le kill du processus OS lui-même reste à rejouer en S6 |
| 16 | Historique V6.2.1 compatible, aucun lien inventé | AndroidTest `legacyV621HistoryIsPreservedWithoutInventedGoalLinks`, puis liaison explicite contrôlée |

Couverture additionnelle : `staleGoalSnapshotIsRejectedBeforeAnyRootWrite` injecte une divergence après préparation et vérifie zéro écriture; `goalMutationWaitsUntilFocusTransactionCommits` et `allSevenGoalMutationEntryPointsWaitForTheFocusTransactionLock` couvrent la sérialisation; `everyLegacyLocalStartEntryPointUsesOneRootTransaction` couvre les anciens points d’entrée. `localGoalLinkSurvivesPauseResumeFinishAndUnrelatedRootWrites` et `agentGoalLinkSurvivesPauseResumeFinishAndUnrelatedRootWrites` vérifient le miroir disque après plusieurs commits; leurs variantes AndroidTest recréent un véritable contrôleur depuis le stockage. `focusAndGoalLinkUseOneRootCommit` observe l’ordre alarme → outbox → message/navigation; la transaction agent observe que sa carte n’est publiée qu’après ces effets. `singleGoalCanChooseNoGoalAndChoiceStaysFreeWhenEligibilityChanges` couvre le choix explicite libre avec un candidat, puis l’ajout d’un autre candidat.

Les 16 scénarios C4 sont mappés aux tests ci-dessus, mais les AndroidTest restent **compilés seulement** : aucun résultat de runtime Compose/instrumentation n’est revendiqué.

## Autorisation et publication proactive B2

Le contrôleur construit `ProactiveCoachCoordinator` avec `capturePublicationEpoch`. Le callback capture les générations proactive et Agent ensemble sous `proactiveAuthorizationEffectLock`, avant l’éligibilité, les consentements, l’horloge/fuseau et toute préparation. Le scheduler lie ensuite cette paire aux triggers exacts avant le commit de réservation. Le `ProactivePublicationAuthorization` qui en résulte reste en mémoire et n’est ni persisté ni envoyé au backend. Le gate compare la paire capturée, `isAgentRequestAllowed()` (mandat actif, dates actuelles des catégories et capability Agent), `isProactiveCoachEnabled()` et le grant courant à la date de chaque trigger.

`requestProactivePlan` passe ce même jeton à `AgentApiClient.plan(... allowSend = ...)`, le revérifie après le retour HTTP et le garde durant l’application locale. Les revalidations sont placées avant la fusion Inbox, l’enregistrement du contact, chaque dispatch préautorisé, la persistance et chaque ACK; l’ACK d’une exécution est aussi contrôlé entre les transitions `accepted` et `executed`. La réponse ne peut pas recapturer les générations courantes. Le callback d’affichage vérifie que les sources sont exactement celles du jeton, puis remet ce même gate au publisher, qui le vérifie sous son verrou immédiatement avant `NotificationManager.notify`.

Le finding S7-P1-01 indépendant portait sur la dernière frontière ACK : le contrôle du contrôleur précédait l’appel client, mais l’ACK ne transportait pas l’époque jusqu’à la sérialisation HTTP. `sendPendingAgentAcks` capture maintenant une seule fermeture `agentApiClient === client && isProactivePublicationAuthorized(authorization)` et passe le même objet à chaque ACK `accepted`, `executed`, `rejected`, `expired` ou `cancelled`. `AgentApiClient.acknowledge` accepte ce callback optionnel et l’évalue dans son `bodyFactory`, avant de créer le JSON et avant l’ouverture de `outputStream`; le gate général `requestAllowed` reste actif. `acknowledgeExecution` transmet aussi la fermeture aux deux transitions et conserve le traitement idempotent du 409. Sans callback explicite, les appels ACK non proactifs gardent leur comportement existant; `@JvmOverloads` conserve les signatures JVM précédentes.

Toutes les mutations des deux générations passent sous le même verrou d’autorité. `blockAgentForRevocation` incrémente la paire et écrit la barrière sous ce verrou; pour une fermeture de catégorie, `agentAuthorizationCommitInProgress` bloque aussi une nouvelle validation avant que les dates de consentement et le mandat soient remplacés. La réoptin fait progresser la génération Agent avec la mutation du mandat; elle ne restaure jamais le jeton antérieur. Le verrou est libéré avant l’annulation WorkManager et le retrait ciblé des notifications, ce qui évite d’inverser l’ordre avec le verrou du publisher. Les fermetures couvrent agent/capabilities, pause d’urgence, catégories Goals/Focus/localisation, révocation UsageStats et accès localisation/mobilité. La purge différée conserve les tombstones, quotas et réservations au plus une fois.

Les deux AndroidTest contrôleur qui bloquent une réponse synthétique valide contenant `home.set_directive` restent présents : l’un bloque le transport; l’autre bloque après son retour et avant la fusion. Chacun retire puis réactive Focus au même instant simulé, remet la même instance client en place, puis exige une inbox vide, aucun effet journalisé ni directive, aucun ACK et aucune notification. Ils utilisent le vrai coordinator et scheduler; seul le transport est contrôlé.

Le nouveau test `categoryWithdrawalAndSameClockReoptInAtDurableReservationCommitNeverEntersTransportOrReplays` bloque l’écriture de la réservation au niveau du `StateBackend` qui délègue au vrai `AndroidStateBackend`/SharedPreferences. Pendant que le thread principal est arrêté dans ce commit, un thread séparé exécute la révocation puis la réoptin publiques à la même heure; le test exige que ce chemin finisse sans deadlock avant de libérer l’écriture. Après commit, aucune callback transport, Inbox, dispatch, ACK ni notification ne part. Le root durable conserve la réservation/quota, son tombstone et le statut `REVOKED_BEFORE_REQUEST`; un nouveau contrôleur recréé depuis ce stockage ne rejoue pas le trigger. Les deux courses de réponse tardive restent conservées.

Les tests JVM `proactiveAckAuthorizationRejectsBeforeHttpRequestOrBodySerialization` et `executedAckRechecksAuthorizationBetweenAcceptedAndExecuted` couvrent le refus avant tout flux HTTP et l’invalidation entre les corps `accepted` puis `executed`; le test 409 préexistant reste inchangé. L’AndroidTest `sameClockReoptInAfterProactiveDispatchPreventsRealAckAtSerializationGate` bloque d’abord au dernier hook contrôleur avant l’appel client, retire puis réactive `focus` à la même horloge, puis libère. Le `requestAllowed` général repasse alors après la réoptin et autorise l’appel; le test bloque ensuite dans le vrai `bodyFactory`, avant l’évaluation de l’époque capturée. L’ACK réel utilise `AgentApiClient` vers un `ServerSocket` local; un GET de prévol prouve la connectivité, tandis que le transport de plan reste au seam contrôlé. Le cas exige un seul passage au `bodyFactory`, aucun ACK reçu après le GET de prévol, `ackPending == true`, et la même histoire d’effet local avant/après. L’effet préautorisé déjà dispatché avant révocation reste appliqué; le test ne prétend pas l’annuler.

**Correction de policy loopback :** l’AndroidTest cible `http://127.0.0.1:<port>`. La ressource [network_security_config.xml](../../android/app/src/debug/res/xml/network_security_config.xml) autorise maintenant cet unique hôte supplémentaire sous le même `domain-config` debug; `10.0.2.2` est conservé et le `base-config` reste refusé. Le manifeste main garde `usesCleartextTraffic="false"` ([AndroidManifest.xml](../../android/app/src/main/AndroidManifest.xml)). Après fusion, la ressource empaquetée debug garde la base false et contient seulement `10.0.2.2` et `127.0.0.1`; le manifest debug fusionné référence cette ressource avec `usesCleartextTraffic=false`. Le manifest release fusionné garde `usesCleartextTraffic=false` et ne référence pas la policy debug. `processReleaseResources` et `processReleaseMainManifest` passent. Cette preuve statique résout le mismatch de configuration; le GET/POST Android reste **NOT_RUN** jusqu’à S6 API34.

Les vérifications runtime de cette correction restent **NOT_RUN**; aucun ADB ni AVD n’a été utilisé. La paire `ce10/893da1` reste une preuve S6 antérieure distincte. Gate5 demeure FAIL/P1 ACK jusqu’au rejeu API34 par S6 et à la contre-revue finale S7.

Les tests JVM scheduler/coordinator S2 sont inclus dans la suite complète. Le test Android publisher S2 conserve le gate final concurrent, le retrait ciblé et les assertions bornées sur `activeNotifications`, mais son runtime reste **NOT_RUN** en attente de S6. Le verrou du publisher sérialise show/retrait dans le processus; Android ne fournit pas de transaction entre génération, stockage et Binder. Si `notify` est déjà entré lorsque la révocation survient, la fermeture attend le verrou et demande ensuite le retrait ciblé; une apparition transitoire ou un crash entre Binder et retrait reste une limite documentée, et un texte déjà aperçu ne peut pas être effacé.

## Rejeu S5 avec couverture ACK positive — candidat courant pour S6

La source de production est identique à la paire debug loopback précédente; seul le fichier AndroidTest a été étendu. La nouvelle méthode utilise le vrai `AgentApiClient` vers un `ServerSocket` local, sans appel Gateway : la réponse de plan reste au seam synthétique. Le test garde le consentement et l’époque valides, lit le POST `accepted`, répond HTTP 200, lit ensuite le POST `executed`, puis vérifie un dispatch et `ackPending=false`. La méthode de course négative reste inchangée.

Commande exécutée depuis `android/` :

```text
./gradlew clean --rerun-tasks :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL en 2 min 18 s — 82 tâches exécutées
XML JVM : 57 fichiers, 317 tests, 0 failure, 0 error, 0 skipped
lintDebug : PASS (53 résultats SARIF de niveau warning)
assembleDebug : PASS; compileDebugAndroidTestKotlin : PASS; assembleDebugAndroidTest : PASS
```

La paire distincte est figée dans `/tmp/atlas-v622-s5-ack-positive-final-20261021/` : app SHA-256 `7c6c41a4b90eb8fa88b3ffcc6003468092c3681a8ef6cd95024e92b73ec7c1ea`, AndroidTest SHA-256 `542280c3c6d020cfe0327056889affeec2dabb9cbaba65a0a03e526ae512deeb`. Le manifeste reproductible [sources-tests-scripts.sha256](</tmp/atlas-v622-s5-ack-positive-final-20261021/sources-tests-scripts.sha256>) couvre 234 fichiers de sources, tests, scripts et configuration Gradle; son SHA-256 est `87d05e9c17da0f4c64ceaaf452548a62e3ce49734b57d8052c281324d5e62e98`. Les métadonnées, hashes APK, résumé du build et inspection de variante sont dans le répertoire. Le manifeste a été revérifié sur les 234 entrées. L’APK app reste byte-identique à la paire loopback précédente, car aucune source de production n’a changé; l’APK AndroidTest inclut maintenant les deux méthodes ACK.

L’inspection statique après fusion est archivée dans [variant-network-config-validation.txt](</tmp/atlas-v622-s5-loopback-debug-final-20261021/variant-network-config-validation.txt>) : la ressource debug embarquée garde `cleartextTrafficPermitted=false` par défaut et autorise seulement `10.0.2.2` et `127.0.0.1`; le manifeste debug garde `usesCleartextTraffic=false` et référence cette ressource. Le manifeste release garde `usesCleartextTraffic=false` sans référence à cette policy debug. L’avertissement non bloquant de packaging indique que `libandroidx.graphics.path.so` n’a pas pu être stripée et a été empaquetée telle quelle.

Les deux méthodes ACK sur Android restent **NOT_RUN** : S5 n’a lancé ni ADB, ni AVD, ni `am instrument`. S6 doit installer cette paire exacte sur API34 et lancer uniquement `sameClockReoptInAfterProactiveDispatchPreventsRealAckAtSerializationGate` et `proactiveDispatchSendsAcceptedThenExecutedThroughRealLoopbackClient` pour cette preuve. La première doit atteindre son GET de prévol puis ne recevoir aucun POST ACK après retrait/réoptin; la seconde doit recevoir dans l’ordre deux POST avec états `accepted`, `executed`, chacun répondu HTTP 200, et constater le dispatch unique avec `ackPending=false`. Ne pas réutiliser une paire antérieure. Le daemon Gradle a été arrêté et libéré. Gate5 reste FAIL/P1 ACK jusqu’au runtime S6 et à la contre-revue finale S7.

## Validation antérieure, avant le correctif ACK courant

Validation précédente exécutée depuis `android/` après la correction S7 antérieure et la modification du message d’échec Focus, mais avant le finding ACK traité ici :

```text
./gradlew --rerun-tasks :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
BUILD SUCCESSFUL en 1 min 31 s — 81 tâches exécutées; XML agrégé : 315 tests / 0 failure / 0 error / 0 skipped; lintDebug et les deux assemblages PASS
Avertissement non bloquant : libandroidx.graphics.path.so non stripable, empaquetée telle quelle.

git diff --check
PASS
```

La suite complète a réellement été rejouée (`--rerun-tasks`); AndroidTest et les nouveaux hooks SharedPreferences ont compilé. Aucun script du harnais S6 n’a été modifié ni exécuté par cette reprise S5.

Les courses contrôleur, Compose, le publisher Android et les tests S1 instrumentés compilent mais sont **NOT_RUN**; aucun appareil, AVD, recette GPS ou harnais complet n’a été lancé par S5. La preuve compacte et les limites sont dans [la preuve S5 C + B2 + S7](preuves-v622/s5-b2-notifications-validation.txt). Le candidat antérieur et son manifeste étaient sous `/tmp/atlas-v622-s5-clean-candidate-20261021/`; ils ne contiennent pas le correctif ACK courant.

### Reconstitution du candidat après le diagnostic S6 — état au handoff S5

La paire précédente `ce10…` / `ea53…` a reproduit un échec du cas d'échec de commit, mais son DEX AndroidTest contenait l'ancien libellé « Impossible d'enregistrer » et pas le message exact attendu par la source actuelle. La source courante attend « Focus non démarré. Aucun lien à l'objectif n'a été enregistré. » et conserve les contrôles de root durable inchangé, session/lien absents, aucune alarme ni événement `FOCUS_STARTED`, et état toujours absent après recréation. Le décalage source → DEX explique le résultat sans attribuer de défaut à la production.

Le rebuild propre antérieur `./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` passe : 315 tests JVM uniques, 0 échec/erreur/ignoré, lint et les deux assemblages PASS. Il précède le correctif ACK décrit ci-dessus; ses APK `ce10…` / `8edb…` ne représentent plus les sources courantes. Les détails et hashes de cette étape restent dans la [preuve S5 reconstituée](preuves-v622/s5-b2-notifications-validation.txt).

À cet instant historique du handoff S5, le résultat Android de cette paire CLEAN était **NOT_RUN**. Les résultats S6 ci-dessous concernent cette paire historique dans un lancement distinct; ils ne qualifient ni le candidat ACK intermédiaire, ni le candidat loopback final préparé plus tard dans cette note.

## Harnais instrumenté

Le harnais appartient à S6. Cette reprise S5 n’a pas modifié ni lancé `scripts/v62-complete-instrumentation.sh`; ses changements de recette présents dans l’arbre partagé sont inclus dans le digest final sans être revendiqués par S5. Les protections personnelles et l’exécution runtime restent à valider par S6.

## Suite d'intégration — plan au handoff S5

1. C, B2 et le correctif ACK sont intégrés; la validation S5 ci-dessus passe. S6 peut installer la paire nouvelle, sans effacer les preuves de `ce10/893da1`, et exécuter le rejeu API34 ciblé.
2. S6 exécute sur les AVD whitelistés l’instrumentation Compose, les trois courses contrôleur, le publisher S2 avec `activeNotifications`, les corrections S1 et les recettes API34/API37. Le scénario de reprise doit tuer réellement le processus si l’environnement le permet; distinguer tout blocage système du PASS applicatif.

Pour la tranche S5 décrite ci-dessus, aucun commit, push, merge, sous-agent ou appareil n’a été utilisé. Les changements partagés restent non commités.

### Premier rejeu S6 du candidat S5 CLEAN — historique avant correction

Le candidat courant a été reconstruit CLEAN par S5 sous `/tmp/atlas-v622-s5-clean-candidate-20261021/` et vérifié par S6 : app SHA-256 `ce10c968988fb3383e06ee94b3c1eb3723f37efcf6035597601643916a9f298a`, AndroidTest SHA-256 `8edb71a818ac01a4c3195cab48def493be117587159f939962f4f15ed053014d`, digest code/tests/harnais `93ec83319a2d1f8c9d1a95205a82432d6f7c253700081c2b1387a3fc4d1916d9`. Les hashes des packages installés après le run E2E correspondent.

Résultats S6 sur cette paire : ciblés 20/20 PASS; suite principale 75 cas, 74 PASS et 1 FAIL reproductible dans l'assertion de quarantaine de `AtlasLaunchTest` ligne 338; huit compléments sûrs PASS et deux compléments UsageStats `NOT_RUN`. Le script complet n'a pas été lancé parce qu'il exécute `pm clear` et les deux tests UsageStats effacent l'état. Localisation visible/logique PASS, acquisition du fournisseur `TEST_SYSTEM_BLOCKED`; deux E2E S6 réels consécutifs puis scénario hors ligne PASS. D3 courant `NOT_RUN`, AppOp UsageStats `ignore`. Qualification partielle, pas de verdict runtime vert global. Voir [RECETTE_V622.md](RECETTE_V622.md) et [la preuve détaillée](preuves-v622/s6-rejeu-clean-20261021.txt).

### Correction ciblée S6 — candidat AndroidTest `0b5c` — 21 octobre 2026

Le RED isolé sur l'ancienne paire `ce10…` / `8edb…` ne reproduit pas une différence de texte JSON : l'assertion d'égalité brute passe, puis l'assertion d'aperçu échoue car la barrière durable d'agent masque les catégories. Le test AndroidTest sauvegarde maintenant son état initial, neutralise la barrière pour cette seule fixture et la restaure dans `@After`. La vérification de quarantaine compare les valeurs JSON attendues et confirme que les deux champs privés synthétiques ont été supprimés. Production inchangée.

Nouvelle paire pour cette revalidation : app `ce10c968988fb3383e06ee94b3c1eb3723f37efcf6035597601643916a9f298a`, AndroidTest `0b5c3d087cbf856207cd85d7a4a5573d532a65d89394a54a6c7d16abcc0b3872`; digest sources/tests/scripts `fbb0f7ecf0b05052a75e6b6006bccd94b9e0fe12b92177d9b7d7909737718d52`. Seul `:app:assembleDebugAndroidTest` a été lancé, et les hashes installés concordent sans effacer les données. Cas cible 1/1, suite principale 75/75 et compléments sûrs 8/8 PASS.

La recette localisation sur cette paire passe en logique, activité visible et préparation consentement; l'acquisition mobilité reste `TEST_SYSTEM_BLOCKED` faute de nouveau fix coarse. Les deux cas UsageStats restent `NOT_RUN`, le script complet n'est pas lancé en raison des `pm clear`, et D3 reste `NOT_RUN`. Les deux E2E OpenClaw et l'hors-ligne restent attachés à la paire antérieure `8edb`; aucune revendication de revalidation E2E n'est faite pour `0b5c`. Voir [preuve de correction](preuves-v622/s6-correction-quarantaine-20261021.txt). S7 doit reprendre la contre-revue après cette livraison.
