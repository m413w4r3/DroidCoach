# ATLAS V7-09 — Recette physique REJECT : assertions et preuves A2

**Ticket :** V7-09-A2 / `REVIEW_REQUIRED`
**Rôle :** QA indépendante, préparation de recette uniquement
**Base SHA :** `efcfdcd0936ab2791f8ea14c151247257b05d19b`
**Worktree :** `v7-09/a2-qa-recipe`
**Écriture autorisée :** ce fichier uniquement
**Appareil visé :** Pixel 9 Pro XL, Android 17 / API 37
**Candidat déjà installé attendu :** `dev.atlas.coach`, versionCode 1 / versionName 0.1.0, SHA-256 APK `c7b0f71f43680f4bb45d8c9e6b808713ea09ff5f826fd79db31acce8ff4355a0`
**Date de préparation :** 2026-11-05 (Europe/Paris)

## Portée et état de cette preuve

Ce document prépare les vérifications du parent Paseo, seul opérateur du Pixel et d’ADB. Aucune commande ADB, aucun accès au Pixel, aucune commande Docker/journalctl, aucun redémarrage de service, aucune écriture en base et aucun push n’ont été exécutés pour cette tâche. La recette physique reste `NOT_RUN` jusqu’à son exécution et à la revue des preuves par le parent.

Les commandes ADB ci-dessous sont des lectures du téléphone. Les actions qui modifient volontairement l’état produit — activation de la conversation V4 si nécessaire, envoi du chat, rejet dans l’UI, synchronisation et reconnexion — sont indiquées séparément comme gestes manuels. Ne pas remplacer ces gestes par `input tap`, `pm clear`, `force-stop`, `am force-stop`, `settings put`, une réinstallation ou un accès en écriture aux fichiers privés.

Le scénario attendu est délibérément étroit : une proposition A0 neuve, `base_version=16`, pour l’activité synthétique `Test ATLAS - seance Focus`, de 22:55 à 23:05 (minutes locales `1375 → 1385`), durée inchangée de 45 minutes. Le résultat REJECT ne crée pas de version de plan.

## Références examinées

- `AGENTS.md`, `.agents/WORKFLOW.md`, `.agents/TASK_TEMPLATE.md`, `.agents/skills/README.md` et le skill `android17-device-qa`.
- `docs/operations/V7-08_FINAL_REPORT.md`, `V7-08_HANDOFF.md`, `V7-08_A0_QA_PLAN.md`, `V7-08_CHAT_DEADLINE.md`, `V7-08_QUOTA_POLICY.md`.
- `backend/tests/test_v7_08_a0_protocol.py`, `test_v4_proposals.py`, `test_v4_projection_store.py`, `test_v4_contracts.py`, `test_v4_routes.py`, `test_chat_orchestration_v4.py`.
- `android/app/src/androidTest/java/dev/atlas/coach/e2e/V7RemoteV4InstrumentedTest.kt`, notamment `remoteV4ProposalCanBeAppliedOrRejectedAfterExplicitTestAction`, et `android/app/src/test/java/dev/atlas/coach/app/PlanControllerAgentRevisionTest.kt`, notamment le refus sans écriture du plan.
- Schéma de persistance `backend/atlas_backend/store.py`, validations `backend/atlas_backend/v4_projection_store.py`, stockage local `PlanController` / `PlanV5JsonCodec` et contrats `contracts/v4/proposal-inbox-ack.schema.json`.

Ces tests établissent des oracles de code et de persistance, pas une preuve du trajet distant sur ce Pixel. Les résultats V7-08 cités ici sont des références de campagne antérieure, pas des résultats V7-09.

## Conditions d’arrêt

Arrêter et marquer les étapes dépendantes `BLOCKED` ou `NOT_RUN` si l’une des conditions suivantes apparaît : appareil/API/paquet/APK/HOME inattendu ; état initial non vérifiable ou sauvegarde app-only vérifiée manquante avant les gestes persistants; plan local absent ou différent de v16 / 1375 / 45 min ; consentement `DAY_PLAN` absent, révoqué ou non synchronisé ; snapshot observé vieux de 5 minutes ou plus ; transport nécessaire indisponible ; proposition expirée, rattachée à un autre `plan_id`, avec une autre `base_version`, une autre activité ou une autre cible ; plus d’une nouvelle proposition ; ambiguïté sur le bouton de rejet ; Core et plan local qui divergent. La sauvegarde est conservée hors dépôt; aucune restauration par-dessus l’état actif n’est prévue dans ce scénario.

Ne pas sélectionner `2da9c5e2-57ed-4bac-8f5f-01eb0e4b780e` : proposition expirée (`expires_at=2026-11-05T19:28:58Z`), même si elle apparaît encore dans l’inbox. `e867c13c-2327-4f04-905e-51de965e2876` est l’ancienne proposition appliquée (v15→v16), référence historique seulement. Aucune des deux ne satisfait le scénario REJECT neuf.

## Pré-vol et commandes locales read-only

Exécuter depuis le poste opérateur qui a accès au Pixel et au Core. Ces commandes n’installent rien et ne changent pas l’état du téléphone. Masquer le numéro de série avant de conserver le résultat.

### P0 — Appareil, APK installé, HOME et réseau

```sh
adb devices -l | awk 'NR == 1 { print; next } { $1 = "<SERIAL>"; print }'
```

