# Validation Android VM-0

## Environnement relevé

- Hôte : Linux x86_64, JDK OpenJDK 21.0.12.1.
- Gradle système : 4.4.1 ; inutilisé. Le wrapper du projet utilise Gradle 8.9.
- Outils Android : SDK `$HOME/Android/Sdk`, `adb` 34.0.4.
- Émulateur : `emulator-5554`, Android 11, API 30, ABI `x86_64` (`Pixel_9_Pro_XL_API_30_2`). Aucun appareil Pixel réel n'a été utilisé.
- Android : `compileSdk` / `targetSdk` 35, `minSdk` 26.
- Versions : AGP 8.7.3, Kotlin 2.0.21, plugin Compose Kotlin 2.0.21, Compose BOM 2024.12.01.

## Verdicts

| Vérification | Verdict | Preuve |
|---|---|---|
| `:app:assembleDebug` via le script | PASS | `BUILD SUCCESSFUL in 39s`, 35 tâches ; APK ci-dessous. |
| `:app:testDebugUnitTest` | PASS | `BUILD SUCCESSFUL in 4s`, 22 tâches (6 exécutées). |
| Installation et démarrage sur `emulator-5554` | PASS | `install -r` a répondu `Success` ; `am start` a démarré `dev.atlas.coach/.app.MainActivity`. |
| Processus et Activity visibles | PASS | PID `2252` ; `dumpsys activity activities` indique `mResumedActivity: dev.atlas.coach/.app.MainActivity`. |
| Recherche logcat au démarrage | PASS | Après remise à zéro du logcat et redémarrage, filtre `FATAL EXCEPTION|ANR in` sans résultat. |
| Capture VM-0 | PASS | Capture réelle de l'écran de l'émulateur, consultable ci-dessous. |
| `:app:connectedDebugAndroidTest` | PASS | 1 test terminé sur `Pixel_9_Pro_XL_API_30_2(AVD) - 11`, `BUILD SUCCESSFUL in 16s`. Le smoke test vérifie que « ATLAS » est affiché. |
| Candidat HOME sans remplacement du launcher | PASS | La requête HOME liste Quickstep, `dev.atlas.coach/.app.AtlasHomeActivity` et FallbackHome. La résolution renvoie `android/com.android.internal.app.ResolverActivity`, pas ATLAS. |

Le premier essai de `connectedDebugAndroidTest` a échoué à compiler à cause d'un import `assertExists` indisponible. L'assertion a été remplacée par `assertIsDisplayed` et le test instrumenté a ensuite passé.

## Commandes exécutées

Depuis la racine du dépôt, génération du wrapper avec Gradle 8.9 déjà présent dans le cache :

```sh
$HOME/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle \
  -p android wrapper --gradle-version 8.9 --distribution-type bin
```

Build debug et tests :

```sh
./scripts/build-android.sh
(cd android && ./gradlew :app:testDebugUnitTest)
(cd android && ./gradlew :app:connectedDebugAndroidTest)
```

La première exécution a terminé avec `BUILD SUCCESSFUL in 39s` et a affiché :

```text
APK: $HOME/perso/AndroCoach/atlas-preparation/android/app/build/outputs/apk/debug/app-debug.apk
```

Installation, démarrage et capture :

```sh
$HOME/Android/Sdk/platform-tools/adb -s emulator-5554 install -r \
  $HOME/perso/AndroCoach/atlas-preparation/android/app/build/outputs/apk/debug/app-debug.apk
$HOME/Android/Sdk/platform-tools/adb -s emulator-5554 shell am start \
  -n dev.atlas.coach/dev.atlas.coach.app.MainActivity
$HOME/Android/Sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p \
  > docs/captures/vm0-accueil.png
```

Contrôle isolé du lancement : `adb -s emulator-5554 logcat -c`, `adb -s emulator-5554 shell am force-stop dev.atlas.coach`, redémarrage par `am start`, puis attente de 2 secondes. `pidof dev.atlas.coach` a répondu `2252`, et le filtre `adb -s emulator-5554 logcat -d -v brief | rg -i 'FATAL EXCEPTION|ANR in'` n'a rien retourné.

