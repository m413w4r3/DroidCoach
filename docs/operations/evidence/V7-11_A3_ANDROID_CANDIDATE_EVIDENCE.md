# V7-11-A3 — APK candidat, préservation AVD et rollback

- **Date :** 2026-11-08
- **Branche :** `audit/v7-11-a3-android-candidate`
- **BASE_SHA :** `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`
- **SHA source de l’APK candidat :** `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`
- **Profil annoncé par le parent :** Codex `gpt-6-luna`, effort `xhigh`
- **Périmètre :** APK debug, AndroidTest et AVD API 37 jetable. Aucun Pixel ou service live utilisé.

## Provenance et identité de l’APK

Pré-vol : `pwd` était le worktree assigné, branche conforme, worktree propre et HEAD égal à BASE_SHA. Aucun CodeGraph n’était initialisé. La provenance embarquée donne `source_tree_sha=8c2dcff837ca369f1e3332ce50ee3aa2a793722e` et `worktree_clean=true`.

Commande exécutée depuis `android/` :

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --offline --max-workers=2 --console=plain
```

Résultat : **PASS — BUILD SUCCESSFUL**, 69 tâches exécutées. Les APK assemblés sont :

| Artefact | SHA-256 |
|---|---|
| `app-debug.apk` | `97ac580d2cdcc5384a1f1ab8e15f2f3b4d57ca468177dede55166be154c321d5` |
| `app-debug-androidTest.apk` | `8d9287db9ab80b959876b66a1b34773b4b20f2a42adfa3c070bb85298676cc07` |

Métadonnées APK vérifiées avec `aapt dump badging`, `aapt dump permissions`, `aapt dump xmltree` et `apksigner verify --verbose --print-certs` :

| Champ | Valeur observée |
|---|---|
| applicationId | `dev.atlas.coach` |
| versionCode / versionName | `1` / `0.1.0` |
| compileSdk | `37.2` |
| minSdk / targetSdk | `26` / `36` |
| debuggable | `true` |
| allowBackup | `false` |
| Signature | V2 valide; DN `C=US, O=Android, CN=Android Debug` |
| Certificat SHA-256 | `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |

Le certificat correspond à l’empreinte complète de référence présente dans le dépôt. Le `versionCode=1` satisfait le seuil d’installation in-place (`>=1`). Permissions déclarées dans l’APK fusionné :

