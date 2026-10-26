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
| V63-L1-01 | Domaine `DayPlan`/`PlanRevision` + stockage atomique + idempotence + DST, JVM | L0-01 | `android/.../plan/**` (main+test), aucune autre modification | `:app:testDebugUnitTest --tests dev.atlas.coach.plan.*` + suite complète | **INTEGRATED** (`17bb723` → `f78e3d9`) |
| V63-L1-02 | Écran Plan + navigation + actions locales, intégré par le parent | L1-01 | `android/.../app/PlanScreen.kt`, `PlanController.kt`, `AtlasApp.kt`, `AtlasApplication.kt`, `AtlasScreenId.kt`, `AtlasScreens.kt`, `MainActivity.kt` | 68 tests ciblés + JVM 426 + lint/build | **ACCEPTED / INTEGRATED** (`4003095`), revue finale `GO_WITH_LIMITATIONS` |
| V63-L1-03 | Carte « prochaine action » sur HOME issue du plan approuvé | L1-01/L1-02 | `LauncherHomeScreen.kt`/`AtlasScreens.kt` (parent) | JVM + UI test | **READY — non livré** |
| V63-L2-01 | Sonde CalendarContract lecture seule + abstraction testable, sans permission manifeste | L0-01 | `android/.../calendar/**` (main+test) | JVM fake provider + compile Android | **INTEGRATED** (`81e1bd2`) |
| V63-L2-02 | Permission `READ_CALENDAR` + écran diagnostic calendriers, **accord propriétaire requis** | L2-01 + accord | manifest + `CalendarPermissionScreen.kt` | permission refusée/accordée, probe | **INTEGRATED (préparation)** (`1c34c56` → `bb15548` → `c990a75`) ; 31 ciblés, lint 0 erreur/54 avert., APK PASS ; JVM intégré 447 tests ; **installation/octroi `BLOCKED_APPROVAL`** |
| V63-L2-03 | Lecture événements/conflits réels sur Pixel + Etar installé | L2-02 + appareil | preuves `docs/v6-3/preuves/**` | Pixel + Etar, `PASS/FAIL` | **BLOCKED_DEVICE** |
| V63-L3-01 | Proposition de révision par OpenClaw, diff et confirmation groupée | L1-01, backend v3 | backend `plan_agent_v3.py`/`main.py` + Kotlin `plan` | pytest + JVM stale/expiry | **READY — scindé L3-01a (backend) / L3-01b (Android)** |
| V63-L4-01 | Écritures calendrier réelles, liens stables, réconciliation | L2-03 + L3-01 + accord | `dev/atlas/coach/calendar/write/**` | provider fake + Pixel/Etar | **BLOCKED_APPROVAL** |
| V63-L5-01 | Connexion wger lecture (auth, routines, logs), **identifiants requis** | accord | backend `integrations/wger.py`, Android `training/**` | payloads synthétiques + compte réel | **ACCEPTED / INTEGRATED** (`a56de74`) ; 37 ciblés, 158 backend + 1 skip ; diagnostic réel routine/exercise/workoutlog/workoutsession 200/200/200/200 ; re-revue `ACCEPTED_WITH_P2` |
| V63-L6-01 | Boucle sportive complète + écriture autorisée/vérifiée | L5-01 + accord | `training/**`, `ExternalOperation` | fixture puis compte réel | **BLOCKED_CREDENTIALS** |
| V63-L7-01 | `MemoryFact` local, rétention, révocation, proactivité bornée | L1-01 | `dev/atlas/coach/memory/**` | JVM rétention/revoke | **READY** |
| V63-L8-01 | Adaptateur Bridge `/health`/capabilities/runs + provenance | Bridge déployé + accord | `backend/integrations/bridge.py`, `dev/atlas/coach/research/**` | mock contrat + service réel | **READY — reprise : Bridge actif ; plugin OpenClaw consommateur principal** |
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

## Faits d'intégration vérifiés (2026-10-26)

