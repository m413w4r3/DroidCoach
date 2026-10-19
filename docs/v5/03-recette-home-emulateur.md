# V5 — Lot 3 : recette HOME sur émulateur

Date : 19 octobre 2026. Branche `feat/atlas-v5-launcher`, HEAD de départ `6c2cbba`. Tous les résultats ci-dessous proviennent de l'AVD Android 17 `Pixel_9_Pro_XL_2` (`emulator-5554`), jamais d'un téléphone Pixel.

## Environnement et installation

| Contrôle | Commande | Sortie observée |
|---|---|---|
| Branche | `git branch --show-current` | `feat/atlas-v5-launcher` |
| AVD | `/home/nill/Android/Sdk/emulator/emulator -list-avds` | `Pixel_9_Pro_XL_2` |
| Appareil connecté | `adb devices -l` | `emulator-5554 device product:sdk_gphone16k_x86_64 model:sdk_gphone16k_x86_64 device:emu64xa16k` |
| Version Android | `adb shell getprop ro.build.version.release`; `adb shell getprop ro.build.version.sdk` | `17`; `37` (image SDK 37.2) |
| JDK / Gradle | `java -version`; `cd android && ./gradlew --version` | OpenJDK `21.0.12.1`; Gradle `9.6.0` |
| Python | `python3 --version` | `Python 3.12.3` |
| Boot initial | `adb shell getprop sys.boot_completed` | `1` |

Commande de build exécutée depuis `android/` : `./gradlew :app:assembleDebug`. Sortie finale : `BUILD SUCCESSFUL in 911ms`; `36 actionable tasks: 36 up-to-date`. Installation : `adb install -r android/app/build/outputs/apk/debug/app-debug.apk` → `Performing Streamed Install` / `Success`.

## Résultats de la recette

Les captures PNG sont des captures `adb exec-out screencap -p`, résolution `1344 × 2992`. Les gestes d'interface ont été envoyés avec `adb shell input`; les écrans et activités ont été contrôlés avec `uiautomator dump`, `dumpsys activity` et la résolution Android de l'activité HOME.

