# ATLAS V7-10 — Rapport final

**Date :** 2026-11-06
**Branche d’implémentation :** feat/atlas-v7-10-security-reliability
**Branche documentaire :** v7-10/d2-final-report
**Base de code :** origin/main, a5aca4b54088d43f4829adf31049417dd8cf32a6
**SHA du code final :** 3123dba4f89579d414406d8c790609d1e4a5adf6
**HEAD documentaire avant ce rapport :** a8de7542573190f471b5d0af067425bc186aeaf5

## Résumé exécutif et verdict

| Axe | Verdict |
|---|---|
| Développement local | **GO** |
| Qualification locale | **GO_WITH_LIMITATIONS** |
| Opérations physiques et services live | **NON EXÉCUTÉES** |
| Revue indépendante A4-delta sur le code final | **GO** |
| Revue finale A6 | **EN ATTENTE** |

V7-10 implémente localement l’authentification V4 par principal/appareil, l’expiration serveur des propositions, la synchronisation Relay demandée par l’utilisateur et les corrections Coach prévues. Les suites backend, connecteur, proxy et Android sont étayées par leurs preuves. La qualification AVD Android 17/API 37 a validé une mise à jour in-place, la préservation de données synthétiques, un démarrage à froid et deux classes instrumentées.

Ce résultat n’autorise ni installation produit, ni migration/révocation live, ni pilote E. Aucun Pixel physique, aucun E2E Relay/Core/Gateway live et aucune CI distante n’ont été exécutés. A4-delta a rendu GO sur le SHA de code final; A6 reste à compléter et ne doit pas être présenté comme acquis.

## Périmètre et restrictions respectées

- Le code examiné est la branche feat/atlas-v7-10-security-reliability, basée sur origin/main à a5aca4b54088d43f4829adf31049417dd8cf32a6. Son SHA final est 3123dba4f89579d414406d8c790609d1e4a5adf6.
- Ce rapport et la transcription des revues sont rédigés sur v7-10/d2-final-report, à partir du HEAD documentaire a8de7542573190f471b5d0af067425bc186aeaf5.
- Aucun push, PR ou merge distant n’a été effectué. Les commits de ce travail sont locaux.
- Aucun Pixel physique ni ADB dirigé vers un Pixel n’a été utilisé. La qualification instrumentée décrite plus bas était sur AVD API 37.
- Aucun service ou volume live Core, Relay ou Gateway n’a été modifié. Aucun E2E réel, déploiement, migration, backfill, réconciliation ou révocation live n’a été exécuté.
- Aucun secret réel ni donnée personnelle n’a été utilisé dans les fixtures ou ajoutés aux rapports.
- Le pilote E n’a pas été autorisé ni lancé.

## Implémentation et traçabilité

| Tranche | Fichiers principaux | Commits d’intégration |
|---|---|---|
| Authentification par appareil, registre des principaux et credentials, capabilities, binding Relay/Core signé, propriété des propositions et contrôles de secret | backend/atlas_backend/{store.py,v4_auth.py,v4_assertion.py,v4_router.py,v4_projection_store.py,v4_tool_service.py}; backend/scripts/atlas_v4_principals.py; integrations/relay/pc/src/{backend.ts,connector.ts}; integrations/relay/pc/scripts/{check-config.mjs,check-secret.mjs,lib/config.mjs}; tests backend et connecteur | 4c50a13, 8dd31a7, acccd0c, 6d8688d, 440b0a5, 0890fd2, 77dc105, 739ad87, d2f9826, 3123dba |
| Expiration A0, audit serveur append-only, sémantique ACK, concurrence et réconciliation offline | backend/atlas_backend/{main.py,store.py,v4_projection_store.py,v4_router.py}; backend/scripts/atlas_v4_expiration_reconcile.py; contracts/v4/error.schema.json; intégration erreur Relay; tests lifecycle | a566503, 70beee9, 7b1004b, c0eb437, 549f658 |
| Synchronisation Relay immédiate et corrections Coach | android/app/src/main/java/dev/atlas/coach/{app,sync}; fichiers principaux : CoreV4SyncNowAction.kt, CoreV4SyncWorker.kt, RelayCoreV4HttpTransport.kt, RelaySettingsStore.kt, RelaySettingsController.kt, AgentCoachScreen.kt, AtlasController.kt, PlanScreen.kt, SyncSettingsScreen.kt, AgentChatClient.kt, AgentConversationArchive.kt; tests JVM et instrumentation associés | 2ff9fa1, a43f115, 298fafe, 5cdbc80, 614b639, ffcd43e |
| Documentation de conception, migration, preuves et handoff | docs/operations/V7-10_*, docs/operations/evidence/V7-10_* | ae76bda, 499b4ef, 710a49a, cef4e13, 00a4a8d, fa82af3, e672ee2, d96810b, d4d639a, d986477, 6847acd, a8de754 |