```text
android.permission.INTERNET
android.permission.PACKAGE_USAGE_STATS
android.permission.POST_NOTIFICATIONS
android.permission.READ_CALENDAR
android.permission.WRITE_CALENDAR
android.permission.AUTHENTICATE_ACCOUNTS
android.permission.READ_SYNC_SETTINGS
android.permission.WRITE_SYNC_SETTINGS
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.ACCESS_COARSE_LOCATION
android.permission.ACCESS_FINE_LOCATION
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_LOCATION
android.permission.WAKE_LOCK
android.permission.ACCESS_NETWORK_STATE
dev.atlas.coach.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

### Comparaison avec le candidat documenté

Le hash documenté dans `V7-10_TEST_REPORT.md` est `2517f2415b7c109fd9739c350ad44282dc68c86fa40b0e27d4207140b82a3214` (APK alors construit depuis `739ad878…`). Le hash candidat présent est différent (`97ac…321d5`), cohérent avec le SHA source distinct demandé ici. L’ancien fichier `/tmp/v7-10-a5-final.apk` est absent; une comparaison binaire directe (`cmp`) est donc **NOT_RUN**. Le hash enregistré permet néanmoins la comparaison de digest. La signature de référence complète `da301466…00eeee` est disponible dans le dépôt et **correspond** au certificat candidat.

## APK de base

L’APK de base a été reconstruit sans déplacer la branche, dans le worktree détaché temporaire `/tmp/v7-11-a3-base-739ad`, au commit documenté `739ad878ea9c2822505bf35b9c345e0917578c38` :

```sh
./gradlew :app:assembleDebug --offline --max-workers=2 --console=plain
```

Résultat : **PASS — BUILD SUCCESSFUL**, provenance embarquée `worktree_clean=true`.

- SHA-256 base : `46cac3f9cd0869155d21765fc5766f413a43259ba8affe01812aaea3122423f8`
- applicationId : `dev.atlas.coach`
- versionCode / versionName : `1` / `0.1.0`
- certificat SHA-256 : `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`

Package, versionCode et certificat correspondent entre base et candidat; aucun `-d` n’était nécessaire pour les deux mises à jour in-place.

## AVD et cible ADB

Inventaire ADB filtré aux seules lignes `emulator-*` avant lancement : aucun émulateur actif. AVD existant utilisé : `atlas_v62_disposable_api37`, série ciblée `<AVD_SERIAL>`. Commande de démarrage :

```sh
emulator -avd atlas_v62_disposable_api37 -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot -memory 2048 -port 5556
```

Boot : **PASS**, `sys.boot_completed=1`, `ro.build.version.sdk=37`; version émulateur `37.2.12.0`, image système x86_64 API 37. Le runtime de l’émulateur a signalé `Increasing RAM size to 4096MB` malgré l’argument `-memory 2048`; cette différence est conservée comme limite d’environnement. Toutes les opérations ADB visant un appareil ont utilisé explicitement `adb -s <AVD_SERIAL>`; le test Gradle a également été épinglé à ce seul serial. Aucun serial physique n’a été conservé dans la preuve ni ciblé.

## Préservation et installation in-place

Les fixtures sont exclusivement synthétiques : marqueur, préférences Relay, opt-in V4, consentements V4 révoqués, préférence Sync locale, état capability, plan DEMO vide du 2026-11-08 et un événement `focus_started` synthétique dans l’outbox. L’URL backend est vide; l’URL Relay est réservée à `.invalid`; aucun bearer, identifiant utilisateur ou service externe n’est configuré. Les fichiers fixtures de l’application sont en mode `0600`.

Une première vérification a confirmé que l’APK de base et l’APK candidat s’installent sans désinstallation. Avec le capability Sync laissé à son défaut `false`, l’application a retiré l’événement synthétique de l’outbox au premier démarrage, car aucun consentement Sync effectif n’était actif. Pour mesurer la conservation d’une outbox autorisée sans ouvrir de réseau, la séquence base → candidat a été rejouée avec `capabilities.sync=true`, catégorie synthétique `focus` et URL backend vide. Cette seconde séquence est la preuve retenue ci-dessous.

| Fixture privée | SHA-256 avant candidat | SHA-256 après candidat et cold start | SHA-256 après rollback |
|---|---|---|---|
| `files/v7_11_a3_marker.txt` | `ec4f657c33404300a21d0e82a912b0f4252df545b29990c81e90fbded2a82f91` | identique | identique |
| `shared_prefs/atlas_relay_configuration.xml` | `405a722bb733105e9cd5dcfdadc401a120c565b697ab095c75ebef63b84b6663` | identique | identique |
| `shared_prefs/atlas_sync_preferences.xml` | `922435cc2a6f8672cc9664b7db7936ab55bb9f9b87b32fa228d1150b5c9b38c2` | identique | identique |
| `shared_prefs/atlas_local_state.xml` | `07b8132f536090f150805794aebbb13d967aabdffddc446fb79a1f8555c34d3f` | `0c38b0a4a9fce8d7108ebee4e0455b5701952d604c3dd216182cc0547c1339ee` | identique au post-démarrage |
| `shared_prefs/atlas_core_v4_sync.xml` | `7281295e38b787a97e57e4ed0a8b4011efdd1c8e28fff412a0420127e731fa82` | identique | identique |
| `files/atlas_sync_outbox_v1.json` | `892cbd2e8e3bd35172484b5f054a2f31b304af0eea16e1f8d4a58b08858d2293` | identique | identique |
| `files/plans/day-plan-2026-11-08.json` | `8ef1d50a0088a7952f83643be2bf51fc6b888622bf6b556fc00be0ff1ae4e626` | identique | identique |

`atlas_local_state.xml` a été réécrit par le premier démarrage pour sérialiser l’état racine avec ses valeurs par défaut; la valeur synthétique `capabilities.sync=true` est restée présente. Les six autres fichiers ont conservé exactement leur hash. Le cold start a relu un outbox schema 1 avec un événement synthétique, le plan schema 3 `DEMO` (aucune activité), Relay `RELAY`, opt-in V4 `true` et deux consentements Core V4 `REVOKED`.

| Étape | Commande / résultat observé |
|---|---|
| Installation base | `adb -s <AVD_SERIAL> install /tmp/v7-11-a3-base.apk` — **Success**; `firstInstallTime=2026-11-08 11:05:12` |
| Mise à jour candidat | `adb -s <AVD_SERIAL> install -r android/app/build/outputs/apk/debug/app-debug.apk` — **Success**; `lastUpdateTime=11:13:07`, `firstInstallTime=11:05:12` |
| Cold start candidat | `am start -W -n dev.atlas.coach/.app.MainActivity` — `Status: ok`, `LaunchState: COLD`, `TotalTime: 1262 ms`, PID présent; aucune ligne `FATAL EXCEPTION` ni `Process: dev.atlas.coach` dans le logcat relevé |
| HOME | rôle et résolution inchangés : `com.google.android.apps.nexuslauncher` |
| État Relay / V4 | `relay_mode=RELAY`, `v4_chat_opt_in_v1=true`; URL `.invalid`, sans credential |

## Instrumentation

Les deux classes ont été exécutées séparément sur `atlas_v62_disposable_api37 (AVD) - 17`; Gradle a confirmé cette seule cible.

Commandes exécutées depuis `android/` (serial explicitement fixé) :

```sh
ANDROID_SERIAL=<AVD_SERIAL> ./gradlew :app:connectedDebugAndroidTest --offline \
  -Pandroid.injected.device.serial=<AVD_SERIAL> \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.app.AgentCoachConversationInstrumentationTest \
  --max-workers=2 --console=plain