**Attendu :** une seule ligne d’appareil, état `device`; aucune autre cible ADB. Ne conserver que la sortie où le premier champ est remplacé par `<SERIAL>`.

```sh
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.sdk_full
adb shell getprop ro.product.model
adb shell dumpsys package dev.atlas.coach | rg 'versionCode=|versionName=|DEBUGGABLE'
```

**Attendu :** API `37`, Pixel 9 Pro XL, `versionCode=1`, `versionName=0.1.0`, paquet présent. Si `sdk_full` est vide, l’API entière reste obligatoire et le parent note cette limite.

```sh
sha256sum "$HOME/.local/state/atlas-v7-07/candidate-final/app-debug.apk"
```

**Attendu :** `c7b0f71f43680f4bb45d8c9e6b808713ea09ff5f826fd79db31acce8ff4355a0`.

```sh
set -o pipefail
APK_REMOTE="$(adb shell pm path dev.atlas.coach | sed -n 's/^package://p' | tr -d '\r' | head -n 1)"
test -n "$APK_REMOTE" && adb exec-out cat "$APK_REMOTE" | sha256sum
```

**Attendu :** le même SHA-256 pour l’APK actuellement installé. La lecture du fichier installé est envoyée à `sha256sum`; aucun APK n’est poussé vers le téléphone.

```sh
apksigner verify --print-certs "$HOME/.local/state/atlas-v7-07/candidate-final/app-debug.apk" | rg 'Signer #1 certificate SHA-256 digest'
```

**Attendu :** empreinte de certificat `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.

```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

**Attendu :** HOME résolu vers `dev.atlas.coach/.app.LauncherActivity`.

```sh
adb shell settings get global airplane_mode_on
adb shell dumpsys connectivity | rg -i 'Active default network|VALIDATED|TRANSPORT_WIFI|TRANSPORT_CELLULAR|\bWIFI\b|\bCELLULAR\b' | sed -E 's/(SSID|BSSID)=([^, ]+)/\1=<REDACTED>/Ig; s/([0-9]{1,3}\.){3}[0-9]{1,3}/<IP>/g'
```

**Attendu :** mode avion `0`, réseau par défaut validé et transport cohérent avec le parcours relayé déjà configuré. Ne pas conserver SSID, BSSID ni adresse IP. Ne pas changer le réseau pendant le pré-vol.

### P1 — Santé des services sans les redémarrer

```sh
curl --fail --silent --show-error http://127.0.0.1:8100/health
curl --fail --silent --show-error http://127.0.0.1:4402/health
```

**Attendu :** HTTP 200 pour Core et Relay, si ces endpoints répondent depuis le poste opérateur. Ne pas inclure d’URL Funnel privée dans le dépôt.

```sh
docker inspect -f '{{.State.Status}} {{.State.Health.Status}}' atlas-core-atlas-core-1
```

**Attendu :** `running healthy`. Ne pas utiliser `docker inspect` sur `Config.Env` et ne pas lancer `docker restart`, `docker compose up/down` ou une commande de changement de configuration dans cette recette.

Si le proxy/connecteur requis pour le chemin choisi est arrêté, noter `BLOCKED` et attendre une instruction opérationnelle distincte. Le présent runbook ne démarre ni n’arrête de service.

### P2 — Lecture filtrée du plan local (avant, puis après REJECT)

Le candidat APK est `debuggable=true`; `run-as` lit uniquement le JSON de plan du jour. Le filtre n’affiche ni titre, notes, identifiants d’activité, contenu de conversation ni autres activités.

```sh
set -o pipefail
adb exec-out run-as dev.atlas.coach sh -c 'cat "files/plans/day-plan-$(date +%F).json"' \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); v4=json.loads(d["v4_state"]); v3=json.loads(v4["schema3_state"]); p=v3["day_plan"]; xs=[a for a in p["activities"] if a.get("title")=="Test ATLAS - seance Focus"]; print(json.dumps({"local_date":p["local_date"],"version":p["version"],"synthetic_match_count":len(xs),"synthetic":[{"status":a["status"],"start_minute":a["scheduled_local"]["start_minute"],"duration_minutes":a["scheduled_local"]["duration_minutes"]} for a in xs]},sort_keys=True))'
```

**Avant :** date locale `2026-11-05`, version `16`, exactement un match synthétique `PLANNED`, `start_minute=1375`, `duration_minutes=45`.
**Après :** mêmes date, version et horaire; même activité toujours présente. Aucune nouvelle version ni mutation de l’heure après REJECT.

Pour obtenir en mémoire le `plan_id` exact du fichier local et s’en servir comme filtre SQL sans l’imprimer, lancer une fois :

```sh
PLAN_ID="$(adb exec-out run-as dev.atlas.coach sh -c 'cat "files/plans/day-plan-$(date +%F).json"' | python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.loads(json.loads(d["v4_state"])["schema3_state"])["day_plan"]["plan_id"])')"
test "${#PLAN_ID}" -eq 36
```

Si `run-as` échoue, ne contourner pas l’isolation; constater le plan dans l’écran Plan et noter que la vérification locale structurée est `BLOCKED`.

### P3 — État local durable de l’inbox et de l’ACK

À lancer après le REJECT, puis après reconnexion. La lecture ne sort que les champs de l’ACK correspondant à `PROPOSAL_ID`; elle n’imprime ni le corps source de la proposition ni le reste de l’inbox.

