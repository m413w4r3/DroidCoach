# Lot G — interface ATLAS et launcher HOME

Date : 2026-10-19  
État : **PASS**, avec limites consignées ci-dessous.  
Commit d’implémentation : `70b54729c2523cd3aea051bd8bc116b81bb9d1aa`  
APK debug : `android/app/build/outputs/apk/debug/app-debug.apk`  
SHA-256 APK : `ab9b1ccf61c5951365a564d8515add9ce12a0b31ab23738693deede3f9132efb`

## Résultats par critère

| Critère | Résultat | Éléments vérifiés |
|---|---|---|
| 1. Système de design | **PASS** | Thèmes clair/sombre, typographie et espacements communs; `AtlasScreenFrame` gère les insets, le défilement et la navigation basse. Les contrôles communs ont des zones tactiles d’au moins 48 dp, les lignes de réglage 56 dp. Paires de texte principales calculées entre 6,52:1 et 17,23:1. Tests fontScale 1,3/2,0; vérification visuelle en paysage. |
| 2. Accueil coach | **PASS** | Directive, action principale unique, état Focus, habitudes du jour et favoris. Négociation secondaire; navigation « Plus » pour bibliothèque, journal et réglages; retour cohérent. Pause d’urgence discrète dans la barre supérieure. |
| 3. HOME launcher | **PASS** | Liste ciblée `MAIN`/`LAUNCHER`, recherche sans accents, tri alphabétique, favoris persistants, ouverture explicite, horloge à la minute, glissement vertical vers la liste. Activation du rôle seulement après action explicite; rôle relu à la reprise; sortie vers Pixel Launcher testée. Pas de `QUERY_ALL_PACKAGES`. |
| 4. Réglages et capacités | **PASS** | Parcours quotidien séparé du diagnostic avancé; états actif/simulé/autorisé/impossible; capacités indépendantes; aucun réglage placeholder ajouté. Sync reste local/offline tant qu’il n’est pas activé et consenti. |
| 5. Découpage | **PASS** | `AtlasApp.kt` passe de 844 à 200 lignes; écrans extraits dans `AtlasScreens.kt`. `AtlasNavigationState`, `AtlasLauncherState` et `AtlasScreenId` sont séparés et couverts par tests JVM. `AtlasController.kt` passe de 1497 à 1472 lignes; il reste le coordinateur des moteurs existants, conservés conformément au périmètre du lot. |
| 6. Validation | **PASS** | JVM, tests instrumentés/Compose, `lintDebug`, assemblage et suite connectée passent sur l’AVD. Détails et commandes ci-dessous. |
| 7. Captures | **PASS** | 35 captures avant/après sont enregistrées et inspectées; l’unique défaut visuel qui reste notable est décrit dans « Limites ». |
| 8. Rapport et provenance | **PASS** | Ce rapport, les décisions et la provenance Olauncher héritée sont documentés. Aucun code ou asset Olauncher nouveau n’a été copié par G. |

## Validation

Environnement : JDK 21, AGP 9.4.1, Gradle 9.6.0, compileSdk 37.2, targetSdk 35. AVD Android 17, Pixel 9 Pro XL, `emulator-5556`. Aucun appareil Pixel réel n’a été utilisé.

Commande complète exécutée :

```sh
cd android
export ANDROID_SERIAL=emulator-5556
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest
```

Résultats des XML Gradle :

- JVM : **108 tests, 0 échec, 0 erreur**.
- Android connecté : **30 tests, 0 échec, 0 erreur**.
- Lint : **0 erreur, 37 avertissements**. Les avertissements couvrent notamment `OldTargetApi` (targetSdk 35), versions dépendances/outils, `ObsoleteSdkInt`, `UseKtx` et `SetTextI18n`.
- `git diff --check` : succès.
- Après l’ajout du commentaire de provenance, `./gradlew :app:assembleDebug` a été relancé : `BUILD SUCCESSFUL`.

La suite Android inclut les tests Compose de navigation, accueil et fontScale, les captures de chaque écran, le tiroir launcher et l’ouverture réelle de Settings. Deux bancs optionnels H2 sont présents dans le décompte connecté : sans argument `scenario`, le test backend journalise un no-op; sans `capture_state`, le test de capture Sync journalise aussi un no-op. Aucun backend distant, identifiant ou secret n’était configuré et aucun appel externe n’a été fait.

### Parcours HOME sur l’AVD

1. Installation de l’APK debug, activation de la capacité facultative depuis l’UI, puis sélection explicite de « Proposer ATLAS comme accueil ».
2. Le sélecteur système RoleManager est apparu. Après sélection d’ATLAS, `cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME` a résolu `dev.atlas.coach/.app.AtlasHomeActivity`.
3. Ouverture de la liste, recherche et épinglage de Settings; le favori a lancé `com.android.settings/.Settings`. Retour a ramené à ATLAS.
4. « Ouvrir le launcher système » a affiché Pixel Launcher. Le rôle par défaut a ensuite été explicitement restauré et vérifié :