ANDROID_SERIAL=<AVD_SERIAL> ./gradlew :app:connectedDebugAndroidTest --offline \
  -Pandroid.injected.device.serial=<AVD_SERIAL> \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.sync.v4.CoreV4SyncSchedulerInstrumentationTest \
  --max-workers=2 --console=plain
```

| Classe | Oracle exact | Résultat |
|---|---|---|
| `dev.atlas.coach.app.AgentCoachConversationInstrumentationTest` | `connectedDebugAndroidTest`, arguments `class=dev.atlas.coach.app.AgentCoachConversationInstrumentationTest` | **PASS — 9 tests, 0 failures, 0 errors, 0 skipped** |
| `dev.atlas.coach.sync.v4.CoreV4SyncSchedulerInstrumentationTest` | `connectedDebugAndroidTest`, arguments `class=dev.atlas.coach.sync.v4.CoreV4SyncSchedulerInstrumentationTest` | **PASS — 1 test, 0 failures, 0 errors, 0 skipped** |

La première classe comprend notamment `newConversationArchivesAndRestoresThePreviousThreadWithoutChangingOtherStores` et `v4OptInSurvivesColdStartAndModeExitWithoutNegotiatingOrChangingConsent`. Le scheduler vérifie qu’une demande immédiate crée un seul travail WorkManager et que les répétitions le réutilisent. Il s’exécute dans un `isolatedContext`; ce résultat **ne démontre pas** l’envoi Relay réel. L’action de synchronisation à travers un service Relay/Core est **NOT_RUN**, car aucun service live ni pairing n’était disponible ou autorisé.

Le parcours A0 synthétique expiration/APPLY/REJECT/idempotence est **NOT_RUN** : l’instrumentation intégrée `V7_06B_CoachLoopInstrumentedTest` exige un package QA séparé, une connexion Relay/Core authentifiée et un pairing synthétique. Aucun de ces services n’a été démarré pour cette qualification.

## Rollback et état final

Commande de rollback :

```sh
adb -s <AVD_SERIAL> install -r /tmp/v7-11-a3-base.apk
```

Résultat : **PASS — Success**. L’APK de base `dev.atlas.coach` versionCode `1` est réinstallé en place sur le candidat versionCode `1`. Aucun `-d`, désinstallation, `pm clear` ou changement HOME n’a été utilisé. `firstInstallTime` est resté `2026-11-08 11:05:12`; `lastUpdateTime=2026-11-08 11:14:57`. Les sept fichiers synthétiques ont les hashes indiqués après cold start; l’état capability reste `sync=true`.

Arrêt : `adb -s <AVD_SERIAL> emu kill` a répondu `OK: killing emulator, bye bye` / `OK`. Le processus `atlas_v62_disposable_api37` n’était plus présent après l’arrêt. **État final AVD : arrêté.**

## Verdicts et limites

| Oracle | Statut |
|---|---|
| Build candidat et provenance source | **PASS** |
| Package/version/installabilité/signature | **PASS** |
| Hash candidat vs hash de référence documenté | **DIGEST DIFFÉRENT**; binaire de référence absent, comparaison directe **NOT_RUN** |
| Installation base → candidat et préservation synthétique | **PASS**, avec normalisation attendue de l’état racine au premier démarrage |
| Cold start et HOME | **PASS** |
| Agent Coach instrumentation | **PASS 9/9** |
| Scheduler Core V4 instrumentation | **PASS 1/1**; transport Relay réel **NOT_RUN** |
| Parcours A0 synthétique APPLY/REJECT/expiration/idempotence | **NOT_RUN** |
| Rollback in-place et préservation | **PASS** |
| Pixel physique / Android réel | **NOT_RUN**; ce rapport qualifie uniquement un AVD Android 17/API 37 |

Les avertissements de build observés sont ceux du dépôt (conditions Kotlin toujours vraies/fausses, un symbole AndroidTest déprécié et APIs Gradle dépréciées); aucune erreur de build. Aucun fichier produit n’a été modifié. Le seul fichier de preuve à intégrer est ce document; le SHA du commit local de preuve est retourné par Git à la clôture de la tâche.

## Suivi V7-11-A3-F1 — HOME et Core A0 isolé

Cette section complète et actualise les verdicts de la première passe. Le candidat reste l’APK SHA-256 `97ac580d2cdcc5384a1f1ab8e15f2f3b4d57ca468177dede55166be154c321d5`, construit au `BASE_SHA` `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`, applicationId `dev.atlas.coach`, versionCode `1`. Le certificat candidat documenté reste `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.