```sh
PROPOSAL_ID='<UUID_PROPOSITION_NEUVE>'
export PROPOSAL_ID
set -o pipefail
adb exec-out run-as dev.atlas.coach cat files/plans/agent-proposal-inbox-v4.json \
  | python3 -c 'import json,os,sys; d=json.load(sys.stdin); pid=os.environ["PROPOSAL_ID"]; rows=[r for r in d.get("acks",[]) if r.get("ack",{}).get("proposal_id")==pid]; print(json.dumps({"ack_count":len(rows),"acks":[{"state":r["ack"].get("state"),"result_code":r["ack"].get("result_code"),"sequence":r["ack"].get("sequence"),"applied_plan_version_present":"applied_plan_version" in r["ack"],"delivery_state":r.get("delivery_state")} for r in rows]},sort_keys=True))'
```

**PASS après rejet :** `ack_count=1`, `state=REJECTED`, `result_code=USER_REJECTED`, `sequence=1`, `applied_plan_version_present=false`, `delivery_state=DELIVERED`. Après reconnexion, les mêmes champs sont encore présents et identiques. `delivery_state=DELIVERED` atteste la persistance locale et l’acceptation côté transport; il ne prouve pas un rejeu.

Captures écran seulement si utiles et dans le dossier de preuves privé du parent, hors dépôt : `adb exec-out screencap -p > "$EVIDENCE_DIR/<etape>.png"`. La capture est une lecture du téléphone, mais peut montrer d’autres données visibles; inspecter et recadrer/masquer avant tout partage.

### P13 — État final du téléphone

```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell settings get global airplane_mode_on
adb shell dumpsys connectivity | rg -i 'Active default network|VALIDATED|TRANSPORT_WIFI|TRANSPORT_CELLULAR|\bWIFI\b|\bCELLULAR\b' | sed -E 's/(SSID|BSSID)=([^, ]+)/\1=<REDACTED>/Ig; s/([0-9]{1,3}\.){3}[0-9]{1,3}/<IP>/g'
adb shell dumpsys activity activities | rg -m 2 'mResumedActivity|topResumedActivity'
```

**Attendu :** HOME `dev.atlas.coach/.app.LauncherActivity`; mode avion et réseau identiques au pré-vol; composant au premier plan ATLAS; pas de dialogue en attente. Le réseau doit rester dans son état initial.

## Commandes SQL exactes — SQLite Core en mode lecture seule

Le conteneur V7-08 utilise `/data/atlas.sqlite3` et s’appelle `atlas-core-atlas-core-1`. Le script ouvre SQLite avec `mode=ro` **et** active `PRAGMA query_only=ON`. Il n’affiche jamais `device_ref`, le bearer, la clé d’idempotence ni les JSON complets. `PLAN_ID` vient du plan local ci-dessus. Capturer `CAMPAIGN_START_UTC` juste avant le premier chat, après le contrôle de fraîcheur :

```sh
CAMPAIGN_START_UTC="$(date -u +%FT%TZ)"
printf '%s\n' "$CAMPAIGN_START_UTC"
```

Conserver ce seul horodatage UTC et le réutiliser après l’action. Ne pas le remplacer par une heure locale.

### Q1 — Snapshot et consentement DAY_PLAN

```sh
docker exec -i -e PLAN_ID="$PLAN_ID" atlas-core-atlas-core-1 python - <<'PY'
import json
import os
import sqlite3

con = sqlite3.connect("file:/data/atlas.sqlite3?mode=ro", uri=True)
con.row_factory = sqlite3.Row
con.execute("PRAGMA query_only=ON")
params = {"plan_id": os.environ["PLAN_ID"], "title": "Test ATLAS - seance Focus"}
snapshot_sql = """
SELECT substr(s.plan_id,1,8)||'…' AS plan_key,
       s.local_date, s.timezone, s.plan_version, s.observed_at, s.received_at,
       s.consent_version,
       ROUND((julianday('now')-julianday(s.observed_at))*1440,1) AS observed_age_min,
       ROUND((julianday('now')-julianday(s.received_at))*1440,1) AS received_age_min,
       (SELECT COUNT(*) FROM json_each(s.projection_json,'$.activities') a
        WHERE json_extract(a.value,'$.title')=:title) AS synthetic_match_count,
       (SELECT json_extract(a.value,'$.status') FROM json_each(s.projection_json,'$.activities') a
        WHERE json_extract(a.value,'$.title')=:title LIMIT 1) AS synthetic_status,
       (SELECT json_extract(a.value,'$.scheduled_local.start_minute')
        FROM json_each(s.projection_json,'$.activities') a
        WHERE json_extract(a.value,'$.title')=:title LIMIT 1) AS synthetic_start_minute,
       (SELECT json_extract(a.value,'$.scheduled_local.duration_minutes')
        FROM json_each(s.projection_json,'$.activities') a
        WHERE json_extract(a.value,'$.title')=:title LIMIT 1) AS synthetic_duration_minutes
FROM day_plan_snapshots_v4 s
WHERE s.plan_id=:plan_id
ORDER BY s.plan_version DESC
LIMIT 1
"""
consent_sql = """
SELECT c.category, c.state, c.consent_version, c.updated_at, c.source,
       (SELECT s.consent_version FROM day_plan_snapshots_v4 s
        WHERE s.plan_id=:plan_id ORDER BY s.received_at DESC LIMIT 1) AS snapshot_consent_version
FROM consent_states_v4 c
WHERE c.category='DAY_PLAN'
  AND c.device_ref=(SELECT s.device_ref FROM day_plan_snapshots_v4 s
                    WHERE s.plan_id=:plan_id ORDER BY s.received_at DESC LIMIT 1)
"""
print(json.dumps({
    "snapshot": [dict(r) for r in con.execute(snapshot_sql, params)],
    "day_plan_consent": [dict(r) for r in con.execute(consent_sql, params)],
}, sort_keys=True))
con.close()
PY
```

