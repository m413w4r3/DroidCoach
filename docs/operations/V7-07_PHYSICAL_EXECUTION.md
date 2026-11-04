# ATLAS V7-07 — Exécution physique A+B+C et blocage D

**Date :** 2026-11-02
**Branche locale :** `feat/atlas-v7-07-preproduction` (aucun push, PR ou merge
distant)
**Base mission :** `32e7c70c78fbe295f66f67d6be50dc797a3f6e6d`
**Code produit final :** `b9f17f4fb1da930eeabfcb85cffd58c3929acd35`
**APK installé :** `c7b0f71f43680f4bb45d8c9e6b808713ea09ff5f826fd79db31acce8ff4355a0`
**Appareil :** Pixel 9 Pro XL, Android 17 / API 37.1, serial hash
`f2e9258b85327628c8d25b1652b00601e698f9ccb2a8b23fb1e913ff355a5946`
**Périmètre autorisé :** scopes A+B+C+D, exécutés dans l'ordre ; E non autorisé.
**Mise à jour 2026-11-04 :** le transport public a été rétabli et D a été
repris ; DNS/TLS/WSS et appairage **PASS**, la rotation locale du
`conversation_id` a été vérifiée et un plan futur frais a été synchronisé.
La tentative produit unique a été arrêtée par le quota journalier Core
(`429`, 102062/100000), sans proposition A0. Voir `V7-07_D_RESUME.md`.

## 1. Scope A — sécurité live

### 1.1 Sauvegarde préalable

Sauvegarde privée `0700`, fichiers `0600`, hors dépôt et hors `/tmp` :

- `identity.json`, `devices.json` avec manifeste et hashes ;
- `operations.json`, `android-token`, tokens/pairing `pixel-v7`,
  `relay.env` ;
- secrets Core (`atlas-core.token`, `atlas-tool.token`,
  `openclaw-gateway.token`) ;
- copie SQLite cohérente de `/data/atlas.sqlite3` et export des consentements
  V4.

La base copiée passe `PRAGMA integrity_check=ok` ; l'export contient 2 lignes.
Les hashes du répertoire sont vérifiés par `SHA256SUMS`. Aucun secret n'est
publié.

### 1.2 Identités Relay

- L'application produit installée utilise l'identité `pixel-v7` : le
  `relay_device_id` de ses préférences correspond au hash
  `214db932b987…` et non à `r3c-pixel-final`.
