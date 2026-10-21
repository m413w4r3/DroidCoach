# Rapport UI — ATLAS V6.2.3, mission C

## Base et périmètre

- Base initiale de l'implémentation : `aa9b2daf703d7653b33d6433e3d97b915c955731`; la révision intégrée UI-R3 examinée est `e690323` sur `feat/atlas-v6-2-3`.
- Le travail UI reste dans son worktree isolé; aucun chemin hôte ou nom de compte local n'est consigné dans ce rapport.
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
- À partir de 1,5×, l'en-tête place le titre complet sur une ligne puis Retour et Urgence sur une seconde; aucune taille de texte n'est réduite sous l'échelle système.

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

## Revue corrective UI-R2 — 2026-10-21

Base de cette correction : commit `8e94802` (UI-R1). Aucun fichier `androidTest` n'a été modifié.

- Dans [AtlasScreens.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt), le titre de chaque habitude et son action de validation sont maintenant sur la même ligne. L'action garde ses libellés exacts, sa description accessible, son callback et une cible minimale de 48 dp. Cela maintient l'action dans la zone visible quand Compose restaure le défilement à hauteur du titre.
- La tentative R2 de déplacer la demande de permission hors de l'activation des routines a été révertée par le commit `53ba3a6`, qui restaure `requestNotifications()`. Cette décision utilisateur est conservée en UI-R3; ce parcours n'est pas modifié.
- Aucun tag de test n'a changé. La recherche de `habit_input`, « Valider aujourd'hui », « Annuler aujourd'hui », `CAP_FOCUS_ROUTINES` et « Routines de focus » dans `androidTest` trouve [AtlasLaunchTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt), [AtlasVisualCaptureTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/app/AtlasVisualCaptureTest.kt) et [V6RealAgentE2eInstrumentationTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/app/V6RealAgentE2eInstrumentationTest.kt). `LauncherPresentationTest` est également demandé explicitement pour cette revue.

| Vérification | Statut | Résultat observé |
| --- | --- | --- |
| Compilation debug et APK AndroidTest (source de R2) | PASS | Code ensuite consigné dans `25ca41c` (intégré comme `78d55cf`). |
| Tests JVM (source de R2) | PASS | 317 tests, 0 échec, 0 erreur, 0 ignoré; code ensuite consigné dans `25ca41c`. |
| Android lint (source de R2) | PASS | 0 erreur, 53 avertissements; code ensuite consigné dans `25ca41c`. |
| `git diff --check` (revue R2) | PASS | Aucune sortie pour le diff de travail UI. |
| `AtlasLaunchTest` avant correction, sur 8e94802 | FAIL | Rapport pré-correction : 12 tests, 10 réussis, 2 échecs aux lignes 137 et 290. |
| `AtlasLaunchTest` après correction | BLOCKED | Pas relancé : l'image API 34 ATD a forcé 2560 Mo malgré `-memory 2048`; le garde-fou a arrêté le lot avant instrumentation. |
| `LauncherPresentationTest` après correction | BLOCKED | Même blocage de capacité AVD; aucune instrumentation après correction. |
| Classes UI repérées par `rg` (`AtlasVisualCaptureTest`, `V6RealAgentE2eInstrumentationTest`) | BLOCKED | Même blocage de capacité AVD; aucune instrumentation après correction. |
| Vérification de la régression sur la base aa9b2da | BLOCKED | Le test de comparaison n'a pas démarré, pour le même blocage mémoire. Aucune attribution au harnais n'est affirmée. |
| Scénarios instrumentés avec comptes réels | NOT_RUN | Aucun test instrumenté après correction n'a été exécuté et aucun compte réel n'a été utilisé. |
| Arrêt et nettoyage de l'AVD | PASS | Le lot a arrêté et supprimé son AVD temporaire; `adb devices -l` sous le verrou affiche uniquement « List of devices attached », sans appareil. |
| Arrêt des démons Gradle | PASS | `./gradlew --stop` a indiqué `1 Daemon stopped`. |

Commandes lancées depuis `android/` :

```text
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --max-workers=1 --console=plain
BUILD SUCCESSFUL in 1m 36s
81 actionable tasks: 15 executed, 66 up-to-date
JVM XML: 57 fichiers, 317 tests, 0 failure, 0 error, 0 skipped
Lint XML: 53 issues, 53 warnings, 0 errors

./gradlew --stop
Stopping Daemon(s)
1 Daemon stopped
```

Tentative instrumentée exécutée sous le verrou exclusif partagé de l'AVD avec `atlas_v623_ui_api34_atd`, port 5582 et `-memory 2048`. Le journal de cette tentative indique `Increasing RAM size to 2560MB`; l'instrumentation n'a donc pas été lancée. L'AVD temporaire a été arrêté et supprimé par le nettoyage du lot. Aucun autre AVD n'était actif et le port 5583 n'a pas été utilisé.

Le rapport XML pré-correction de `AtlasLaunchTest` montre que le premier échec était « The component is not displayed! » après recreation ([AtlasLaunchTest.kt:137](../../android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt:137)). Pour le second, le logcat montre l'ouverture de `GrantPermissionsActivity` juste après l'activation de la capacité et avant l'échec « No compose hierarchies found » à [AtlasLaunchTest.kt:290](../../android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt:290). La suppression de cette demande a été révertée par `53ba3a6`; le diagnostic n'est pas attribué au harnais sans comparaison instrumentée sur `aa9b2da`.

Les captures R1 déjà relues restent dans `captures/apres/`; cette correction touche l'écran Habitudes et le moment de demande de permission, sans modifier les vues HOME ou Coach capturées. Aucune nouvelle capture n'a été prise pendant cette revue bloquée par la limite de mémoire.