Les commits 77dc105, 739ad87 et d2f9826 sont des étapes corrigées de détection de racine et sont détaillés dans la [transcription A4/A4-delta](evidence/V7-10_INDEPENDENT_REVIEWS.md). Le commit de code final 3123dba ferme les erreurs d’inspection et la course de lecture du chemin du secret. Les commits documentaires d4d639a, d986477, 6847acd et a8de754 alignent respectivement la qualification A5, l’architecture/handoff et les preuves sur les SHA finaux. Aucun code n’a été modifié après 3123dba dans la consolidation documentaire.

Les changements principaux couvrent :

- Des identités Core stables par principal, des audiences séparées, une allowlist/garde de capabilities, une résolution fail-closed et une procédure offline de provisionnement.
- Le binding Relay authentifié vers principal Core, une assertion HMAC liée à la requête et des credentials choisis depuis la session Relay authentifiée.
- L’ownership serveur des propositions, avec protection des anciennes lignes NULL par un principal LEGACY explicitement désigné.
- L’expiration d’une proposition échue avec audit serveur atomique distinct des ACK Android. UNCERTAIN n’est pas auto-expiré.
- Une action de synchronisation Core V4 qui passe par le transport Relay en mode RELAY, l’archivage des conversations, la persistance opt-in V4, les réponses Coach plus lisibles, les conflits STALE_DATA et le rendu du diff A0.

## Vérifications réellement exécutées

Les résultats distinguent la QA A5, la vérification de l’orchestrateur et la revue indépendante. La preuve Android finale réexécutée par l’orchestrateur ci-dessous remplace l’absence de rerun complet signalée initialement dans le rapport A5; elle ne change pas la provenance de la preuve AVD.

| Domaine et SHA | Résultat | Qui / preuve |
|---|---|---|
| Backend V4, code final | **451 passed, 1 skipped, 3 warnings** avec adaptateur mock. | QA A1-FIX/A5; voir [A1-FIX](evidence/V7-10_A1_FIX_EVIDENCE.md) et [rapport A5](V7-10_TEST_REPORT.md). Tests sur bases synthétiques temporaires. |
| Connecteur Relay PC, 3123dba | **131/131**, 18 fichiers; TypeScript compilé. | Orchestrateur et revue indépendante A4-delta; [preuve A1-FIX](evidence/V7-10_A1_FIX_EVIDENCE.md) et [revue A4-delta](evidence/V7-10_INDEPENDENT_REVIEWS.md). |
| Proxy Relay | **18/18**, zéro échec. Le cas épinglé sur 4402 a été exclu de la commande. | QA A5; [log proxy](evidence/V7-10_A5_R3_PROXY_TESTS.log). |
| Android JVM, lint et build, HEAD documentaire a8de754 | **934 tests, 0 failure, 0 error, 0 skipped**; lint **0 erreur, 89 avertissements**; APK debug et androidTest assemblés. Android est identique entre le SHA de code 3123dba et a8de754. | Rerun final de l’orchestrateur, commande et sortie dans /tmp/v7-10-final-android-suite.log. Agrégation des XML : 124 fichiers. A3 apporte aussi la preuve de la tranche Android dans [Android reliability](V7-10_ANDROID_RELIABILITY.md). |
| AVD Android 17/API 37 | APK debug final installé en mise à jour in-place; firstInstallTime inchangé; marqueur et préférences synthétiques préservés; démarrage COLD sans FATAL; AgentCoach **9/9**, scheduler V4 **1/1**. | QA A5, sur APK compilé au SHA 739ad878. Les sources Android de production/JVM sont identiques au SHA 3123dba. Preuves : logs [mise à jour](evidence/V7-10_A5_R3_AVD_UPDATE_INSTALL.log), [pré-instrumentation](evidence/V7-10_A5_R3_AVD_PREINSTRUMENTATION_PROOF.log), [Agent Coach](evidence/V7-10_A5_R3_AGENT_COACH_TESTS.log) et [scheduler](evidence/V7-10_A5_R3_CORE_V4_SCHEDULER_TESTS.log). |
| A4 initiale et A4-delta | A4 initiale **NO_GO**; A4-delta intermédiaires **NO_GO**; A4-delta final **GO** sur 3123dba. | Revues indépendantes, IDs d’agents, SHA et limites dans [V7-10_INDEPENDENT_REVIEWS.md](evidence/V7-10_INDEPENDENT_REVIEWS.md). |