```sh
adb shell cmd package set-home-activity --user 0 com.google.android.apps.nexuslauncher/com.google.android.apps.nexuslauncher.NexusLauncherActivity
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

La résolution finale indique `com.google.android.apps.nexuslauncher.NexusLauncherActivity`. L’APK G a été réinstallé ensuite sans modifier ce rôle.

Pour le contrôle paysage, la taille AVD a temporairement été réglée sur `2992x1344`, puis rétablie sur `1344x2992`; HOME système est resté Pixel Launcher. Le contrôle paysager porte sur l’accueil principal, pas sur chaque route secondaire.

## Captures inspectées

Chaque image ci-dessous se trouve dans `docs/v4/captures` et a été ouverte pour contrôle visuel.

**Avant** — accueil coach, launcher précédent, états Focus, habitudes, routines, observation, intervention, bibliothèque, réglages et sync :

- [Accueil coach](../captures/G-before-home.png), [HOME launcher précédent](../captures/G-before-launcher-home.png)
- Focus [actif](../captures/G-before-focus-active.png), [pause](../captures/G-before-focus-pause.png), [terminé](../captures/G-before-focus-finished.png)
- [Habitudes](../captures/G-before-habits.png), [routines](../captures/G-before-routines.png), [observation/permissions](../captures/G-before-observation-permissions.png), [intervention](../captures/G-before-intervention.png), [bibliothèque](../captures/G-before-library.png)
- Réglages [quotidiens](../captures/G-before-settings-daily.png), [diagnostic](../captures/G-before-settings-diagnostic.png), [sync offline](../captures/G-before-sync-offline.png)

**Après** :

- Accueil coach [clair](../captures/G-home-light.png), [sombre](../captures/G-home-dark.png), [paysage](../captures/G-home-landscape.png) et [fontScale 2,0](../captures/G-font-2.0.png)
- Focus [actif](../captures/G-focus-active.png), [pause](../captures/G-focus-pause.png), [terminé](../captures/G-focus-finished.png)
- [Habitudes](../captures/G-habits.png), [routines](../captures/G-routines.png), [observation/permissions](../captures/G-observation-permissions.png), [intervention](../captures/G-intervention.png), [bibliothèque](../captures/G-library.png)
- Réglages [quotidiens](../captures/G-settings-daily.png), [diagnostic avancé](../captures/G-settings-diagnostic.png), [sync offline](../captures/G-sync-offline.png)
- Launcher [accueil](../captures/G-launcher-home.png), [liste filtrable](../captures/G-launcher-apps.png), [dialogue de demande de rôle](../captures/G-launcher-role-request.png), [ATLAS sélectionné](../captures/G-launcher-role-selected.png), [accueil HOME réel](../captures/G-launcher-home-real.png), [liste réelle](../captures/G-launcher-apps-real.png), [sortie vers Pixel Launcher](../captures/G-launcher-system-exit.png)

La capture avant du launcher a été produite depuis le commit H2 `3a95925` dans un worktree temporaire; l’activité HOME a été lancée explicitement, sans changer le launcher par défaut.

## Limites constatées

- La liste d’applications et d’autorisations de l’écran Observation reste dense et longue; elle défile, mais sa première vue est moins hiérarchisée que le reste de l’interface.
- Le dialogue RoleManager est fourni par Android et apparaît en anglais sur cet AVD configuré ainsi; les écrans ATLAS sont en français.
- Le HOME a été vérifié sur AVD Android 17 uniquement. Aucun comportement sur téléphone Pixel réel n’est revendiqué.
- Le scénario backend en ligne n’a pas été exécuté; aucune API de service ou donnée d’authentification n’était configurée.
- `AtlasController.kt` reste un coordinateur conséquent (1472 lignes) afin de ne pas réécrire les moteurs intégrés. Les nouveaux state holders UI extraits sont testables séparément.

## Provenance et repli de commit

Le HOME reste une interface Compose ATLAS. Le fichier est historiquement dérivé d’Olauncher `ui/HomeFragment.kt`, commit `f8e8d6497c457734c7a3171d36374157678e1f33`, licence GPLv3; la refonte G n’ajoute pas de source tierce. Les références de fichiers et licence sont maintenues dans [`THIRD_PARTY_NOTICES.md`](../../../THIRD_PARTY_NOTICES.md) et [`AUDIT_CIBLE.md`](../../AUDIT_CIBLE.md).

L’outil Nimbalyst `developer_git_commit_proposal` a refusé les chemins d’implémentation et de rapport en les classant « hors des dépôts de la session ». Le repli CLI autorisé a créé le commit d’implémentation ci-dessus avec les **62 chemins explicites** du lot G; le rapport fait l’objet d’un commit documentaire séparé par son chemin explicite. `README_APPLIQUER.md` et les deux logs H2 préexistants ont été exclus.
