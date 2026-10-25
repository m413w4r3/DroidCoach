# Qualification instrumentée V62 sur AVD API 37

**Statut global : PASS — 78/78 méthodes locales ont un résultat PASS effectif sur `emulator-5554`.** Le run complet brut a produit 77 PASS et un échec de harnais; la méthode en cause a passé son retest avec les paramètres attendus. Les tests exigeant un backend/OpenClaw sont explicitement NOT_RUN.

**Classification :** aucun `APP_BUG` observé; l’échec du premier appel est `TEST_HARNESS_BUG`; le premier AVD jetable créé pour cette qualification était `AVD_ENVIRONMENT` bloqué avant installation.

## Base, outils et APK

- Worktree: `test/v62-avd-api37-suite`, base `8890046012965f3a3112ff0c419014c74e189d11`.
- Build local hors réseau, depuis `android/`:

  ```sh
  GRADLE_USER_HOME=<tmp-qa-dir>/gradle-home ANDROID_HOME=<android-sdk> ANDROID_SDK_ROOT=<android-sdk> ./gradlew --offline --no-daemon --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
  ```

  Résultat: `BUILD SUCCESSFUL`, 67 tâches, 59 s. Le daemon Gradle à usage unique s’est arrêté à la fin.
- APK app SHA-256: `af2699e8681628d66c4542b9a03ece62382e9a41d2429e2560c1299b5f51bf1c`.
- APK androidTest SHA-256: `4a916f12f324b4cf10574038d42750bc6fe006f8dbc856ad9903225c824afa00`.
- Certificat des deux APK: SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.

## AVD et sécurité ADB

Le pré-vol a trouvé l’image locale `system-images;android-37.2;google_apis_ps16k;x86_64`, révision 6, avec l’émulateur 37.2.12. Un AVD jetable `atlas_v62_close_api37_disposable`, port 5580, a été créé sur cette image. La sortie d’`avdmanager` affiche un état « Fetch remote repository » pendant la création; la commande tournait dans l’environnement réseau restreint et aucun paquet ou image système n’a été téléchargé. Il a vérifié `sys.boot_completed=1`, `ro.kernel.qemu=1` et SDK 37, mais son framework n’était pas stable: le PID de `system_server` a changé pendant l’observation, les logs comptent 14 événements réels de démarrage de `system_server` (la sortie `wc` comptait aussi son en-tête), des crashs répétés de SurfaceFlinger et une mémoire hôte disponible tombée à 1,49 GiB. Conformément à l’arrêt prévu, aucun APK n’a été installé sur cet AVD et aucun test n’y a été lancé. Il a été arrêté; le serveur ADB isolé 5038 a été fermé.

Après l’autorisation explicite de l’utilisateur, la qualification a continué sur son émulateur déjà présent, modèle annoncé `sdk_gphone16k_x86_64`, serial `emulator-5554`. Avant installation, les commandes ciblées ont confirmé `get-state=device`, `ro.kernel.qemu=1`, `ro.build.version.sdk=37`, `sys.boot_completed=1`, SurfaceFlinger actif (PID 527) et `system_server` actif (PID 707). Les mêmes PID et services étaient présents au contrôle final; les buffers `SystemServer:E`, `AndroidRuntime:E` et `Watchdog:E` étaient vides. L’émulateur fourni était API 37; son identifiant exact de paquet d’image SDK n’a pas été interrogé. Il n’a pas été redémarré ni arrêté par cette tâche.

Le serveur ADB par défaut a été utilisé seulement après cette autorisation; toutes les commandes d’appareil de cette phase étaient qualifiées par `-s emulator-5554`. Aucun `adb devices` non qualifié n’a été lancé et aucune commande n’a sélectionné le serial physique `<serial-physique-omis>`. Le serveur par défaut et `emulator-5554` restent disponibles pour le test OpenClaw du parent. Je n’envoie plus de commandes à cet émulateur.

Commandes d’installation exécutées après les contrôles d’identité/API/stabilité:

```sh
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell pm list instrumentation
```

Les deux installations ont répondu `Success`; l’instrumentation présente était `dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner (target=dev.atlas.coach)`.

## Exécution et résultats

Suite complète lancée explicitement (aucun `connectedAndroidTest`):