Vérification HOME :

```sh
adb -s emulator-5554 shell cmd package query-activities --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME
adb -s emulator-5554 shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME
```

La liste a contenu `dev.atlas.coach/.app.AtlasHomeActivity`. La résolution a renvoyé `android/com.android.internal.app.ResolverActivity`, donc aucun remplacement par ATLAS n'a été effectué.

## Artefacts et limites

- APK debug : [`android/app/build/outputs/apk/debug/app-debug.apk`](../android/app/build/outputs/apk/debug/app-debug.apk).
- Capture : [`docs/captures/vm0-accueil.png`](captures/vm0-accueil.png), PNG 1344 × 2992 capturé par `adb exec-out screencap -p`.
- L'accueil ne fait qu'afficher ATLAS et un bouton de démonstration local. Aucun serveur, accès réseau, secret ou permission sensible n'est requis.
- L'alias HOME est disponible comme candidat Android ; aucun réglage système n'a été modifié pour en faire le launcher par défaut.
- Cette validation ne couvre pas les fonctions prévues pour VM-1 et ne constitue pas un test sur Pixel physique.

# Validation Android VM-1

## Environnement et artefacts

- Hôte Linux x86_64, JDK 21.0.12.1, SDK `$HOME/Android/Sdk`.
- Émulateur utilisé : `emulator-5554`, Android 11 / API 30, ABI x86_64 (`Pixel_9_Pro_XL_API_30_2`). Aucun téléphone Pixel réel n'a été utilisé et aucun AVD n'a été créé ou effacé.
- Application `dev.atlas.coach`, module unique `:app`, minSdk 26, compile/target SDK 35.
- APK debug final : [`android/app/build/outputs/apk/debug/app-debug.apk`](../android/app/build/outputs/apk/debug/app-debug.apk), 9 627 215 octets, SHA-256 `a0f70653348711034c72101ed6fd9909c5692b89bd386e02a9d3632025cd1ba4`.
- Les PNG ci-dessous proviennent d'une capture ADB réelle (1344 × 2992), puis ont été lus pour vérifier que l'écran n'était ni vide ni illisible.

## Build, tests et analyse statique

Commande finale depuis `android/` :

```sh
./gradlew :app:testDebugUnitTest --rerun-tasks :app:assembleDebug :app:lintDebug
```

Sortie finale : `BUILD SUCCESSFUL in 13s`. Les rapports XML contiennent **30 tests unitaires, 0 échec, 0 erreur, 0 test ignoré**. Ils couvrent aussi les maps de persistance/restauration, les transitions Focus, les règles, les habitudes, les réponses coach et les ouvertures de contenu. Deux avertissements de cast non vérifié sont émis dans `PersistedRepositoriesTest`.

Test instrumenté Compose sur l'émulateur :

```sh
./gradlew :app:connectedDebugAndroidTest
```

Sortie finale : `BUILD SUCCESSFUL in 13s`, 1 test exécuté sur `Pixel_9_Pro_XL_API_30_2(AVD) - 11`. Ce test vérifie les parcours directive→focus, pause et recréation d'Activity, la restauration de l'habitude après recréation, ajout/cochage/annulation/export d'habitude, confirmation explicite du lien, quatre événements simulés→une proposition, doublon, cooldown, heures calmes et pause, réponses et journal du coach, puis ouverture d'un contenu par l'adaptateur Android.

`lintDebug` réussit avec un groupe d'avertissements `GradleDependency` (5 détections liées aux versions plus récentes proposées pour Compose BOM, Activity Compose, AndroidX Test JUnit et Runner). Les versions S0 validées ont été conservées pour ce jalon. Aucun avertissement lint bloquant n'est rapporté.

