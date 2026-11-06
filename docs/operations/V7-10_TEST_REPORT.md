# V7-10-A5 — Qualification intégrée et préservation AVD

- **Date :** 2026-11-06
- **Branche du rapport :** `v7-10/a5-qa`
- **Base du ticket :** `d96810bcf756a5c52f4dbfbc3926e14ef1c8de69`
- **SHA de la preuve AVD/Android :** `739ad878ea9c2822505bf35b9c345e0917578c38`
- **SHA final d’intégration :** `3123dba4f89579d414406d8c790609d1e4a5adf6`
- **Verdict : `PASS_WITH_LIMITATIONS`**

## Résultat

Les suites backend et proxy passent avec les nombres rapportés. La preuve AVD/Android et son APK debug correspondent au SHA `739ad878...`; l’APK a été installé en mise à jour in-place sur l’AVD API 37. Le marqueur et les préférences synthétiques ont conservé leur contenu et leurs hashes; `firstInstallTime` est resté inchangé, le lancement à froid a réussi et aucun `FATAL EXCEPTION` n’a été trouvé avant instrumentation. Les deux classes instrumentées passent séparément : Agent Coach **9/9**, scheduler V4 **1/1**. Le connecteur final passe **131/131** sur `3123dba`; le **125/125** enregistré dans les preuves A5 est la photo antérieure du SHA `739ad878`.

Les limites sont l’absence de test sur Pixel réel et de Relay/service live, l’usage d’un APK debug, ainsi que le rerun combiné JVM/lint qui n’a pas démarré à cause d’un verrou Gradle en lecture seule. Le résultat complet précédent reste valide pour les sources Android de production et JVM inchangées; le changement Android intervenu ensuite concerne trois arguments d’assertion dans un test instrumenté, compilé et exécuté 9/9 sur le SHA final.

## Profil, portée et provenance

Profil demandé : **Codex / gpt-6-luna / xhigh** (« Luna Implementer »). Aucun sous-agent.

Le checkout principal a été vérifié propre et à `739ad878...` avant le build final. L’APK embarque `source_sha=739ad878...` et `worktree_clean=true`; c’est le SHA de la preuve AVD/Android. Le SHA final d’intégration est `3123dba4f89579d414406d8c790609d1e4a5adf6`. La commande `git diff --stat 739ad878..3123dba -- android` ne produit aucune sortie : l’arbre `android/` est identique entre les deux SHA. Les tests connecteur/proxy enregistrés ici ont été exécutés dans un clone complet au SHA `739ad878`; le connecteur final 131/131 sur `3123dba` est une vérification rapportée par l’orchestrateur, distincte de cette preuve A5. Voir [provenance APK](evidence/V7-10_A5_R3_FINAL_APK_METADATA.log), [portée Android/backend de la preuve](evidence/V7-10_A5_R3_FINAL_SHA_SCOPE.log) et [avancement observé du checkout](evidence/V7-10_A5_R3_FINAL_SHA_ADVANCE.log).

Aucun fichier de code produit, test, contrat ou configuration n’a été modifié par cette qualification. Le commit local contient uniquement ce rapport et des preuves sous `docs/operations/evidence/`; aucun push/PR n’a été effectué. Aucun Pixel physique, service Relay/Core/backend live, `adb devices`, `adb -d` ou `adb -e` n’a été utilisé. La suite proxy exclut explicitement le cas épinglé sur le port 4402.

## Gates

| Gate | Statut | Preuve et limite |
|---|---|---|
| `GO_BACKEND_TESTS` | **GO** | 451 passed, 1 skipped, adaptateur mock. |
| `GO_ANDROID_JVM_LINT` | **GO, portée documentée** | 934 tests JVM passés, lint 0 erreur / 89 avertissements, APK debug et androidTest assemblés au précédent snapshot Android. Les sources Android de production et JVM sont identiques au SHA final; le changement final de test instrumenté a été compilé et exécuté 9/9. Le rerun global au SHA final n’a pas pu démarrer car le wrapper ne pouvait pas ouvrir son verrou dans `<HOME>/.gradle` en lecture seule. |
| `GO_ANDROID_DATA_PRESERVATION_AVD` | **GO** | Marqueur et SharedPreferences identiques après l’installation `-r` de l’APK final sur API 37, vérifiés immédiatement avant instrumentation. |
| `GO_NEW_CONVERSATION` | **GO** | `AgentCoachConversationInstrumentationTest`: 9/9, 0 échec/erreur/ignoré. |
| `GO_V4_COLD_START` | **GO** | Après update et avant instrumentation : `LaunchState: COLD`, PID présent, préférences V4 relues, aucun fatal crash. La suite instrumentée inclut également le scénario V4 cold start. |
| `GO_RELAY_IMMEDIATE_SYNC` (partie locale) | **GO, portée locale** | Scheduler : 1/1; enqueue WorkManager local vérifié. Aucun envoi vers un Relay réel n’est démontré. |
| `GO_INDEPENDENT_SECURITY_REVIEW` | **EN ATTENTE** | En attente du verdict final A4-delta; ce rapport ne prononce pas ce gate. |
| `GO_PRODUCT_INSTALL` | **NO_GO / NOT_RUN** | Cette qualification AVD ne constitue pas une autorisation d’installation produit. |
| `GO_LIVE_SECURITY` | **NO_GO / NOT_RUN** | Aucun service live ni test de sécurité live. |
| `GO_DAILY_PILOT` | **NO_GO / NOT_RUN** | Aucun pilote quotidien ni Pixel réel. |