### Rôle HOME et réinstallations

AVD utilisé : `atlas_v62_disposable_api37`, Android 17/API 37, serial `<AVD_SERIAL>`. Démarrage :

```sh
emulator -avd atlas_v62_disposable_api37 -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot -memory 2048 -port 5556
adb -s <AVD_SERIAL> shell getprop sys.boot_completed
```

Le boot a atteint `1`; l’émulateur a signalé une RAM effective de 4096 MiB malgré `-memory 2048`. Aucun autre appareil n’a reçu de commande. À ce démarrage, `pm path dev.atlas.coach` ne retournait aucun chemin et `run-as` répondait `unknown package`; des fixtures synthétiques neuves ont donc été créées après la première installation du candidat. `dumpsys package` indiquait déjà `firstInstallTime=2026-11-08 11:05:12`, valeur restée identique à chaque contrôle suivant; l’absence initiale du package signifie que cette valeur de pré-vol ne prouve pas la conservation de données applicatives d’une passe précédente.

Le manifeste fusionné de l’APK candidat déclare `dev.atlas.coach.app.LauncherActivity` avec `MAIN`, `HOME` et `DEFAULT`; `MainActivity` conserve son filtre `MAIN`/`LAUNCHER`. Après `adb -s <AVD_SERIAL> install -r android/app/build/outputs/apk/debug/app-debug.apk`, ces commandes ont été exécutées :

```sh
adb -s <AVD_SERIAL> shell cmd role add-role-holder android.app.role.HOME dev.atlas.coach
adb -s <AVD_SERIAL> shell cmd role get-role-holders android.app.role.HOME
adb -s <AVD_SERIAL> shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME
```

Résultats : le détenteur retourné est `dev.atlas.coach`; le premier lancement HOME a répondu `Status: ok`, `LaunchState: COLD`, `Activity: dev.atlas.coach/.app.LauncherActivity`, puis `topResumedActivity` a confirmé `LauncherActivity`. L’écran ATLAS s’est affiché. Aucun `FATAL EXCEPTION` n’a été relevé pendant ce premier cold start.

