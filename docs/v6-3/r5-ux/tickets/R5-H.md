# R5-H — Actions Coach simplifiées

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R5-H / READY` (base `62fbca4`, F intégré) |
| Catégorie | Implémentation UI + formulaires |
| Depuis | `62fbca4dd67b47e516471d957b285ac723545014` |
| Profil exigé | `codex:gpt-6-luna`, `effort=xhigh`, vérifié dans les métadonnées |
| Worktree | Paseo-managed, branche `feat/r5-h-coach-actions`, aucune sous-délégation |
| Propriété d'écriture | `android/app/src/main/java/dev/atlas/coach/app/AgentCoachScreen.kt`<br>`android/app/src/main/java/dev/atlas/coach/app/CoachQuickActions.kt` (nouveau : formulaires + validation pure)<br>`android/app/src/test/java/dev/atlas/coach/app/CoachQuickActionsTest.kt` (nouveau)<br>`android/app/src/androidTest/java/dev/atlas/coach/app/CoachQuickActionsInstrumentationTest.kt` (nouveau) |
| Interdit d'écriture | `AtlasController.kt` (réutiliser l'API existante), `CoachTabScreen.kt`, `CoachInsightsScreen.kt`, domaine Plan/Focus/Goals, `AtlasApp.kt` |
| Lecture | `GoalsPanel.kt`, `FocusModels.kt`, `PlanController.kt`, `AtlasController.kt` (méthodes publiques uniquement), tests Coach existants |

## Objectif observable

Depuis le haut de l'écran Coach, trois actions compactes permettent de préparer un appel au coach en quelques gestes, avec **valeurs pré-remplies** et **confirmation explicite** avant effet :

1. **Organiser ma journée** : formulaire court (début, fin, priorité ou énergie), aperçu d'une ligne, `Envoyer au coach` après confirmation ; réutilise `controller.sendAgentMessage(...)`.
2. **Planifier un objectif** : titre, métrique (`Focus minutes` / `Habitudes` / `Manuel`), cible, échéance pré-remplie à J+7, prochaine action ; confirmation puis `controller.createGoal(GoalPanelDraft(...))`.
3. **Focus prêt** : preset `25/5` ou `10/2`, titre de tâche pré-rempli (directive/plan), nombre de cycles ; confirmation puis `controller.startFocus(FocusPlan..., goalId=null)` et navigation vers `AtlasScreenId.FOCUS`.

## Règles

1. **Aucun nouvel effet, aucune nouvelle permission, aucun nouvel envoi réseau autonome.** Réutilisation stricte des contrôleurs existants.
2. **Confirmation explicite obligatoire** avant chaque création/envoi/démarrage. Le dialogue de confirmation rappelle l'effet exact (titre, cible, durée).
3. **Formulaires courts** : 3 champs maximum visibles par action ; valeurs par défaut dérivées de l'état local (directive, plan, objectif existant).
4. **Validation pure** dans `CoachQuickActions.kt` (longueurs, cible > 0, heures valides, cycles 1..12, titre non vide) avec messages d'erreur d'une ligne. Tests JVM dédiés.
5. **Conserver les tags et textes** de `R5-F` (conversation, proposition, contexte, statut). Les trois actions sont ajoutées au-dessus de la conversation dans une rangée compacte qui passe à la ligne à police 2×.
6. **Pas de duplication de logique** : ne pas recalculer les plans Focus ni les quotas du mandat ; `startFocus` applique déjà ses règles.
7. **Aucune modification de `AtlasController.kt`.** Si une API manque, arrêter et proposer au parent.
8. **Données de test synthétiques**, aucun nom personnel.

## Oracle

- `cd android && ./gradlew :app:testDebugUnitTest --tests '*CoachQuickActionsTest*' --rerun-tasks` → PASS.
- Cas : valeurs par défaut, validation heures, cible 0/négative, cycles hors borne, titre vide/trop long, confirmation annulée = aucun effet, confirmation acceptée = un seul appel.
- `./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` → PASS.
- Test instrumenté compilé (parent sur AVD API 37) : les trois actions sont visibles, le dialogue de confirmation apparaît, `Annuler` ne crée rien, `Confirmer` crée l'objectif / envoie le message / démarre le focus, la conversation reste utilisable.
- Aucune régression des tests Coach existants ni des tags de `R5-F`.

## Stop conditions

- API contrôleur manquante ou insuffisante → `BLOCKED`, ne pas modifier `AtlasController.kt`.
- Conflit d'écriture avec `R5-G2` → arrêter et signaler (G2 ne doit pas toucher `AgentCoachScreen.kt`).
- Modèle/effort incorrect → `BLOCKED`.

## Livrables

- Commit unique, SHA complet.
- Matrice action → API réutilisée → confirmation → test.
- Sorties de tests, limites.