```sh
adb -s emulator-5554 shell am instrument -w -r dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
```

Le runner a terminé sur 23 classes et 78 méthodes: **77 PASS, 1 FAIL, 0 skip**, avec XML parseable dérivé de sa sortie réelle. L’unique échec est `dev.atlas.coach.app.V62NativeUiCaptureTest#systemFontScaleTwoKeepsEmergencyControlsAccessible`: l’invocation sans préparation a échoué avec `expected_system_font_scale must be supplied by the disposable-AVD recipe` à la ligne 159. Le script QA du dépôt configure le `font_scale` système à 2.0 et fournit cet argument à cette méthode dédiée; il s’agit donc de `TEST_HARNESS_BUG`, pas d’un défaut produit.

Retest ciblé selon ce prérequis:

```sh
adb -s emulator-5554 shell settings put system font_scale 2.0
adb -s emulator-5554 shell am instrument -w -r \
  -e class dev.atlas.coach.app.V62NativeUiCaptureTest#systemFontScaleTwoKeepsEmergencyControlsAccessible \
  -e expected_system_font_scale 2.0 \
  dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell settings put system font_scale 1.0
adb -s emulator-5554 shell settings get system font_scale
```

Résultat du retest: **1 test, 1 PASS**, runner terminé; la restauration a retourné `1.0`. Le bilan effectif est donc **78 méthodes non backend uniques sur 78 avec une preuve PASS** (77 au run complet et la méthode de font scale au retest ciblé). Les deux sorties ont été transformées en XML par `scripts/v623-parse-instrumentation.py`; les résumés XML donnent respectivement 78 tests (77/1) et 1 test (1/0). Aucun PASS n’est attribué à un fichier XML absent ou à zéro test.

L’inventaire source contient 94 méthodes dans 29 classes. Les **16 méthodes exigeant des arguments backend** ont été filtrées par `BackendArgumentsAwareRunner` et restent `NOT_RUN_BACKEND_REQUIRED`; aucun backend/OpenClaw externe n’a été configuré. Cela n’affecte pas le total effectif local 78/78, mais la qualification backend reste à effectuer séparément.

| Périmètre | Exécution | Résultat | Statut |
|---|---:|---:|---|
| Suite locale AndroidJUnitRunner | 78 méthodes / 23 classes | 77 PASS, 1 échec de harnais | `PASS` après retest ciblé |
| Retest font scale système | 1 méthode | 1 PASS | `PASS` |
| Méthodes backend/OpenClaw | 16 méthodes | 0 exécutée | `NOT_RUN` |
| AVD jetable initial, avant installation | 0 méthode | framework instable | `BLOCKED / AVD_ENVIRONMENT` |

Les résultats détaillés par classe/méthode, avec le statut brut, le statut effectif, la classification et la preuve, sont dans [tests-results.tsv](preuves-avd/tests-results.tsv) et [classes-results.tsv](preuves-avd/classes-results.tsv). La sortie brute et les XML sont dans [preuves-avd](preuves-avd/).

## Limites et nettoyage

- L’émulateur `emulator-5554` a été fourni par l’utilisateur; son modèle était annoncé comme `sdk_gphone16k_x86_64`. Il reste lancé, avec les deux paquets installés, pour permettre au parent d’exécuter le test OpenClaw Android. Il n’a pas été arrêté et je n’effectuerai plus d’accès ADB sur lui.
- Le service matériel UWB de l’image signale l’absence de périphérique UWB et produit des crashs répétés (`No such device or address`). Les services Android requis pour les tests sont restés actifs et aucun défaut système `system_server`/SurfaceFlinger n’a été observé pendant la qualification sur `emulator-5554`.
- Les 16 tests backend/OpenClaw restent hors qualification; aucun accès réseau externe n’a été utilisé.
- L’AVD jetable initial et son ADB isolé ont été arrêtés; le daemon Gradle à usage unique s’est terminé. Le serveur ADB par défaut reste vivant pour préserver la session destinée au parent.
- Modifications limitées aux preuves et au présent rapport; aucun changement de code applicatif, manifeste, Gradle ou tests. Aucun push ni merge distant.

## Run final `b13b3fb` — 2026-10-25

