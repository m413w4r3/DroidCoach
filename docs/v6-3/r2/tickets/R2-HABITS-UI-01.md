# R2-HABITS-UI-01 — Refonte Habitudes en trois espaces

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R2-HABITS-UI-01 / READY (vague B)` |
| BASE_SHA | HEAD de la campagne **après intégration de `R2-UI-01`** (SHA exact fourni au lancement) |
| Profil imposé | `provider=codex`, `model=gpt-6-luna`, `effort=xhigh` |
| Worktree | `feat/r2-habits-ui` |
| Accès | écriture bornée ci-dessous ; aucun ADB, aucun réseau |
| Dépendances | `R2-UI-01` intégré et accepté |

## Objectif observable

Séparer l'usage quotidien de la configuration :

1. **Mes habitudes** — liste compacte, validation facile, progression du jour,
   mini-historique, accès au détail, ajout simple.
2. **Détail** — historique en grille, progression réelle, série, taux,
   modification, rappel, archivage.
3. **Création / édition** — formulaire dédié, quotidien simple par défaut.

Le formulaire de création ne précède plus la liste. Les données, rappels,
exports, archivage, liens d'objectifs et événements coach existants sont
préservés.

## Scope d'écriture exclusif

```text
android/app/src/main/java/dev/atlas/coach/app/AtlasScreens.kt
android/app/src/main/java/dev/atlas/coach/app/HabitsScreen.kt (neuf)
android/app/src/main/java/dev/atlas/coach/app/HabitRow.kt (neuf)
android/app/src/main/java/dev/atlas/coach/app/HabitHistoryGrid.kt (neuf)
android/app/src/main/java/dev/atlas/coach/app/HabitDetailScreen.kt (neuf)
android/app/src/main/java/dev/atlas/coach/app/HabitEditor.kt (neuf)
android/app/src/androidTest/java/dev/atlas/coach/app/HabitsScreenInstrumentationTest.kt (neuf)
android/app/src/test/java/dev/atlas/coach/app/HabitUiLogicTest.kt (neuf)
```

Dans `AtlasScreens.kt`, **seul** le bloc `HabitsScreen` et les helpers
strictement utilisés par lui peuvent être retirés/déplacés. Les autres écrans,
imports et fonctions partagés doivent rester intacts.

Interdictions : `AtlasController.kt`, `AtlasApp.kt`, `AtlasScreenId.kt`,
`app/ui/**`, `habits/**`, `plan/**`, manifeste, `build.gradle.kts`, `docs/**`.
Aucune nouvelle route de navigation : le détail est un état interne de
`HabitsScreen`. Conserver la signature exacte
`HabitsScreen(controller, revision, onExport)` utilisée par `AtlasApp.kt`.

## Exigences

### Espace 1 — Mes habitudes

- En-tête court : progression du jour (`x sur y validées`), action d'ajout.
- Habitudes **attendues aujourd'hui** en premier ; les autres restent
  accessibles sans être mises en avant.
- Ligne compacte : nom, état du jour, bouton de validation/annulation avec
  cible ≥ 48 dp et `contentDescription` explicite, mini-grille 7 jours,
  indicateur de régularité discret, accès au détail.
- Ne pas afficher le formulaire de création au-dessus de la liste.
- L'action « Ajouter une habitude » ouvre l'espace 3.
- Exporter CSV reste accessible (menu « Plus » de l'écran ou bouton discret) et
  appelle `onExport`.
- État vide : phrase courte + action d'ajout.

### Espace 2 — Détail d'une habitude

- Nom, objectif de fréquence lisible, état du jour.
- Grille d'historique 7 jours immédiate, bascule 30 jours sur demande.
- Distinguer visuellement et textuellement :
  - validé ;
  - dû et manqué ;
  - non planifié.
  Utiliser forme + glyphe + légende, pas la couleur seule.
- Progression calculée depuis les check-ins réels et la fréquence prévue :
  `completedDueDays / dueDays`, taux, série actuelle, meilleure série.
- Rappel : afficher l'état, permettre modification/retrait via l'éditeur.
- Archivage : action explicite avec confirmation ; le libellé indique que
  l'historique local est conservé et que l'habitude quitte la liste active.
- Retour à la liste sans changer de route.
- Ne jamais fabriquer une validation à partir d'une échéance.

### Espace 3 — Création / édition

- Formulaire dédié (feuille modale ou dialogue plein écran).
- Champs : nom, type de fréquence (quotidien, jours, quota hebdo, alternance),
  jours ou quota, date d'ancrage si alternance, rappel facultatif.
- Une habitude quotidienne simple ne demande que le nom ; les autres champs
  sont facultatifs ou ont des valeurs par défaut explicites.
- Enregistrer / Annuler ; erreurs de validation affichées dans le formulaire.
- Édition préremplie avec les valeurs existantes.
- Préserver les rappels existants si le champ n'est pas modifié.

### Contraintes

- Utiliser les API existantes de `AtlasController` : `activeHabits`,
  `checkedHabitIds`, `toggleHabitToday`, `addHabit`, `updateHabit`,
  `setHabitReminder`, `archiveHabit`, `changeHistoryDays`, `historyDays`,
  `habits.history(...)`, `habits.strength(...)`, `capabilities`.
- Aucun accès ADB, aucune capture.
- Cibles ≥ 48 dp, sémantique Compose, police 2×, rotation.
- Pas de message culpabilisant, pas de score fictif, pas de série trompeuse.
- Aucun changement du modèle `Habit`/`HabitSchedule`/`HabitCheckIn`.

## Oracle

Tests JVM (`HabitUiLogicTest.kt`) :

1. tri attendu aujourd'hui / non dû ;
2. statut d'une journée (validé, manqué, non planifié) ;
3. calcul de progression et de série à partir de `HabitHistory` ;
4. libellé de fréquence pour les quatre types ;
5. état vide et état avec habitudes.

Tests instrumentés (compilés, exécutés par le parent) :

1. aucune habitude → action d'ajout visible ;
2. cinq habitudes → liste et progression ;
3. validation puis annulation d'un check-in ;
4. détail → grille 7 jours et bascule 30 jours ;
5. jour non planifié distinct d'un jour manqué ;
6. formulaire quotidien simple → nom + Enregistrer ;
7. archivage → confirmation et disparition de la liste active ;
8. police 2× → validation et ajout atteignables.

Commandes :

```bash
cd android && ./gradlew :app:testDebugUnitTest \
  --tests "dev.atlas.coach.app.HabitUiLogicTest" --max-workers=1
cd android && ./gradlew :app:testDebugUnitTest --max-workers=1
cd android && ./gradlew :app:assembleDebug
cd android && ./gradlew :app:assembleDebugAndroidTest
```

## Livrables

Commit local, SHA de sortie, fichiers, commandes, résultats, limites.
Indiquer explicitement ce qui a été retiré de `AtlasScreens.kt` et vérifier que
les autres écrans compilent.

## Stop conditions

Modèle/effort non conformes, conflit de fichier, besoin de modifier
`AtlasController`/`AtlasApp`/`AtlasScreenId`/`habits/**`, besoin d'ADB ou de
réseau, ou toute régression des données/rappels/exports.
