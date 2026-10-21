# Rapport UI — ATLAS V6.2.3, mission C

## Base et périmètre

- HEAD vérifié avant modification : `aa9b2daf703d7653b33d6433e3d97b915c955731`, conforme à la base attendue. Le dossier `docs/v6-2` était présent; aucun rebase n'a été nécessaire.
- Branche de ce worktree : `worktree/frosty-hollow`. La branche `feat/atlas-v6-2-3` est déjà attachée au worktree `/home/nill/perso/AndroCoach/atlas-preparation` à HEAD `fb8b4452`; je n'ai pas changé de worktree ni modifié cette branche.
- Les captures « avant » ont été prises depuis l'APK construit à cette base, avant les modifications UI, sur l'AVD dédié.
- Aucun fichier métier, [AtlasApp.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasApp.kt), [MainActivity.kt](../../android/app/src/main/java/dev/atlas/coach/app/MainActivity.kt), manifeste, fichier Gradle ou `androidTest` n'a été modifié.

## Direction visuelle retenue

- Fond sombre mat `#11100F`, texte ivoire `#F2EBDD`; fond clair ivoire `#F2EEE4`, texte presque noir. Le bronze atténué `#B49A63` (sombre) / `#705D37` (clair) reste l'accent des actions principales et états actifs.
- Titres éditoriaux en sérif système, texte courant sans sérif système; aucun fichier de police téléchargé.
- Marques de statut réduites à un filet et une légende. Les grands panneaux et cartes conversationnels deviennent du texte séparé par des règles fines.
- Le rameau de HOME est un dessin Canvas original, décoratif pour l'accessibilité, sans image tierce. Aucun code ni asset Olauncher n'a été repris; la référence a porté sur la sobriété et la hiérarchie textuelle.
- HOME conserve l'heure, la date, la prochaine étape, les favoris, le Coach, Pause, les réglages du launcher et l'accès au launcher précédent. En paysage, état et prochaine étape occupent la colonne gauche, les favoris la droite.
- Les appels à l'action HOME « Préparer le focus » et « Ouvrir Focus » gardent une cible de 48 dp, un libellé ivoire et un filet court; leur fond plein a disparu.
- Le pied HOME passe sur deux lignes à 320 dp et à police agrandie jusqu'à 360 dp. Les quatre accès conservent leurs tags et leurs cibles de 48 dp.
- Coach distingue message, provenance locale, proposition et « Action à confirmer ». Accepter, Modifier, Plus tard et Refuser deviennent des actions textuelles séparées par filets; Accepter garde le poids et l'accent les plus forts. La carte de proposition a un contour rectangulaire fin.
- L'en-tête Coach garde Retour et Urgence, ajoute un filet inférieur et emploie un titre sérif. La navigation conserve ses quatre destinations et rôles d'onglet, sur fond transparent, avec un filet supérieur et un court soulignement pour l'onglet actif.
- Mandat et données partagées utilisent les mêmes couleurs et règles; suspension, révocation, pause d'urgence et consentements restent accessibles.
- À 320 dp avec police 2×, l'en-tête compacte certains titres visibles tout en exposant le titre complet comme description d'accessibilité; les commandes Retour et Urgence restent visibles.

## Fichiers concernés

- HOME et écrans : [LauncherHomeScreen.kt](../../android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt), [AgentCoachScreen.kt](../../android/app/src/main/java/dev/atlas/coach/app/AgentCoachScreen.kt), [AtlasScreens.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt), [GoalsPanel.kt](../../android/app/src/main/java/dev/atlas/coach/app/GoalsPanel.kt), [FocusRoutinesScreen.kt](../../android/app/src/main/java/dev/atlas/coach/app/FocusRoutinesScreen.kt), [FocusStatsScreen.kt](../../android/app/src/main/java/dev/atlas/coach/app/FocusStatsScreen.kt), [ObservationScreen.kt](../../android/app/src/main/java/dev/atlas/coach/app/ObservationScreen.kt), [SyncSettingsScreen.kt](../../android/app/src/main/java/dev/atlas/coach/app/SyncSettingsScreen.kt), [LocationPanel.kt](../../android/app/src/main/java/dev/atlas/coach/app/LocationPanel.kt).
- Thème et éléments communs : [AtlasDesign.kt](../../android/app/src/main/java/dev/atlas/coach/app/ui/AtlasDesign.kt), [AtlasTheme.kt](../../android/app/src/main/java/dev/atlas/coach/app/ui/AtlasTheme.kt), [AtlasThemePalette.kt](../../android/app/src/main/java/dev/atlas/coach/app/ui/AtlasThemePalette.kt), [colors.xml](../../android/app/src/main/res/values/colors.xml), [styles.xml](../../android/app/src/main/res/values/styles.xml).
- Test JVM de thème : [AtlasThemePreferenceTest.kt](../../android/app/src/test/java/dev/atlas/coach/app/ui/AtlasThemePreferenceTest.kt).
- Rapport et captures : ce fichier et `docs/v6-2-3/captures/`.