**PASS avant chat :** une ligne de snapshot pour le `plan_id` local; date du jour; `plan_version=16`; horaire `1375`, durée `45`; `0 <= observed_age_min < 5`; consentement `DAY_PLAN=GRANTED`; `consent_version == snapshot_consent_version`. Un `received_age_min` récent ne compense pas un `observed_age_min >= 5` ou négatif.
**FAIL/BLOCKED :** snapshot absent, plan différent, âge d’observation ≥5 min ou négatif, consentement absent/révoqué/non synchronisé. Ne pas poursuivre le chat; faire rafraîchir/synchroniser le snapshot par le geste UI prévu, puis relancer Q1. Si le consentement est révoqué, arrêter jusqu’à un consentement utilisateur explicite dans l’écran **Plus → Partage backend → Plan approuvé**.

### Q2 — Proposition neuve, unique et conforme

Après avoir inscrit `CAMPAIGN_START_UTC`, envoyer une seule demande de chat (voir étape 5 de la matrice), puis lancer :

```sh
docker exec -i -e PLAN_ID="$PLAN_ID" -e CAMPAIGN_START_UTC="$CAMPAIGN_START_UTC" atlas-core-atlas-core-1 python - <<'PY'
import json
import os
import sqlite3

con = sqlite3.connect("file:/data/atlas.sqlite3?mode=ro", uri=True)
con.row_factory = sqlite3.Row
con.execute("PRAGMA query_only=ON")
params = {"plan_id": os.environ["PLAN_ID"], "started_at": os.environ["CAMPAIGN_START_UTC"]}
sql = """
SELECT p.proposal_id, substr(p.plan_id,1,8)||'…' AS plan_key,
       p.base_version, p.capability, p.state, p.created_at, p.expires_at,
       CAST((julianday(p.expires_at)-julianday('now'))*86400 AS INTEGER) AS expires_in_seconds,
       json_extract(p.proposal_json,'$.confirmation_mode') AS confirmation_mode,
       json_extract(p.proposal_json,'$.auto_application') AS auto_application,
       json_array_length(p.proposal_json,'$.changes') AS change_count,
       json_extract(p.proposal_json,'$.changes[0].op') AS change_op,
       json_extract(p.proposal_json,'$.changes[0].target_start_minute') AS target_start_minute,
       json_extract(p.proposal_json,'$.diff[0].before.start_minute') AS before_start_minute,
       json_extract(p.proposal_json,'$.diff[0].after.start_minute') AS after_start_minute,
       json_extract(p.proposal_json,'$.diff[0].after.duration_minutes') AS after_duration_minutes,
       json_array_length(p.proposal_json,'$.conflicts') AS conflict_count
FROM agent_proposals_v4 p
WHERE p.plan_id=:plan_id
  AND p.base_version=16
  AND julianday(p.created_at)>=julianday(:started_at)
ORDER BY p.created_at
"""
rows = [dict(r) for r in con.execute(sql, params)]
print(json.dumps({"new_candidate_count": len(rows), "new_candidates": rows}, sort_keys=True))
con.close()
PY
```

**PASS :** `new_candidate_count=1`; nouvelle `proposal_id` différente de `e867c13c…` et `2da9c5e2…`; même `plan_key` que Q1; `base_version=16`; `capability=plan_revision.propose`; état initial `PENDING_ANDROID`; `expires_in_seconds > 0`; `confirmation_mode=A0_USER_REVIEW`; `auto_application=NONE_AT_CORE`; une seule modification `reschedule`; `target_start_minute=1385`; diff `1375 → 1385`, durée après `45`; `conflict_count=0`. Le parent note le nouvel UUID comme `PROPOSAL_ID` pour les requêtes Q3.
**FAIL :** zéro ou plusieurs candidates, mauvaise activité/plan/base/cible, expiration passée, état terminal, conflit, auto-application ou autre changement. Ne pas sélectionner/rejouer une proposition par approximation et ne pas envoyer un second chat sur une réponse ambiguë; examiner les bases/logs puis consigner l’écart.

### Q3 — ACK durable, un événement, aucun changement de plan

Remplacer `<UUID_PROPOSITION_NEUVE>` dans l’affectation par l’identifiant unique issu de Q2. Ne pas y placer `2da9c5e2…` ni `e867c13c…`.