- Etar : applicationId **`ws.xsoh.etar`** (debug `.debug`), licence GPL-3.0,
  compile/target 37. La valeur `org.dmfs.calendar` était erronée et a été
  corrigée avant intégration de V63-L2-01.
- SDK 37.2 : `CUSTOM_APP_PACKAGE` et `CUSTOM_APP_URI` existent dans
  `CalendarContract.EventsColumns` ; `CALENDAR_ACCESS_LEVEL` est déclaré dans
  `CalendarColumns` (héritage statique).
- Risque L4 : la création d'un calendrier local exige la sémantique d'un sync
  adapter (`CALLER_IS_SYNCADAPTER`, `ACCOUNT_TYPE_LOCAL`). ATLAS ne doit donc
  pas supposer qu'il peut créer son propre calendrier ; il faut qualifier un
  calendrier existant modifiable ou marquer L4 `BLOCKED_ENVIRONMENT`.
- OpenClaw Gateway : loopback `127.0.0.1:18789` actif ; aucune requête réelle
  effectuée dans ce lot.
- Bridge et wger : non configurés, non qualifiés.

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
| 2026-10-26 | V63-L0-01 | `acda7a9` | `51d126e` | revue documentaire | ACCEPTED / INTEGRATED |
| 2026-10-26 | V63-L0-02 (contrat backend plan) | `51d126e` | `ba38524` | `pytest tests/test_plan_v3*.py` : 24 PASS ; suite backend 88 PASS, 1 skip | ACCEPTED / INTEGRATED |
| 2026-10-26 | V63-L0-03 (références Etar/CalendarContract) | `ba38524` | `bc10afe` | sources primaires + `javap` SDK 37.2 | ACCEPTED / INTEGRATED |
| 2026-10-26 | V63-L2-01 (calendar read) | `51d126e` | `dd9760d` (enfant) → `81e1bd2` (intégration) | ciblé 23 PASS ; JVM complet 362 PASS, 0 échec ; manifeste/Gradle inchangés | ACCEPTED / INTEGRATED |
| 2026-10-26 | V63-L1-01 (plan domain) | `51d126e` | `17bb723` (enfant) → `f78e3d9` (intégration) | ciblé 42 PASS ; JVM enfant 381 PASS | ACCEPTED / INTEGRATED |
| 2026-10-26 | V63-L1-02 (plan UI) | `f78e3d9` | `5dedcf6` | ciblé 57 PASS ; JVM 415 PASS (re-run sur `5dedcf6`), lint 0 erreur/52 avertissements, APK et androidTest compile PASS | REVIEW_REQUIRED → 3 P1 |
| 2026-10-26 | V63-L1-02 correction P1/P2 | `5dedcf6` | `6cef04b` | ciblé 66 PASS ; JVM 424 PASS ; backend 91 passed/1 skipped ; lint 0 erreur ; build/androidTest PASS | re-revue : 1 P1 collision + 1 evidence gap |
| 2026-10-26 | V63-L1-02 collision de hachage | `6cef04b` | `4003095` | ciblé 68 PASS ; JVM 426 PASS ; backend 91 passed/1 skipped ; lint 0 erreur ; build/androidTest PASS | ACCEPTED / INTEGRATED |
| 2026-10-26 | Revue indépendante finale | `4003095` | docs courantes | re-revue ciblée : collision corrigée, preuves visibles, aucun P0/P1 ; P2 documentaire corrigé | **GO_WITH_LIMITATIONS** |

## Campagne de reprise — 26 octobre 2026

Base de reprise : `ab477ef70dcf7749fb8283b0213c4fcdaa824341` (`main`).
Branche : `feat/atlas-v6-3-reprise`. Registre unique conservé.

### Constats qui débloquent des tickets

- Pixel 9 Pro XL Android 17/API 37 connecté (pré-vol lecture seule PASS).
- Bridge actif sur `127.0.0.1:8001`, extension connectée, `accepting_runs=true`.
- OpenClaw 2026.10.5 actif, SDK `defineToolPlugin` disponible.
- `.env` contient les clés wger et Bridge (noms vérifiés, valeurs jamais lues).
- Port 8000 occupé par un tiers : backend de dev sur **8010**.