Le changement est de présentation et de composition UI. Les callbacks, règles métier, tags d'automatisation et descriptions accessibles existants ont été conservés.

## Comparaison des captures

Les captures sont des PNG bruts de l'AVD; les états utilisent des données locales synthétiques (« Lis/avance 10 min », message Coach « Focus »), sans donnée personnelle.

| État | Avant | Après |
| --- | --- | --- |
| HOME sombre, 360 dp | [01 avant](captures/avant/01-home-sombre-360dp.png) | [01 après](captures/apres/01-home-sombre-360dp.png) |
| HOME ivoire, 360 dp | [02 avant](captures/avant/02-home-ivoire-360dp.png) | [02 après](captures/apres/02-home-ivoire-360dp.png) |
| HOME avec Focus, 360 dp | [03 avant](captures/avant/03-home-focus-360dp.png) | [03 après](captures/apres/03-home-focus-360dp.png) |
| Tiroir, 360 dp | [04 avant](captures/avant/04-tiroir-360dp.png) | [04 après](captures/apres/04-tiroir-360dp.png) |
| HOME police 2×, 360 dp | [05 avant](captures/avant/05-home-police-2x-360dp.png) | [05 après](captures/apres/05-home-police-2x-360dp.png) |
| HOME, 320 dp | [06 avant](captures/avant/06-home-320dp.png) | [06 après](captures/apres/06-home-320dp.png) |
| HOME police 2×, 320 dp | [07 avant](captures/avant/07-home-police-2x-320dp.png) | [07 après](captures/apres/07-home-police-2x-320dp.png) |
| Coach, 360 dp | [08 avant](captures/avant/08-coach-360dp.png) | [08 après](captures/apres/08-coach-360dp.png) |
| Mandat, 360 dp | [09 avant](captures/avant/09-mandat-360dp.png) | [09 après](captures/apres/09-mandat-360dp.png) |
| Données partagées, 360 dp | [10 avant](captures/avant/10-donnees-partagees-360dp.png) | [10 après](captures/apres/10-donnees-partagees-360dp.png) |