Pendant l'ajout d'une assertion de persistance UI, deux exécutions intermédiaires du test instrumenté ont échoué parce qu'il cherchait Habitudes alors qu'une session active restaurait l'écran Focus. Le parcours a été corrigé pour revenir à Accueil puis ouvrir Habitudes; la sortie finale ci-dessus passe et confirme que l'habitude est toujours présente après recréation. L'AVD s'est déconnecté d'ADB après le second échec. Le même appareil virtuel a été redémarré, sans création ni wipe :

```sh
$HOME/Android/Sdk/emulator/emulator -avd Pixel_9_Pro_XL_API_30_2 -port 5554 -no-window -no-audio
```

Après réinstallation, `KEYCODE_HOME` a ouvert `ResolverActivity`, comme au début; aucun choix HOME n'a été effectué.

Le manifeste final ne déclare aucune permission d'observation, réseau, accessibilité ou autre permission runtime. `aapt dump permissions` affiche uniquement la permission AndroidX auto-générée `dev.atlas.coach.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

## Verdicts VM-1 ordonnés

| Cas | Verdict | Preuve et limite |
|---|---|---|
| 1. Accueil | PASS | Accueil lisible, directive « Lis/avance 10 min », justification, raccourcis et pause d'urgence fixe visibles dans [vm1-accueil.png](captures/vm1-accueil.png). Capture finale relue. Les principaux boutons ont des descriptions sémantiques et des cibles d'au moins 48 dp dans le code Compose. |
| 2. Directive et journal | PASS | Test Compose : Commencer démarre un focus associé à la directive; Discuter, Alternative, Reporter et Pause affichent chacun un résultat. Les cinq libellés de réponse sont visibles dans Coach · journal. |
| 3. Focus | PASS | Presets 25/5, 10/2 et démo 20 s présents; tests unitaires valident les presets, pause/reprise/annulation/fin et transitions. Le test Compose recrée l'Activity en pause puis reprend. La vérification ADB force-stop/relancement a restauré une session 25/5 et son lien; le temps affiché est passé de 24:56 à 24:53. Capture [vm1-focus.png](captures/vm1-focus.png). |
| 4. Habitudes | PASS | Test Compose : création, validation du jour, annulation, vues 7/30 jours, export et lien à une session après choix puis confirmation distincte. Capture [vm1-habitudes.png](captures/vm1-habitudes.png). |
| 5. Règles | PASS | Test Compose : 4 injections rapprochées → exactement 1 proposition; doublon ignoré, cooldown de 15 min, heures calmes et pause d'urgence vérifiés. Le libellé précise que ce sont des événements simulés, pas une observation réelle. Capture [vm1-simulateur.png](captures/vm1-simulateur.png). |
| 6. Bibliothèque / YouTube | PASS (ouverture) · NON TESTÉ (absence de client) | Le parcours instrumenté envoie un vrai `ACTION_VIEW` par `AndroidContentOpener` sans crash; le catalogue expose trois liens démo. L'AVD dispose d'un gestionnaire compatible (WebView Browser Tester), donc le sous-cas « aucune application compatible » n'a pas pu être forcé. Le gestionnaire absent est traité par un résultat utilisateur `NO_COMPATIBLE_APP` dans l'adaptateur. |
| 7. Mode avion / permissions | PASS | `cmd connectivity airplane-mode enable` a donné `airplane_mode_on=1`. Le timer a continué de 00:19 à 00:07 et l'écran Habitudes restait accessible; retour vérifié à `airplane_mode_on=0`. Aucun consentement ou identifiant n'est requis et aucune permission runtime n'est déclarée. |
| 8. Sortie et pause de secours | PASS (retour/urgence) · NON TESTÉ (sélection HOME) | Pause d'urgence accessible depuis tous les écrans, testée avec le simulateur. `KEYCODE_HOME` et le bouton de sortie renvoient à `ResolverActivity`; après l'installation finale, la résolution reste `android/com.android.internal.app.ResolverActivity`, sans launcher choisi par défaut. Aucun choix de launcher ni demande RoleManager n'a été effectué. Capture Paramètres : [vm1-parametres.png](captures/vm1-parametres.png). |
| 9. Preuves et redémarrages | PASS | APK, 30 tests unitaires, test Compose connecté, cinq captures et contrôle logcat consignés ici. Après nettoyage du logcat et lancement final, `FATAL EXCEPTION|ANR in` n'a produit aucune ligne. Recréation Activity vérifiée par test Compose; kill/relaunch par `am force-stop` vérifié manuellement. |

