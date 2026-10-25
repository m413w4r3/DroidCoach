# ATLAS V6.3 — Registre des tâches

Base de mission : `acda7a967b26aa3ccc8325eb194c8daa2f9a58e4` (`main`).
Branche d'intégration : `feat/atlas-v6-3`.
Règle : une notification de fin n'est pas une acceptation. Le parent inspecte le
diff, exécute l'oracle sur le SHA intégré et met à jour ce registre.

## Légende

- `READY` : ticket spécifié, non lancé.
- `RUNNING` : enfant ou parent en cours.
- `REVIEW_REQUIRED` : livrable reçu, en attente de revue parent.
- `ACCEPTED` : diff revu et oracle rejoué.
- `INTEGRATED` : commit présent sur la branche de mission.
- `CORRECTION_REQUIRED` : défaut reproductible, correction ciblée.
- `BLOCKED` : dépendance externe ou autorisation manquante.
- `NOT_RUN` : non exécuté, aucune revendication.

## Tickets

| TASK_ID | Objectif observable | Dépendances | Scope d'écriture exclusif | Oracle | Statut |
|---|---|---|---|---|---|
| V63-L0-01 | Pré-vol, contrats de domaine, registre | — | `docs/v6-3/PREFLIGHT.md`, `CONTRATS_V63.md`, `REGISTRE_TACHES.md` | revue parent + JSON/schémas valides | **INTEGRATED** |
| V63-L1-01 | Domaine `DayPlan`/`PlanRevision` + stockage atomique + idempotence + DST, JVM | L0-01 | `android/.../plan/**` (main+test), aucune autre modification | `:app:testDebugUnitTest --tests dev.atlas.coach.plan.*` + suite complète | **READY** |
| V63-L1-02 | Écran Plan + navigation + actions locales, intégré par le parent | L1-01 | `android/.../app/PlanScreen.kt`, `AtlasApp.kt`, `AtlasController.kt`, `AtlasNavigationState.kt`, `AtlasApplication.kt` | build + test navigation + capture AVD si dispo | **READY** |
| V63-L1-03 | Carte « prochaine action » sur HOME issue du plan approuvé | L1-01/L1-02 | `LauncherHomeScreen.kt`/`AtlasScreens.kt` (parent) | JVM + UI test | **READY** |
| V63-L2-01 | Sonde CalendarContract lecture seule + abstraction testable, sans permission manifeste | L0-01 | `android/.../calendar/**` (main+test) | JVM fake provider + compile Android | **READY** |
| V63-L2-02 | Permission `READ_CALENDAR` + écran diagnostic calendriers, **accord propriétaire requis** | L2-01 + accord | manifest + `CalendarPermissionScreen.kt` | permission refusée/accordée, probe | **BLOCKED_APPROVAL** |
| V63-L2-03 | Lecture événements/conflits réels sur Pixel + Etar installé | L2-02 + appareil | preuves `docs/v6-3/preuves/**` | Pixel + Etar, `PASS/FAIL` | **BLOCKED_DEVICE** |
| V63-L3-01 | Proposition de révision par OpenClaw, diff et confirmation groupée | L1-01, backend v3 | backend `models_v2.py`/`main.py` + Kotlin `plan` | pytest + JVM stale/expiry | **READY** |
| V63-L4-01 | Écritures calendrier réelles, liens stables, réconciliation | L2-03 + L3-01 + accord | `dev/atlas/coach/calendar/write/**` | provider fake + Pixel/Etar | **BLOCKED_APPROVAL** |
| V63-L5-01 | Connexion wger lecture (auth, routines, logs), **identifiants requis** | accord | backend `integrations/wger.py`, Android `training/**` | payloads synthétiques + compte réel | **BLOCKED_CREDENTIALS** |
| V63-L6-01 | Boucle sportive complète + écriture autorisée/vérifiée | L5-01 + accord | `training/**`, `ExternalOperation` | fixture puis compte réel | **BLOCKED_CREDENTIALS** |
| V63-L7-01 | `MemoryFact` local, rétention, révocation, proactivité bornée | L1-01 | `dev/atlas/coach/memory/**` | JVM rétention/revoke | **READY** |
| V63-L8-01 | Adaptateur Bridge `/health`/capabilities/runs + provenance | Bridge déployé + accord | `backend/integrations/bridge.py`, `dev/atlas/coach/research/**` | mock contrat + service réel | **BLOCKED_SERVICE** |
| V63-L9-01 | Mesure de durée UsageStats + `UsageSummary` minimisé | L0-01 | `dev/atlas/coach/usage/**` | JVM + comparaison système Pixel | **READY (JVM)** |
| V63-L9-02 | Quotas/fenêtres + friction launcher réellement testée | L9-01 | `dev/atlas/coach/rules/**`, launcher | JVM + Pixel | **READY (JVM)** |
| V63-L9-03 | Blocage inter-applications (Accessibility) | audit séparé + accord | hors MVP | — | **BLOCKED_POLICY** |
| V63-L10-01 | Contexte localisation opt-in borné | accord + issue #1 | `dev/atlas/coach/location/**` | Pixel | **BLOCKED_APPROVAL** |
| V63-L11-01 | Consolidation, E2E réel, revue indépendante, GO/NO-GO | tous les gates utiles | `docs/v6-3/**` | matrice finale | **READY** |

## Vague 1 — smoke-test à deux worktrees

| Enfant | Worktree | Périmètre | Statut |
|---|---|---|---|
| `V63-L1-01` | `.paseo/worktrees/.../v63-plan-domain` | package `plan` uniquement | READY |
| `V63-L2-01` | `.paseo/worktrees/.../v63-calendar-read` | package `calendar` uniquement | READY |
| Parent | branche de mission | contrats, UI, intégration, backend v3 | RUNNING |

Contraintes communes : provider `codex`, modèle `gpt-6-luna`, effort `xhigh`,
aucune sous-délégation, aucun push, aucune action ADB/Pixel, aucune permission
manifeste, aucune donnée personnelle, commit local dans le worktree.

## Portes nécessitant le propriétaire

1. Connexion du Pixel et actions ADB (pré-vol, installation, permissions, sauvegarde).
2. Ajout/octroi `READ_CALENDAR` et `WRITE_CALENDAR`.
3. Installation/utilisation d'Etar et écritures de test.
4. Identifiants wger et écriture de données sportives.
5. Configuration Bridge (URL, Bearer, déploiement).
6. Réseau privé/Tailscale et ouverture de port.
7. Push/PR/fusion distante.

## Journal d'intégration

| Date | Ticket | SHA base | SHA résultat | Oracle | Verdict |
|---|---|---|---|---|---|
| 2026-10-26 | V63-L0-01 | `acda7a9` | à compléter | revue documentaire | ACCEPTED (en attente commit) |