```sh
PROPOSAL_ID='<UUID_PROPOSITION_NEUVE>'
docker exec -i -e PLAN_ID="$PLAN_ID" -e PROPOSAL_ID="$PROPOSAL_ID" -e CAMPAIGN_START_UTC="$CAMPAIGN_START_UTC" atlas-core-atlas-core-1 python - <<'PY'
import json
import os
import sqlite3

con = sqlite3.connect("file:/data/atlas.sqlite3?mode=ro", uri=True)
con.row_factory = sqlite3.Row
con.execute("PRAGMA query_only=ON")
params = {
    "plan_id": os.environ["PLAN_ID"],
    "proposal_id": os.environ["PROPOSAL_ID"],
    "started_at": os.environ["CAMPAIGN_START_UTC"],
}
proposal_sql = """
SELECT p.proposal_id, substr(p.plan_id,1,8)||'…' AS plan_key,
       p.base_version, p.state AS proposal_state,
       COUNT(e.ack_id) AS event_count,
       MAX(e.sequence) AS ack_sequence,
       MAX(e.state) AS ack_state,
       MAX(e.result_code) AS result_code,
       MAX(e.applied_plan_version) AS applied_plan_version,
       MAX(e.occurred_at) AS occurred_at,
       MAX(e.received_at) AS received_at
FROM agent_proposals_v4 p
LEFT JOIN agent_proposal_events_v4 e ON e.proposal_id=p.proposal_id
WHERE p.proposal_id=:proposal_id AND p.plan_id=:plan_id
GROUP BY p.proposal_id
"""
proposal_cohort_sql = """
SELECT COUNT(*) AS new_proposal_count
FROM agent_proposals_v4
WHERE plan_id=:plan_id AND base_version=16
  AND julianday(created_at)>=julianday(:started_at)
"""
plan_sql = """
SELECT s.plan_version, s.observed_at, s.received_at,
       (SELECT COUNT(*) FROM json_each(s.projection_json,'$.activities') a
        WHERE json_extract(a.value,'$.title')='Test ATLAS - seance Focus') AS synthetic_match_count,
       (SELECT json_extract(a.value,'$.status') FROM json_each(s.projection_json,'$.activities') a
        WHERE json_extract(a.value,'$.title')='Test ATLAS - seance Focus' LIMIT 1) AS synthetic_status,
       (SELECT json_extract(a.value,'$.scheduled_local.start_minute')
        FROM json_each(s.projection_json,'$.activities') a
        WHERE json_extract(a.value,'$.title')='Test ATLAS - seance Focus' LIMIT 1) AS synthetic_start_minute,
       (SELECT json_extract(a.value,'$.scheduled_local.duration_minutes')
        FROM json_each(s.projection_json,'$.activities') a
        WHERE json_extract(a.value,'$.title')='Test ATLAS - seance Focus' LIMIT 1) AS synthetic_duration_minutes
FROM day_plan_snapshots_v4 s
WHERE s.plan_id=:plan_id
ORDER BY s.plan_version DESC
LIMIT 1
"""
print(json.dumps({
    "proposal_and_ack": [dict(r) for r in con.execute(proposal_sql, params)],
    "new_proposal_cohort": [dict(r) for r in con.execute(proposal_cohort_sql, params)],
    "latest_core_plan": [dict(r) for r in con.execute(plan_sql, params)],
}, sort_keys=True))
con.close()
PY
```

**PASS après le geste REJECT et après chaque rejeu/reconnexion :** une ligne pour l’UUID neuf; `proposal_state=REJECTED`; `event_count=1`; `ack_sequence=1`; `ack_state=REJECTED`; `result_code=USER_REJECTED`; `applied_plan_version=null`; une seule nouvelle proposition dans la cohorte; snapshot Core toujours v16 avec l’activité à 1375 et durée 45. Q3 ne sélectionne que les colonnes utiles, sans `device_ref` ni charge utile brute.

Le schéma V4 exige qu’un ACK `REJECTED/USER_REJECTED` ne porte aucune preuve d’application. La colonne SQLite `applied_plan_version` doit donc être `NULL`; les tests Core existants valident aussi l’absence des autres champs de preuve.

## Matrice d’assertions avant / après

Les commandes **P0–P3 / P13 / Q1–Q3** sont définies exactement ci-dessus et en lecture seule. Les étapes de manipulation sont des gestes UI explicites du parent; aucun tap ADB n’est proposé.

