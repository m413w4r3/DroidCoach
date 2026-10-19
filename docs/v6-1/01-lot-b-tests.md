# Lot B — Stabilisation de la suite instrumentée Android

Branche demandée : `feat/atlas-v6-1-stabilisation`. Aucun changement de branche,
commit, push ou merge n'a été effectué.

## Environnement et ADB

- AVD existant : `Pixel_9_Pro_XL_2`, série `emulator-5556`, Android 17 / API 37.
- JDK : OpenJDK 21.0.12.1; Python : 3.12.3; AGP 9.4.1 / Gradle 9.6.0;
  SDK Android : Platform 37.2, Build Tools 37.0.0, Emulator 37.2.12,
  Platform Tools 35.0.2.
- Au début, `adb devices -l` ne listait aucun appareil et aucun processus
  `emulator`/`qemu-system` n'était actif. `emulator -list-avds` listait bien
  `Pixel_9_Pro_XL_2`. Le serveur ADB a été démarré, puis l'AVD existant a été
  lancé avec :

  ```sh
  /home/nill/Android/Sdk/emulator/emulator -avd Pixel_9_Pro_XL_2 -no-snapshot-save -no-boot-anim -no-window -port 5556
  ```

  Aucun `-wipe-data`, recréation ou effacement de données n'a été effectué.
  Après démarrage, `adb shell getprop sys.boot_completed` a renvoyé `1` et
  `ro.build.version.sdk` a renvoyé `37`. ADB est resté `device` pendant la
  première suite complète; aucune déconnexion en cours de test n'a été observée.
  Le premier état « aucun appareil » correspondait à l'AVD arrêté, pas à un
  appareil physique ni à une cible `offline`.

## Échecs de référence et diagnostic

Commande de référence, avant corrections :

```sh
cd android
ANDROID_SERIAL=emulator-5556 ./gradlew --console=plain connectedDebugAndroidTest
```

Sortie du rapport instrumenté : `38 tests`, `4 failures`, `0 errors`,
`0 skipped` (34 réussis). Gradle a terminé avec `BUILD FAILED in 1m 31s` et a
indiqué `There were failing tests`.

| Test | Cause observée | Classement et adaptation |
|---|---|---|
| `AtlasLaunchTest.reefPomodoroSetupStartsAnAtlasSessionWithCycleState` | Le test cherchait le texte `Démarrer le Pomodoro`; l'écran Focus présente maintenant le bouton `Démarrer`. L'échec arrivait avant le clic et n'évaluait donc pas le démarrage. | Sélecteur obsolète. Après remplacement par `focus_pomodoro_start`, le scénario démarre la session et confirme le cycle 1/4. |
| `AtlasLaunchTest.directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline` | Après les interactions locales de l'accueil, qui passaient toutes (`Discuter`, alternative, report et pause), le test ouvrait Coach et y attendait les anciens boutons de réponse. Coach affiche maintenant la conversation et la boîte de propositions; la trace échouait sur l'ancien `Discuter` à la ligne 140. | Assertions d'écran obsolètes. Le scénario garde les vérifications des réponses locales déterministes sur l'accueil, puis valide le champ de conversation courant via le tag `agent_message_input`; il passe après adaptation. |
| `AtlasVisualCaptureTest.capturesLauncherHomeAndSearchableAppsList` | Le test attendait le texte `Ouvrir le tiroir d'applications`, absent du CTA actuel `Applications et recherche`; le délai de 10 s expirait avant l'ouverture du tiroir. | Sélecteur obsolète. Avec `launcher_open_drawer`, le test clique le CTA et ouvre le tiroir; l'attente utilise le tag existant `launcher_search`. |
| `LauncherPresentationTest.homeHasNoCoachChromeAndSwipeSearchPinAndFavoritesWork` | L'écran HOME n'a plus le libellé `Directive du jour`; son titre de section actuel est `Prochaine étape`. | Libellé de test obsolète. Le test cible le titre via `launcher_next_step_heading` et passe après adaptation. |

## Changements Lot B

- Remplacement des sélecteurs de texte renommés par des `testTag` stables dans
  les tests concernés.