## Commandes et résultats

| Suite / commande réellement exécutée | Résultat |
|---|---|
| Backend : `cd backend && ATLAS_AGENT_ADAPTER=mock /tmp/v7-10-a5-backend-venv/bin/python -m pytest -q` | **PASS — 451 passed, 1 skipped, 3 warnings, 38.05 s.** Venv temporaire local, sans accès live. [Log](evidence/V7-10_A5_BACKEND_TESTS.log) |
| Connecteur de la preuve A5 : `export npm_config_cache=/tmp/v7-10-a5-npm-cache; npm ci --offline && npm run test:unit` dans `/tmp/v7-10-a5-r3-final-739ad/integrations/relay/pc` (clone complet, HEAD exact `739ad878...`) | **PASS — TypeScript compilé (`tsc -p tsconfig.json`), 18 fichiers, 125/125 tests.** Cette preuve est une photo antérieure. [Log](evidence/V7-10_A5_R3_FINAL_CONNECTOR.log) |
| Connecteur final d’intégration `3123dba` | **PASS — 131/131**, vérifié par l’orchestrateur. Ce total remplace 125/125 comme résultat final du connecteur; le log A5 conserve le résultat observé au SHA `739ad878`. |
| Proxy : `node --test --test-skip-pattern='pinned relay integration on 4402' integrations/relay/proxy/test/proxy.test.mjs` | **PASS — 18 pass, 0 fail.** Le cas `pinned relay integration on 4402` a été exclu par le filtre et n’a pas été exécuté. [Log](evidence/V7-10_A5_R3_PROXY_TESTS.log) |
| Android complet antérieur : `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=1 --console=plain` depuis `android/` | **PASS — 934 JVM, 0 échec, 0 ignoré; lint 0 erreur / 89 avertissements; APK debug et androidTest assemblés.** Sources de production/JVM identiques au SHA final. [Log](evidence/V7-10_A5_ANDROID_BUILD.log) |
| Tentative de rerun Android complet au SHA final | **NOT_RUN avant exécution Gradle.** Le wrapper a échoué sur `FileNotFoundException: <HOME>/.gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/gradle-9.6.0-bin.zip.lck (Read-only file system)`. Ce résultat d’environnement n’est pas compté comme un test. [Sortie exacte](evidence/V7-10_A5_R3_FINAL_ANDROID_FULL.log) |
| Build APK final depuis `<REPO>/android` au SHA `739ad878...`: `./gradlew :app:assembleDebug --offline --max-workers=1 --console=plain` | **PASS — BUILD SUCCESSFUL en 1m18s.** APK copié vers `/tmp/v7-10-a5-final.apk`, SHA-256 `2517f2415b7c109fd9739c350ad44282dc68c86fa40b0e27d4207140b82a3214`. Paquet `dev.atlas.coach`, versionCode 1, versionName `0.1.0`, compileSdk 37. [Build](evidence/V7-10_A5_R3_FINAL_ANDROID_ASSEMBLE.log), [badging et hash](evidence/V7-10_A5_R3_FINAL_APK_METADATA.log) |
| Agent Coach : `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.app.AgentCoachConversationInstrumentationTest --max-workers=1 --console=plain` | **PASS — 9 tests, 0 failure, 0 error, 0 skipped.** [Log](evidence/V7-10_A5_R3_AGENT_COACH_TESTS.log), [XML](evidence/V7-10_A5_R3_AGENT_COACH_TEST_RESULTS.xml) |
| Scheduler V4 : `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.sync.v4.CoreV4SyncSchedulerInstrumentationTest --max-workers=1 --console=plain` | **PASS — 1 test, 0 failure, 0 error, 0 skipped.** [Log](evidence/V7-10_A5_R3_CORE_V4_SCHEDULER_TESTS.log), [XML](evidence/V7-10_A5_R3_CORE_V4_SCHEDULER_TEST_RESULTS.xml) |

## APK et préservation in-place sur AVD API 37

