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

## Revue corrective UI-R2 — 2026-10-21

Base de cette correction : commit `8e94802` (UI-R1). Aucun fichier `androidTest` n'a été modifié.

- Dans [AtlasScreens.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt), le titre de chaque habitude et son action de validation sont maintenant sur la même ligne. L'action garde ses libellés exacts, sa description accessible, son callback et une cible minimale de 48 dp. Cela maintient l'action dans la zone visible quand Compose restaure le défilement à hauteur du titre.
- Activer « Routines de focus locales » ne lance plus immédiatement l'activité système de permission Android, qui masquait l'interface Compose au test suivant. La demande de permission reste disponible dans « Notifications facultatives » et par les commandes dédiées aux notifications; aucune capacité ni permission n'est retirée.
- Aucun tag de test n'a changé. La recherche de `habit_input`, « Valider aujourd'hui », « Annuler aujourd'hui », `CAP_FOCUS_ROUTINES` et « Routines de focus » dans `androidTest` trouve [AtlasLaunchTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt), [AtlasVisualCaptureTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/app/AtlasVisualCaptureTest.kt) et [V6RealAgentE2eInstrumentationTest.kt](../../android/app/src/androidTest/java/dev/atlas/coach/app/V6RealAgentE2eInstrumentationTest.kt). `LauncherPresentationTest` est également demandé explicitement pour cette revue.

| Vérification | Statut | Résultat observé |
| --- | --- | --- |
| Compilation debug et APK AndroidTest | PASS | `assembleDebug` et `assembleDebugAndroidTest` terminés dans le lot Gradle courant. |
| Tests JVM | PASS | 317 tests, 0 échec, 0 erreur, 0 ignoré. |
| Android lint | PASS | 0 erreur, 53 avertissements. |
| `git diff --check` | PASS | Aucune sortie. |
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

Tentative instrumentée exécutée sous `flock -x /tmp/v623-emulator.lock` avec l'AVD `atlas_v623_ui_api34_atd` sur le port 5582 et le paramètre `-memory 2048`. Le journal `/tmp/v623-emulator-5582-atd.log` indique `Increasing RAM size to 2560MB`; l'instrumentation n'a donc pas été lancée. L'AVD temporaire a été arrêté et supprimé par le nettoyage du lot. Aucun autre AVD n'était actif et le port 5583 n'a pas été utilisé.

Le rapport XML pré-correction de `AtlasLaunchTest` montre que le premier échec était « The component is not displayed! » après recreation ([AtlasLaunchTest.kt:137](../../android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt:137)). Pour le second, le logcat montre l'ouverture de `GrantPermissionsActivity` juste après l'activation de la capacité et avant l'échec « No compose hierarchies found » à [AtlasLaunchTest.kt:290](../../android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt:290). La correction retire ce lancement automatique de permission de cette capacité; faute de pouvoir lancer l'AVD sous la limite demandée, le résultat n'a pas été confirmé par instrumentation. Aucun résultat identique sur `aa9b2da` n'est revendiqué.

Les captures R1 déjà relues restent dans `captures/apres/`; cette correction touche l'écran Habitudes et le moment de demande de permission, sans modifier les vues HOME ou Coach capturées. Aucune nouvelle capture n'a été prise pendant cette revue bloquée par la limite de mémoire.

## Limites

- Les changements UI-R2 compilent et passent les tests JVM et lint. Le passage de l'AVD a été limité par son augmentation automatique à 2560 Mo; les tests instrumentés après correction restent donc `BLOCKED`.
- Les deux échecs pré-correction sont conservés comme historique de la régression; aucune suite après correction ni comparaison `aa9b2da` n'a été exécutée.
- Aucun téléphone physique ni compte réel n'a été utilisé. Aucun résultat Pixel n'est revendiqué.
- `lintDebug` passe avec 53 avertissements et 0 erreur. Le `UseKtx` d'[AtlasScreens.kt](../../android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt) est inchangé; les autres avertissements concernent la configuration Gradle/manifest et des fichiers hors périmètre UI.
- Les captures HOME/Coach/Mandat acceptées en R1 n'ont pas été régénérées. Aucune session TalkBack n'a été exécutée.