Captures supplémentaires après refonte : [Coach à l'état initial](captures/apres/11-coach-initial-360dp.png), [HOME paysage, 800 × 360 dp](captures/apres/12-home-paysage-800dp.png) et [actions de la proposition Coach](captures/apres/13-coach-actions-360dp.png). La capture 11 est prise à l'ouverture, avec « Proposition prioritaire » entièrement visible sous l'en-tête. La capture 13 descend jusqu'aux quatre actions. Le paysage sert de contrôle d'adaptation sans capture avant équivalente.

La comparaison des captures réelles montre que le grand CTA plein du HOME devient un libellé souligné; l'état Focus suit le même traitement. Le Coach garde les actions en texte, distingue l'acceptation du refus et place un filet sous l'en-tête et au-dessus des onglets. À 320 dp et 2×, Apps, Coach, Pause et Plus occupent deux lignes lisibles.

## Vérifications et sorties

| Vérification | Statut | Résultat observé |
| --- | --- | --- |
| HEAD de base | PASS | `aa9b2daf703d7653b33d6433e3d97b915c955731` |
| Compilation et APK debug | PASS | La commande combinée ci-dessous a exécuté `assembleDebug`; `compileDebugKotlin` exécuté et APK installé sur l'AVD. |
| Tests JVM | PASS | Commande combinée ci-dessous; 317 tests, 0 échec, 0 erreur, 0 ignoré. |
| Android lint | PASS | Commande combinée ci-dessous; 0 erreur et 53 avertissements non bloquants dans le [rapport Lint](../../android/app/build/reports/lint-results-debug.html). |
| Propreté du diff | PASS | `git diff --check`; aucune sortie. |
| Contrôle visuel HOME/Coach/mandat/partage | PASS | Captures réelles comparées sur l'AVD indiqué ci-dessous. |
| Largeurs 320 dp et 360 dp | PASS | HOME contrôlé en 1×; le pied garde ses quatre accès sans chevauchement. Les listes longues restent défilables. |
| Police système 2× | PASS | Captures HOME 320 et 360 dp; le pied passe sur deux lignes et les quatre accès restent lisibles. Les actions Coach sont empilées. |
| Rotation paysage | PASS | HOME contrôlé à 800 × 360 dp; date, état, prochaine étape, CTA et trois favoris apparaissent sans troncature bloquante. |
| `LauncherPresentationTest` | PASS | AVD `emulator-5582`; 2 tests, 0 échec. Le test de police 2× confirme aussi les cibles HOME ≥ 48 dp. |
| `AtlasLaunchTest` | FAIL | AVD `emulator-5582`; 12 tests, 10 réussis, 2 échecs. Détails ci-dessous; aucun fichier `androidTest` n'a été changé. |
| Téléphone physique | NOT_RUN | Aucun téléphone n'a été utilisé; aucun résultat Pixel n'est revendiqué. |

Commandes principales lancées depuis `android/` :

```text
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
BUILD SUCCESSFUL in 50s

ANDROID_SERIAL=emulator-5582 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.app.LauncherPresentationTest
BUILD SUCCESSFUL; 2 tests, 0 failure

ANDROID_SERIAL=emulator-5582 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.AtlasLaunchTest
BUILD FAILED; 12 tests, 2 failures

ANDROID_SERIAL=emulator-5582 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.atlas.coach.AtlasLaunchTest#directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline
BUILD FAILED; the assertion at line 137 reproduces in isolation
```

Échecs `AtlasLaunchTest` observés sur le code final : `directiveFocusHabitSimulatorAndLibraryJourneysWorkOffline` ne trouve pas « Valider aujourd'hui » après recreation ([AtlasLaunchTest.kt:137](../../android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt:137)); `focusRoutineIsCapabilityGatedAndRequiresExplicitStart` ne retrouve ensuite aucune hiérarchie Compose lorsqu'il sélectionne Plus ([AtlasLaunchTest.kt:290](../../android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt:290)). Le premier échec a aussi été relancé seul et reste reproductible. Le second est rapporté séparément sans conclure sur sa cause.

Contrôle UI effectué avec `adb -s emulator-5582`, AVD Android API 34 `atlas_v623_ui_api34`, écran 1080 × 2400 à 480 dpi (360 dp), 960 × 2400 (320 dp), puis 2400 × 1080 (800 × 360 dp). Police réglée à `font_scale=2.0` pour les captures agrandies. L'APK installé a répondu `Success`. Le port réservé 5583 n'a pas été utilisé. L'AVD personnel n'a pas été démarré.

## Limites

- `lintDebug` réussit avec 53 avertissements et 0 erreur. Le `UseKtx` d'[AtlasScreens.kt:668](../../android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt:668) est inchangé; les autres avertissements concernent la configuration Gradle/manifest et des fichiers hors périmètre UI.
- `AtlasLaunchTest` conserve deux échecs décrits ci-dessus. Les tests instrumentés n'ont pas été modifiés.
- À 320 dp et 2×, la zone HOME est défilable; les contenus sous le pied fixe s'atteignent par défilement. Le pied reste visible sur deux lignes.
- L'accessibilité a été contrôlée visuellement et par les assertions de hauteur de `LauncherPresentationTest`; aucune session TalkBack n'a été exécutée.
- Pas de vérification sur matériel physique. Le rendu réel peut varier selon densité, police système et constructeur.