| # | Moment / assertion | Geste du parent | Commande exacte à lancer | Sortie attendue et oracle PASS / FAIL | Preuve à conserver |
|---:|---|---|---|---|---|
| 1 | Pré-vol appareil, APK, signature, HOME, réseau | Aucun changement. Choisir une seule cible après inspection. | P0, commandes appareil/APK/HOME/réseau; P1 pour Core/Relay. | **PASS** si une cible `device`, API 37, modèle attendu, APK local+installé au SHA attendu, certificat attendu, HOME ATLAS et réseau validé. **FAIL/BLOCKED** sinon. | Horodatage UTC, versions, SHA, certificat; sortie ADB avec série masquée; HOME/network sans SSID/IP. |
| 2 | Baseline locale du plan et de l’activité synthétique | Ouvrir Plan seulement pour observer. Ne pas éditer/supprimer d’activité. | P2, première lecture. | **PASS** si `2026-11-05`, v16, un match `PLANNED`, 1375, 45 min. **FAIL** si tout autre état; ne pas poursuivre. | JSON filtré P2 et capture du Plan ne montrant que la fixture synthétique, stockés hors dépôt. |
| 3 | Consentement V4 `DAY_PLAN` | Dans **Plus → Partage backend**, observer la ligne **Plan approuvé**. Ne toucher au toggle que sur instruction/consentement utilisateur explicite; l’état attendu est déjà accordé. | Q1. | **PASS** si Core dit `GRANTED`, versions de consentement égales, snapshot v16 même plan. **FAIL/BLOCKED** si non accordé, révoqué ou décalé. | Ligne `day_plan_consent` de Q1 + capture de la ligne Plan approuvé, sans autres catégories/personnes. |
| 4 | Snapshot Core frais | Rafraîchir le partage via geste UI décrit en étape 7 seulement si nécessaire; aucune écriture manuelle en base. | Q1 juste avant le chat. | **PASS** si `observed_age_min < 5`, snapshot du plan local v16, 1375/45. **FAIL** à 5 min ou plus, même si `received_age_min` est récent. | Résultat Q1 horodaté UTC et l’âge observé. |
| 5 | Préparer la cohorte et faire une nouvelle proposition A0 | Juste avant le chat, noter `CAMPAIGN_START_UTC`. Dans Coach, envoyer une seule requête : **« Propose de décaler de 10 minutes l’activité synthétique “Test ATLAS - seance Focus” prévue aujourd’hui à 22:55, durée 45 minutes. La cible est 23:05. Ne l’applique pas; je veux examiner la proposition. »** | Commande `CAMPAIGN_START_UTC=...` ci-dessus; Q2 après la réponse. | **PASS** si chat terminé avec exactement un `proposal_id`, Q2 voit exactement une candidate neuve et elle est encore valide. HTTP 200 ou texte du modèle seuls = **FAIL de preuve**. | Heure de départ, capture de la réponse (texte synthétique seulement), nouvel UUID, résultat Q2. Ne pas enregistrer le prompt dans des logs partagés. |
| 6 | Propriétés A0, base et cible, absence d’APPLY auto | Ne cliquer sur aucun bouton appliquer. Comparer la candidate à la fixture de P2/Q1. | Q2. | **PASS** uniquement si même plan (clé locale), base 16, A0, `NONE_AT_CORE`, une action vers 1385, diff 1375→1385, durée 45, zéro conflit et `expires_in_seconds>0`. Toute valeur divergente = **FAIL**. | Sortie Q2 filtrée; nouvel ID complet dans la preuve privée pour corrélation. |
| 7 | Réception dans l’inbox Android | Si la proposition n’arrive pas, ouvrir **Plus → Partage backend → Enregistrer la connexion** une fois pour déclencher le work immédiat; ne modifier ni URL ni jeton. Le pull normal peut attendre 15 min. | Q2 puis capture écran de **Plan → Propositions reçues**. | **PASS** si une seule carte neuve correspond au même UUID/plan/base/cible et est encore reviewable. Ne jamais ouvrir la carte 2da9c5e2 (expirée) ni appliquer e867c13c (historique). | Capture de la liste et du détail, contenu réduit à la fixture; heure de pull et UUID corrélé. |
| 8 | REJECT explicite | Dans le détail de cette carte seulement : **Examiner les changements → Refuser définitivement**. Confirmer le rejet explicite si demandé; ne pas choisir « Appliquer ». | Aucune commande ADB. Après le geste, relancer P2, P3 et Q3. | **PASS** si l’UI confirme le refus, plan v16 reste à 1375/45, P3 voit l’ACK durable local, et Q3 voit l’ACK Core attendu. **FAIL** si le plan change ou si l’action ciblée n’est pas sans ambiguïté. | Capture du détail avant, confirmation de refus après, P2/P3/Q3. |
| 9 | Plan local inchangé | Revenir au Plan, sans édition. | P2 après rejet. | **PASS** si date, v16, fixture `PLANNED`, 1375, 45, strictement identiques à #2. **FAIL** si version/heure/statut changent ou fixture disparaît. | Deux sorties P2 (avant/après) et comparaison des seuls champs autorisés. |
| 10 | ACK durable dans Core | Attendre le prochain sync immédiat autorisé; ne pas envoyer d’ACK fabriqué par curl. | P3 puis Q3. | **PASS** si l’ACK local est durable et livré; dans Core, proposition `REJECTED`, événement durable `REJECTED`, `USER_REJECTED`, séquence 1, `applied_plan_version=null`; Core plan v16/1375. **FAIL** si ligne absente après délai observé, state/code incorrects ou preuve d’application présente. | P3/Q3, timestamps, événement associé à l’UUID neuf. |
| 11 | Un seul événement et absence de deuxième proposition | Déclencher une nouvelle synchronisation par le même geste UI seulement si l’ACK est resté rejouable (`PENDING`/`UNCERTAIN`); observer un second POST ACK corrélé. Ne pas modifier le stockage privé pour forcer un rejeu. | P3/Q3 avant puis après le rejeu; L1 ci-dessous pour corroborer le second POST. | **PASS** si une seconde livraison ACK est visible, SQL reste `event_count=1`, et la cohorte reste `new_proposal_count=1`. Si aucun rejeu réel n’est observé, marquer cette seule assertion `NOT_RUN`; un refresh sans POST ne prouve pas l’idempotence. **FAIL** si deuxième événement ou proposition créée. | Deux P3/Q3 horodatées, deux POST ACK expurgés (statut 200); sinon `NOT_RUN` documenté. |
| 12 | Persistance après reconnexion | Reconnecter la session relayée avec le pair enregistré seulement si nécessaire; ne pas re-pairer, tourner d’identité, changer de bearer, désactiver/réactiver le partage ou arrêter de service. Retourner à Plan/inbox après connexion. | P2, P3 et Q3 après reconnexion. | **PASS** si carte/état terminal et ACK restent REJECTED localement, P3 garde le même ACK `DELIVERED`, Q3 garde une seule ligne/événement, plan v16/1375. **FAIL** si ACK/carte perdus, pending indéfini, plan altéré ou événement doublé. | Capture état connecté + inbox terminal, P2/P3/Q3 post-reconnexion; sans identifiant de pair. |
| 13 | État final du téléphone | Revenir à l’accueil ATLAS et laisser le réseau dans son état initial constaté au pré-vol. Ne pas modifier HOME ni nettoyer la fixture dans cette campagne REJECT. | P13. | **PASS** si HOME reste `dev.atlas.coach/.app.LauncherActivity`, mode avion et type de réseau correspondent au pré-vol, téléphone utilisable; activité visible ATLAS, aucun dialogue REJECT en suspens. **FAIL/BLOCKED** si état initial non restauré ou écran incohérent. | Sorties finales redacted, horodatage UTC, capture de l’écran d’accueil sans données personnelles. |