Après création des fixtures synthétiques, la réinstallation candidat `adb -s <AVD_SERIAL> install -r android/app/build/outputs/apk/debug/app-debug.apk` a répondu `Success`; `firstInstallTime` et le rôle HOME sont restés identiques. Le rollback final `adb -s <AVD_SERIAL> install -r /tmp/v7-11-a3-base.apk` a également répondu `Success`, sans `-d`, `pm clear`, désinstallation ni suppression de rôle. `firstInstallTime=2026-11-08 11:05:12`, rôle `dev.atlas.coach` et résolution de l’intent HOME vers `LauncherActivity` ont été confirmés après rollback. `lastUpdateTime=2026-11-08 11:51:16`.

Les hashes avant réinstallation candidat, après réinstallation candidat et après rollback base sont identiques :

| Donnée synthétique | SHA-256 conservé |
|---|---|
| `files/v7_11_a3_followup_marker.txt` | `3473f13926c74b7a0996fb69320b8c4cc6e22a1ffec6ab0bf6473eec2e7de823` |
| `files/v7_11_a3_synthetic.json` | `a3a1342a062012317ad2920bf948bad29e873708dbae7521a8e181fb361ac874` |
| `shared_prefs/v7_11_a3_synthetic.xml` | `cc824ef0b296bdab844f7625da91cbd031c701176d2ac0a30ae84f5046cd9560` |
| `files/plans/day-plan-2026-11-08.json` | `dcbb33476fdf5591d48a283604f403c2c2be2d37c33b4be65a08961d48122820` |
| `files/plans/agent-proposal-inbox-v4.json` | `96be19894f2b454a9dac43cac19bdae803923c6d190ea2055fa9c2309671120c` |
| `shared_prefs/atlas_relay_configuration.xml` (mode Direct) | `9936fe8258996ec57b9855ac67c2a5220cba1d38f5274f59c1614c34e053048c` |
| `shared_prefs/atlas_sync_configuration.xml` (URL et bearer synthétique chiffré) | `18ac31e759c8c3e82a0d0bfb44bc3ef54346220fba8c8207569fb564d16fddde` |
| `shared_prefs/atlas_core_v4_sync.xml` | `69c66d0a721ad662368ea66de0fee6018416b136422629a76331591b71998d94` |

Anomalie observée une seule fois pendant le premier remplacement de l’APK de base à 11:25:53 : `FATAL EXCEPTION` du processus app avec NPE dans le framework Android (`ConfigurationController.updateLocaleListFromAppContext`, `Resources.getConfiguration()` nul), au moment où le package détenait HOME. Le plan et les fixtures sont restés intacts; le système a relancé HOME. Le rollback répété à 11:51:16 et l’intent HOME qui a suivi n’ont produit aucun nouveau `FATAL` après 11:51. Cette anomalie de remplacement est conservée dans le verdict et n’est pas effacée par le cold start candidat réussi.

### Tentative A0 avec Core local et mock

Un worktree détaché et propre au SHA source `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c` a été créé sous `/tmp/v7-11-a3-core-8dac`. Aucun Docker ni service live n’a été lancé ou contacté. Le Core a écouté seulement `127.0.0.1:18100`, avec SQLite `/tmp/v7-11-a3-core.sqlite3`, `ATLAS_AGENT_ADAPTER=mock`, `ATLAS_V4_AUTH_MODE=legacy`, des capacités V4 synthétiques et des jetons temporaires synthétiques. La liaison AVD a été faite avec `adb -s <AVD_SERIAL> reverse tcp:18100 tcp:18100`; l’app a été réglée dans son UI sur Direct et `http://127.0.0.1:18100`. Aucun bearer n’est enregistré dans cette preuve; Android Keystore l’a chiffré. L’identité legacy Core dérivée de ce bearer était synthétique.

Le chemin `backend/.venv/bin/python` documenté dans le README était absent; la commande de lancement équivalente a utilisé le `python3` hôte (3.12.3) et ses dépendances FastAPI `0.141.1` / Uvicorn `0.52.3`, avec `PYTHONPATH` pointant sur le checkout détaché. Le serveur a démarré et servi les routes ci-dessous.