- Ajout de tags uniquement aux composables nécessaires : bouton Pomodoro,
  bouton d'ouverture du tiroir et titre de la section HOME. Aucun changement de
  logique, de mise en page, de navigation, de manifeste, de Gradle ou de
  `AtlasController`.
- Les méthodes backend portent `@RequiresBackendArguments`. Le runner
  `BackendArgumentsAwareRunner` les filtre hors du décompte autonome si aucun
  des arguments requis n'est fourni; elles restent sélectionnables par le
  harnais dédié. Une tentative avec `Assume` puis une tentative avec
  `fireTestIgnored` ont toutes deux produit trois `<failure>` vides dans le
  rapport Android, même avec `BUILD SUCCESSFUL`; elles ont été remplacées par ce
  filtre explicite.

## Scénarios avec backend séparé

- `V6RealAgentE2eInstrumentationTest.realCoachResponseCreatesDirectiveAndStartsFocus`
  et `restoredFocusCanPauseResumeAndCancel` exigent `backend_url` et
  `backend_token`. Les exécuter via le harnais OpenClaw dédié, avec l'AVD déjà
  démarré et la configuration/token Gateway requis :

  ```sh
  ANDROID_SERIAL=emulator-5556 ./scripts/v6-e2e-real.sh
  ```

- `RemoteBackendE2eInstrumentationTest.runRequestedBackendScenario` exige
  `scenario`, `backend_url` et `backend_token`. Le script démarre son backend
  FastAPI local temporaire et exécute les scénarios `online`, `unauthorized`,
  `disabled`, `offline` et `resume` :

  ```sh
  ANDROID_SERIAL=emulator-5556 ./scripts/v4-backend-e2e.sh
  ```

- Le scénario synthétique autonome `limitedMandatePreauthorizesFocusPlanOnAndroid`
  reste dans `connectedDebugAndroidTest`; il ne dépend pas d'OpenClaw.

## Résultats après corrections

Commande finale :

```sh
cd android
ANDROID_SERIAL=emulator-5556 ./gradlew --console=plain connectedDebugAndroidTest
```

Sortie Gradle : `BUILD SUCCESSFUL in 1m`, `68 actionable tasks: 10 executed,
58 up-to-date`. Le XML `TEST-Pixel_9_Pro_XL_2(AVD) - 17.xml` et le rapport HTML
indiquent `35 tests`, `0 failure`, `0 error`, `0 skipped`; les 35 méthodes
applicables ont réussi. Les trois méthodes requérant un backend sont filtrées
avant le décompte quand leurs arguments manquent :

- `V6RealAgentE2eInstrumentationTest.realCoachResponseCreatesDirectiveAndStartsFocus`
- `V6RealAgentE2eInstrumentationTest.restoredFocusCanPauseResumeAndCancel`
- `RemoteBackendE2eInstrumentationTest.runRequestedBackendScenario`

La relance intermédiaire a utilisé la même commande; elle a échoué avant
instrumentation sur `compileDebugKotlin`, dans `ActionDispatcher.kt` modifié en
parallèle hors périmètre Lot B (`AgentInboxEntry`, `proposal` et `effect` non
résolus). Gradle a affiché `Task :app:compileDebugKotlin FAILED`, puis
`BUILD FAILED in 2s` (`53 actionable tasks: 1 executed, 52 up-to-date`). La
compilation a ensuite abouti et la commande finale ci-dessus a exécuté toute la
suite applicable. Aucun fichier de cet autre travail n'a été modifié pour le
Lot B.

Pendant la validation finale, l'AVD est resté `device`,
`sys.boot_completed=1`; aucune déconnexion ADB n'a été constatée.

## NON TESTÉ

- `scripts/v6-e2e-real.sh` et l'échange avec OpenClaw/Gateway réel. Les deux
  méthodes correspondantes sont correctement filtrées du run sans arguments,
  mais le script n'a pas été exécuté dans cette tâche.
- `scripts/v4-backend-e2e.sh` et ses scénarios FastAPI autonomes; le script
  n'a pas été exécuté dans cette tâche.
- Tests JVM, lint, pytest, test de publication ou installation signée.
- Tout téléphone physique. Aucune affirmation de test sur Pixel physique.
