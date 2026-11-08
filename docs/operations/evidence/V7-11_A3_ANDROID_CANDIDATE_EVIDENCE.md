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