## Logs read-only à corréler

Ces commandes lisent les journaux du Core et du Gateway. Leur sortie brute peut contenir des paramètres ou du texte de conversation : la consulter localement, n’enregistrer que les champs autorisés ci-dessous et expurger avant partage. Ne pas faire de `docker logs` sans filtre dans un fichier de preuve.

### L1 — Accès Core

```sh
docker logs --since 30m --timestamps atlas-core-atlas-core-1 2>&1 \
  | python3 -c 'import re,sys; pattern=re.compile(r"\"(POST) (/v4/agent/proposals/pull|/v4/agent/proposals/[0-9a-f-]{36}/ack) HTTP/\d(?:\.\d)?\" (\d{3})",re.I); uuid=re.compile(r"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"); [(print(line.split(maxsplit=1)[0],method,uuid.sub("<UUID>",path),status)) for line in sys.stdin if (m:=pattern.search(line)) for method,path,status in [m.groups()]]'
```

Relever seulement : timestamps, opération (`pull` ou `ack`), statut HTTP, ordre du premier et du second ACK, UUID proposition masqué sauf dans la preuve privée restreinte. Deux POST ACK 200 sont requis pour dire que le rejeu réseau a réellement eu lieu; Q3 doit confirmer qu’ils n’ont produit qu’un événement.

### L2 — Gateway OpenClaw

```sh
journalctl --user -u openclaw-gateway.service --since '30 minutes ago' --no-pager -o short-iso \
  | python3 -c 'import re,sys; needles=(("atlas_get_day_plan","tool=atlas_get_day_plan"),("atlas_propose_plan_revision","tool=atlas_propose_plan_revision"));
for line in sys.stdin:
 ts=line.split(maxsplit=1)[0] if line.split() else "unknown-time"; tags=[label for token,label in needles if token in line];
 if re.search(r"stopReason.{0,8}tool_calls",line,re.I): tags.append("stopReason=tool_calls")
 elif re.search(r"stopReason.{0,8}stop",line,re.I): tags.append("stopReason=stop")
 if tags: print(ts,*tags)'
```

Relever uniquement : horodatage, présence des outils `atlas_get_day_plan` puis `atlas_propose_plan_revision`, nombre d’appels/outils si structuré, fin du tour et identifiant de corrélation masqué. Ne pas conserver le prompt, les arguments bruts, la réponse complète, les messages utilisateur ou les données du plan. Si les journaux ne donnent pas un champ structuré, inscrire `NOT_OBSERVABLE` plutôt que l’inférer.

### Champs autorisés et interdits dans les preuves

**À extraire :** horodatage UTC; API/modèle téléphone; version et SHA APK; rôle HOME; état réseau sans identifiant; plan local version et minute/ durée synthétique; `plan_key` tronquée; `observed_at`, `received_at`, âge et consent version; UUID de proposition uniquement pour corrélation restreinte; base_version, cible, A0/NONE_AT_CORE, expiry; état/code/sequence/event_count/applied_plan_version; route HTTP, statut, ordre des tentatives; verdict par assertion.

**Ne jamais enregistrer :** bearer, jetons, secrets, URL privée/Funnel privée, en-têtes Authorization, valeurs d’environnement, device_ref, numéro de série ADB en clair, identifiant de pair, SSID/BSSID, IP, prompt/réponse complète, nom de vraie activité, notes, événements ou autres données personnelles, JSON complet de snapshot/proposal/ACK, captures non expurgées. Ne pas lancer `docker inspect ...Config.Env`, `env`, `set`, `adb logcat -b all`, ni sélectionner `device_ref` dans les sorties.

Stocker les captures et extraits de preuve dans le dossier privé de campagne du parent, hors dépôt. Ce commit ne doit contenir aucun artefact Pixel, identifiant réel ou sortie de service.

## Pièges V7-08 à respecter

