# R3-SPORT-WIRE-01 — wger lecture → FastAPI → Android, `trainingRef`

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3-SPORT-WIRE-01 / READY` |
| Objectif | Charger une projection sportive réelle en lecture seule depuis wger via FastAPI, l'afficher avec ses états de synchronisation, et persister `trainingRef` dans le `DayPlan` |
| Dépendances | `R3-CALENDAR-01` intégré ; `R3-PREFLIGHT-01` |
| BASE_SHA | `a1d1105` (à re-vérifier au lancement) |
| Profil exigé | `codex:gpt-6-luna` / `xhigh` |
| Worktree | dédié, écriture limitée au scope |
| Lire | `contracts/sport-plan-v1.schema.json`, `backend/atlas_backend/integrations/wger_plan.py`, `backend/atlas_backend/integrations/wger.py`, `backend/atlas_backend/main.py`, `android/.../training/**`, `android/.../app/SportScreen.kt`, `android/.../plan/PlanModels.kt`, `PlanJsonCodec.kt`, `PlanRevisionEngine.kt` |
| Écrire | `backend/atlas_backend/main.py` (ajout de route uniquement), `backend/atlas_backend/integrations/wger_plan.py`, `backend/tests/test_sport_route.py` (neuf), `android/app/src/main/java/dev/atlas/coach/training/**`, `android/app/src/main/java/dev/atlas/coach/plan/PlanModels.kt`, `PlanJsonCodec.kt`, `PlanRevisionEngine.kt`, `android/app/src/main/java/dev/atlas/coach/app/SportScreen.kt`, `android/app/src/test/java/dev/atlas/coach/training/**`, `android/app/src/test/java/dev/atlas/coach/plan/**` |
| Ne pas écrire | `AtlasController.kt`, `AtlasApp.kt`, `PlanScreen.kt`, `PlanController.kt`, manifeste, `calendar/**`, `integrations/relay/**`, `references/**` |
| Oracle | pytest ciblé + suite complète ; JVM ciblé + suite complète ; compilation debug ; `trainingRef` round-trip et rétrocompatibilité d'un plan sans ce champ ; aucun appel wger en écriture |
| Stop conditions | besoin de consommer le refresh token rotatif ; besoin d'écrire hors scope ; modèle/effort non conforme |
| Livrables | commit SHA, diff, preuves, rapport `PASS/FAIL/BLOCKED` |

## Détail

1. **Backend** : `GET /v1/sport/plan?local_date=YYYY-MM-DD&timezone=...` authentifié.
   - Utilise uniquement `WGER_API_TOKEN` en lecture (`ApiTokenWgerAuth`). Refuse explicitement
     `WGER_AUTH_MODE=jwt_refresh` pour cette route : ne consomme jamais le refresh token
     rotatif. Si la configuration manque, retourne `200` avec
     `empty_sport_plan(sync_state="UNAVAILABLE")`.
   - Lit routine/day/exercises/logs/sessions via `WgerClient` en lecture, puis
     `project_sport_plan` avec `source="WGER_READ"`.
   - Mappe les erreurs wger via `wger_error_to_sync_state` et retourne le contrat
     `sport-plan-v1` sans secret. Réponse bornée. Aucune écriture wger.
   - Tests avec faux client : config absente, succès, `STALE`, `MISMATCH`,
     `UNAVAILABLE`, refus du mode `jwt_refresh`, absence de secrets dans la réponse.
2. **Android client** : `SportPlanClient` HTTP Bearer, timeout, réponse bornée,
   parse `SportPlanJson`, mapping des erreurs vers `FRESH/STALE/UNAVAILABLE/MISMATCH`.
   Aucun log de token.
3. **UI** : `SportScreen` accepte un état réel et un `demoMode` explicite. La fixture
   n'est utilisée qu'en démo explicite. Affiche la dernière synchronisation, la
   source, l'état, et distingue prévu/effectué.
4. **`trainingRef`** : modèle `TrainingPlanReference` conforme au contrat ; champ
   optionnel dans `PlanActivity` ; codec JSON optionnel et rétrocompatible ;
   diff de révision qui préserve/refuse correctement le champ. Tests de
   round-trip, plan legacy sans champ, révision avec/sans `trainingRef`.
5. Le parent câblera `SportScreen` dans la navigation et le client dans
   `AtlasController` après intégration.

## Reprise

Session ID, SHA produit, tests exécutés, blocages. Aucune sous-délégation.
