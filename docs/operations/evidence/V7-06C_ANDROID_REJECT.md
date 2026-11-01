# V7-06C — Android REJECT physique (Pixel QA)

**Date :** 2026-11-01
**Résultat :** **PASS** — `GO_ANDROID_QA_REJECT` ; avec APPLY, `GO_QA_COACH_LOOP`.
**Exécution réelle :** SHA `b4db8da5f3ae3aa1ea85d52a4bcaf996f4d3744a`
**Oracle post-hoc :** harnais corrigé `5ca0a927ecac7c0811f6c465bc4100a23c3da966`,
lecture seule, **aucune nouvelle instrumentation**.
**Preuve privée :** `v7-06c-reject-2fb57424d534.json` (0600, hors dépôt)
**Registre :** `docs/operations/REGISTRY_V7_06C.md`

## 1. Appareil et artefacts

| Élément | Valeur |
|---|---|
| Appareil | Pixel 9 Pro XL, Android 17 / API 37, arm64-v8a |
| Série | hashée `f2e9258b85327628c8d25b1652b00601e698f9ccb2a8b23fb1e913ff355a5946` |
| Paquet QA | `dev.atlas.coach.qa.v706b.v706creject.b4d9e602` |
| Paquet test | `dev.atlas.coach.qa.v706b.v706creject.b4d9e602.test` |
| APK app SHA-256 | `b599532c171f3d169b80823f2e5dc63794864d7ae4042138dc7bce3aa32f9a6b` |
| APK AndroidTest SHA-256 | `be283081cb0ec14e3853524feecc668aaae10629c1a1d25342579b08dcaa0e83` |
| Signature | certificat debug `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Runner | `OK (1 test)`, 21,214 s |
| Réseau | local, `ws://127.0.0.1:4414` via `adb reverse` temporaire ; reverse absent après le run |
| Logcat SHA-256 | `943621558002ecd55bef2bb9672cdafee334ff1781ec6fadf775470cf1ca6efb` |

Le produit `dev.atlas.coach` est resté intact (`lastUpdateTime=2026-10-31 00:18:13`),
le rôle HOME est inchangé, et seuls les paquets QA ont été installés.

## 2. Étapes du test (logcat expurgé)

```text
step=01 PASS pairing_imported=true pairing_file_deleted=true token_logged=false
step=02 PASS plan_id=fe03d6ef-… plan_version=2 activity_id=5f2af165-… start_minute=600
step=03 PASS relay_connected=true coach_capabilities=enabled mandate=PROPOSALS_ONLY
step=04 PASS day_consent=GRANTED:2/2 status_consent=GRANTED:2/2 plan_receipt_version=2 freshness=FRESH presentation=SYNCED
step=05 PASS agent_status=CONNECTED tool_state=completed tool_call_count=2 reply_proposal_id=7512ea09-…
step=06 PASS proposal_id=7512ea09-… state=RECEIVED confirmation=A0_USER_REVIEW auto_application=NONE_AT_CORE base_version=2 changes=1 conflicts=0
step=07 PASS action=REJECT plan_version=2 applied=false
step=08 PASS ack_id=c22d1829-… ack_sequence=1 ack_state=REJECTED proof=false pending_acks=0
step=09 PASS final_plan_version=2 plan_receipt_version=2 freshness=FRESH
step=10 PASS second_pull=true new_proposals=0 new_acks=0 pending_acks=0 proposal_state=REJECTED plan_version=2
step=11 PASS ack_replay=identical ack_id=c22d1829-… ack_state=REJECTED ack_events_local=1 pending_acks=0
step=12 PASS final_day_plan=REVOKED:3/3 final_activity_status=REVOKED:3/3
v7_06b_result=PASS action=REJECT base_version=2 final_plan_version=2 token_logged=false
```

## 3. Oracle Core indépendant

Lecture SQLite `mode=ro` :

| Oracle | Constat |
|---|---|
| Proposition | `7512ea09-07f9-4cab-b693-074ea3504308`, `REJECTED`, `base_version=2` |
| Politique | `A0_USER_REVIEW`, `NONE_AT_CORE` |
| Changement proposé | 1 `reschedule`, `target_start_minute=610`, durée 45 |
| ACK | `c22d1829-502e-4ce5-adf5-539cf574fc26`, `REJECTED`, `result_code=USER_REJECTED`, `applied_plan_version=NULL`, séquence 1 |
| Preuve d’application | absente (`revision_id`, `scope_hash`, `effect_ids` vides) |
| Snapshots | **uniquement v2**, start=600, durée 45 ; aucune v3 |
| Consentements | `DAY_PLAN` et `ACTIVITY_STATUS` `REVOKED` v3, 4 événements |
| Proposition en attente | 0 |

## 4. Journal connecteur et trace outils

- Journal : 1 `v4.proposals.ack` `completed_redacted` avec l’`ack_id` Android ;
  1 snapshot complété ; 1 chat complété ; 0 `uncertain`/`in_flight`.
- Trace proxy : 3 enregistrements ; DeepSeek réel ; 6 outils client exacts ;
  `atlas_get_day_plan` puis `atlas_propose_plan_revision` ; corrélation des
  `tool_call_id` ; `finish_reason=stop` ; aucun outil interdit.

## 5. Rejeu ACK

Le test a rejoué l’enveloppe `InboxV4Ack` exacte (`ack_id=c22d1829…`) avant la
révocation. Le Core a renvoyé le même ACK `REJECTED` ; le dépôt local est resté à
1 ACK, `pending_acks=0`, et SQLite ne contient qu’un seul événement ACK. Aucun plan
v3 n’a été créé.

## 6. Incident de harnais et correction

Le premier wrapper a exécuté le test avec succès mais a refusé de produire la preuve :
`ANDROID_ACK_REPLAY_ATTESTATION_MISSING`. Cause : le buffer logcat contenait deux
attestations `step=11` (APPLY précédent et REJECT courant) et le harnais exigeait
« exactement une ». Le test et le Core n’étaient pas en cause.

Correctif `f9017f40b5112aa5d47676094e592bcea6eb3240`, intégré
`5ca0a927ecac7c0811f6c465bc4100a23c3da966` : l’attestation est désormais liée à
l’`ack_id` et à l’état ACK lus dans le Core ; le mode `verify-existing` produit un
oracle post-hoc en lecture seule. Le proof porte
`execution_mode=post_hoc_existing_stack` et le résumé précise « aucune nouvelle
exécution ». Les 14 tests du harnais passent.

## 7. Limites

- Preuve sur paquet QA, pas sur l’application produit.
- Oracle post-hoc sur une exécution réelle unique ; aucune ré-exécution n’a été
  effectuée pour « faire passer » le scénario.
- Le rejeu ACK peut être servi par le cache d’idempotence du connecteur ;
  l’idempotence Core/Relay est prouvée séparément par le scénario backend.
- Réseau local `adb reverse` : ne vaut pas `GO_REMOTE_V4_PUBLIC`.
