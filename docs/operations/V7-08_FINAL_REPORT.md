# ATLAS V7-08 — Rapport final : quota Core, deadline et parcours A0

**Date :** 2026-11-04
**Branche d'intégration :** `feat/atlas-v7-08-core-quota-and-a0`
**BASE_SHA :** `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef`
**Dernier SHA de code revu :** `2392e51dfd84ad028ef32749ef826b8283a3233a`
**Pilote E :** **NON AUTORISÉ**, non lancé.

## 1. État Git initial

| Référence | SHA | Observation |
|---|---|---|
| `main` / `origin/main` | `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef` | identiques |
| `feat/atlas-v7-07-preproduction` | `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef` | identique à `main` |
| Commits V7-07 absents de `main` | aucun | V7-07 déjà intégré |
| Branche V7-08 | créée sur `cbdf0e2` | aucun reset, aucun force-push |

## 2. Orchestration

Tous les sous-agents ont été lancés via Paseo en `codex/gpt-6-luna`,
`thinkingOptionId=xhigh`, worktree isolé, sans sous-délégation. Les revues A4 et
A6 ont été exécutées en mode Plan et worktree en lecture seule (`chmod -R a-w`).
Paseo a intégré chaque commit après inspection du diff et relance des tests.

| Tâche | Rôle | Sortie | Revue parent |
|---|---|---|---|
| A1 | quota Core + tests + doc | `60f926a`, puis `cadf1c3` | ACCEPTED, cherry-pick `56705da`, `346e129` |
| A2 | diagnostic 504/400/200 sans outil | `8228922` | ACCEPTED, cherry-pick `67cbf61` |
| A3 | tests protocole A0 + plan QA | `610634c` | ACCEPTED, cherry-pick `8833da7` |
| A4 | revue sécurité/architecture | rapport | GO_WITH_LIMITATIONS, aucun P0/P1 |
| A6 | revue deadline + delta P2 | rapports | GO_WITH_LIMITATIONS, aucun P0/P1 |
| A5 | revue intégration/Git | à lancer avant merge | PENDING |

Limites de concurrence respectées : 6 enfants maximum, 3 producteurs de code
maximum, un propriétaire par fichier.

## 3. Cause exacte du quota `429`

- Le plafond était **local au Core**, pas une limite OpenAI.
- `OpenClawAgentAdapter` initialisait `daily_token_limit` depuis
  `ATLAS_AGENT_DAILY_TOKENS` (défaut `100000`) et `_begin_attempt` refusait la
  tentative dès `_daily_tokens + réservation > limite`.
- Le compteur est en mémoire, alimenté par des réservations
  `payload + max_tokens` puis ajusté à l'usage réel ; il est remis à zéro au
  changement de jour UTC et au redémarrage du processus.
- L'état V7-07 observé était `daily_tokens=102062 / 100000`, `limited=true`,
  HTTP `429` après le premier outil, sans proposition.
- Le `429` n'explique ni le `504` de 20 s ni le `400` observé en V7-07.

## 4. Correction retenue

**Quota** — `ATLAS_AGENT_DAILY_TOKENS_ENFORCED` :
- booléen strict `true`/`false`, absent = `true` (comportement historique) ;
- valeur invalide = échec explicite à la construction (fail closed) ;
- `false` : seul le refus local fondé sur le compteur quotidien est désactivé ;
  la réservation, la comptabilisation et l'ajustement restent inchangés ;
- `status().quota` expose `daily_tokens`, `daily_token_limit` et le nouveau
  `daily_token_limit_enforced` ;
- RPM, fenêtre 60 s, timeout par requête, deadline de tour, circuit breaker,
  retries bornés, concurrence, limites d'outils/propositions, consentements,
  identités et mapping des vrais `429` fournisseur sont conservés ;
- `compose.openclaw.yaml` fixe `ATLAS_AGENT_DAILY_TOKENS_ENFORCED: "false"`
  pour ce déploiement personnel ; rollback par `true` ou suppression.

**Deadline V4** — `ATLAS_CHAT_V4_DEADLINE_S` :
- défaut `20.0` conservé ; bornes `1.0–50.0` ; invalide = erreur explicite ;
- `compose.openclaw.yaml` fixe `40` pour ce déploiement ;
- le catalogue `/internal/v4/capabilities` annonce désormais
  `chat_timeout_seconds = ceil(deadline)` et le schéma autorise `1–50` ;