Un premier `POST /v4/agent/chat` sans capacités activées a renvoyé `403 CAPABILITY_UNAVAILABLE`. Après activation locale des capacités, le même parcours avec l’adaptateur mock a renvoyé `HTTP 200`, `agent_status=mock`, `tool_state=none`, `tool_call_count=0`, `proposal_ids=[]`. Le mock V4 ne produit donc pas la proposition A0 par chat/tool-call. Pour borner la tentative, une fixture locale a ensuite été créée par les routes d’API V4 : consentement `DAY_PLAN` et snapshot synthétique ont répondu `HTTP 200`; `POST /internal/v4/plan-revision-proposals` a créé `409a7e34-e264-4449-b7e4-d1788f324d67`, `QUEUED`, `confirmation_mode=A0_USER_REVIEW`, `auto_application=NONE_AT_CORE`.

| Oracle isolé | Résultat observé |
|---|---|
| Pull API et rejeu exact | `POST /v4/agent/proposals/pull`: `HTTP 200`; le rejeu identique retourne le même corps. SHA-256 des deux réponses : `42990005c9c9c2662a9c7bae06fd1e8c67a3aa953c3b368cdfee8abc52da631f`. |
| Pull depuis l’app AVD | **PASS** — `CoreV4SyncWorker` a réussi; l’inbox locale contenait l’ID ci-dessus en `RECEIVED`, puis la proposition était visible dans `Plan > Propositions reçues` en mode A0. |
| APPLY | **BLOCKED** — le plan local AVD est `plan_id=12000000-0000-4000-8000-000000000001`, version 2, origin `DEMO`; la fixture Core est `plan_id=00000000-0000-4000-8000-000000000811`, base version 1. Le détail Android montrait une activité absente (`? → 13:15`). Le bouton de confirmation a exercé le garde-fou local: état `VALIDATION_REJECTED` / `VALIDATION_FAILED`, un ACK créé puis livré. Le hash du plan AVD est resté identique. Aucune application de changement n’est revendiquée. |
| Rejeu de l’ACK de validation | **PASS** — le rejeu de l’ACK livré répond `HTTP 200`, `x-atlas-replayed: true`; aucune seconde transition n’est ajoutée. Cela ne qualifie pas l’idempotence d’un APPLY, puisque l’APPLY est bloqué. |
| REJECT utilisateur distinct | **PASS** — seconde proposition synthétique `e93cf4f0-4a06-48f6-83d3-3b73505d6dfc`; `Refuser définitivement` a créé `REJECTED` / `USER_REJECTED`, ACK livré au Core, plan local inchangé. Le rejeu de cet ACK a répondu `HTTP 200`, `x-atlas-replayed: true`. |
| Expiration | **PASS API Core uniquement** — proposition synthétique `7892a880-3476-4576-bd97-9f093b3ff409`; échéance forcée dans la SQLite temporaire, puis pull local `HTTP 200` avec `proposals=[]`; état réconcilié `EXPIRED`, événement `PULL`. Ce test ne simule pas l’écoulement de l’horloge Android ni l’expiration UI. |
| Chat mock générant A0 | **NOT_RUN** — résultat précis du mock V4 : `tool_state=none`, aucun `proposal_id`; création possible seulement par la route outil locale synthétique. |

Le serveur local a été arrêté, le reverse ADB supprimé et le port `18100` vérifié fermé avant le rollback final. Aucun appel n’a visé Core `8100`, Relay `4402`, Gateway `18789`, Funnel ou Tailscale. L’émulateur a répondu `OK: killing emulator, bye bye`; ensuite `adb -s <AVD_SERIAL> get-state` a répondu `device ... not found`. **État final AVD : arrêté.**

### Verdicts actualisés du suivi