## Parcours ADB réellement exécutés

Les commandes ci-dessous sont lancées depuis la racine du dépôt. L'AVD `Pixel_9_Pro_XL_API_30_2` était actif au départ. Après une déconnexion ADB en cours de test, le même AVD a été redémarré sans création ni wipe; il s'est remis en ligne sous `emulator-5554`.

```sh
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell pm clear dev.atlas.coach
adb -s emulator-5554 shell am start -n dev.atlas.coach/dev.atlas.coach.app.MainActivity
adb -s emulator-5554 exec-out screencap -p > docs/captures/vm1-accueil.png
```

L'installation a répondu `Success`; l'Activity reprise était `dev.atlas.coach/.app.MainActivity`. La session a ensuite été lancée depuis l'interface. Avant arrêt forcé, l'écran Focus montrait `24:56` et `Session liée à Lis/avance 10 min`. Après :

```sh
adb -s emulator-5554 shell am force-stop dev.atlas.coach
adb -s emulator-5554 shell am start -n dev.atlas.coach/dev.atlas.coach.app.MainActivity
```

l'écran Focus a été restauré avec `24:53` et le même lien de directive.

Mode avion :

```sh
adb -s emulator-5554 shell cmd connectivity airplane-mode enable
adb -s emulator-5554 shell settings get global airplane_mode_on
# vérification visuelle du timer et des habitudes dans l'application
adb -s emulator-5554 shell cmd connectivity airplane-mode disable
adb -s emulator-5554 shell settings get global airplane_mode_on
```

Les deux lectures ont répondu `1`, puis `0`; l'état réseau a été restauré.

Sortie HOME :

```sh
adb -s emulator-5554 shell input keyevent KEYCODE_HOME
adb -s emulator-5554 shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME
```

Après installation finale, le résultat est resté `android/com.android.internal.app.ResolverActivity`. Aucun launcher n'a été choisi et le rôle HOME n'a pas été demandé. Après la touche HOME, l'Activity reprise était `ResolverActivity`; `airplane_mode_on` est resté `0`.

## Captures

- [Accueil](captures/vm1-accueil.png)
- [Focus](captures/vm1-focus.png)
- [Habitudes](captures/vm1-habitudes.png)
- [Simulateur](captures/vm1-simulateur.png)
- [Paramètres](captures/vm1-parametres.png)

Limites restantes : essai uniquement sur AVD Android 11, pas sur Pixel réel; chemin sans client `ACTION_VIEW` non déclenché sur cet AVD; pas de changement/sélection du rôle HOME; les tests Compose utilisent un unique test instrumenté regroupant les parcours. L'absence d'implémentation d'observation réelle est voulue pour VM-1 et le consentement associé reste sans effet de collecte.

## Après reprise S6

Validation effectuée le 2026-10-18 sur l'AVD existant `emulator-5554`, Android 11 / API 30 (`Pixel_9_Pro_XL_API_30_2`). Aucun appareil Pixel réel n'a été utilisé. L'APK installée est `android/app/build/outputs/apk/debug/app-debug.apk`, 9 742 147 octets, SHA-256 `687e15efe460d69cd12c0531944622bc3eb937d9cb4578f6869568998764a88d`.

### Build, tests et lint