### Preuve AVD : portée exacte

L’AVD jetable utilisait Android 17/API 37. L’APK debug de base et l’APK candidat avaient le paquet attendu et la même signature debug, permettant l’installation in-place. Après install -r, firstInstallTime est resté constant, et les hashes du marqueur synthétique et du fichier SharedPreferences étaient identiques. Le lancement COLD avant instrumentation s’est terminé sans FATAL EXCEPTION.

Les classes instrumentées AgentCoachConversationInstrumentationTest et CoreV4SyncSchedulerInstrumentationTest ont passé séparément 9/9 et 1/1. Après instrumentation, le harness AGP avait retiré le paquet; la vérification post-harness des préférences et du cold start est donc NOT_RUN. Aucun service live n’était requis par ces tests.

## Revue indépendante

A4 initiale sur fa82af3 a prononcé NO_GO avec deux findings P1 et des findings P2. Après les remédiations, A4-delta a relevé des défauts successifs de frontière sur 77dc105, 739ad87 et d2f9826, puis a prononcé **GO final sur 3123dba** après reproduction des contrôles EACCES, symlink/TOCTOU et du connecteur 131/131.

La revue A4-delta n’a pas exécuté Android JVM/lint indépendamment ni un AVD et n’a touché à aucun service live. A6 est encore en attente. Voir la [transcription détaillée](evidence/V7-10_INDEPENDENT_REVIEWS.md).

## Gates — 17 critères

| Gate | Statut | Portée de preuve |
|---|---|---|
| GO_CORE_DEVICE_PRINCIPALS | **GO** | Registre, capabilities, ownership et tests backend synthétiques. |
| GO_RELAY_DEVICE_BINDING | **GO — synthétique** | Mapping, binding et assertion vérifiés en tests; aucun Relay live. |
| GO_BEARER_MIGRATION_DESIGN | **GO — conception locale** | Plan et contrôles revus; aucune migration live. |
| GO_BEARER_REVOKE_FAIL_CLOSED | **GO — tests synthétiques** | Refus fail-closed couvert par tests; aucune révocation réelle. |
| GO_V4_PROPOSAL_EXPIRATION | **GO** | Échéances, audit et expiration couverts sur SQLite temporaire. |
| GO_V4_ACK_CONCURRENCY | **GO** | Courses ACK/balayeur testées localement. |
| GO_RELAY_IMMEDIATE_SYNC | **GO local; E2E live NOT_RUN** | Scheduler local et AVD 1/1; aucun envoi vérifié vers un Relay réel. |
| GO_NEW_CONVERSATION | **GO** | Instrumentation Agent Coach 9/9 sur AVD API 37. |
| GO_V4_COLD_START | **GO** | Cold start COLD pré-instrumentation, sans FATAL. |
| GO_ANDROID_DATA_PRESERVATION_AVD | **GO** | Mise à jour in-place et données synthétiques préservées sur AVD API 37. |
| GO_BACKEND_TESTS | **GO** | 451 passés, 1 ignoré, 3 warnings. |
| GO_ANDROID_JVM_LINT | **GO** | Rerun final orchestrateur : 934/934, lint 0 erreur/89 avertissements, APK et androidTest assemblés. |
| GO_INDEPENDENT_SECURITY_REVIEW | **GO — A4-delta** | Revue finale du correctif au SHA 3123dba. A6 distincte reste en attente. |
| GO_SOURCE_AND_CI | **GO local; CI distante NOT_RUN** | Sources et suites locales GO; aucune CI distante revendiquée sans push autorisé. |
| GO_PRODUCT_INSTALL | **NO_GO / NOT_RUN** | Aucune installation sur Pixel produit. |
| GO_LIVE_SECURITY | **NO_GO / NOT_RUN** | Aucun service live ni test de sécurité live. |
| GO_DAILY_PILOT | **NO_GO / NOT_RUN** | Aucun pilote E ou pilote quotidien lancé. |

