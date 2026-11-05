# V7-10-A3 — Fiabilité Android quotidienne

Date: 2026-11-06<br>
Branche: `v7-10/a3-android-reliability`<br>
Base demandée: `710a49a0094e49ae1e794d009e55536fe1aba7fe`<br>
Périmètre: Android seulement. Aucun fichier backend, connecteur ou contrat serveur modifié.

## Verdict

**PASS.** Les changements Android et la documentation sont committés localement. Les validations s’appliquent au même contenu source que les commits; depuis leur exécution, seules les deux notes documentaires ont changé. Le worktree est propre après vérification.

## Changements

- Action « Synchroniser maintenant » dans Coach, Plan et Sync Settings, alimentée par le worker Core V4. L’enqueue manuel utilise `ExistingWorkPolicy.KEEP`; l’anti-double-tap local est borné à 750 ms. Le suivi interne conserve `APPEND_OR_REPLACE`. Le bouton observe les états WorkManager, libère son verrou pour les états terminaux/inactifs, et rend les échecs de transport/authentification compréhensibles avec accès aux réglages.
- L’action réutilise le transport Relay configuré et le worker existant, qui applique les consentements et garde l’outbox/ACK. L’action ne modifie pas les consentements ni le plan. Le mode Relay n’introduit pas de requête HTTP directe.
- « Nouvelle conversation » archive le fil dans `agentConversationArchivesV1` versionné, persiste un UUID neuf et vide le fil actif; l’action refuse pendant `SENDING`. Les données V4 existantes `agentConversation` et `agentConversationId` sont toujours lues. Le snapshot d’archives est un champ immuable remplaçable, séparé de Compose; `stateMap()` le traite comme vide s’il est absent pendant une construction.
- Le V4 opt-in est enregistré dans la préférence `v4_chat_opt_in_v1`, défaut `false`, et n’est plus réinitialisé en quittant Relay. L’état demandé et `v4ChatNegotiated` sont séparés. Si V4 est demandé sans capability négociée, le chat est refusé sans envoi, activation de consentement ou repli V2.
- Les réponses de chat affichent uniquement le texte utilisateur. L’état des outils et les identifiants restent dans `diagnosticDetails`, disponibles via « Détails ». L’envoi et la nouvelle conversation sont désactivés pendant `SENDING`; le composer est ramené en vue avec `BringIntoViewRequester`, sans scroll imbriqué. Les propositions de plan affichent le changement avant/après, le delta et l’expiration à l’heure locale; UUID et versions restent dans le panneau « Détails ».
- Les erreurs `409` dont `error.code=STALE_DATA` deviennent `CONFLICT` avec un message français sûr et stable dans les chemins direct et Relay. Les arguments distants ne sont pas exposés; aucun retry ou repli V2 n’est déclenché.
- L’enquête sur les 14 échecs JVM a trouvé un NPE de délégué Compose non initialisé sur `agentConversationArchivesSnapshot`, déclenché par le harness `Unsafe` lors d’un persist racine de GoalRepository. Le champ de persistance n’est plus délégué à Compose; le test racine vérifie aussi la valeur initiale vide du champ versionné. Aucun test existant n’a été affaibli ou supprimé.

## Fichiers concernés

- `android/app/src/main/java/dev/atlas/coach/app/CoreV4SyncNowAction.kt`
- `android/app/src/main/java/dev/atlas/coach/app/AgentCoachScreen.kt`
- `android/app/src/main/java/dev/atlas/coach/app/AtlasController.kt`
- `android/app/src/main/java/dev/atlas/coach/app/PlanScreen.kt`
- `android/app/src/main/java/dev/atlas/coach/app/RelaySettingsController.kt`
- `android/app/src/main/java/dev/atlas/coach/app/SyncSettingsScreen.kt`
- `android/app/src/main/java/dev/atlas/coach/sync/agent/coach/AgentApiClient.kt`
- `android/app/src/main/java/dev/atlas/coach/sync/agent/coach/AgentChatClient.kt`
- `android/app/src/main/java/dev/atlas/coach/sync/agent/coach/AgentConversationArchive.kt`
- `android/app/src/main/java/dev/atlas/coach/sync/agent/coach/AgentConversationMessage.kt`
- `android/app/src/main/java/dev/atlas/coach/sync/relay/RelaySettingsStore.kt`
- `android/app/src/main/java/dev/atlas/coach/sync/v4/CoreV4SyncWorker.kt`
- `android/app/src/androidTest/java/dev/atlas/coach/app/AgentCoachConversationInstrumentationTest.kt`
- `android/app/src/androidTest/java/dev/atlas/coach/e2e/V7RemoteV4InstrumentedTest.kt`
- `android/app/src/androidTest/java/dev/atlas/coach/e2e/V7_06B_CoachLoopInstrumentedTest.kt`
- `android/app/src/androidTest/java/dev/atlas/coach/sync/v4/CoreV4SyncSchedulerInstrumentationTest.kt`
- `android/app/src/test/java/dev/atlas/coach/app/CoreV4SyncActionTest.kt`
- `android/app/src/test/java/dev/atlas/coach/app/GoalFocusStartTransactionTest.kt`
- `android/app/src/test/java/dev/atlas/coach/app/PlanUiLogicTest.kt`
- `android/app/src/test/java/dev/atlas/coach/e2e/E2eV63Test.kt`
- `android/app/src/test/java/dev/atlas/coach/e2e/V7_06DProposalOracleTest.kt`
- `android/app/src/test/java/dev/atlas/coach/sync/agent/coach/AgentApiClientTest.kt`
- `android/app/src/test/java/dev/atlas/coach/sync/agent/coach/AgentConversationArchiveTest.kt`
- `android/app/src/test/java/dev/atlas/coach/sync/agent/coach/RelayAgentChatClientTest.kt`
- `android/app/src/testShared/java/dev/atlas/coach/e2e/V7_06BChatTestSupport.java`
- `docs/DECISIONS_AUTOMATIQUES.md`
- `docs/operations/V7-10_ANDROID_RELIABILITY.md`