Depuis `android/`, la commande demandée a été exécutée après les dernières modifications :

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks
```

Résultat : **PASS**, `BUILD SUCCESSFUL in 24s`. Les rapports XML enregistrent **51 tests JVM**, 0 échec, 0 erreur, 0 ignoré. Les tests ajoutés couvrent les conversions d'état Pomodoro/cycles, la force et l'export CSV Loop, et les fenêtres/consentements des routines. Lint a terminé avec succès; deux avertissements Kotlin de cast non vérifié subsistent dans `PersistedRepositoriesTest.kt` (lignes 35–36).

Sur le même `emulator-5554`, la suite instrumentée a été exécutée :

```sh
./gradlew :app:connectedDebugAndroidTest
```

Résultat : **PASS**, `Finished 10 tests on Pixel_9_Pro_XL_API_30_2(AVD) - 11`, `BUILD SUCCESSFUL in 50s`, 10 réussites, 0 échec, 0 ignoré. Les dix scénarios comprennent les parcours Focus/habitudes/routines/licences et les tests d'instrumentation S5 conservés.

### Installation, restauration et sortie HOME

L'APK finale a été installée avec `adb -s emulator-5554 install -r ...` (`Success`), puis l'état privé de l'app a été nettoyé avant la démo avec `adb -s emulator-5554 shell pm clear dev.atlas.coach` (`Success`). Une habitude générique `Lire` a été créée et validée; elle ne contient aucune donnée personnelle. Le flag du launcher facultatif a été activé seulement sur l'AVD pour en parcourir l'écran; `Calendar` y a été épinglé. Dans l'installation, l'app list ne montre que les activités déclarées `MAIN` + `LAUNCHER`.

Une session liée à `Lis/avance 10 min` a été démarrée. Avant arrêt forcé, UIAutomator lisait `Concentration · En cours`, `Cycle 1 / 4` et `Session liée à : Lis/avance 10 min`. Les commandes suivantes ont ensuite été réellement lancées :

```sh
adb -s emulator-5554 shell am force-stop dev.atlas.coach
adb -s emulator-5554 shell am start -n dev.atlas.coach/dev.atlas.coach.app.MainActivity
```

Après relancement, UIAutomator lisait toujours ces trois libellés et la session était active. La capture de restauration est [vm1c-focus-restaure.png](captures/vm1c-focus-restaure.png).

L'installation du candidat HOME ATLAS a fait apparaître le sélecteur Android entre `Use Quickstep as Home` et `ATLAS (accueil facultatif)`. Le launcher par défaut observé avant l'installation était `com.android.launcher3/.uioverrides.QuickstepLauncher`; `Quickstep` a été remis en choix permanent dans le sélecteur pour restaurer ce défaut. Après `KEYCODE_HOME`, `mResumedActivity` désignait `com.android.launcher3/.uioverrides.QuickstepLauncher`, et `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME` le renvoyait avec `isDefault=true`. ATLAS ne choisit ni ne modifie automatiquement ce rôle.

### Permissions, journaux et captures

`aapt dump permissions android/app/build/outputs/apk/debug/app-debug.apk` ne liste que la permission générée `dev.atlas.coach.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Aucun `QUERY_ALL_PACKAGES`, AccessibilityService, UsageStats, permission runtime ou service de blocage n'est présent. Le manifeste déclare une requête `<queries>` ciblée `MAIN` + `LAUNCHER`.

Après le parcours, `adb -s emulator-5554 logcat -d -v brief | rg -i 'FATAL EXCEPTION|ANR in'` n'a produit aucune ligne. Les captures `vm1c-*.png` ont été ouvertes et inspectées :

- [Accueil et directive](captures/vm1c-accueil.png)
- [Session Focus active](captures/vm1c-focus.png)
- [Session après force-stop et restauration](captures/vm1c-focus-restaure.png)
- [Habitudes avec historique et force](captures/vm1c-habitudes.png)
- [Accueil launcher facultatif avec directive et épingle](captures/vm1c-launcher.png)