- `r3c-pixel-final` ne correspond à aucune configuration de l'application
  produit actuelle ; elle a été **révoquée** le 2026-11-02T08:36:45.843Z
  (hash d'identité `6ccc561ee2b5…`).
- `pixel-v7` a été **préservée** : elle est l'identité actuelle de
  l'application produit. La révoquer aurait coupé l'appairage courant.
- Une identité dédiée `atlas-product-pilot-20261102` a été créée pour préparer
  D, puis révoquée après le blocage du transport, le
  2026-11-02T09:04:11.131Z (hash `5c7b46fa4a60…`). Son token privé a été
  supprimé. Aucune identité de produit active n'a été ajoutée.
- État final : 20 entrées dans `devices.json`, **une seule active**
  (`pixel-v7`), 19 révoquées. Aucune autre identité n'a été modifiée.

### 1.3 Consentements et bearer

Les deux consentements Core `DAY_PLAN` et `ACTIVITY_STATUS` restent
`GRANTED` v2 pour l'ancien `device_ref`, sans modification. Ils sont considérés
comme **historiques** et ne valent pas consentement produit actuel : D n'a pas
été exécuté, et aucun consentement n'a été réutilisé implicitement.

La rotation du bearer Android n'a **pas** été exécutée, conformément à
l'autorisation. La séparation Core par appareil reste donc une limite ouverte.

## 2. Scope B — Gateway/Core

### 2.1 Sauvegarde et configuration

- Sauvegarde de `~/.openclaw/openclaw.json`, de l'unité systemd et de
  l'inventaire effectif initial.
- Patch validé en dry-run (6 opérations, schéma et résolvabilité OK), puis
  appliqué :
  - agent `atlas` : `profile=minimal`, `allow` supprimé, deny étendu aux
    groupes `plugins/fs/runtime/web/sessions/nodes/ui`, `sessions_spawn`,
    `subagents` ;
  - plugin natif `atlas_core_tools` désactivé (`enabled=false`,
    `config.enabled=false`) ;
  - `codeMode=false`.
- Hachage avant : `8b8d59d19154c24092f3e9330907b719caa7af9172cc6b910f1b9372ae26be5c`.
- Hachage après : `a316862aa43ace10b00a0144744a2374581b9e442eaeb07c5038d51b134f96bc`.
- Redémarrage contrôlé de `openclaw-gateway.service` ; service `active`,
  connectivité loopback OK, provider/modèle effectif `openai` /
  `gpt-6-luna`.

### 2.2 Outils effectifs

`tools.effective` pour l'agent `atlas` après redémarrage :

- profil actif `minimal` (source `agents.entries.atlas.tools.profile`) ;
- 66 outils natifs, **tous exclus** ;
- `exec`, `process`, `code_execution`, `gateway`, `session_status`,
  `presence`, `read`, `write`, `edit`, `apply_patch`, `web_search`,
  `web_fetch`, `sessions_spawn`, `subagents`, `plugins`, `terminal`,
  `browser`, `ls` exclus par profil et/ou deny ;
- aucun outil natif `atlas_*` ;
- plugin natif non chargé.

### 2.3 Qualification réelle du modèle

Harnais Core isolé (base SQLite temporaire, `device_ref` synthétique, aucune
donnée personnelle) appelant le **Gateway live** avec les six outils client
V4. Résultat :

- 3 appels provider réels, modèle effectif `gpt-6-luna` / provider `openai` ;
- 6 outils exactement dans chaque requête, aucun outil interdit ;
- appels `atlas_get_day_plan` puis `atlas_propose_plan_revision` ;
- une seule proposition, `confirmation_mode=A0_USER_REVIEW`,
  `auto_application=NONE_AT_CORE`, `base_version=1`, déplacement
  `target_start_minute=610` (10:00 → 10:10) ;
- rejeu de la même requête : réponse identique, aucun appel provider
  supplémentaire, toujours une seule proposition, aucun ACK/APPLY ;
- réponse finale du modèle : proposition à confirmer, rien n'a été appliqué.

Preuves privées : `~/.local/state/atlas-v7-07/qualification/scopeB-result.json`
(SHA-256 `cde15942…`), script `scopeB_model_qualification.py`
(`157385b7…`), inventaire `tools-effective-after-scopeB.json`
(`8dea11a8…`). Le patch et l'état après sont archivés avec la sauvegarde B.

## 3. Scope C — APK produit sur Pixel

### 3.1 Gates avant installation

| Gate | Résultat |
|---|---|
| Identité Pixel / API | **PASS** — Pixel 9 Pro XL, Android 17 / API 37.1, un seul appareil ADB |
| Paquet / version | **PASS** — `dev.atlas.coach`, versionCode 1, versionName 0.1.0 |
| Signature | **PASS** — APK installé et candidat : `da301466…eeee`, identiques |
| VersionCode compatible | **PASS** — même versionCode, `adb install -r` |
| Permissions | **PASS** — 16 permissions identiques, aucune ajoutée |
| Provenance candidate | **PASS** — `source_sha=b9f17f4…`, tree `6b1a6c43…`, `worktree_clean=true` |
| APK précédent conservé | **PASS** — `previous-base.apk`, SHA-256 `0d0ce524…` |
| HOME initial | **PASS** — rôle HOME `dev.atlas.coach`, `LauncherActivity` |
| Sauvegarde app-only réelle | **PASS** — archive `5969966b…`, 590 848 octets, 17 fichiers, `VERIFY_OK` |
| Restauration/rollback | **PASS outil + AVD synthétique** ; extraction réelle vérifiée ; restauration réelle non exécutée |
| Keystore | **NOT_EXPORTABLE** — conservé par mise à jour in-place ; perdu en cas de désinstallation/réinstallation |
| Données importantes | **PASS** — état local, V4 sync, plans, WorkManager, calendrier, inbox, config Relay inclus |

La sauvegarde contient les 17 fichiers non-cache, dont
`atlas_local_state.xml`, `atlas_core_v4_sync.xml`,
`atlas_relay_configuration.xml`, les plans 2026-10-28/31, l'inbox agent et
`no_backup/androidx.work.workdb` (+ WAL/SHM). L'extraction privée vérifie les
17 hashes du manifeste ; la base WorkManager passe `PRAGMA integrity_check=ok`.
Aucune clé Keystore n'est dans l'archive. Aucun cache n'est sauvegardé.

### 3.2 Installation

```sh
adb install -r .../candidate.apk
# Performing Streamed Install
# Success
```

Après installation :

- `versionCode=1`, `versionName=0.1.0`, `lastUpdateTime=2026-11-02 09:55:45` ;
- APK installé hash `c7b0f71f…`, signature `da301466…eeee` ;
- hash des données clés identiques au manifeste de sauvegarde :
  `atlas_local_state.xml` `bd92985c…`, `atlas_core_v4_sync.xml` `4d0a7315…`,
  `atlas_relay_configuration.xml` `24a8f6eb…`, plans `a13c7d41…` /
  `8125b687…`, WorkManager `5bd84e3f…` ;
- permissions runtime inchangées (calendrier/notifications/localisation
  toujours refusés, `ACCESS_LOCAL_NETWORK` accordé) ;
- HOME toujours `dev.atlas.coach/.app.LauncherActivity` ;
- application non lancée automatiquement, aucun crash.

### 3.3 Smoke local

| Point | Résultat |
|---|---|
| Démarrage à froid | **PASS** — `Status: ok`, `LaunchState: COLD`, 435 ms |
| HOME | **PASS** — launcher ATLAS affiché, applications favorites visibles |
| Applications essentielles | **PASS** — tiroir Applications/recherche ; Calculator lancé et vérifié (`com.google.android.calculator`), retour HOME ; Brave/Paseo/YouTube ReVanced/AnkiDroid/Calendar/Camera/etc. visibles, aucune modification |
| Plan | **PASS** — écran Plan ouvert, programme du jour lisible |
| Focus | **PASS** — écran Focus ouvert, tâche et durée existantes conservées |
| Coach | **PASS** — écran Coach ouvert, état hors ligne honnête, conversation locale |
| Données conservées | **PASS** — hashes plans/état V4/config Relay identiques |
| Hors ligne | **PASS** — mode avion + Wi-Fi/data coupés, `ping` échoue, HOME/Plan/Focus/Pause restent utilisables |
| Pause d'urgence | **PASS** — activée hors ligne, message « Pause d'urgence active pendant 30 minutes », puis terminée ; état restauré |
| Réseau restauré | **PASS** — mode avion 0, Wi-Fi connecté, `ping` OK |
| Crash/ANR | **PASS** — aucun `FATAL EXCEPTION`/ANR ATLAS dans les logs consultés |

Le smoke complet 16 points de la recette pilote n'est pas exécuté : les points
distants (Relay, chat réel, proposition, APPLY/REJECT/ACK) dépendent de D.

## 4. Scope D — blocage initial du transport (2026-11-02), levé le 2026-11-04

### 4.1 Préparation effectuée

- Identité produit dédiée créée puis révoquée après blocage (voir A).
- Proxy loopback `127.0.0.1:4400` démarré, health `{"status":"ok"}`.
- Connecteur PC démarré, `relay_connected:true`, métriques sur `4401`.
- Aucun `adb reverse`, aucune connexion directe au Core/Gateway.

### 4.2 Blocage constaté

Le chemin public requis par D n'est pas disponible :

- `all.taildc7886.ts.net` renvoie **NXDOMAIN** depuis `1.1.1.1` et `8.8.8.8` ;
- le Pixel ne résout pas non plus le nom (`ping: unknown host`) ;
- `tailscale funnel status` déclare pourtant `tcp://all.taildc7886.ts.net:8443
  → 127.0.0.1:4400` et `AllowFunnel` ;
- la terminaison TLS testée vers l'IP tailnet `100.119.62.63:8443` avec SNI
  `all.taildc7886.ts.net` échoue (`unexpected eof` / `errno=104`) ;
- `tailscale cert all.taildc7886.ts.net` est refusé sans privilège
  (`Access denied`), et la configuration DNS Tailscale locale signale
  `Tailscale DNS: disabled` / échec de lecture DNS.

Sans DNS public ni TLS public fonctionnel, l'application ne peut pas établir
la connexion WSS publique exigée. Aucun contournement (`adb reverse`, boucle
locale, URL IP avec certificat incompatible) n'a été utilisé.

### 4.3 État après arrêt

- Proxy et connecteur arrêtés ; `4400`/`4401` ne sont plus en écoute ;
- Relay `127.0.0.1:4402` reste sain ;
- application non réappairée, configuration Relay toujours `pixel-v7` ;
- aucun consentement produit nouveau, aucun chat distant, aucune proposition
  distante, aucun APPLY/REJECT/ACK ;
- aucun `adb reverse` ;
- un seul device Relay actif (`pixel-v7`), 19 révoqués.

## 5. Verdicts après exécution

| Verdict | État | Portée |
|---|---|---|
| `GO_PRODUCT_APK_CANDIDATE` | **GO_WITH_LIMITATIONS** | APK `c7b0f71f…` revu, provenance `b9f17f4`, installé in-place ; limites Pixel/Keystore/restauration |
| `GO_PRODUCT_INSTALL` | **GO_WITH_LIMITATIONS** | installation in-place réelle PASS, données conservées, signature/permissions/HOME inchangés ; restauration réelle non exécutée, Keystore non exportable |
| `GO_PRODUCT_LOCAL_SMOKE` | **GO_WITH_LIMITATIONS** | smoke local ciblé PASS ; smoke complet 16 points non exécuté |
| `GO_PRODUCT_REMOTE_CHAT` | **NO-GO** | transport public PASS le 2026-11-04, mais aucun tour réussi avec outils fiables |
| `GO_PRODUCT_PLAN_REVISION` | **NO-GO** | aucune proposition A0, aucun APPLY/REJECT/ACK |
| `GO_PRODUCT_REMOTE` | **NO-GO** | parcours produit distant incomplet |
| `GO_DAILY_PILOT` | **NO-GO** | D non franchi ; E non autorisé |

## 6. Limites et prochaine action

1. **Transport public :** DNS/TLS/WSS **PASS** depuis le 2026-11-04 ; le
   problème restant est le parcours outillé/proposition (`V7-07_D_RESUME.md`),
   pas le Funnel.
2. **Restauration réelle non exécutée** : l'archive a été extraite et vérifiée,
   et l'outil a été validé sur AVD synthétique. Une restauration sur le produit
   réel modifierait ses données ; elle reste à autoriser si le propriétaire
   veut une preuve de restauration in-situ.
3. **Keystore** : les alias ne sont pas exportables. La mise à jour in-place les
   conserve ; une désinstallation/réinstallation les perdrait et exigerait un
   réappairage.
4. **Séparation Core** : le bearer Android reste partagé, `pixel-v7` est
   toujours l'unique identité active. La rotation a été refusée et n'a pas été
   contournée.
5. **Consentements** : les anciens `GRANTED v2` n'ont pas été utilisés comme
   consentement produit ; aucun nouveau consentement n'a été créé.

**Conclusion :** A, B et C sont exécutés et prouvés dans leurs limites ; le
transport public D a été rétabli le 2026-11-04 et l’appairage fonctionne, mais
la recette de proposition A0 reste **NO-GO**. Le pilote quotidien E n'est ni
autorisé ni proposé en l'état.
