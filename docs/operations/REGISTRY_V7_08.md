# ATLAS V7-08 — Registre d'orchestration et de preuves

**Date d'ouverture :** 2026-11-04
**Orchestrateur :** Paseo (session parent)
**Branche d'intégration :** `feat/atlas-v7-08-core-quota-and-a0`
**BASE_SHA :** `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef`
**Périmètre autorisé :** correction du quota quotidien Core, qualification du parcours
Coach A0 sur le Pixel physique, mise à jour contrôlée de `main`. Pilote E **non autorisé**.

## 1. État Git initial (vérifié après `fetch`)

| Référence | SHA | Observation |
|---|---|---|
| HEAD local initial (`main`) | `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef` | arbre propre |
| `origin/main` | `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef` | identique au local |
| `feat/atlas-v7-07-preproduction` | `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef` | identique à `main` |
| Commits V7-07 absents de `main` | aucun | V7-07 déjà intégré et poussé |
| Worktrees | nombreux worktrees historiques | aucun conflit avec V7-08 |

Le SHA de référence documentaire `9380445` cité par la mission est un ancêtre de
`cbdf0e2` ; aucun commit V7-07 n'a été perdu.

## 2. Environnement au démarrage

| Élément | État |
|---|---|
| Core Docker `atlas-core-atlas-core-1` | actif, healthy, image hash `00b88ef9…` |
| Gateway OpenClaw | `openclaw-gateway.service` actif, `127.0.0.1:18789` |
| Relay | `atlas-v7-relay` actif, `127.0.0.1:4402` |
| Proxy public `4400` | arrêté (état V7-07) |
| Funnel Tailscale | `tcp://all.taildc7886.ts.net:8443 → 127.0.0.1:4400` |
| Pixel ADB | `45301FDAS001QT`, Pixel 9 Pro XL, Android 17 / SDK 37.1 |
| `dev.atlas.coach` | versionCode 1, versionName 0.1.0, HOME `LauncherActivity` |
| Quota Core au démarrage | `daily_tokens=102062 / 100000`, `limited=true` |
| Snapshot plan Core | `plan_version=4`, `consent_version=4`, observé `2026-11-04T16:22:01Z` |
| Consentements Core | `DAY_PLAN v4 GRANTED`, `ACTIVITY_STATUS v3 REVOKED` |
| Propositions Core | 0 |
| Conversation locale | `agentConversationId` hash `eea09c4751a6f2fa…`, 13 messages |

## 3. Équipe Luna (Paseo)

| Tâche | Agent ID | Modèle effectif | Effort effectif | Worktree | Statut |
|---|---|---|---|---|---|
| A1 quota | `db38370c-b557-41af-b932-df865cc1cbda` | `codex/gpt-6-luna` | `xhigh` | `v7-08-a1-quota` | ACCEPTED — sortie `60f926a`, intégrée `56705da` |
| A2 diagnostic 504/400 | `36257e6e-f71d-43a6-877b-80f7809de4ad` | `codex/gpt-6-luna` | `xhigh` | `v7-08-a2-diag` | RUNNING |
| A3 QA protocole A0 | `a77e26b2-1a1a-47cc-b6b3-2abcb885b3fa` | `codex/gpt-6-luna` | `xhigh` | `v7-08-a3-qa` | RUNNING |
| A4 revue sécurité | à lancer après A1 | `codex/gpt-6-luna` | `xhigh` | lecture seule | PENDING |
| A5 revue intégration/Git | à lancer avant publication | `codex/gpt-6-luna` | `xhigh` | lecture seule | PENDING |

Règles appliquées : aucun push, aucun merge, aucun ADB par les enfants ; un
propriétaire par fichier ; Paseo seul intégrateur ; concurrence ≤ 6 enfants et
≤ 3 producteurs de code.

## 4. Constats techniques initiaux

1. Le plafond quotidien est local : `OpenClawAgentAdapter.daily_token_limit`
   (`ATLAS_AGENT_DAILY_TOKENS`, défaut 100000) et blocage dans
   `_begin_attempt`. Le compteur est en mémoire, réinitialisé au changement de
   jour UTC et à chaque redémarrage. Le `429` V7-07 n'est pas une preuve de
   limite OpenAI.
2. Le chemin `atlas_propose_plan_revision` exige une projection de moins de
   5 minutes (`FRESHNESS_AGE`). Le snapshot Core du 2026-11-04 a été observé à
   `16:22:01Z` ; les tentatives postérieures sont hors fenêtre.