### Tickets de la reprise

| TASK_ID | Objectif observable | Dépendances | Scope d'écriture exclusif | Oracle | Statut |
|---|---|---|---|---|---|
| V63-R0-01 | Pré-vol, alignement documentaire, registre, décisions, smoke Paseo | — | `.agents/WORKFLOW.md`, `.agents/skills/README.md`, `docs/v6-3/PREFLIGHT.md`, `REGISTRE_TACHES.md`, `docs/DECISIONS_AUTOMATIQUES.md`, `docs/v6-3/tickets/**` | revue parent + smoke Paseo | **RUNNING** |
| V63-R3-SCOUT-01 | Audit lecture seule Bridge local + SDK plugin OpenClaw ; plan d'implémentation `atlas_chatgpt_research` | — | aucun (lecture seule) | rapport avec chemins, API, prérequis, risques, oracle de recette | **ACCEPTED (audit)** — `AUDIT_BRIDGE_PLUGIN.md` ; `BLOCKED_POLICY` pour la délégation tant que `deny:["*"]` reste |
| V63-R5-SCOUT-01 | Audit lecture seule transport Paseo/Relay : composants, licences, vecteurs, plan de réemploi | — | aucun (lecture seule) | rapport avec SHA, fichiers, licences, gaps Android | **ACCEPTED (audit)** — `AUDIT_TRANSPORT_PASEO.md` ; `protected`-only obligatoire |
| V63-L3-01a | Endpoint backend `POST /v3/agent/plan-revision` : OpenClaw propose `plan.revise_day`, validation stricte, idempotence durable, aucun outil | L0-02 | `backend/atlas_backend/plan_agent_v3.py`, `openclaw_adapter.py`, `main.py`, `store.py`, `policy.py`, `plan_v3.py`, `backend/tests/test_plan_agent_v3*.py` | pytest ciblé + suite backend ; 2e POST idempotent ; contenu différent même clé → 409 ; proposition invalide rejetée | **ACCEPTED / INTEGRATED** (`6240ca1` → `9dc2685` → `6b64ddc` → `8c402ee`) ; 30 ciblés, 152 backend + 1 skip ; re-revue `ACCEPTED_WITH_P2` |
| V63-L5-01 | Client wger lecture seule `ApiTokenWgerAuth` + `RotatingJwtWgerAuth` sous interface unique, stockage JWT atomique 0600, diagnostic sans secret | accord lecture | `backend/atlas_backend/integrations/**`, `backend/atlas_backend/config.py`, `backend/scripts/wger_diagnostic.py`, `backend/tests/test_wger*.py` | pytest mock 200/pagination/401/403/429/5xx/timeout/schéma ; aucun secret dans logs | **ACCEPTED / INTEGRATED** (`0f0702c` → `6e17e11` → `6aee55a` → `5f68c88` → `a56de74`) ; 37 ciblés, 158 backend + 1 skip ; re-revue `ACCEPTED_WITH_P2` ; diagnostic réel 4 ressources 200/200/200/200 ; `.env` 0600 |
| V63-L1-03 | Carte « prochaine action » sur HOME issue du plan approuvé | L1-01/L1-02 | `android/.../app/HomeNextAction.kt`, `LauncherHomeScreen.kt`, `AtlasScreens.kt`, tests ciblés | JVM ciblé + suite | **ACCEPTED / INTEGRATED** (`6f5941d` → `b6d4a7b` → `38c70d7`) ; P1 corrigé ; intégré 439 tests, lint 0 erreur/52 avert., APK PASS ; `preuves/l1-03-home-next.txt` |
| V63-L3-01b | Android : parse strict V3, diff, confirmation groupée, application locale idempotente | L3-01a (contrat gelé) | `android/.../plan/**` (nouveaux fichiers + tests) | JVM stale/expiry/idempotence/contenu altéré | **ACCEPTED / INTEGRATED** (`039ce02` → `6d78fa0` → `4df7e68`) ; 87 ciblés, 471 JVM intégrés, lint 0/54, APK PASS |
| V63-L3-01c | Câblage UI « Demander au coach », consentement, URL loopback debug, diagnostic calendrier | L3-01b | `PlanScreen.kt`, `AtlasController.kt`, `AtlasApp.kt`, `AtlasScreens.kt`, `AtlasScreenId.kt`, `SyncSettingsScreen.kt`, `BackendUrlPolicy.kt` | JVM + lint + build ; visibilité/consentement vérifiés statiquement | **ACCEPTED / INTEGRATED** (`c2f6fea` → `1791030`) ; 474 JVM, lint 0/54, APK PASS |
| V63-L8-02 | Plugin OpenClaw `atlas_chatgpt_research` (outil unique, données non fiables, provenance) | R3-SCOUT-01 | `integrations/openclaw/atlas_chatgpt_research/**` | `npm test` + build + `openclaw plugins validate` + recette Bridge mock | **REVIEW_REQUIRED** — `3c5738a`, 13 fichiers ; npm install parent PASS ; 33/34 tests, 1 échec retry ; build `dist/src` à corriger ; activation `BLOCKED_POLICY` |
| V63-L2-02 | Permission `READ_CALENDAR` + écran diagnostic calendriers | accord explicite | manifest + `CalendarPermissionScreen.kt` | permission refusée/accordée, probe | **INTEGRATED (préparation)** (`1c34c56` → `bb15548` → `c990a75`) ; 31 ciblés, lint 0/54, APK PASS ; install/octroi `BLOCKED_APPROVAL` |
| V63-L4-SCOUT-01 | Audit lecture seule du chemin d'écriture calendrier, propriété, réconciliation, plan Pixel | — | aucun (lecture seule) | rapport + proposition de ticket L4 | **ACCEPTED (audit)** — `AUDIT_CALENDAR_WRITE.md` ; écriture réelle `BLOCKED_APPROVAL`/`BLOCKED_ENVIRONMENT` |
| V63-L4-01 | Écritures calendrier réelles, liens stables, réconciliation | L2-03 + L3-01 + accord | `dev/atlas/coach/calendar/write/**` | provider fake + Pixel/Etar | **BLOCKED_APPROVAL** |
| V63-L7-01 | `MemoryFact` local, rétention, révocation | L1-01 | `dev/atlas/coach/memory/**` | JVM rétention/revoke | **READY (vague 3)** |
| V63-L9-01 | Mesure de durée UsageStats + `UsageSummary` minimisé | L0-01 | `dev/atlas/coach/usage/**` | JVM + comparaison Pixel | **READY (vague 3)** |