- le timeout par requête (`OPENCLAW_TIMEOUT_S=30`), les retries, le breaker,
  les limites d'outils/propositions et le cache idempotent sont inchangés.

## 5. Tests de quota et preuves

- `backend/tests/test_v7_08_quota_policy.py` : 16 tests (défaut, override,
  invalides, dépassement avec comptabilisation, RPM, breaker, timeout, `429`
  fournisseur, chemin `propose_plan_revision`, rollback).
- `backend/tests/test_v7_08_chat_deadline.py` : défaut, override, invalides,
  processus neuf adaptateur **et** routeur, propagation.
- `test_v4_routes.py` : catalogue par défaut et surcharge `40,5 → 41`.
- Suite backend complète sur le SHA intégré `2392e51` : **408 passed,
  1 skipped** (avertissements Starlette/OpenAPI préexistants).
- A4 : aucun P0/P1 sur le correctif quota ; A6 : aucun P0/P1 sur le correctif
  deadline et son delta.
- Preuve live : `daily_token_limit_enforced=false`, `limited=false`,
  `daily_tokens=54207` mesurés pendant un tour V4 réel ; aucun `429` local.
  Le seuil >100000 n'a pas été reproduit en live après redémarrage ; il est
  prouvé par les tests unitaires.

## 6. Sauvegardes et rollback Core

- Sauvegarde privée de la configuration Compose et de la base avant
  déploiement ; `PRAGMA integrity_check=ok`, 3 snapshots, 2 consentements,
  0 proposition au moment de la sauvegarde.
- Images de rollback : `atlas-core-atlas-core:v7-08-quota-only` (`89d921fe`)
  et `v7-07-live` (`00b88ef9`).
- Rollback quota : `ATLAS_AGENT_DAILY_TOKENS_ENFORCED=true` + redémarrage.
- Rollback deadline : `ATLAS_CHAT_V4_DEADLINE_S=20` ou suppression + redémarrage.
- Aucun rollback live n'a été exécuté ; les procédures sont documentées et
  testées, mais le compteur mémoire ne survit pas à un redémarrage.

## 7. Recette Pixel

| Contrôle | Résultat |
|---|---|
| Appareil | Pixel 9 Pro XL, Android 17 / SDK 37.1, un seul appareil ADB |
| Paquet | `dev.atlas.coach`, versionCode 1, versionName 0.1.0 |
| APK | `c7b0f71f43680f4bb45d8c9e6b808713ea09ff5f826fd79db31acce8ff4355a0`, inchangé, aucune réinstallation |
| Signature / permissions | inchangées (`da301466…eeee`) |
| HOME | `dev.atlas.coach/.app.LauncherActivity`, conservé |
| Sauvegardes app-only | 3 sauvegardes vérifiées avant rotations et actions UI |
| Réseau pendant D | LTE, Wi-Fi désactivé, Tailscale Android non exécuté, aucun `adb reverse` |
| Transport | DNS/TLS public PASS, Funnel `:8443` → proxy `4400` → Relay `4402` ; WSS/Noise et pinning PASS ; session `pixel-v7` active |
| Modèle réel | `openai/gpt-6-luna` via Gateway OpenClaw, `profile=minimal`, 6 outils client |
| État final | transport arrêté (`4400/4401` fermés), Wi-Fi restauré, HOME ATLAS, téléphone utilisable |

## 8. Modèle réel et outils réellement exécutés

Le tour A0 réussi a été exécuté depuis l'application produit, en LTE, via le
Funnel public, le Relay/Noise, le connecteur PC, le Core, le Gateway OpenClaw
et le modèle réel. Les journaux Gateway montrent :

- catalogue des 6 outils client ;
- run 1 terminé `stopReason=tool_calls` (`atlas_get_day_plan`) ;
- run 2 terminé `stopReason=tool_calls` (`atlas_propose_plan_revision`) ;
- run final `stopReason=stop` annonçant la proposition créée sans application.

La session Gateway utilisée était une session neuve issue d'une rotation
non destructive du `conversation_id`, avec messages/plans/consentements/identité
préservés.

## 9. Résultat A0 : proposition, APPLY, ACK, idempotence, REJECT

**A0 obtenu** : proposition `e867c13c-2327-4f04-905e-51de965e2876`
- `base_version=15`, `confirmation_mode=A0_USER_REVIEW`,
  `auto_application=NONE_AT_CORE` ;