Le GO d’A4-delta est un verdict de revue indépendant sur le correctif du connecteur et ses scénarios; il ne vaut pas preuve Android physique, migration live ou A6.

## Risques, limites et incidents

- **Pas de Pixel physique.** L’AVD API 37 ne qualifie pas un Pixel Android 17. Aucun test E2E réel Relay/Core/Gateway n’a été exécuté.
- **Pas d’opération live.** Aucun déploiement, migration, backfill, dry-run/apply du réconciliateur, rotation ou révocation réelle. Les secrets de production n’ont pas été lus.
- **UNCERTAIN reste ouvert.** Cet état n’est pas auto-expiré; une réconciliation explicite est nécessaire.
- **APK debug.** La preuve d’installation concerne un APK debug, avec la signature debug commune à l’APK de base. Ce n’est pas un APK release ni un GO produit.
- **Réconciliation offline jamais appliquée à une base réelle.** L’outil exige une sauvegarde vérifiée; son application fonctionne par transactions de lot et n’est pas globalement atomique.
- **Incident A1 de base locale.** L’import du backend a migré la base SQLite ignorée du worktree A1 en version 4. La base du dépôt principal est restée en version 3 et le volume Docker Core live n’a pas été touché. L’incident est documenté dans [A1](evidence/V7-10_A1_IMPLEMENTATION_EVIDENCE.md); aucune ligne métier n’a été lue pour cette constatation.
- **Incident proxy 4402.** Deux exécutions A2 et une tentative de l’orchestrateur avec un filtre incorrect ont établi trois contacts en lecture seule avec le service local : GET /health, GET /ready, upgrade WebSocket puis fermeture. Aucune donnée ni configuration n’a été modifiée. Le cas est exclu des commandes canoniques depuis.
- **Rerun Gradle A5 bloqué, puis vérification finale réussie.** La tentative A5 sur le SHA final s’est arrêtée avant l’exécution Gradle, car le wrapper ne pouvait pas ouvrir le verrou dans un cache en lecture seule. La commande finale exécutée ensuite par l’orchestrateur sur a8de754 a réussi avec 934/934, lint 0/89 et les deux APK assemblés; elle est la preuve JVM/lint finale.
- **Harness AVD.** Le harness AGP a retiré l’application après les tests instrumentés; la relecture post-harness n’a pas été faite. La préservation avait été vérifiée avant instrumentation.
- **A6 en attente.** Ajouter son agent ID, SHA examiné, verdict, findings, tests/limites et décision de gate après réception réelle de son rapport. Ne pas marquer A6 GO par anticipation.

## Migration physique, sauvegarde et rollback

Le [handoff physique V7-10](V7-10_PHYSICAL_MIGRATION_HANDOFF.md) décrit les **13 étapes** et reste un protocole, pas une preuve d’exécution :

1. Autorisation physique ciblée, gel de configuration et voie d’arrêt.
2. Inventaire des versions, hashes, appareil, services et base.
3. Sauvegarde complète, intégrité et SHA vérifiés.
4. Vérification/restauration des sauvegardes sur copies jetables.
5. Provisionnement offline des principaux, credentials et grants sur copie.
6. Passage contrôlé en dual avec principal LEGACY et secret curseur.
7. Backfill des propositions NULL sur copie vérifiée.
8. Configuration Relay, map, binding et HMAC, puis redémarrage contrôlé.
9. Installation in-place et vérification des données, consentements et opt-in.
10. Tests A/A, B/B, A avec bearer B, révocation et reconnexion.
11. Passage en principal, refus LEGACY et création d’une nouvelle paire.
12. Révocation irréversible de l’ancien bearer, rotation et preuve fail-closed.
13. Réconciliation des gates et décision explicite sur le pilote E.

La sauvegarde et sa vérification précèdent toute mutation. Avant révocation, le rollback documenté peut revenir explicitement à legacy seulement si l’ancien credential reste actif; cela rouvre le risque P1. Après révocation monotone, ne pas réactiver l’ancien credential ni promouvoir une sauvegarde pré-révocation comme base active; arrêter le Core, préserver l’état, restaurer une copie vérifiée si nécessaire et émettre un credential neuf. Le pilote E reste bloqué tant que les autorisations physiques ciblées et les gates live correspondants ne sont pas satisfaits.

## Annexe — commandes et résultats

Les commandes ci-dessous sont reprises des logs ou preuves cités; elles ne signifient pas qu’elles ont été relancées pendant la rédaction de D2.