### Vague 1 — trois implémentations + deux scouts

| Enfant | Worktree | Périmètre | Session Paseo | Statut |
|---|---|---|---|---|
| `V63-R3-SCOUT-01` | `audit-v63-r3-bridge-plugin` (`chmod a-w`) | Bridge + SDK OpenClaw | `c818a83c-46db-473b-b38c-823be3a108f3` | **ACCEPTED (audit)** ; worktree `f625f0d`, statut vide |
| `V63-R5-SCOUT-01` | `audit-v63-r5-paseo-transport` (`chmod a-w`) | transport Paseo | `5c393291-ad7a-467a-80ee-e90a78595b0b` | **ACCEPTED (audit)** ; worktree `f625f0d`, statut vide |
| `V63-L3-01a` | `feat-v63-l3-01a-plan-backend` | backend plan revision | `6d98e14c-ec23-4b3d-aa28-1b2d7f5c0398` | **ACCEPTED / INTEGRATED** `8c402ee` |
| `V63-L5-01` | `feat-v63-l5-01-wger-read` | backend integrations wger | `998e3ac3-de59-42af-88e5-d184092e9ea6` | **ACCEPTED / INTEGRATED** `a56de74` ; diagnostic réel 4 ressources PASS |
| `V63-L5-01-REVIEW` | `review-v63-l5-01-wger-read` (`chmod a-w`) | relecture indépendante | `f4bfb411-6ad0-42b4-b749-36ae6e9addc9` | **ACCEPTED_WITH_P2** ; P1 `.env` corrigé ; P2 mémoire documenté |
| `V63-L3-01a-REVIEW` | `review-v63-l3-01a-plan-backend` (`chmod a-w`) | relecture indépendante | `97538bfb-56a2-4221-8bff-fac62c36aa5d` | **ACCEPTED_WITH_P2** ; P1/P2 traités ou documentés |
| `V63-L3-01b` | `feat-v63-l3-01b-plan-revision-android` | parse/mapping/confirmation V3 | `65d84b7e-c1a2-4279-a03b-0272ebe30af8` | **ACCEPTED / INTEGRATED** `4df7e68` ; 471 JVM intégrés |
| `V63-L3-01c` | `feat-v63-l3-01c-wiring` (parent) | câblage Plan/coach/calendrier | parent | **ACCEPTED / INTEGRATED** `1791030` ; 474 JVM, lint 0/54, APK |
| `V63-L8-02` | `feat-v63-l8-02-openclaw-research` | plugin recherche OpenClaw | `21d2cdbe-ebd3-4542-be97-a848091b76c5` | **REVIEW_REQUIRED** `3c5738a` ; correction build/test en cours |
| `V63-L1-03` | `feat-v63-l1-03-home-next` | HOME next action | `b5722d8a-db34-42eb-8ac8-2086021a99ce` | RUNNING |
| `V63-L4-SCOUT-01` | `audit-v63-l4-calendar-write` (`chmod a-w`) | écriture calendrier, lecture seule | `9b3b505a-9e11-4fe5-8d39-6cce70a91e8d` | RUNNING |
| `V63-L2-02` | `feat-v63-l2-02-calendar-read` | préparation READ_CALENDAR + écran diagnostic | `d434f447-e4e5-4de3-ac26-88fb637c2891` | **ACCEPTED / INTEGRATED (préparation)** `c990a75` ; installation/octroi non faits |