- changement `reschedule` de l'activité `e6d34cf3…` de 22:45 à 22:55
  (`target_start_minute=1375`) ;
- créée à `2026-11-04T19:25:05Z`, aucune application automatique ;
- tirée dans l'application par le worker V4 (entrée inbox, mode A0).

**APPLY** : effectué explicitement dans Android.
- Plan local v15 → **v16**, activité déplacée à `1375` (22:55).
- ACK durable : `ack_id=49f0f982…`, `sequence=1`, `state=APPLIED`,
  `result_code=APPLIED`, `applied_plan_version=16`,
  `revision_id=73a7c3e2…`, `occurred_at=19:27:50.762Z`,
  `received_at=19:28:13Z`.
- Plan v16 poussé au Core (`observed_at=19:28:13Z`).

**Idempotence** : le worker a rejoué le même ACK
(`POST /v4/agent/proposals/e867c13c…/ack 200 OK`) ; le nombre d'événements
`agent_proposal_events_v4` est resté **1**, sans double mutation.

**REJECT : NON TERMINÉ.**
- Une seconde proposition `2da9c5e2-57ed-4bac-8f5f-01eb0e4b780e` a bien été
  créée (`base_version=16`, A0, 22:55→23:05, `target_start_minute=1385`,
  créée `2026-11-04T19:28:58Z`, `PENDING_ANDROID`).
- Elle n'a **pas** été tirée dans l'application ni refusée avant la clôture de
  la session ; aucun ACK `REJECTED` n'a été émis. Le scénario REJECT et son
  rejeu idempotent restent donc `NOT_RUN`.

**Tentatives antérieures non probantes** :
- un tour V4 sans nom d'outil explicite a renvoyé HTTP 200 avec
  `tool_call_count=0` et aucune proposition : ce n'est **pas** un A0 ;
- une proposition de décalage à 21:55 a été bloquée par chevauchement avec des
  entrées `POSTPONED` synthétiques ; aucune proposition n'a été créée ;
- le plan a ensuite été déplacé à 22:45–23:30, hors chevauchement, avant la
  tentative réussie.

## 10. Erreurs 504/400 restantes

- **504** : reproduit et mesuré après la correction du quota. Première
  complétion provider 16,36 s, seconde complétion coupée après 3,25 s par
  l'échéance totale de 20 s ; HTTP 504, zéro proposition. Le correctif
  `ATLAS_CHAT_V4_DEADLINE_S=40` a permis les tours suivants (le tour A0 réussi
  a duré environ 24 s, sous 40 s).
- Le `Future.cancel()` à l'échéance n'interrompt pas un dispatch d'outil
  synchrone déjà lancé ; c'est un P2 préexistant, documenté par A2/A6.
- **400** : A2 a démontré que `STALE_DATA` retombe sur HTTP 400 dans
  `_chat_error_response` (absence de mapping), alors que le mapping général V4
  le classe 409. Le corps avec outils ne porte pas `error.code`. Le `400` V7-07
  reste **INCONCLUSIVE** : validation d'arguments, `STALE_DATA` ou autre
  `V4ToolError` n'ont pas pu être départagés sans journaux corrélés. Ce mapping
  n'a pas été modifié.
- **200 sans outil** : ne vaut pas A0 ; le critère retenu exige un
  `proposal_id` persistant, vérifié en base.

## 11. CI, revues et provenance

- Tests backend sur `2392e51` : **408 passed, 1 skipped**.
- `git show --check`, `git diff --check` : PASS.
- A4 : GO_WITH_LIMITATIONS, aucun P0/P1 ; P2 traités ou documentés.
- A6 : GO_WITH_LIMITATIONS sur le deadline et son delta, aucun P0/P1 ;
  P2 catalogue/registre traités, P2 `Future.cancel` préexistant documenté.
- Aucun test Android JVM/lint instrumenté n'a été relancé en V7-08 (aucun code
  Android modifié) : **NOT_RUN** pour cette campagne ; les résultats V7-07
  restent la référence.
- Aucun secret, token, dump privé ou donnée personnelle n'a été introduit dans
  les commits. Les identifiants de session, séries/empreintes et préfixes de
  conversation ont été retirés du registre.

## 12. Mise à jour de `main`

- Revue A5 sur `883e92a` : Git PASS, aucun P0/P1 nouveau, tests backend
  **408 passed, 1 skipped** sur une copie exacte ; verdict strict
  `GO_MAIN_INTEGRATION = NO-GO` en raison du P1 bearer Android partagé,
  **préexistant et hors du diff V7-08**.
