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
| Funnel Tailscale | Funnel public TLS actif (nom d'hôte public non reproduit dans ce registre) |
| Pixel ADB | un seul appareil, Pixel 9 Pro XL, Android 17 / SDK 37.1 (série et empreinte non publiées) |
| `dev.atlas.coach` | versionCode 1, versionName 0.1.0, HOME `LauncherActivity` |
| Quota Core au démarrage | `daily_tokens=102062 / 100000`, `limited=true` |
| Snapshot plan Core | `plan_version=4`, `consent_version=4`, observé `2026-11-04T16:22:01Z` |
| Consentements Core | `DAY_PLAN v4 GRANTED`, `ACTIVITY_STATUS v3 REVOKED` |
| Propositions Core | 0 |
| Conversation locale | UUID rotationné, valeur et hash non publiés ; 13 messages |

## 3. Équipe Luna (Paseo)

| Tâche | Session Paseo | Modèle effectif | Effort effectif | Worktree | Statut |
|---|---|---|---|---|---|
| A1 quota | ID privé non reproduit | `codex/gpt-6-luna` | `xhigh` | `v7-08-a1-quota` | ACCEPTED — sortie `60f926a`, intégrée `56705da` |
| A2 diagnostic 504/400 | ID privé non reproduit | `codex/gpt-6-luna` | `xhigh` | `v7-08-a2-diag` | ACCEPTED — rapport `8228922`, intégré `67cbf61` |
| A3 QA protocole A0 | ID privé non reproduit | `codex/gpt-6-luna` | `xhigh` | `v7-08-a3-qa` | ACCEPTED — tests `610634c`, intégrés `8833da7` |
| A4 revue sécurité | ID privé non reproduit | `codex/gpt-6-luna` | `xhigh` | lecture seule | GO_WITH_LIMITATIONS, P2 traités |
| A6 revue deadline | ID privé non reproduit | `codex/gpt-6-luna` | `xhigh` | lecture seule | GO_WITH_LIMITATIONS, P2 traités |
| A5 revue intégration/Git | ID privé non reproduit | `codex/gpt-6-luna` | `xhigh` | lecture seule | Git/tests PASS ; NO-GO strict P1 bearer préexistant ; dérogation propriétaire pour le merge |

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
8. **Quota live prouvé** : après déploiement, `daily_token_limit_enforced=false`,
   `daily_tokens=54207` mesurés lors d'un tour V4 réel, `limited=false` ; aucun
   `429` local.
9. **504 mesuré** le 2026-11-04 : première complétion provider 16,36 s,
   seconde complétion coupée après 3,25 s par le budget total de 20 s ; HTTP
   504, `agent_proposals_v4=0`. Correctif minimal : `ATLAS_CHAT_V4_DEADLINE_S`
   (défaut 20 s conservé, valeur personnelle 40 s), tests dédiés, suite
   **408 passed, 1 skipped**.
10. **Rotations de conversation** : après le 504, trois rotations
    supplémentaires non destructives ont été exécutées (valeurs et hashes non
    publiés), chacune avec sauvegarde app-only préalable et vérification que
    seul `agentConversationId` changeait. Messages, plans, consentements,
    identité Relay et clés préservés. Le plan local final est en **v16** avec
    une seule activité `PLANNED` (« Test ATLAS - seance Focus », 22:55–23:40,
    `target_start_minute=1375`) et cinq entrées `POSTPONED` synthétiques.
11. **A0 produit prouvé** : proposition `e867c13c-2327-4f04-905e-51de965e2876`,
    `base_version=15`, `A0_USER_REVIEW`, `NONE_AT_CORE`, déplacement
    22:45→22:55, créée `2026-11-04T19:25:05Z`. APPLY explicite dans Android :
    plan v15→v16, ACK durable `APPLIED` (`ack_id=49f0f982…`, séquence 1,
    `applied_plan_version=16`). Rejeu du même ACK : un seul événement
    `agent_proposal_events_v4`, aucune double mutation.
12. **REJECT non terminé** : seconde proposition
    `2da9c5e2-57ed-4bac-8f5f-01eb0e4b780e` (`base_version=16`, 22:55→23:05,
    `PENDING_ANDROID`, créée `19:28:58Z`) non tirée dans l'application et non
    refusée. Aucun ACK `REJECTED`. Voir le handoff V7-08 pour la reprise.

## 5. Sauvegardes et rollback

| Élément | Emplacement privé | État |
|---|---|---|
| Config Compose pré-déploiement | répertoire privé hors dépôt, horodaté | créé, `SHA256SUMS` |
| Base Core pré-déploiement | copie privée hors dépôt | `integrity_check=ok`, 3 snapshots, 2 consentements, 0 proposition |
| Secrets Core | non copiés dans le dépôt | préservés |
| Image Core courante | `atlas-core-atlas-core:v7-08-quota-only` = `89d921fee385` | tag de rollback créé avant le rebuild deadline |
| Image Core initiale | `atlas-core-atlas-core:v7-07-live` = `00b88ef90466` | rollback complet pré-V7-08 |
| Rollback deadline | `ATLAS_CHAT_V4_DEADLINE_S=20` ou suppression + redémarrage | documenté dans `V7-08_CHAT_DEADLINE.md`, non exécuté live |
| Rollback quota | `ATLAS_AGENT_DAILY_TOKENS_ENFORCED=true` + image de rollback | documenté dans `V7-08_QUOTA_POLICY.md`, test de retour à `true` PASS ; non exécuté live |

## 6. Gates

| Gate | État |
|---|---|
| `GO_CORE_QUOTA_POLICY` | **GO_WITH_LIMITATIONS** — mesure live sans `429`, seuil >100000 prouvé par tests |
| `GO_CORE_ROLLBACK` | **GO_WITH_LIMITATIONS** — documenté/testé, non exécuté live |
| `GO_LIVE_OPENCLAW_CONFIG` | **GO_WITH_LIMITATIONS** — Gateway/modèle/secrets/Tailscale inchangés |
| `GO_PRODUCT_LOCAL_SMOKE` | **GO_WITH_LIMITATIONS** (héritage V7-07, non rejoué) |
| `GO_PRODUCT_REMOTE_CHAT` | **GO_WITH_LIMITATIONS** — A0 outillé réel obtenu ; un tour antérieur sans outil |
| `GO_PRODUCT_PLAN_REVISION` | **GO_WITH_LIMITATIONS** — APPLY + ACK APPLIED + rejeu idempotent ; REJECT NOT_RUN |
| `GO_PRODUCT_REMOTE` | **GO_WITH_LIMITATIONS** — REJECT incomplet, P1 bearer |
| `GO_LIVE_SECURITY` | **GO_WITH_LIMITATIONS** — aucun P0/P1 nouveau, P1 bearer ouvert |
| `GO_SOURCE_AND_CI` | **GO_WITH_LIMITATIONS** — backend 408/1, Android JVM/lint NOT_RUN |
| `GO_MAIN_INTEGRATION` | **GO_WITH_LIMITATIONS** — merge ff-only sous dérogation propriétaire ; A5 strict NO-GO sur le P1 bearer préexistant |
| `GO_DAILY_PILOT` | **NO-GO** (E non autorisé, REJECT incomplet) |

## 7. Journal des décisions

| Date (UTC) | Décision | Motif |
|---|---|---|
| 2026-11-04T16:39Z | Branche d'intégration créée sur `cbdf0e2` | `main` déjà à jour, V7-07 intégré |
| 2026-11-04T16:40Z | 3 agents Luna XHigh lancés en worktrees disjoints | indépendance A1/A2/A3 |
| 2026-11-04T16:41Z | Sauvegarde config Compose avant tout déploiement | réversibilité |
| 2026-11-04T16:44Z | Pas de réinitialisation manuelle du compteur quota | preuve par tests + config, pas de contournement |
| 2026-11-04T17:55Z | A1 accepté et cherry-pické sur `feat/atlas-v7-08-core-quota-and-a0` (`56705da`) | diff minimal, 101 ciblés + 388 suite backend PASS |
| 2026-11-04T17:56Z | Plan synthétique déplacé à 21:00–21:45, A1 `PERSONNEL` déverrouillée, plan local v5 | activité future du jour, sauvegarde app-only privée vérifiée |
| 2026-11-04T18:02Z | `compose.openclaw.yaml` active explicitement `ATLAS_AGENT_DAILY_TOKENS_ENFORCED=false` | environnement personnel, mesure conservée, rollback par `true` |
| 2026-11-04T18:20Z | Correctif deadline `ATLAS_CHAT_V4_DEADLINE_S` (défaut 20 s, live 40 s) | 504 mesuré : 16,36 s + seconde complétion > 3,25 s ; revue A6 requise avant déploiement |
| 2026-11-04T19:25Z | Proposition A0 `e867c13c…` créée (base v15, 22:45→22:55, A0_USER_REVIEW, NONE_AT_CORE) | modèle et outils réels, aucune application Core |
| 2026-11-04T19:27Z | APPLY explicite Android : plan v16, ACK `APPLIED` durable (`applied_plan_version=16`) | preuve produit réelle |
| 2026-11-04T19:28Z | Rejeu ACK : un seul événement ; seconde proposition `2da9c5e2…` créée (base v16, 22:55→23:05) | idempotence APPLIED prouvée ; REJECT à reprendre |
| 2026-11-04T19:29Z | Clôture demandée par le propriétaire ; REJECT non exécuté, handoff préparé | téléphone à restaurer |
| 2026-11-04T19:41Z | Transport proxy/connecteur arrêté, Wi-Fi restauré, HOME ATLAS, aucune réinstallation | état final sûr |
| 2026-11-04T19:45Z | A5 : Git/tests PASS, aucun P0/P1 nouveau, NO-GO strict à cause du P1 bearer préexistant ; merge demandé par le propriétaire | dérogation P1 consignée, risque non masqué |

Les mises à jour de ce registre sont append-only ; chaque verdict final doit
référencer un SHA exact et une commande reproductible.
