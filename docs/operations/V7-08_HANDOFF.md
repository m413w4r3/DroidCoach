# ATLAS V7-08 — Handoff de reprise

**Date :** 2026-11-04
**Branche :** `feat/atlas-v7-08-core-quota-and-a0`
**BASE_SHA :** `cbdf0e2fb26190015a0d529a3ef713ed4b4379ef`
**Dernier SHA de code revu :** `2392e51dfd84ad028ef32749ef826b8283a3233a`
**Rapport complet :** `docs/operations/V7-08_FINAL_REPORT.md`
**Registre :** `docs/operations/REGISTRY_V7_08.md`

## 1. État de reprise (à relire avant toute action)

- Le Core personnel tourne avec l'image `8604535e1447` :
  `ATLAS_AGENT_DAILY_TOKENS_ENFORCED=false` et
  `ATLAS_CHAT_V4_DEADLINE_S=40`.
- Le proxy `127.0.0.1:4400` et le connecteur PC sont **arrêtés** ; les ports
  `4400/4401` sont fermés. Le Relay `4402`, le Core `8100` et le Gateway
  `18789` sont actifs.
- Le Pixel est en Wi-Fi, mode avion 0, HOME ATLAS, package `dev.atlas.coach`
  v1/0.1.0, APK `c7b0f71f…` inchangé. Aucune réinstallation, aucun `pm clear`,
  aucun `adb reverse`.
- Le plan local est en **v16** : une activité `PLANNED`
  « Test ATLAS - seance Focus » à **22:55** (`target 1375`) et plusieurs
  entrées `POSTPONED` synthétiques. Aucun planning personnel réel n'a été
  modifié.
- Une proposition A0 `e867c13c-2327-4f04-905e-51de965e2876` a été créée puis
  **APPLIQUÉE** ; l'ACK `APPLIED` est durable et son rejeu est idempotent.
- Une seconde proposition A0 `2da9c5e2-57ed-4bac-8f5f-01eb0e4b780e`
  (`base_version=16`, 22:55→23:05, `target_start_minute=1385`) est
  **PENDING_ANDROID** dans Core. Elle n'a pas été tirée dans l'application ni
  refusée. C'est le premier travail de reprise.

## 2. Ce qui fonctionne (prouvé)

- Quota local configurable : compteur toujours mesuré, plus de `429` local
  quand `ATLAS_AGENT_DAILY_TOKENS_ENFORCED=false` ; RPM, breaker, timeouts,
  retries, limites d'outils et mapping des vrais `429` conservés.
- Deadline V4 configurable : `ATLAS_CHAT_V4_DEADLINE_S=40` a permis un tour
  réel de ~24 s ; catalogue `chat_timeout_seconds=40`.
- Transport public réel : Pixel LTE → Funnel TLS :8443 → proxy 4400 →
  Relay/Noise 4402 → connecteur → Core 8100 → Gateway 18789 →
  `openai/gpt-6-luna`, sans Tailscale Android et sans `adb reverse`.
- Chat réellement outillé : `atlas_get_day_plan` puis
  `atlas_propose_plan_revision`, une seule proposition A0, aucune application
  Core.
- APPLY explicite dans Android : plan v15→v16, activité déplacée à 1375,
  ACK `APPLIED` (ack_id `49f0f982…`, séquence 1, `applied_plan_version=16`).
- Rejeu idempotent de l'ACK : un seul événement `agent_proposal_events_v4`
  malgré un second POST 200.
- Suite backend : **408 passed, 1 skipped** sur `2392e51`.
- Revues A4/A6 : aucun P0/P1 ; P2 traités ou documentés.

## 3. Ce qui ne fonctionne pas / reste à faire

1. **REJECT non terminé** : la proposition `2da9c5e2` est en attente dans Core ;
   aucun ACK `REJECTED` n'a été émis, aucun rejeu idempotent REJECT n'a été
   exécuté.
2. **Nettoyage du plan synthétique** : les entrées `POSTPONED` et l'activité
   de test restent dans le plan local/Core. Elles sont synthétiques ; ne pas
   les confondre avec un planning personnel.
3. **Toggle « Conversation V4 » non persistant** après redémarrage à froid ;
   il doit être réactivé à chaque cold start.
4. **Bouton « Pousser les projections V4 »** désactivé sans configuration
   backend directe, alors que le worker relais fonctionne. Workaround utilisé :
   ouvrir Plus → Partage backend → « Enregistrer la connexion » (déclenche
   `CoreV4SyncScheduler.schedule`), même avec URL/jeton vides.
5. **Pull des propositions** : pas de bouton explicite en mode relais ; le
   worker périodique (15 min) ou le workaround ci-dessus tire l'inbox.
6. **Mapping `STALE_DATA`** : retombe sur HTTP 400 dans le chemin chat ; non
   corrigé (diagnostic A2).
7. **P1 bearer Android partagé** : toujours ouvert, non tourné. Toute rotation
   exige une autorisation distincte.
