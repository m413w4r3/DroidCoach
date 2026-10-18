# Lot S3 — Intégration app Compose + persistance + UX Olauncher → VM-1

Date : 2026-10-18  
État : livré et validé sur AVD Android 11/API 30; limites de recette détaillées ci-dessous.

## Résultat

L'application Compose présente une expérience locale hors ligne autour d'une directive du jour, d'un focus, d'habitudes, d'un journal de coach, d'une bibliothèque démo et d'un simulateur de friction. Elle ne requiert ni serveur, ni IA, ni compte, ni clé, ni permission système runtime. Aucun code Olauncher n'a été copié : l'interface reprend uniquement les intentions visuelles d'un accueil textuel, sombre et dépouillé.

L'état conservé localement comprend les sessions Focus, habitudes et validations, la directive et le journal, les événements simulés et l'état des règles, les paramètres de politique, les capacités indépendantes et le dernier résultat visible. Les réponses de directive affichent leur résultat et sont consignées. `Commencer` lance un focus rattaché à l'ID de directive. Le rattachement d'une session terminée à une habitude passe par deux confirmations UI puis `HabitLinkPolicy.confirmLink`.

Le timer n'utilise ni service d'avant-plan, ni notification, ni tâche en arrière-plan : l'interface visible actualise le compte à rebours chaque seconde. Une session en cours est persistée et restaurée après recréation d'Activity et relancement du processus.

## Architecture et choix

- Un seul module Gradle `:app`, navigation Compose simple et DI manuelle dans `AtlasController`.
- `LocalStateStore` encode un instantané JSON versionné. `AndroidStateBackend` le stocke dans des préférences privées, synchrones et locales via `commit()`. Cette solution reste adaptée au petit volume VM-1 sans Room, DataStore ni service. `org.json` est apporté par Android à l'exécution; sa dépendance JVM est limitée à `testImplementation`.
- `AtlasController` restaure l'état puis compose avec les implémentations synchrones S1/S2. `System.currentTimeMillis()` fournit les horodatages UTC et `SystemClock.elapsedRealtime()` le temps monotone. Le moteur reconstitue le temps écoulé au redémarrage avec la borne déjà testée côté Focus.
- Le mode démo Focus dure 20 secondes. Les presets de production visibles sont 25/5 et 10/2. Les événements de friction sont toujours annoncés comme simulés; aucune observation réelle n'est intégrée. Le flag de consentement d'observation reste indépendant, désactivé par défaut et n'active aucune collecte dans VM-1.
- Les capacités Coach, Focus, Habitudes, Bibliothèque et Règles simulées sont des flags séparés, sauvegardés localement. Le bouton de pause d'urgence reste visible en bas de tous les écrans.
- Le manifeste interdit les sauvegardes Android de l'app et inclut une règle d'exclusion de transfert. L'alias HOME est facultatif. `RoleManager.ROLE_HOME` n'est invoqué que depuis son action explicite; la sortie standard lance `ACTION_MAIN` + `CATEGORY_HOME`.
- La bibliothèque ouvre des URL démo au moyen de `AndroidContentOpener` et `ACTION_VIEW`; une absence d'application compatible renvoie un résultat géré plutôt qu'un crash.

Les détails des décisions automatiques de stockage, horloge, dépendances, flags et HOME sont consignés dans [DECISIONS_AUTOMATIQUES.md](../DECISIONS_AUTOMATIQUES.md).

## Fichiers concernés

- App et interface : `android/app/src/main/java/dev/atlas/coach/app/` (Activity, contrôleur, navigation et écrans Compose, thème), manifest, icône vectorielle et règles d'exclusion de sauvegarde.
- Persistance : `android/app/src/main/java/dev/atlas/coach/data/` (`LocalStateStore`, backend Android, conversion des maps persistées).
- Ajustements ciblés à S1/S2 nécessaires à la restauration : `habits/Habits.kt` ajoute la restauration des maps; `coach/Coach.kt` ajoute l'instantané et la restauration idempotente du journal. Aucun autre code domaine S1/S2 n'a été modifié.
- Tests : `LocalStateStoreTest`, `PersistedRepositoriesTest`, test Compose `AtlasLaunchTest`, et adaptations Gradle de dépendances d'instrumentation/JSON test.
- Livraison : README (section VM-1), `docs/DECISIONS_AUTOMATIQUES.md`, `docs/VALIDATION_VM.md`, cinq captures sous `docs/captures/`.