3. `_chat_error_response` ne contient pas de mapping `STALE_DATA → 409` ; un
   rejet `STALE_DATA` d'outil ressort donc en HTTP 400 avec ce code, ce qui
   peut expliquer le `400` observé (à confirmer par A2, pas encore PROVEN).
4. Le journal Gateway du 2026-11-04 contient un `caller-abort` /
   `ClientDisconnectError` à `16:40:54Z`, compatible avec le `504` de 20 s.
5. L'activité synthétique « Test ATLAS - seance Focus » est planifiée
   `2026-11-04 18:00–18:45 Europe/Paris`, plan v4, et est la seule activité du
   plan du jour. Le snapshot Core doit être rafraîchi avant tout chat A0.
6. La politique A1 locale de cette activité est désormais `PERSONNEL` /
   `unlocked=true`, après classement explicite dans l'écran Plan ; le plan local
   est en version 5 (21:00–21:45) et doit être resynchronisé vers Core.
7. Preuves A1 : `tests/test_v7_08_quota_policy.py` (14 tests), ciblés
   `test_agent_v2.py` + `test_chat_orchestration_v4.py` (101 passed, 1 skipped
   sur le SHA intégré), suite backend complète **388 passed, 1 skipped** sur
   `56705da`.

## 5. Sauvegardes et rollback

| Élément | Emplacement privé | État |
|---|---|---|
| Config Compose pré-déploiement | `~/.local/state/atlas-v7-08/core-backup/20261104T164105Z/` | créé, `SHA256SUMS` |
| Base Core pré-déploiement | `…/20261104T164105Z/atlas.sqlite3.bak` | `integrity_check=ok`, 3 snapshots, 2 consentements, 0 proposition |
| Secrets Core | non copiés dans le dépôt | préservés |
| Image Core courante | `atlas-core-atlas-core:v7-07-live` = `00b88ef90466` | tag de rollback créé avant rebuild |
| Rollback quota | `ATLAS_AGENT_DAILY_TOKENS_ENFORCED=true` + image `v7-07-live` | à documenter |

## 6. Gates

| Gate | État |
|---|---|
| `GO_CORE_QUOTA_POLICY` | PENDING |
| `GO_CORE_ROLLBACK` | PENDING |
| `GO_LIVE_OPENCLAW_CONFIG` | PENDING |
| `GO_PRODUCT_LOCAL_SMOKE` | PENDING (héritage V7-07 GO_WITH_LIMITATIONS) |
| `GO_PRODUCT_REMOTE_CHAT` | PENDING |
| `GO_PRODUCT_PLAN_REVISION` | PENDING |
| `GO_PRODUCT_REMOTE` | PENDING |
| `GO_LIVE_SECURITY` | PENDING |
| `GO_SOURCE_AND_CI` | PENDING |
| `GO_MAIN_INTEGRATION` | PENDING |
| `GO_DAILY_PILOT` | **NO-GO** (E non autorisé) |

## 7. Journal des décisions

| Date (UTC) | Décision | Motif |
|---|---|---|
| 2026-11-04T16:39Z | Branche d'intégration créée sur `cbdf0e2` | `main` déjà à jour, V7-07 intégré |
| 2026-11-04T16:40Z | 3 agents Luna XHigh lancés en worktrees disjoints | indépendance A1/A2/A3 |
| 2026-11-04T16:41Z | Sauvegarde config Compose avant tout déploiement | réversibilité |
| 2026-11-04T16:44Z | Pas de réinitialisation manuelle du compteur quota | preuve par tests + config, pas de contournement |
| 2026-11-04T17:55Z | A1 accepté et cherry-pické sur `feat/atlas-v7-08-core-quota-and-a0` (`56705da`) | diff minimal, 101 ciblés + 388 suite backend PASS |
| 2026-11-04T17:56Z | Plan synthétique déplacé à 21:00–21:45, A1 `PERSONNEL` déverrouillée, plan local v5 | activité future du jour, sauvegarde app-only `90098635…` |
| 2026-11-04T18:02Z | `compose.openclaw.yaml` active explicitement `ATLAS_AGENT_DAILY_TOKENS_ENFORCED=false` | environnement personnel, mesure conservée, rollback par `true` |

Les mises à jour de ce registre sont append-only ; chaque verdict final doit
référencer un SHA exact et une commande reproductible.