8. **P2 préexistants** : `Future.cancel()` n'interrompt pas un outil synchrone
   déjà lancé ; enveloppe de pull non publiée dans le contrat ; compteur de
   quota en mémoire (reset au redémarrage).
9. **Android JVM/lint** : non relancés en V7-08 (aucun code Android modifié).
10. **Pilote E** : toujours **NON AUTORISÉ**.

## 4. Procédure de reprise REJECT

1. Rétablir le transport :
   ```sh
   cd /home/nill/perso/AndroCoach/atlas-preparation
   nohup node integrations/relay/proxy/src/server.mjs >/tmp/atlas-proxy.log 2>&1 &
   cd integrations/relay/pc
   nohup node scripts/run-cli.mjs "$HOME/.config/atlas/relay.env" start >/tmp/atlas-connector.log 2>&1 &
   ```
   Vérifier `curl http://127.0.0.1:4400/health`, `:4401/metrics` et
   `https://<funnel-public>:8443/health` (200 ; nom d'hôte dans la
   configuration privée, non reproduit ici).
2. Pixel : LTE, Wi-Fi désactivé, Tailscale Android non exécuté, aucun
   `adb reverse`. Ouvrir ATLAS → Plus → Connexion relayée → Connecter.
3. Activer « Conversation V4 » dans Coach (le toggle repart à zéro après un
   cold start).
4. Ouvrir Plus → Partage backend → « Enregistrer la connexion » pour déclencher
   le work immédiat ; vérifier que l'inbox tire `2da9c5e2`.
5. Plan → « Propositions reçues » → « Examiner les changements » →
   « Refuser définitivement ».
6. Vérifier dans le plan local : version inchangée v16, activité toujours à
   1375. Déclencher un nouveau work immédiat pour livrer l'ACK.
7. Vérifier en Core :
   ```sh
   docker exec atlas-core-atlas-core-1 python -c "
   import sqlite3
   con=sqlite3.connect('file:/data/atlas.sqlite3?mode=ro',uri=True)
   print(con.execute('SELECT proposal_id,state FROM agent_proposals_v4').fetchall())
   print(con.execute('SELECT proposal_id,ack_id,state,result_code,applied_plan_version FROM agent_proposal_events_v4').fetchall())
   "
   ```
   Attendre `2da9c5e2` en `REJECTED`, un événement `REJECTED`, aucun
   `applied_plan_version` et aucun plan modifié. Rejouer le work immédiat pour
   vérifier qu'aucun second événement n'est créé.
8. Nettoyer le plan synthétique par l'UI (supprimer les entrées de test), puis
   synchroniser. Ne jamais restaurer une archive par-dessus l'état actif.
9. Arrêter proxy/connecteur et restaurer Wi-Fi/HOME en fin de session.

## 5. Commandes utiles (lecture seule)

- Statut Core : `TOKEN=$(cat ~/.local/state/atlas-core/secrets/compose/atlas-core.token); curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8100/v2/agent/status`
- Snapshot plan : requête SQL `day_plan_snapshots_v4` (voir ci-dessus).
- Propositions/ACK : `agent_proposals_v4`, `agent_proposal_events_v4`.
- Logs Gateway : `journalctl --user -u openclaw-gateway.service --since "10 minutes ago"`.
- Logs Core : `docker logs --since 10m atlas-core-atlas-core-1`.
- Santé : `curl http://127.0.0.1:8100/health`, `curl http://127.0.0.1:4402/health`.

## 6. Fichiers de preuve

- `docs/operations/V7-08_FINAL_REPORT.md` — rapport complet.
- `docs/operations/REGISTRY_V7_08.md` — orchestration, SHAs, gates.
- `docs/operations/V7-08_QUOTA_POLICY.md` — quota, tests, rollback.
- `docs/operations/V7-08_CHAT_DEADLINE.md` — mesure 504, deadline, catalogue.
- `docs/operations/V7-08_DIAGNOSTIC_504_400.md` — diagnostic A2.
- `docs/operations/V7-08_A0_QA_PLAN.md` — plan QA A0.
- `backend/tests/test_v7_08_quota_policy.py`,
  `backend/tests/test_v7_08_chat_deadline.py`,
  `backend/tests/test_v7_08_a0_protocol.py` — tests.

## 7. Avertissements

- Ne pas réinstaller l'application, ne pas faire `pm clear`, ne pas modifier
  le rôle HOME sans autorisation.
- Ne pas toucher au planning personnel réel ; les seules activités présentes
  sont synthétiques, mais vérifier avant toute suppression.
- Ne pas utiliser `adb reverse` pour valider le distant.
- Ne pas réactiver `r3c-pixel-final` ni une autre identité révoquée.
- Ne pas modifier Funnel/DNS/Serve Tailscale, le Gateway, le modèle ou les
  secrets sans autorisation distincte.
- Ne pas déclarer GO sur la base d'un HTTP 200 ou d'un texte de modèle : exiger
  un `proposal_id` persistant et vérifié.