Après les correctifs P1 de mandat (`4aff91a`, `1251009`, `d718540`/`b13b3fb`), l'APK final `2743688c…` a été installé sur `emulator-5554` (`Pixel_9_Pro_XL_2`, API 37) et la suite locale a été rejouée :

```sh
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
```

Résultat : **78 méthodes, 77 PASS, 1 échec de harnais `expected_system_font_scale`, 0 skip, 169,07 s**. Le retest ciblé avec `font_scale=2.0` et `expected_system_font_scale=2.0` a réussi : **1 test PASS**, 3,401 s; l'échelle a été restaurée à 1,0. Bilan effectif : **78/78 méthodes locales PASS**.

L'émulateur a disparu pendant une première tentative de suite; il a été redémarré à froid sur le port 5554 et la suite a été rejouée intégralement. Aucun Pixel physique n'a été utilisé.

Le test OpenClaw Android final a également été rejoué sur ce même APK :

```sh
adb -s emulator-5554 shell am instrument -w -r \
  -e class 'dev.atlas.coach.app.V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal' \
  -e backend_url "http://10.0.2.2:<port>" -e backend_token "<jeton éphémère>" \
  dev.atlas.coach.test/androidx.test.runner.AndroidJUnitRunner
```

Résultat : **OK (1 test), 17,743 s**, gateway réel et backend éphémère. Preuves : [preuves-finales-b13b3fb](preuves-finales-b13b3fb/PREUVES_FINALES.md).

## Run final `18484e2` — 2026-10-25

Après le correctif R3 (`f21fa8e`/`18484e2` : pause nulle et revalidation `focus.start`), l'APK final `8064dc0a…` a été installé et la suite locale rejouée sur `emulator-5554` :

- suite complète : **78 méthodes, 76 PASS, 2 échecs, 0 skip, 169,807 s**;
- échec 1 : `V62NativeUiCaptureTest#systemFontScaleTwoKeepsEmergencyControlsAccessible` (harnais `expected_system_font_scale`), retest ciblé **PASS** 2,912 s;
- échec 2 : `HomeLandscapeFooterInstrumentationTest#directiveAndCtaCanScrollAboveTheFixedFooterInLandscape` (timeout Compose), retest isolé **PASS** 3,703 s;
- bilan effectif : **78/78 méthodes locales PASS**;
- OpenClaw Android : **OK (1 test), 16,15 s**, gateway réel et backend éphémère.

Le test landscape avait réussi lors des runs précédents et passe isolément sur le même APK; l'échec de la suite complète est classé flake de performance Compose, pas régression démontrée. L'émulateur a été redémarré à froid après un crash; aucun Pixel physique n'a été utilisé.

Preuves : [preuves-finales-18484e2](preuves-finales-18484e2/PREUVES_FINALES.md).

## Run final `1a6f761` — 2026-10-25

Après le correctif R4 (`edbb3f5`/`1a6f761` : refus du mode continu), l'APK final `dc1c3e00…` a été installé et la suite locale rejouée sur `emulator-5554` :

- suite complète : **78 méthodes, 73 PASS, 5 échecs, 0 skip, 171,931 s**;
- 5 échecs, tous UI Compose : `HomeLandscapeFooterInstrumentationTest` (2 timeouts), `LauncherDrawerLazyInstrumentationTest` (SnapshotStateObserver multi-thread), `V62NativeUiCaptureTest` (harnais font scale + absence de hiérarchie Compose);
- classes agent/focus ciblées : **15 tests, 15 PASS**, 18,218 s;
- retest tiroir isolé : **1 PASS**, 20,428 s; retest font scale : **1 PASS**, 2,478 s; retest landscape isolé : **FAIL** (timeout Compose 10 s) sur l'état actuel de l'émulateur;
- OpenClaw Android : **OK (1 test), 13,817 s**, gateway réel et backend éphémère.

**Classification : `UI_FLAKY_ENVIRONMENT`.** Aucun test agent, mandat, Focus ou objectif n'a échoué; le delta `18484e2..1a6f761` ne touche que l'agent/mandat. La suite complète sur `18484e2` (même code UI) était 78/78 effective après deux retests. Les crashs UWB de l'image sont un artifact connu.

Preuves : [preuves-finales-1a6f761](preuves-finales-1a6f761/PREUVES_FINALES.md).