| Oracle | Verdict F1 |
|---|---|
| HOME manifeste, attribution de rôle, lancement/navigation | **PASS** |
| Réinstallation candidat et rollback base avec rôle/données synthétiques conservés | **PASS** depuis les fixtures semées après première installation; le pré-vol package absent ne prouve pas la continuité de données d’une passe antérieure. |
| Cold start candidat sans FATAL | **PASS**, avec anomalie framework ponctuelle séparée lors du premier remplacement base; aucun nouveau FATAL au rollback final. |
| A0 app inbox pull | **PASS** |
| A0 APPLY | **BLOCKED** par le mismatch vérifié d’identifiant et version du plan; aucun changement appliqué. |
| A0 REJECT utilisateur / rejeu ACK | **PASS** |
| A0 expiration | **PASS** côté API Core/SQLite isolée uniquement; expiration UI Android **NOT_RUN**. |
| Pixel physique / services live | **NOT_RUN**; l’AVD ne qualifie pas un Pixel.

## Suivi V7-11-A3-F2 — retry APPLY A0 et reproduction contrôlée du FATAL

Cette passe reprend le candidat `97ac580d2cdcc5384a1f1ab8e15f2f3b4d57ca468177dede55166be154c321d5`, construit depuis `BASE_SHA=8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`; application `dev.atlas.coach`, `versionCode=1`, certificat SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`. Branche de preuve : `audit/v7-11-a3-android-candidate`.

### Plan AVD relu et remplacement HOME

Seul l’AVD `atlas_v62_disposable_api37` / `<AVD_SERIAL>` a été utilisé. Le plan relu par `run-as` depuis `files/plans/day-plan-2026-11-08.json` est l’état `schema3_state` :

| Champ | Valeur observée |
|---|---|
| `plan_id` | `12000000-0000-4000-8000-000000000001` |
| version | `2` |
| révision courante | `f28f8ef5-cd33-4c3b-9f09-ab3a879ddb12` |
| date / fuseau | `2026-11-08` / `Europe/Paris` |
| état / origine | `APPROVED` / `DEMO` |
| activité | `8a01f432-5534-4a54-a9a3-0aa4bcece8d9`, `FOCUS`, `A0-synthetic-plan`, `PLANNED`, début minute `540`, durée `50` |
| SHA-256 du fichier plan | `dcbb33476fdf5591d48a283604f403c2c2be2d37c33b4be65a08961d48122820` |

Le détenteur du rôle HOME avant remplacement était `dev.atlas.coach`. Après lancement de l’intent HOME, `adb -s <AVD_SERIAL> install -r android/app/build/outputs/apk/debug/app-debug.apk` a retourné `Success`. `firstInstallTime=2026-11-08 11:05:12` est resté inchangé; `lastUpdateTime=2026-11-08 12:02:20`, `versionCode=1`. `cmd role get-role-holders android.app.role.HOME` a toujours retourné `dev.atlas.coach`; l’intent HOME a répondu `Status: ok`, activité `dev.atlas.coach/.app.LauncherActivity`.

La reproduction contrôlée n’a pas produit de nouvelle ligne correspondant à `FATAL EXCEPTION`, `ConfigurationController.updateLocaleListFromAppContext` ou `Resources.getConfiguration` dans le logcat après le remplacement. Le hash du plan est resté `dcbb33476fdf5591d48a283604f403c2c2be2d37c33b4be65a08961d48122820`; les hashes du marqueur `files/v7_11_a3_followup_marker.txt`, du JSON synthétique `files/v7_11_a3_synthetic.json` et des préférences synthétiques `shared_prefs/v7_11_a3_synthetic.xml` sont restés respectivement `3473f13926c74b7a0996fb69320b8c4cc6e22a1ffec6ab0bf6473eec2e7de823`, `a3a1342a062012317ad2920bf948bad29e873708dbae7521a8e181fb361ac874` et `cc824ef0b296bdab844f7625da91cbd031c701176d2ac0a30ae84f5046cd9560`. L’inbox existante est restée au hash F1 `96be19894f2b454a9dac43cac19bdae803923c6d190ea2055fa9c2309671120c`; aucune nouvelle proposition n’y a été ajoutée.

L’occurrence unique F1 pendant un remplacement antérieur reste documentée : NPE framework sur `Resources.getConfiguration()` nul dans `android.app.ConfigurationController.updateLocaleListFromAppContext(ConfigurationController.java:286)`, vers 11:25:53. Elle ne s’est pas reproduite lors de ce remplacement candidat avec HOME détenu par l’application. Cette absence de reproduction ne qualifie pas un Pixel.

### Fixture Core locale et garde-fou A0

Le checkout Core détaché était propre au SHA source `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c`. Le serveur Uvicorn a écouté uniquement `127.0.0.1:18100`, avec `ATLAS_AGENT_ADAPTER=mock`, `ATLAS_V4_AUTH_MODE=legacy` et la base synthétique `/tmp/v7-11-a3-f2-core.sqlite3`. Les jetons Android/outils étaient synthétiques et ne sont pas consignés. Aucun conteneur ni service externe n’a été démarré ou contacté.

Commandes et routes tentées :

```sh
python3 -m uvicorn atlas_backend.main:app --host 127.0.0.1 --port 18100 --no-access-log
POST /v4/sync/consent-state
POST /v4/sync/day-plan-snapshots
POST /internal/v4/plan-revision-proposals
```

Le consentement local synthétique a répondu `HTTP 200 ACCEPTED`. Le snapshot V4 a répondu `HTTP 200 ACCEPTED` et a été vérifié dans SQLite avec les champs du plan AVD : même `plan_id`, version `2`, révision, date, fuseau et activité. Son origine réelle `DEMO` a été préservée. Son hash Core est `sha256:088bcdc17490e75f1e5d9a1e67f838d493ebd39901ddb40c2208fa3d0b0c6696`.

La proposition tentée était un déplacement valide de cinq minutes de l’activité existante : `activity_id=8a01f432-5534-4a54-a9a3-0aa4bcece8d9`, départ `540`, cible `545`, `plan_id=12000000-0000-4000-8000-000000000001`, `base_version=2`. Une première requête de qualification a été rejetée `400 BAD_REQUEST` car la clé d’idempotence n’était pas un UUID; la requête corrigée avec un UUID valide a atteint le garde-fou et répondu exactement `HTTP 503 DATA_UNAVAILABLE — un plan de démonstration n'est pas une projection Android`.