1. **`2da9c5e2` expirée** : créée le 2026-11-04, expire le 2026-11-05 à `19:28:58Z`. À l’heure du scénario (~21:00 Europe/Paris), elle n’est plus valide. Elle peut rester visible dans les historiques; vérifier UUID, `created_at`, `expires_at` et Q2. Ne pas la sélectionner.
2. **`e867c13c` est APPLIED** : proposition historique ayant produit v16 et ACK `APPLIED`. Elle explique la base v16; elle n’est jamais la cible REJECT.
3. **Workaround de pull** : pas de bouton d’inbox explicite en mode relais; **Plus → Partage backend → Enregistrer la connexion** déclenche le travail immédiat. Ne renseigner ni vider URL/jeton. Sinon le worker périodique peut attendre jusqu’à 15 minutes.
4. **Toggle Conversation V4** : non persistant après démarrage à froid selon V7-08. Après tout démarrage à froid antérieur au scénario, vérifier Coach et le réactiver via UI seulement si nécessaire; ne pas attribuer une absence de proposition à l’inbox avant cette vérification.
5. **Plan erroné** : des POSTPONED synthétiques coexistent. Faire correspondre `plan_id` local exact, `base_version=16`, activité Focus synthétique précise et `1375→1385`. Ne jamais choisir une proposition seulement parce que son titre/heure ressemble; les heures et le plan doivent concorder.
6. **A0 n’est pas APPLY** : texte assistant ou HTTP 200 n’est pas preuve. Il faut un UUID neuf persistant dans Core, `A0_USER_REVIEW`, `NONE_AT_CORE`, puis un refus explicite dans Android.
7. **Pas de pull immédiat garanti** : une absence dans l’inbox juste après la réponse peut être un délai de pull. Utiliser le workaround UI une fois, puis contrôler la réception; ne pas refaire le chat.
8. **429/504 ou timeout de chat** : le quota Core désactivé ne neutralise pas les limites fournisseur ni le deadline. Ne pas renvoyer le chat; lancer Q2 et vérifier l’absence/présence d’une proposition persistée avant toute reprise.
9. **ACK déjà livré** : le worker rejoue les ACK non livrés; une fois `delivery_state=DELIVERED`, un sync ordinaire ne force pas forcément un deuxième POST. Un rejeu idempotent n’est prouvé que par un second POST réel du même ACK (souvent après une livraison incertaine). Ne créer ni panne réseau ni écriture privée pour forcer ce cas; si aucun rejeu naturel n’arrive, noter `NOT_RUN`.
10. **Les logs bruts sont sensibles** : ne pas sauvegarder de dumps larges. Q1–Q3 et P3 sont les oracles persistants; logs uniquement pour l’ordre et les timestamps de transport.

## Checklist bonus — observations UI, sans correction

Noter chaque item `OK / À revoir / N/A`, écran, orientation/densité visible, étape et description courte. Le propriétaire demande ce retour UX comme bonus; cette tâche ne corrige aucun libellé ni layout.

| Axe à observer | Questions concrètes | Exemple d’écran / moment |
|---|---|---|
| Texte inutile | Une phrase répète-t-elle l’action ou l’état déjà montré? Un avertissement reste-t-il affiché après résolution? | **Plus → Partage backend**, état du Plan approuvé; écran **Connexion relayée** en état connecté. |
| Alignement / hiérarchie | Titres, états, boutons et détails sont-ils alignés? L’information principale se repère-t-elle sans lire toutes les lignes? | Carte dans **Plan → Propositions reçues**; écran détail « Examiner les changements ». |
| Complexité | Le chemin consentement → inbox → rejet est-il compréhensible sans connaître V4/Core? Les termes A0, plan/version ou relay sont-ils expliqués au bon endroit? | **Coach** (toggle Conversation V4), **Plan approuvé**, détail de proposition. |
| Ergonomie | Bouton Refuser clairement distinct d’Appliquer? Confirmation et retour d’état visibles? Cible tactile/retour navigation sans ambiguïté? | Détail proposition, dialogue **Refuser définitivement**, retour à la liste. |
| Ton trop LLM/robotique | Phrase trop longue, fausse assurance, jargon, justification répétitive ou voix trop directive? | Réponse du Coach à la demande synthétique; explication de la proposition; confirmation de refus. |
| Surcharge textuelle | Trop de prose, statuts ou détails visibles d’un coup? L’action principale reste-t-elle visible sans défilement excessif? | **Connexion relayée**, **Partage backend**, liste puis détail des propositions. |
| État final | Message de succès/erreur cohérent avec un rejet sans changement du plan? | Retour Plan après REJECT et écran d’accueil ATLAS. |

**Exemples d’écrans à observer au minimum :** Coach avant/après réponse; **Plus → Connexion relayée**; **Plus → Partage backend** avec ligne **Plan approuvé**; **Plan → Propositions reçues** (liste et détail); confirmation « Refuser définitivement »; Plan après rejet; accueil ATLAS final. Les captures destinées à partage doivent masquer toute donnée hors fixture synthétique.

## Verdict de campagne à renseigner par le parent

Pour chaque assertion : `PASS`, `FAIL`, `BLOCKED` ou `NOT_RUN`, SHA de l’APK/code, heure UTC, environnement/API, preuve expurgée et état final. La simulation backend, les tests Android JVM et le présent plan ne valent pas une recette physique. Ne pas annoncer « testé sur Pixel » avant une exécution réelle documentée.

- REJECT explicite : `NOT_RUN`
- ACK durable `REJECTED / USER_REJECTED`, sans version appliquée : `NOT_RUN`
- Rejeu HTTP réel idempotent, sans deuxième événement : `NOT_RUN`
- Persistance après reconnexion : `NOT_RUN`
- État final vérifié : `NOT_RUN`
- Observations UI bonus : `NOT_RUN`