## Commits locaux

| SHA | Commit | État |
|---|---|---|
| `efda82d8bb79f0d21e60a27f7138efa1f112589d` | `feat(v7-10): add explicit relay v4 sync now action` | Worker, action partagée, Sync Settings et tests de scheduler/gate. |
| `317b6b31989798cc2b728865f29ccd388f1e3d9f` | `fix(v7-10): simplify coach replies and map stale data conflicts` | Parsing diagnostics et mapping direct/Relay `STALE_DATA`, avec tests et adaptation des supports V7-06B/V7-06D. Regroupement motivé par la frontière commune des réponses chat. |
| `2e2b06c9f861f16bc455d420afb60f93d5ddc5fe` | `feat(v7-10): add new conversation archive and persist v4 opt-in` | Archives, contrôleur, paramètres Relay/V4 et tests de persistance racine. |
| `91aaef8842883efe25069c6e33e7bf8b0be45c16` | `fix(v7-10): improve coach and plan ergonomics` | Coach/Plan, nouvelle conversation consultable, composer, détails techniques, diff/horaires et tests. |
| Voir le commit docs final dans `git log` | `docs(v7-10): document android reliability` | Ce rapport et `docs/DECISIONS_AUTOMATIQUES.md`; SHA indiqué dans la livraison pour éviter une référence circulaire. |

## Tests et builds

| Commande | Résultat |
|---|---|
| `cd android && ./gradlew :app:testDebugUnitTest --max-workers=1 --console=plain` | **PASS** — 932 tests, 0 échec, 0 erreur, 0 ignoré. Les tests ciblés `GoalFocusStartTransactionTest` et `V621ReviewFindingsTest` passent également. |
| `cd android && ./gradlew :app:lintDebug --max-workers=1 --console=plain` | **PASS** en 46 s; `app/build/reports/lint-results-debug.xml` présent, 89 avertissements, 0 erreur. Aucun avertissement ne cible une ligne ajoutée ou modifiée. Le premier processus a été interrompu après être resté sans sortie à `lintAnalyzeDebug`; le second a fini normalement. |
| `cd android && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1 --console=plain` | **PASS** — APK debug assemblé et androidTest compilés. |
| `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1 --console=plain` | **PASS** sur le dernier état source, après l’ajustement du snapshot d’archives. |
| `git diff --check` et `git show --check` sur chaque commit | **PASS**. |
| Exécution des tests instrumentés / AVD | **NOT_RUN**, conformément au périmètre; leur compilation est PASS. |
| Vérification Pixel physique | **NOT_RUN**; aucune preuve Pixel revendiquée. |
| `git status --short` | **PASS**, worktree propre après les cinq commits locaux. |

Lint a signalé 89 avertissements (tous de sévérité `Warning`), dont `ApplySharedPref` (13), `UseKtx` (43), `ObsoleteSdkInt` (14), `GradleDependency` (7), `SetTextI18n` (6), `NewerVersionAvailable` (2), `DiscouragedApi` (2), `OldTargetApi` (1) et `AndroidGradlePluginVersion` (1). Le build a aussi rappelé des fonctionnalités Gradle dépréciées pour Gradle 10; aucune erreur de compilation ou lint.

## Limites et risques

- Les nouveaux tests WorkManager et Coach instrumentés compilent mais n’ont pas été exécutés sur émulateur ou appareil. La déduplication UI est testée par un test instrumenté déterministe; la cause historique V7-09 n’a pas été reproduite dans cette exécution et n’est pas présentée comme résolue expérimentalement.
- Il n’existe aucune preuve Pixel pour cette tranche. La compatibilité runtime Android 17/API 37 reste à qualifier séparément.
- Les archives sont locales et ne constituent pas un export utilisateur; l’historique se restaure au redémarrage depuis l’état local versionné. Toute évolution de format doit conserver la lecture des archives V1 et des clés historiques.
- L’opt-in V4 est une préférence UI persistante, séparée des consentements serveur. Une capability non négociée bloque l’envoi. Les erreurs d’authentification et `STALE_DATA` demandent une action manuelle; aucun retry silencieux n’est ajouté.
- L’expiration des propositions conserve ses contrôles existants; l’UI ne change que l’affichage local de la date/heure. Un conflit `STALE_DATA` n’est pas résolu automatiquement et nécessite actualisation puis nouvel essai explicite.
- `docs/operations/V7-10_UI_DEFECTS.md` n’était pas présent dans le checkout; la revue s’est appuyée sur les autres documents demandés et les sources/test mentionnés dans la cartographie S3.