- Décision propriétaire : le propriétaire a explicitement demandé le merge et
  la clôture le 2026-11-04, en connaissance du P1 documenté. La dérogation P1
  est consignée ; aucun nouveau P1 n'est introduit par V7-08.
- Merge : fast-forward de `cbdf0e2` vers le SHA de la branche
  `feat/atlas-v7-08-core-quota-and-a0`, puis push `origin/main`.
- Le SHA final exact et la vérification `origin/main` sont consignés dans le
  commit documentaire post-merge.

## 13. Verdicts et risques

| Gate | Verdict | Portée |
|---|---|---|
| `GO_CORE_QUOTA_POLICY` | **GO_WITH_LIMITATIONS** | mesure live sans `429`, seuil >100000 prouvé par tests seulement |
| `GO_CORE_ROLLBACK` | **GO_WITH_LIMITATIONS** | procédures documentées/testées, non exécutées live |
| `GO_LIVE_OPENCLAW_CONFIG` | **GO_WITH_LIMITATIONS** | Gateway/modèle/secrets/Tailscale non modifiés |
| `GO_PRODUCT_LOCAL_SMOKE` | **GO_WITH_LIMITATIONS** | héritage V7-07, non rejoué |
| `GO_PRODUCT_REMOTE_CHAT` | **GO_WITH_LIMITATIONS** | chat réel outillé et A0 obtenu ; un tour antérieur sans outil, REJECT non terminé |
| `GO_PRODUCT_PLAN_REVISION` | **GO_WITH_LIMITATIONS** | A0 + APPLY + ACK APPLIED + rejeu idempotent ; REJECT NOT_RUN |
| `GO_PRODUCT_REMOTE` | **GO_WITH_LIMITATIONS** | transport/chat/apply réels ; REJECT incomplet, P1 bearer |
| `GO_LIVE_SECURITY` | **GO_WITH_LIMITATIONS** | aucun P0/P1 nouveau ; P1 bearer Android partagé toujours ouvert |
| `GO_SOURCE_AND_CI` | **GO_WITH_LIMITATIONS** | backend 408/1, Android lint/JVM NOT_RUN |
| `GO_MAIN_INTEGRATION` | **GO_WITH_LIMITATIONS** | merge ff-only sous dérogation propriétaire ; A5 strict NO-GO sur le P1 bearer préexistant |
| `GO_DAILY_PILOT` | **NO-GO** | E non autorisé, REJECT incomplet |

**Risques ouverts**
- **P1** : bearer Android partagé, non tourné (hors autorisation).
- **P2** : `Future.cancel()` n'interrompt pas un outil synchrone déjà lancé.
- **P2** : enveloppe de pull des propositions non publiée dans le contrat.
- **P2** : bouton « Pousser les projections V4 » dépendant d'une config
  backend directe, alors que le worker relais fonctionne ; workaround utilisé
  via « Enregistrer la connexion ».
- **P2** : toggle « Conversation V4 » non persistant après redémarrage à froid.
- **P2** : `STALE_DATA` retombe sur HTTP 400 dans le chemin chat.
- **P2** : compteur de quota en mémoire, remis à zéro au redémarrage.

## 14. État final du Pixel et des services

- Pixel : Wi-Fi restauré, mode avion 0, HOME ATLAS (`dev.atlas.coach`),
  téléphone utilisable, aucune réinstallation, données/Keystore/signature
  préservés.
- Application : hors ligne ; transport proxy/connecteur arrêté ; `4400/4401`
  fermés ; aucun `adb reverse`.
- Core : image `8604535e1447` healthy ; `ATLAS_AGENT_DAILY_TOKENS_ENFORCED=false`,
  `ATLAS_CHAT_V4_DEADLINE_S=40`, catalogue 40 ; DB préservée.
- Relay `atlas-v7-relay` et `openclaw-gateway.service` actifs.
- Funnel/DNS/Serve Tailscale et identités Relay inchangés ; `pixel-v7` seule
  identité active.
- Plan synthétique : une activité `PLANNED` « Test ATLAS - seance Focus » à
  22:55 (v16), plusieurs entrées `POSTPONED` synthétiques ; proposition
  `e867c13c` APPLIED ; proposition `2da9c5e2` PENDING_ANDROID dans Core.