Tous les enfants : `provider=codex`, `model=gpt-6-luna`,
`effectiveThinkingOptionId=xhigh`, `modeId=auto-review`, vérifiés via
`get_agent_status` après lancement. Base commune `f625f0d`.

Contraintes communes : `provider=codex`, `model=gpt-6-luna`, `effort=xhigh`,
aucune sous-délégation, aucun push, aucune action ADB/Pixel, aucun secret réel
dans le prompt ou les fixtures, commit local dans le worktree.

### Journal de la reprise

| Date | Ticket | SHA base | SHA résultat | Oracle | Verdict |
|---|---|---|---|---|---|
| 2026-10-26 | V63-R0-01 | `ab477ef` | `f625f0d` | pré-vol + alignement + baseline backend 91 PASS / 1 skip | **ACCEPTED / INTEGRATED** |
| 2026-10-26 | Smoke Paseo reprise | `f625f0d` | — | 5 enfants Luna XHigh, 2 worktrees RO, modèle/effort vérifiés | **PASS (lancement)** |
| 2026-10-26 | V63-R5-SCOUT-01 | `f625f0d` | — | premier rapport | **BLOCKED_CONTEXT** : clones Paseo absents du worktree (`references/repos/` ignoré) ; réengagé sur le checkout principal aux SHA exacts |
| 2026-10-26 | Diagnostic Bridge réel | `f625f0d` | — | `/capabilities` + `/metrics` authentifiés, secrets non affichés | **PASS lecture** ; recherche vérifiée **NON DISPONIBLE** (`prompt_instructed`) |
| 2026-10-26 | Pré-vol Pixel | `f625f0d` | — | appareil, packages, signature, HOME, stockage | **PASS lecture seule** ; ATLAS installé signé debug, HOME = NexusLauncher |
| 2026-10-26 | Baseline JVM Android | `f625f0d` | — | `:app:testDebugUnitTest` forcé | **PASS** — 62 suites, 426 tests, 0 échec |
| 2026-10-26 | Smoke OpenClaw V2 réel | `f625f0d` | — | backend éphémère + gateway loopback | **PASS** — adapter `openclaw`, chat 200, 2 propositions, aucune action appareil |
| 2026-10-26 | V63-R3-SCOUT-01 | `f625f0d` | `f625f0d` | audit statique Bridge + SDK | **ACCEPTED (audit)** ; `GO_WITH_LIMITATIONS` plugin hors activation ; `BLOCKED_POLICY` délégation |
| 2026-10-26 | V63-R5-SCOUT-01 | `f625f0d` | `f625f0d` | audit statique Paseo/Relay | **ACCEPTED (audit)** ; Noise NK + vecteurs + Apache-2.0 ; legacy permissif à interdire |
| 2026-10-26 | V63-L4-SCOUT-01 | `4dcb7cc` | `4dcb7cc` | audit statique calendrier write | **ACCEPTED (audit)** — `AUDIT_CALENDAR_WRITE.md` ; worktree RO propre ; écriture réelle bloquée |
| 2026-10-26 | V63-L1-03 | `f625f0d` | `6f5941d` → `b6d4a7b` → `38c70d7` | revue parent + Gradle hors sandbox | **ACCEPTED / INTEGRATED** — 439 tests, lint 0 erreur/52 avert., APK PASS ; `preuves/l1-03-home-next.txt` |
| 2026-10-26 | V63-L3-01a | `f625f0d` | `6240ca1` → `9dc2685` → `6b64ddc` → `8c402ee` | 30 ciblés + backend intégré | **ACCEPTED / INTEGRATED** — 152 passed, 1 skipped ; re-revue `ACCEPTED_WITH_P2` ; UUID/RecursionError/concurrence documentés ; `preuves/l3-01a-plan-revision.txt` |
| 2026-10-26 | V63-L5-01 | `f625f0d` | `0f0702c` → `6e17e11` → `6aee55a` → `e4b480c` | 31 ciblés + backend intégré | **ACCEPTED / INTEGRATED** — 145 passed, 1 skipped ; re-revue `ACCEPTED_WITH_P2` ; diagnostic réel `api_token` 200/200, `.env` 0600 |
| 2026-10-26 | V63-L2-02 | `38c70d7` | `1c34c56` → `bb15548` → `c990a75` | ciblé + lint + build ; JVM intégré | **ACCEPTED / INTEGRATED (préparation)** — 31 ciblés, lint 0 erreur/54 avert., APK PASS ; JVM intégré 447 tests ; installation/octroi non faits |
| 2026-10-26 | V63-L5-01 qualification réelle | `e4b480c` | `5f68c88` → `a56de74` | `/api/v2/` + 4 ressources | **ACCEPTED / INTEGRATED** — IDs chaîne corrigés, sessions ajoutées, diagnostic réel 200/200/200/200 ; P2 date ISO à renforcer en L5-02 |
| 2026-10-26 | V63-L3-01b | `9d51021` | `039ce02` → `6d78fa0` → `4df7e68` | 87 ciblés + intégré | **ACCEPTED / INTEGRATED** — 471 JVM, 0 échec, lint 0/54, APK PASS ; parseur V3 borné, UUID déterministes, focus/training_ref rejetés |
| 2026-10-26 | V63-L3-01c | `4df7e68` | `c2f6fea` → `1791030` | 474 JVM + lint + build | **ACCEPTED / INTEGRATED** — UI coach consentie, `BackendUrlPolicy` loopback debug, diagnostic calendrier câblé |
| 2026-10-26 | V63-L8-02 | `9d51021` | `3c5738a` | npm parent | **REVIEW_REQUIRED** — 33/34 tests, 1 échec retry ; `dist/src` au lieu de `dist` ; correction demandée |
| 2026-10-26 | V63-L3-01a-REVIEW | `9dc2685` → `6b64ddc` | — | relecture indépendante | **ACCEPTED_WITH_P2** — session `97538bfb-56a2-4221-8bff-fac62c36aa5d` ; P1 scope résolu par ticket, P2 traités/documentés |
