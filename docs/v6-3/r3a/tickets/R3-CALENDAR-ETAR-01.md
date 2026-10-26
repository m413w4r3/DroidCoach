# R3-CALENDAR-ETAR-01 — DayPlan ↔ calendrier ↔ Etar

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R3-CALENDAR-ETAR-01 / READY` |
| Objectif | Raccorder le plan approuvé au moteur d'écriture durable : consentement, permissions, sélection du calendrier ATLAS, confirmation groupée, journal d'effets, divergence et révocation |
| Dépendances | `R3-CALENDAR-01` intégré ; `R3-CALENDAR-LOCAL-01` accepté |
| BASE_SHA | `a1d1105` (à re-vérifier au lancement) |
| Profil exigé | `codex:gpt-6-luna` / `xhigh` |
| Worktree | dédié, écriture limitée au scope |
| Lire | `docs/v6-3/r3a/CALENDRIER_ETAR.md`, `docs/v6-3/r3a/DECISIONS_ARCHITECTURE.md` (D5, D6, D10), `docs/v6-3/CONTRATS_V63.md` §3.4, `android/.../calendar/write/**`, `android/.../calendar/local/**`, `android/.../plan/**`, `android/.../app/PlanScreen.kt`, `CalendarPermissionScreen.kt` |
| Écrire | `android/app/src/main/java/dev/atlas/coach/app/CalendarPermissionScreen.kt`, `android/app/src/main/java/dev/atlas/coach/app/PlanScreen.kt`, nouveaux fichiers `android/app/src/main/java/dev/atlas/coach/app/Calendar*.kt`, `android/app/src/test/java/dev/atlas/coach/app/Calendar*.kt` (neufs), `android/app/src/androidTest/java/dev/atlas/coach/app/Calendar*.kt` (neufs) |
| Ne pas écrire | `AtlasController.kt`, `AtlasApp.kt`, `PlanController.kt`, `calendar/write/**`, `calendar/local/**`, manifeste, backend, relais |
| Oracle | JVM ciblé + suite complète ; compilation debug + androidTest ; tests de consentement, permission refusée/accordée, sélection locale/étrangère, confirmation groupée exacte, idempotence, reprise, révocation, divergence |
| Stop conditions | besoin de modifier un fichier hors scope ; écriture réelle depuis les tests JVM ; modèle/effort non conforme |
| Livrables | commit SHA, diff, tests, rapport `PASS/FAIL/BLOCKED` |

## Détail

1. **Consentement d'écriture distinct** : nouveau store local, séparé du consentement
   de lecture. Accorder `WRITE_CALENDAR` n'accorde pas le consentement ATLAS, et
   inversement. Révocation immédiate.
2. **Parcours permission** : demande explicite `READ_CALENDAR` puis
   `WRITE_CALENDAR`; affichage des états refusé/accordé/non déclaré. Aucune demande
   automatique.
3. **Calendrier local** : appeler `AtlasLocalCalendar.ensure(context)` après action
   explicite ; sélectionner uniquement `ATLAS R3A TEST` / type
   `dev.atlas.coach.calendar` (ou `LOCAL`). Refuser tout calendrier étranger.
4. **`CalendarEffectController`** (nouveau fichier) :
   - utilise `CalendarWriteService.durable(provider, File(filesDir, "calendar/write"))`;
   - `recoverPending()` à l'initialisation ;
   - `syncAppliedPlan(plan: DayPlan)` calcule les effets depuis les liens durables :
     création si absent, mise à jour si `content_hash` diffère, suppression si
     l'activité a disparu ; clés d'idempotence déterministes
     `plan-<planId>-v<version>-<activityId>-<kind>` ;
   - `verifyPlan(plan)` expose `CONFIRMED/MISSING/DIVERGED/ORPHANED/UNKNOWN` ;
   - ne réessaie jamais aveuglément ; un `UNKNOWN` exige une réconciliation ;
   - refuse tout effet si consentement, permission ou sélection manque.
5. **Confirmation groupée** : un seul dialogue énumère exactement les effets
   (titre, date/heure, type), avec le nombre d'événements et la version du plan.
   Annulation = aucun effet. Une modification du plan entre l'ouverture et la
   confirmation invalide la confirmation.
6. **PlanScreen** : après application d'une révision, action « Synchroniser avec
   le calendrier » discrète ; affichage d'un état de divergence et d'un lien vers
   le diagnostic. Ne pas surcharger HOME.
7. **CalendarPermissionScreen** : sections lecture, écriture, calendrier local,
   consentement, permissions, journal d'effets, divergence, révocation, dernier
   statut ; pas de clé ni d'URL.
8. **Tests JVM** avec faux provider : sélection, refus étranger, confirmation
   exacte, plan modifié entre confirmation et exécution, idempotence (2 sync =
   1 effet), reprise après crash, révocation avant effet, divergence externe,
   calendrier supprimé. Tests instrumentés à exécuter par le parent.
9. Le parent câblera le controller dans `AtlasApp`/`MainActivity` et exécutera la
   recette réelle Etar.

## Reprise

Session ID, SHA produit, tests exécutés, blocages. Aucune sous-délégation.