Le garde-fou a refusé avant le calcul du diff : aucun `proposal_id` ni `scope_hash` n’a été généré (`scope_hash=NOT_GENERATED_BEFORE_DEMO_ORIGIN_GUARD`) et SQLite ne contient aucune ligne de proposition dans cette base de passe. Aucune origine n’a été réécrite et aucun contrôle n’a été affaibli. Le refus observé est distinct du mismatch de plan de F1 : ici plan, version, révision et activité correspondent, mais l’origine `DEMO` interdit la proposition.

| Oracle F2 | Verdict | Observation |
|---|---|---|
| Snapshot Core synthétique aligné sur le plan AVD | **PASS** | Même plan ID/version/révision/fuseau/activité; hash `sha256:088bcdc17490e75f1e5d9a1e67f838d493ebd39901ddb40c2208fa3d0b0c6696`; origine `DEMO` conservée. |
| Proposition A0 via route V4 réelle | **BLOCKED** | `503 DATA_UNAVAILABLE` à cause de l’origine `DEMO`; les champs d’activité et version étaient valides. |
| Pull inbox, revue et APPLY dans l’app | **NOT_RUN** | Le Core n’a créé aucune proposition; l’inbox AVD n’a donc pas reçu de nouvel élément à examiner. |
| Version/hash du plan après APPLY, ACK `APPLIED`, rejeu idempotent et absence de second ACK | **NOT_RUN** | Aucun APPLY n’a été possible; aucune preuve de succès n’est revendiquée. Le plan et les marqueurs sont restés inchangés après remplacement APK. |
| Reproduction FATAL framework avec HOME actif | **NOT_REPRODUCED** | Aucun motif FATAL ciblé après le remplacement candidat; l’occurrence F1 reste une occurrence unique. |

Le Core a été arrêté; `127.0.0.1:18100` n’écoutait plus. L’AVD a ensuite répondu `OK: killing emulator, bye bye`; `adb -s <AVD_SERIAL> get-state` a répondu `device '<AVD_SERIAL>' not found`. **État final Core et AVD : arrêtés.** Aucun accès n’a visé Core `8100`, Relay `4402`, Gateway `18789`, Funnel ou Tailscale. Les verdicts valent uniquement pour cet AVD Android 17/API 37, pas pour le Pixel physique.