## Vérification exécutée

Environnement : JDK 21, Gradle wrapper 8.9, AGP 8.7.3, Kotlin 2.0.21, AVD déjà actif `emulator-5554` Android 11/API 30. Aucun Pixel physique, aucun AVD créé ou effacé.

Depuis `android/` :

```sh
./gradlew :app:testDebugUnitTest --rerun-tasks :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Résultats finaux réellement obtenus :

- Build + unit tests + lint : `BUILD SUCCESSFUL in 13s`; 30 tests, zéro échec/erreur/ignoré.
- Instrumentation Compose : `BUILD SUCCESSFUL in 13s`; un parcours instrumenté exécuté sur `Pixel_9_Pro_XL_API_30_2(AVD) - 11`.
- Lint réussit avec cinq détections dans un groupe `GradleDependency` suggérant des versions plus récentes (Compose BOM, Activity Compose, AndroidX Test JUnit et Runner). Les versions S0 prévalidées ont été conservées. Deux avertissements de casts non vérifiés restent dans le test JVM `PersistedRepositoriesTest`.
- L'APK debug final est `android/app/build/outputs/apk/debug/app-debug.apk` (9 627 215 octets; SHA-256 `a0f70653348711034c72101ed6fd9909c5692b89bd386e02a9d3632025cd1ba4`).
- L'installation ADB a répondu `Success`, l'Activity a démarré, et le filtre logcat `FATAL EXCEPTION|ANR in` n'a retourné aucune ligne après lancement final.
- Les essais manuels ont inclus force-stop/relance avec Focus restauré (24:56 → 24:53, même directive), mode avion avec minuterie et habitudes disponibles, pause d'urgence/simulateur, et touche HOME. L'état mode avion a été restauré à `0`.
- Les captures réelles de l'AVD ont été lues et vérifiées : [accueil](../captures/vm1-accueil.png), [focus](../captures/vm1-focus.png), [habitudes](../captures/vm1-habitudes.png), [simulateur](../captures/vm1-simulateur.png), [paramètres](../captures/vm1-parametres.png).
- Une assertion de persistance ajoutée au test Compose a d'abord cherché Habitudes alors qu'une session active restaurait l'écran Focus; après correction de la navigation de test, l'instrumentation repasse. L'AVD s'est déconnecté d'ADB pendant ces essais intermédiaires et a été relancé comme le même AVD, sans wipe ni création.

La matrice complète PASS/FAIL/NON TESTÉ et les commandes ADB sont dans [VALIDATION_VM.md](../VALIDATION_VM.md).

## Périmètre extérieur et limites

- Aucun fichier hors du périmètre S3 n'a été modifié. `backend/`, `contracts/`, `prompts/`, `references/` et `docs/00–09` restent intacts.
- L'AVD possède un gestionnaire `ACTION_VIEW` compatible (WebView Browser Tester); le cas sans application compatible est donc **NON TESTÉ**, bien que l'adaptateur gère l'exception.
- Après l'installation finale, la résolution HOME renvoie `android/com.android.internal.app.ResolverActivity`, comme au départ. Aucun choix ni remplacement de launcher n'a été fait; le prompt RoleManager n'a pas été ouvert.
- La recette ne démontre pas le comportement sur matériel Pixel réel. Aucun test d'observation réelle des applications n'est implémenté en VM-1; le flag de consentement correspondant n'active rien.
- Le test Compose est un test instrumenté regroupant les principaux parcours; le test timer de longue durée reste naturellement limité au temps écoulé et aux tests unitaires à horloge simulée.

## Travaux restant à faire

- Si nécessaire pour VM-2, valider le rôle HOME avec un testeur qui accepte explicitement la demande, puis restaurer un choix système préexistant sans laisser l'AVD modifié.
- Ajouter un test contrôlé du chemin `NO_COMPATIBLE_APP` si un AVD ou contexte d'instrumentation isolé sans gestionnaire `ACTION_VIEW` est disponible.
- Une éventuelle observation d'apps nécessite un lot d'audit distinct et ne doit pas être présentée comme garantie temps réel par `UsageStatsManager`.