## Limites

- Les changements UI-R2 compilent et passent les tests JVM et lint. Le passage de l'AVD a été limité par son augmentation automatique à 2560 Mo; les tests instrumentés après correction restent donc `BLOCKED`.
- Les deux échecs pré-correction sont conservés comme historique de la régression; aucune suite après correction ni comparaison `aa9b2da` n'a été exécutée.
- Aucun téléphone physique ni compte réel n'a été utilisé. Aucun résultat Pixel n'est revendiqué.
- `lintDebug` passe avec 53 avertissements et 0 erreur. Le `UseKtx` d'[AtlasScreens.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt) est inchangé; les autres avertissements concernent la configuration Gradle/manifest et des fichiers hors périmètre UI.
- Les captures HOME/Coach/Mandat acceptées en R1 n'ont pas été régénérées. Aucune session TalkBack n'a été exécutée.

## Revue corrective UI-R3 — 2026-10-21

Base intégrée utilisée : `e690323` sur `feat/atlas-v6-2-3`. Le code R3 compilé, testé JVM/lint et capturé en portrait est consigné dans `1760bc7fab28f501ff1041eddff3c796b433f8c5`; cette ligne de rapport est un ajout documentaire postérieur, sans changement au code testé. La modification applicative ne touche que [AtlasDesign.kt](../../android/app/src/main/java/dev/atlas/coach/app/ui/AtlasDesign.kt). Le retour de `requestNotifications()` lors de l'activation des routines Focus, restauré par `53ba3a6`, a été conservé.

### Corrections et décisions

- `AtlasTopBar` ne remplace plus la taille système par des valeurs compactes. À partir de `fontScale >= 1.5`, le titre complet occupe une première rangée et Retour/Urgence une seconde; sous ce seuil, le titre peut se replier dans la rangée sans taille de police ni nombre de lignes imposés. Les deux commandes gardent des cibles minimales de 48 dp et leurs descriptions/callbacks. Les captures à 320 dp et 360 dp, police 2×, montrent les trois libellés entiers en portrait.
- La revue confirme que les paires de texte/fond vérifiées dépassent AA : minima rapportés de 6,19:1 en sombre, 6,19:1 en AMOLED et 5,13:1 en clair. Aucune palette n'a été modifiée.
- Les 110 chaînes `testTag`/textes attendues examinées dans `androidTest` étaient présentes; aucune n'a été changée pour cette correction. Aucun fichier `androidTest` n'a été modifié.
- Le rapport UI ne contient plus de chemin de worktree, nom de compte ou chemin local. Les captures R3 ajoutées sont des écrans de démonstration avec données synthétiques; elles ne montrent pas de donnée personnelle.
- Le signalement de chemins hôte et de numéros de série dans les preuves API37 hors UI est conservé sans édition : ces pièces sont des preuves QA partagées, hors propriété UI, et leur réécriture risquerait d'altérer l'historique de test. Le point est signalé à QA.
- Les constats sur la classification du harnais QA et la couverture de la frontière de crash du contrôleur ne sont pas corrigés ici : ils concernent des scripts QA et la logique métier/tests hors propriété UI. La revue indépendante reste le point de suivi.
- Les espaces de fin relevés pour P3 ont été retirés de `STABILISATION_RAPPORT.md` et des trois preuves `r3-*` nommées dans la revue.

### Captures et vérifications

| Vérification | Statut | Résultat réel |
| --- | --- | --- |
| En-tête, portrait 320 dp, police 2× | PASS | [14 — PNG 960 × 2400](captures/apres/14-topbar-320dp-2x-portrait.png); le titre se replie, Retour et Urgence restent visibles. |
| En-tête, portrait 360 dp, police 2× | PASS | [15 — PNG 1080 × 2400](captures/apres/15-topbar-360dp-2x-portrait.png); titre, Retour et Urgence visibles. |
| En-tête, paysage, 320/360 dp de hauteur courte, police 2× | NOT_RUN | Le premier lot a conservé une surface portrait et ses images ne sont pas conservées comme preuves paysage. Au second démarrage, `MemAvailable` est tombé à 4 522 072 kB après boot, sous le seuil requis de 5 242 880 kB; arrêt avant toute capture paysage. |
| Compilation debug et APK AndroidTest | PASS | `BUILD SUCCESSFUL in 2m 54s`; 81 tâches, 20 exécutées et 61 à jour. |
| Tests JVM | PASS | 58 fichiers XML, 321 tests, 0 failure, 0 error, 0 skipped. |
| Android lint | PASS | 53 avertissements, 0 erreur. |
| `git diff --check aa9b2da` après nettoyage P3 | PASS | Aucune sortie. |
| AndroidTest | NOT_RUN | Aucun test instrumenté UI-R3 n'a été lancé. |
| Arrêt de l'AVD et de Gradle | PASS | AVD 5582 arrêté sous le verrou partagé; `adb` ne signale plus d'AVD. `./gradlew --stop` a répondu « No Gradle daemons are running. » |
| Téléphone physique | NOT_RUN | Aucun test ni commande ADB n'a visé un téléphone physique. |

Commande Gradle exécutée depuis `android/` :

```text
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --max-workers=1 --console=plain
BUILD SUCCESSFUL in 2m 54s
81 actionable tasks: 20 executed, 61 up-to-date
JVM XML: 58 fichiers, 321 tests, 0 failure, 0 error, 0 skipped
Lint XML: 53 warnings, 0 errors

./gradlew --stop
No Gradle daemons are running.
```

Les nouvelles captures paysage restent `NOT_RUN`; elles ne sont pas simulées ni remplacées par des captures portrait. Les captures précédentes conservent leur statut historique et ne prouvent pas le nouvel en-tête en paysage.