| Commande réellement exécutée | Résultat et provenance |
|---|---|
| cd backend && ATLAS_AGENT_ADAPTER=mock /tmp/v7-10-a5-backend-venv/bin/python -m pytest -q | 451 passed, 1 skipped, 3 warnings; QA A1-FIX/A5, adaptateur mock. Voir [preuve A1-FIX](evidence/V7-10_A1_FIX_EVIDENCE.md). |
| cd integrations/relay/pc && npm ci --offline && npm run test:unit | 131/131 tests, 18 fichiers; TypeScript compilé; SHA 3123dba. QA/revue A4-delta; voir [A1-FIX](evidence/V7-10_A1_FIX_EVIDENCE.md) et [revue indépendante](evidence/V7-10_INDEPENDENT_REVIEWS.md). |
| node --test --test-skip-pattern='pinned relay integration on 4402' integrations/relay/proxy/test/proxy.test.mjs | 18 pass, 0 fail; test 4402 exclu. [Log proxy](evidence/V7-10_A5_R3_PROXY_TESTS.log). |
| cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --max-workers=1 --console=plain | BUILD SUCCESSFUL en 4m30s; 934 tests, 0 failure/error/skipped; lint 0 erreur/89 avertissements; APK debug et androidTest assemblés. Exécuté par l’orchestrateur sur a8de754; log /tmp/v7-10-final-android-suite.log. |
| ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.app.AgentCoachConversationInstrumentationTest --max-workers=1 --console=plain | 9/9, 0 échec/erreur/ignoré, sur AVD API 37 par A5. Log [Agent Coach](evidence/V7-10_A5_R3_AGENT_COACH_TESTS.log). |
| ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.sync.v4.CoreV4SyncSchedulerInstrumentationTest --max-workers=1 --console=plain | 1/1, 0 échec/erreur/ignoré, sur AVD API 37 par A5. Log [scheduler](evidence/V7-10_A5_R3_CORE_V4_SCHEDULER_TESTS.log). |
| adb -s emulator-5556 install -r /tmp/v7-10-a5-final.apk | Success; firstInstallTime inchangé et données synthétiques vérifiées avant instrumentation. [Logs update et preuve](evidence/V7-10_A5_R3_AVD_UPDATE_INSTALL.log). Aucun Pixel ciblé. |
| ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1 --console=plain | Run antérieur A5 : 934 JVM, 0 échec, lint 0 erreur/89 avertissements, APK debug et androidTest assemblés. [Rapport A3](V7-10_ANDROID_RELIABILITY.md) et [rapport A5](V7-10_TEST_REPORT.md). |
| Tentative de rerun Gradle A5 au SHA final | NOT_RUN avant démarrage Gradle : verrou du wrapper en lecture seule. Le résultat final est fourni par la commande orchestrateur ci-dessus; l’échec historique reste conservé dans le rapport A5. |
| git diff --check; git diff --cached --check | PASS sur les deux documents avant commit local D2. |
| git show --check --oneline HEAD | PASS après création du commit local documentaire. |

## Références des preuves

- Architecture et état du code : [V7-10_ARCHITECTURE.md](V7-10_ARCHITECTURE.md), [migration sécurité](V7-10_SECURITY_MIGRATION.md), [cycle d’expiration](V7-10_EXPIRATION_LIFECYCLE.md), [fiabilité Android](V7-10_ANDROID_RELIABILITY.md).
- Conceptions/cartographies : [S1 auth](evidence/V7-10_S1_AUTH_DESIGN.md), [S2 expiration](evidence/V7-10_S2_EXPIRATION_DESIGN.md), [S3 Android](evidence/V7-10_S3_ANDROID_MAP.md).
- Implémentation et remédiations : [A1](evidence/V7-10_A1_IMPLEMENTATION_EVIDENCE.md), [A1-FIX](evidence/V7-10_A1_FIX_EVIDENCE.md), [A2](evidence/V7-10_A2_IMPLEMENTATION_EVIDENCE.md), [A5 test report](V7-10_TEST_REPORT.md), [revues indépendantes](evidence/V7-10_INDEPENDENT_REVIEWS.md).
- Procédure opérateur, non exécutée : [handoff physique](V7-10_PHYSICAL_MIGRATION_HANDOFF.md).
- Contexte du défaut d’expiration et de la clôture précédente : [rapport V7-09](V7-09_FINAL_REPORT.md).