| Scénario | Commandes / entrées UI exécutées | Résultat | Capture |
|---|---|---|---|
| 1. Choisir ATLAS comme HOME | Démarrage de `MainActivity`; Plus → Usage quotidien → « Choisir ATLAS comme accueil… »; sélection de la radio ATLAS puis « Set as default » dans la demande RoleManager. | **PASS.** Le dialogue Android affichait « Set ATLAS as your default home app? ». Après confirmation, `dumpsys role` indiquait `holders=dev.atlas.coach` et `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` retournait `dev.atlas.coach/.app.LauncherActivity`. | [sélection RoleManager](captures/selection-launcher-android.png) |
| 2. Geste Accueil depuis une app tierce | Épingler Settings depuis le tiroir, ouvrir Settings, puis `adb shell input swipe 670 2980 670 1800 500`. | **PASS.** `topResumedActivity` est revenu à `dev.atlas.coach/.app.LauncherActivity`; Settings était bien l'activité précédente. | [HOME ATLAS](captures/home-atlas.png) |
| 3. Ouvrir un favori puis revenir | Depuis ATLAS, toucher le favori Settings (`adb shell input tap 350 1256`), vérifier Settings, puis refaire le geste Accueil ci-dessus. | **PASS.** Settings s'ouvre depuis « Applications favorites » et le geste revient au HOME ATLAS. Le favori est enregistré par paquet `com.android.settings`. | [HOME avec favori](captures/home-atlas.png) |
| 4. Recherche et lancement dans le tiroir | Ouvrir le tiroir; rechercher `Chrome` avec `adb shell input text Chrome`; toucher « Ouvrir Chrome ». | **PASS pour Chrome.** Résultat unique Chrome, puis `topResumedActivity=com.android.chrome/com.google.android.apps.chrome.Main`. Recherche séparée `Calculator` → « Aucune application ne correspond à cette recherche » : cette app n'est pas visible/installée sur l'AVD, son lancement est **NON TESTÉ**. | [tiroir](captures/tiroir-applications.png) |
| 5a. Relance explicite d'activité | `adb shell am force-stop dev.atlas.coach`; `adb shell am start -W -n dev.atlas.coach/.app.LauncherActivity`. | **PASS pour `am start`; NON TESTÉ pour une période sans processus.** Android a réactivé le HOME par défaut immédiatement après `force-stop`; `am start` a répondu `Status: ok`, `Activity: dev.atlas.coach/.app.LauncherActivity`, avec l'avertissement « intent has been delivered to currently running top-most instance ». Le PID était déjà de nouveau présent (`pidof dev.atlas.coach` → `12420`). | [HOME ATLAS](captures/home-atlas.png) |
| 5b. Redémarrage AVD sans effacement | `adb reboot`; `adb wait-for-device`; boucle de lecture de `sys.boot_completed` (90 s max). Aucun argument de wipe et aucune commande `pm clear` n'ont été lancés. | **PASS.** `sys.boot_completed=1 after 7 s`; même série `emulator-5554`, AVD toujours `Pixel_9_Pro_XL_2`; le HOME résolu après boot était ATLAS. | [HOME après reboot](captures/home-atlas.png) |
| 6. Persistance directive et favoris après 5 | Après reboot : `uiautomator dump`, lecture UI; `adb shell run-as dev.atlas.coach cat shared_prefs/atlas_local_state.xml`. | **PASS.** Le HOME affichait « Lis/avance 10 min » et Settings. Le document local contenait `launcherPins:[com.android.settings]` et la directive `directive-demo-1`, titre `Lis/avance 10 min`. Les deux étaient encore visibles après le reboot. | [HOME après reboot](captures/home-atlas.png) |
| 7. Retour, gestes système, applications récentes | `adb shell input keyevent KEYCODE_BACK`; geste système depuis Settings/Chrome; `adb shell input keyevent KEYCODE_APP_SWITCH`; `KEYCODE_BACK` depuis Récents. | **PASS.** Après recherche, un premier Retour ne fermait pas le tiroir; le suivant l'a fermé. Retour sur HOME fermé le laisse ouvert. Récents affichait Settings et Chrome (`com.google.android.apps.nexuslauncher/com.android.quickstep.RecentsActivity`); Retour revenait à `LauncherActivity`. | [applications récentes](captures/applications-recentes.png) |
| 8. Basculer vers Pixel Launcher puis revenir | Depuis « Réglages HOME », choisir Pixel Launcher et confirmer « Change »; ensuite ouvrir `android.settings.HOME_SETTINGS`, choisir ATLAS et confirmer « Change ». | **PASS.** Le détenteur du rôle a successivement été `com.google.android.apps.nexuslauncher`, puis `dev.atlas.coach`. Le HOME Pixel a été affiché; après le retour de rôle, une première touche HOME a ramené l'ancienne activité Pixel déjà ouverte, puis la suivante a affiché `LauncherActivity` ATLAS. La résolution HOME finale est ATLAS. | [Pixel Launcher](captures/pixel-launcher.png), [sélection ATLAS](captures/selection-launcher-android.png) |

## Journal des sorties utiles

```text
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
Performing Streamed Install
Success

Après sélection ATLAS :
holders=dev.atlas.coach
dev.atlas.coach/.app.LauncherActivity

Après sélection Pixel :
holders=com.google.android.apps.nexuslauncher
topResumedActivity=com.google.android.apps.nexuslauncher/.NexusLauncherActivity

Après reboot sans wipe :
sys.boot_completed=1 after 7 s
topResumedActivity=dev.atlas.coach/.app.LauncherActivity
directive=Lis/avance 10 min
launcherPins=[com.android.settings]
```

## Limites

- Aucun téléphone Pixel physique n'a été utilisé.
- Calculator n'était pas présent dans le catalogue lançable; Chrome a servi à valider recherche et lancement.
- `force-stop` sur le launcher par défaut ne fournit pas ici une fenêtre de processus mort observable : Android relance rapidement le HOME. Le reboot sans effacement, puis la lecture UI et SharedPreferences, confirme la restauration persistante après redémarrage.
- Aucun défaut nécessitant une modification de code HOME n'a été observé; aucun fichier de production n'a été modifié dans ce lot.

## Validation Gradle finale

Commande demandée exécutée depuis `android/` : `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → `BUILD SUCCESSFUL in 27s`, `51 actionable tasks: 11 executed, 40 up-to-date`. Le task test étant `UP-TO-DATE` dans cette commande, une exécution fraîche a aussi été faite avec `./gradlew :app:testDebugUnitTest --rerun-tasks` → `BUILD SUCCESSFUL in 11s`, `24 actionable tasks: 24 executed`. Les XML du rapport comptent `112 tests, 0 failure, 0 error, 0 skipped`. Le rapport lint indique `0 errors, 36 warnings`. SHA-256 APK debug : `03e03c712665304fe6552e7e0687ee1b7a54aec96201d06011a69a29249a5231`.