L’APK de base a été revérifié avant usage : SHA-256 `5f7719748ebfb29e997966c80465ea49ae13152ee1e9790d1686afed50c4c317`, paquet `dev.atlas.coach`, versionCode 1, versionName `0.1.0`, compileSdk 37. Son installation a retourné `Success`; `firstInstallTime` et `lastUpdateTime` étaient `2026-11-06 07:49:08`.

L’unique AVD `atlas_v62_disposable_api37` a démarré sur le port 5556 avec `-wipe-data -no-snapshot -no-window -no-audio -no-boot-anim`; boot terminé et SDK 37 vérifié. L’activité résolue était `dev.atlas.coach/.app.MainActivity`; le lancement initial était `COLD`, PID `4308`, puis force-stop. Voir [boot](evidence/V7-10_A5_R3_AVD_BOOT.log), [installation base](evidence/V7-10_A5_R3_AVD_BASE_INSTALL.log) et [premier lancement](evidence/V7-10_A5_R3_AVD_FIRST_LAUNCH.log).

Les fixtures synthétiques ont été créées et copiées dans le stockage privé avec des commandes `run-as` séparées :

- `files/v7_10_preservation_marker.txt` : `synthetic-v7-10-marker`, SHA-256 `a4cc3a2d26a7efc7481da7257c5fd9e164834302f6e510d03876e314056ae59a`.
- `shared_prefs/atlas_relay_configuration.xml` : `v4_chat_opt_in_v1=true`, `relay_mode=RELAY`, SHA-256 `827b34480533d2540291ea87bf007a4f93686ebf30d7e50a886e9c7113ac7cf8`.

Le hash et le contenu des fixtures hôte sont conservés dans [les preuves seed](evidence/V7-10_A5_R3_AVD_HOST_FIXTURES.log). Après installation de l’APK final `/tmp/v7-10-a5-final.apk` par `adb -s emulator-5556 install -r ...`, la sortie était `Success`. `firstInstallTime` est resté `2026-11-06 07:49:08`; `lastUpdateTime` est devenu `2026-11-06 07:49:55`. `run-as ls -l` retrouvait les deux fichiers; le contenu et les deux hashes étaient identiques. [Log d’installation update](evidence/V7-10_A5_R3_AVD_UPDATE_INSTALL.log), [preuve avant instrumentation](evidence/V7-10_A5_R3_AVD_PREINSTRUMENTATION_PROOF.log).

Immédiatement après l’update et avant instrumentation, le cold start a retourné `Status: ok`, `LaunchState: COLD`, `TotalTime: 2718`, `WaitTime: 2733`, PID `6038`; `FATAL_EXCEPTION=absent`. Le contrôle antérieur de lancement de cette même preuve rapporte aussi `Status: ok` et `LaunchState: COLD`. Aucune commande de désinstallation, `pm clear` ou option `-d` n’a été utilisée dans cette séquence.

## Instrumentation et nettoyage

Les classes ont été lancées séparément sur le même AVD; aucun service live n’était requis. Après les deux tests, `pm path dev.atlas.coach` et `dumpsys package dev.atlas.coach` n’ont retourné aucune sortie : le harness AGP avait retiré le paquet après instrumentation. Selon la consigne, cela n’invalide pas la preuve de préservation faite avant les tests. La relecture cold start/préférences post-harness n’était pas possible sans réinstallation; elle est donc **NOT_RUN**. Voir [état harness](evidence/V7-10_A5_R3_POST_INSTRUMENTATION_HARNESS.log).

Nettoyage : `adb -s emulator-5556 emu kill` a répondu `OK: killing emulator, bye bye` / `OK`; le PID AVD s’est arrêté. `./gradlew --stop` a arrêté un daemon et `./gradlew --status` a indiqué `No Gradle daemons are running`. La vérification indépendante a trouvé aucun processus `emulator` et seulement `jps` dans la liste JVM. [Arrêt AVD](evidence/V7-10_A5_R3_AVD_CLEANUP.log), [arrêt Gradle](evidence/V7-10_A5_R3_GRADLE_CLEANUP.log), [vérification des processus](evidence/V7-10_A5_R3_PROCESS_CHECK.log).

**Conclusion : `PASS_WITH_LIMITATIONS`.** Tous les gates locaux demandés sont étayés par les sorties indiquées. La qualification ne prouve ni l’installation produit, ni la sécurité live, ni un pilote quotidien; aucun Pixel réel ou service Relay live n’a été utilisé. L’artefact installé est un APK debug, pas une release. Le rerun global JVM/lint au SHA final reste limité par l’accès en lecture seule au verrou du cache Gradle; le résultat antérieur 934/lint couvre les sources Android de production/JVM inchangées, et les deux classes instrumentées finales passent.