La capability `launcherHome` reste `false` par défaut dans le code; elle a été activée sur l'AVD pour inspecter ce chemin. Le parcours HOME n'a pas remplacé le launcher Android par ATLAS. Limites : validation sur AVD Android 11 seulement; aucun Pixel physique; pas d'observation d'usage, de quotas, de blocage ou d'action automatique; Lawnchair n'a pas été repris.

## Après revue S5

### Environnement et APK finale

- Hôte Linux x86_64, OpenJDK 21.0.12.1, wrapper Gradle 8.9, SDK `$HOME/Android/Sdk`, ADB 34.0.4.
- AVD déjà actif : `emulator-5554`, Android 11 / API 30, ABI x86_64 (`Pixel_9_Pro_XL_API_30_2`). Aucun appareil Pixel réel, aucun AVD créé/effacé/wipé.
- APK finale : [`android/app/build/outputs/apk/debug/app-debug.apk`](../android/app/build/outputs/apk/debug/app-debug.apk), 9 660 087 octets, SHA-256 `24c48f833b15ef66cf2ca95bda92b624a3917e0b2193c973f2b6acecab32ebf2`.
- `adb -s emulator-5554 devices -l` a listé `emulator-5554 device product:sdk_phone_x86_64 model:Android_SDK_built_for_x86_64 device:generic_x86_64`; `getprop ro.build.version.release` a répondu `11`, `getprop ro.build.version.sdk` a répondu `30`.

### Build, tests et lint

Commande exacte depuis `android/` :

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --rerun-tasks
```

Résultat : **PASS**, `BUILD SUCCESSFUL in 13s`, 51 tâches exécutées. Les rapports XML contiennent **44 tests JVM, 0 échec, 0 erreur, 0 ignoré**. Deux avertissements Kotlin de cast non vérifié subsistent dans `PersistedRepositoriesTest`; ils concernent les tests.

Commande connectée :

```sh
./gradlew :app:connectedDebugAndroidTest
```

Résultat : **PASS**, `Finished 6 tests on Pixel_9_Pro_XL_API_30_2(AVD) - 11`, `BUILD SUCCESSFUL in 43s`, 0 échec et 0 ignoré. Les six cas instrumentés sont :

- parcours directive, focus, habitude, simulateur et bibliothèque hors ligne;
- désactivation Focus/Habitudes, pause de session et disparition du bouton de lien;
- démarrage d'une session Focus;
- politique/règles mal formées, fallback et quarantaine sans crash;
- version de schéma inconnue, message et quarantaine;
- checkpoint Focus unique après 20 secondes visibles.

### Réinstallation, redémarrage et captures

Réinstallation et lancement réellement exécutés :

```sh
adb -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell pm clear dev.atlas.coach
adb -s emulator-5554 shell am force-stop dev.atlas.coach
adb -s emulator-5554 shell am start -n dev.atlas.coach/dev.atlas.coach.app.MainActivity
```

ADB a répondu `Success` pour l'installation et le nettoyage, puis a démarré `dev.atlas.coach/.app.MainActivity`. L'Accueil et le Focus ont été capturés depuis l'écran réel de l'AVD et relus; ces trois PNG mesurent 1344 × 2992 :

- [Accueil final](captures/vm1b-accueil.png)
- [Focus final](captures/vm1b-focus.png)
- [Message de corruption et quarantaine](captures/vm1b-corruption.png)

Pour la restauration, UIAutomator a lu `Concentration · En cours`, `24:58` et `Session liée à : Lis/avance 10 min`. Après `am force-stop` puis le même `am start`, il lisait toujours le statut et le lien, avec `24:54`. L'horloge a donc avancé pendant l'arrêt, sans perdre la session.

### Corruption réelle des SharedPreferences

Après arrêt forcé, un XML de préférences valide contenant un blob JSON invalide a été écrit dans l'espace debuggable de l'application :

```sh
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" \
  '<map>' '    <string name="state_v1">{broken</string>' '</map>' \
  | adb -s emulator-5554 shell run-as dev.atlas.coach tee shared_prefs/atlas_local_state.xml
