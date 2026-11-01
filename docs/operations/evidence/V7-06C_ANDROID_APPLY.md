# V7-06C — Android APPLY physique (Pixel QA)

**Date :** 2026-11-01
**Résultat :** **PASS** — `GO_ANDROID_QA_CHAT`, `GO_ANDROID_QA_PLAN_APPLY`,
`GO_ANDROID_QA_ACK` (périmètre APPLY).
**Source intégrée :** `b4db8da5f3ae3aa1ea85d52a4bcaf996f4d3744a`
**Base mission :** `3e0d1f4c9ca591f903a894be87183395d30fee0b`
**Preuve privée :** `v7-06c-apply-a49e3a3aa819.json` (0600, hors dépôt)
**Registre :** `docs/operations/REGISTRY_V7_06C.md`

## 1. Appareil et artefacts

| Élément | Valeur |
|---|---|
| Appareil | Pixel 9 Pro XL, Android 17 / API 37, arm64-v8a |
| Série | hashée `f2e9258b85327628c8d25b1652b00601e698f9ccb2a8b23fb1e913ff355a5946` (série brute absente) |
| Empreinte build | hashée `a44a971009b24780b39f441fd2f0b23c33bbb16682408cb3cf57ae626069f2a6` |
| Paquet QA | `dev.atlas.coach.qa.v706b.v706capply.a7f3c21e` |
| Paquet test | `dev.atlas.coach.qa.v706b.v706capply.a7f3c21e.test` |
| APK app SHA-256 | `8441a5dab058d3a2852a77d30ccef7a23cec8c2563ac1083212d46439856224d` |
| APK AndroidTest SHA-256 | `c97427fb9f2a029a065e0924221f351458151860a962604c021f5189576337d8` |
| Signature | certificat debug `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee` |
| Runner | `OK (1 test)`, 19,387 s |
| Réseau | local, `ws://127.0.0.1:4413` via `adb reverse` temporaire, retiré et vérifié |

Le paquet produit `dev.atlas.coach` n’a pas été installé, remplacé, effacé ni
instrumenté. `lastUpdateTime` est resté `2026-10-31 00:18:13`, le rôle HOME est resté
`dev.atlas.coach/.app.LauncherActivity`, et seuls les deux paquets QA ont été ajoutés.

## 2. Étapes du test (logcat expurgé)

```text
step=01 PASS pairing_imported=true pairing_file_deleted=true token_logged=false
step=02 PASS plan_id=e6b20b52-… plan_version=2 activity_id=45ac50dc-… start_minute=600
step=03 PASS relay_connected=true coach_capabilities=enabled mandate=PROPOSALS_ONLY
step=04 PASS day_consent=GRANTED:2/2 status_consent=GRANTED:2/2 plan_receipt_version=2 freshness=FRESH presentation=SYNCED
step=05 PASS agent_status=CONNECTED tool_state=completed tool_call_count=2 reply_proposal_id=71b80fea-…
step=06 PASS proposal_id=71b80fea-… state=RECEIVED confirmation=A0_USER_REVIEW auto_application=NONE_AT_CORE base_version=2 changes=1 conflicts=0
step=07 PASS action=APPLY plan_version=3 start_minute=610
step=08 PASS ack_id=3f67edb5-… ack_sequence=1 ack_state=APPLIED proof=true pending_acks=0
step=09 PASS final_plan_version=3 plan_receipt_version=3 freshness=FRESH
step=10 PASS second_pull=true new_proposals=0 new_acks=0 pending_acks=0 proposal_state=APPLIED plan_version=3
step=11 PASS ack_replay=identical ack_id=3f67edb5-… ack_state=APPLIED ack_events_local=1 pending_acks=0
step=12 PASS final_day_plan=REVOKED:3/3 final_activity_status=REVOKED:3/3
v7_06b_result=PASS action=APPLY base_version=2 final_plan_version=3 token_logged=false
```

## 3. Oracle Core indépendant

Lecture SQLite `mode=ro`, indépendante des assertions du test :

| Oracle | Constat |
|---|---|
| Proposition | `71b80fea-7967-44dc-b145-482a6c87340f`, `APPLIED`, `base_version=2` |
| Politique | `A0_USER_REVIEW`, `NONE_AT_CORE` |
| Changement | 1 `reschedule`, `target_start_minute=610`, durée 45 s |
| ACK | `3f67edb5-bcce-4d4b-b246-34f049b7a045`, `APPLIED`, `applied_plan_version=3`, séquence 1 |
| Preuve ACK | `revision_id=3ea92f60-3328-31bf-9a3e-86ec4fb533bd`, `scope_hash=sha256:8b380f…`, effets `PLAN_APPLY:3ea92f60…` |
| Snapshots | v2 start=600, durée 45 ; v3 start=610, durée 45 |
| Cohérence | `current_revision_id` du snapshot v3 = `revision_id` de l’ACK |
| Consentements | `DAY_PLAN` et `ACTIVITY_STATUS` `REVOKED` version 3, 4 événements (GRANTED v2 + REVOKED v3) |
| Proposition après ACK | 0 proposition en attente |

## 4. Journal connecteur et trace outils

- `operations.json` : 1 `v4.proposals.ack` `completed_redacted` avec
  `idempotencyKey` = ACK Android ; 2 snapshots complétés ; 1 chat complété ;
  0 résultat `uncertain`/`in_flight` pour ACK ou snapshot.
- Trace proxy : 3 enregistrements ; modèle `openclaw/v706c_android-apply_…` ;
  6 définitions d’outils client exactes ; `atlas_get_day_plan` puis
  `atlas_propose_plan_revision` ; `tool_call_id` corrélés ; `finish_reason=stop` ;
  aucun outil interdit ; aucun payload de conversation enregistré.

## 5. Rejeu ACK

Le test a rejoué l’enveloppe `InboxV4Ack` exacte après le second pull et avant la
révocation, via `InboxV4HttpTransport`/`RelayCoreV4HttpTransport`. Le Core a renvoyé
l’ACK identique ; le dépôt local est resté à **1 ACK**, `pending_acks=0`, proposition
et plan inchangés, et SQLite ne contient **qu’un seul** événement ACK. Le Core vérifie
le consentement avant l’idempotence, d’où l’exécution avant révocation.

## 6. Limites

- Preuve sur paquet QA séparé, pas sur l’application produit `dev.atlas.coach`.
- Le rejeu peut être servi par le cache d’idempotence du connecteur ; l’idempotence
  Core et Relay est prouvée séparément par le scénario backend V7-06B reproductible.
- Un succès local `adb reverse` ne vaut pas `GO_REMOTE_V4_PUBLIC`.
- La trace logcat complète et la base Core brute restent privées ; seules les valeurs
  expurgées ci-dessus sont publiées.