adb -s emulator-5554 logcat -c
adb -s emulator-5554 shell am start -n dev.atlas.coach/dev.atlas.coach.app.MainActivity
```

Après le lancement, l'application était active (PID `5633`) et l'UIAutomator a lu le message : « Certaines données locales ont été écartées (JSON local invalide). Une copie brute a été mise en quarantaine; les valeurs de secours sont actives. » La capture [vm1b-corruption.png](captures/vm1b-corruption.png) montre le message. `run-as ... cat shared_prefs/atlas_local_state.xml` a montré `{broken` sous `state_quarantine_1792357489170_3e298f88-d9e5-419e-a54d-1f74746637b4` et `JSON local invalide` sous la clé de raison correspondante. Le blob n'a donc pas été confondu avec une première installation ni perdu avant la quarantaine.

Après ce lancement, la commande `adb -s emulator-5554 logcat -d -v brief | rg -i 'FATAL EXCEPTION|ANR in'` n'a renvoyé aucune ligne (`NO_FATAL_OR_ANR`).

### Sortie HOME et ouverture sans handler

Le bouton de l'écran Paramètres a été activé dans l'UIAutomator (nœud `Choisir l'application d'accueil par défaut`), puis l'action a aussi été lancée directement :

```sh
adb -s emulator-5554 shell input tap 672 2622
adb -s emulator-5554 shell dumpsys activity activities | rg 'mResumedActivity'
adb -s emulator-5554 shell am start -a android.settings.HOME_SETTINGS
adb -s emulator-5554 shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME
```

a placé `com.android.permissioncontroller/.role.ui.DefaultAppActivity` au premier plan. La résolution HOME après cette ouverture est restée `android/com.android.internal.app.ResolverActivity`; aucun launcher par défaut n'a été modifié. Le sous-cas consistant à sélectionner ATLAS comme accueil puis à revenir n'a pas été exécuté afin de ne pas changer le défaut HOME de l'AVD.

Comparaison réelle côté résolveur Android :

```sh
adb -s emulator-5554 shell am start -a android.intent.action.VIEW \
  -d 'atlas-no-handler://example.invalid/path'
```

Android a répondu `Activity not started, unable to resolve Intent`. La passerelle factice JVM valide aussi le retour `NO_COMPATIBLE_APP` avant chooser. Le catalogue ATLAS ne permet que des URL HTTPS, et cet AVD a un navigateur compatible; le cas **absence de handler HTTPS dans l'application reste NON TESTÉ** sur l'émulateur.

### Verdicts et limites après S5

- **P0 restauration** : vérifié et corrigé; le décodage isolé par sous-état, la version inconnue, les `eventId` manquants et la quarantaine sont couverts par tests JVM/instrumentés.
- **P1 JSON corrompu** : repro réelle PASS; message utilisateur non bloquant, copie brute confirmée dans les préférences.
- **P1 flags** : tests JVM/Compose PASS; Focus est mis en pause à la désactivation, aucune reprise/tick ne passe; Habitudes masque et refuse la confirmation de lien.
- **P1 timer** : cycle de vie `STARTED`, transition durable et checkpoint toutes les 20 s visibles vérifiés par instrumentation; force-stop/reprise exacts à la seconde observée sur l'AVD.
- **P1 HOME** : les réglages système s'ouvrent; le choix par défaut de l'AVD n'a pas été modifié.
- **P1 ACTION_VIEW** : test de passerelle PASS et comparaison système exécutée; handler HTTPS absent **NON TESTÉ**.
- **P2 rétention/libellés** : bornes de 200 événements d'historique/journal et expiration d'IDs selon fenêtre testées; les statuts/phases et textes visibles sont en français.
- Validation limitée à l'AVD Android 11/API 30; aucun Pixel réel. Le calcul du temps restauré reste plafonné à 24 heures.
