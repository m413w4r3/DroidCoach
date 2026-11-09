# V7-12 — Rapport final « Docker Stack unifiée »

**Statut :** stack Docker déployée et supervisée sur l'hôte ; recette Pixel réelle non exécutée (appareil absent).
**Date :** 2026-11-09
**Branche :** `feat/atlas-v7-12-docker-runtime`
**Base réconciliée :** `b44d0ffd467b5b90b606b8ccb08a370af4629df9`
**SHA final :** `PENDING` (dernier commit fonctionnel `8e26e69`)
**CI finale :** `PENDING` (à vérifier sur le SHA poussé)

## 1. Réconciliation Git

| Référence | SHA |
|---|---|
| `origin/main` initial annoncé | `8dac699` |
| `origin/main` réel à l'ouverture | `417b335` (merge PR #3, sans B16/B17) |
| PR #3 | `256d517` |
| B16 / B17 | `b73c335` / `e5e67d2` |
| Intégration V7-11 | `16f85cb`, `b44d0ff` |
| Base V7-12 | `b44d0ff` (contient V7-11 + B16/B17) |
| SHA final intégré | `PENDING` |

Aucun force-push, aucun merge direct sur `main`, aucune branche utile abandonnée.

## 2. Images et digests

| Image | Référence | Digest / ID |
|---|---|---|
| Core V7-12 | build `backend/Dockerfile` | `atlas-core-v7-12:v7-12-local` = `sha256:fbbc6ce241a6…` (A4 + A6) |
| Relay | wrapper `deploy/relay/Dockerfile` sur `atlas-paseo-relay:r3a` | base `sha256:75d68fd8…` ; wrapper `atlas-relay-v7-12:v7-12-local` = `sha256:08d1d0500e47…` |
| Proxy | build `integrations/relay/proxy/Dockerfile` | `atlas-proxy-v7-12:v7-12-local` = `sha256:e0b23fef0c13…` |
| Connecteur | build `integrations/relay/pc/Dockerfile` | `atlas-connector-v7-12:v7-12-local` = `sha256:3bc98f889b85…` |
| Gateway OpenClaw | `ghcr.io/openclaw/openclaw@sha256:f40d1e66…` | `2026.10.5` |
| Python base | `python:3.12-slim@sha256:5e2bdb1b…` | épinglé |
| Node base | `node:24-bookworm-slim@sha256:01d566e8…` | épinglé |
| BusyBox healthcheck | `busybox:1.36.1-musl@sha256:3c6ae800…` | épinglé |

## 3. Services et exposition

| Service | État final | Port hôte | Réseau |
|---|---|---|---|
| `atlas-core` | `running|healthy` | aucun | `atlas-internal` |
| `atlas-relay` | `running|healthy` | aucun | `atlas-internal` |
| `atlas-proxy` | `running|healthy` | `127.0.0.1:4400` | `atlas-internal` + `atlas-edge` |
| `atlas-connector` | `running|healthy` | aucun (4401 interne) | `atlas-internal` |
| `openclaw-gateway` | `running|healthy` | aucun | `atlas-internal` + `atlas-egress` |

`scripts/atlas-stack doctor` : **PASS** sur compose, secrets, volume, réseaux, cinq healthchecks, Tailscale/Funnel, ZFS, horloge et multiplicité Gateway. `status` : `relay_connected=true active_sessions=0`. Seul `127.0.0.1:4400` est en écoute sur l'hôte. Aucun `network_mode: host`, aucun socket Docker, aucun port Core/Gateway/Relay/métriques publié.

**Écart Gateway documenté.** L'entrypoint de l'image épinglée exécute `doctor --fix` avant chaque démarrage ; sur l'état live, Doctor refuse la migration du plugin `codex` et le Gateway ne sert pas. Le Compose de production utilise `init: true` + `entrypoint: ["node","/app/openclaw.mjs"]` + `command: ["gateway","--port","18789","--bind","lan"]`, soit la CLI directe du service systemd, sans réparation automatique. Les captures temporaires incompatibles ont été déplacées sous `~/.openclaw.update-captures/plugin-captures-quarantine-20261109T202252Z` ; l'état durable n'a pas été modifié. Vérifié en live : health `OK`, plugin `codex` chargé, agent `atlas`, modèle `openai/gpt-6-luna`, `atlas_core_tools` désactivé, aucun port publié.

## 4. Persistance et sauvegardes

| Élément | Preuve |
|---|---|
| Volume Core | `atlas-core-data`, conservé, `user_version=5`, intégrité `ok`, FK 0 |
| Base et sidecars | 0600, propriétaire 10001 ; `/data/replay` 0700 |
| Backup pré-migration | `~/.local/state/atlas-stack/backups/pre-v7-12-20261109T181021Z` (287 Mo, manifeste SHA-256) |
| Snapshot sécurité pré-cutover | `~/.local/state/atlas-stack/backups/pre-cutover-20261109T194727Z` : 75 744 entrées, base SQLite cohérente (`ok`, FK 0, v5), verrou/replay, 5 secrets Core, `identity.json`/`devices.json`/`operations.json`, état OpenClaw, `stack.env`, manifeste SHA-256 |
| Backup opérationnel live | `~/.local/state/atlas-stack/backups/atlas-backup-20261109T201453Z-56b83945` : Core quiescé (`journal_mode=delete`, base+replay copiés arrêtés), 75 813 entrées, 4 matériaux privés connecteur, OpenClaw, manifeste, rotation 7 jours |
| Restore vérifié | `restore-check.py` : manifeste vérifié, matériaux 5 secrets + 4 privés, SQLite `ok`, FK 0, v5, permissions 0700/0600 ; copie isolée PASS (run 10/11) |
| Chiffrement au repos | ZFS `aes-256-gcm`, clé disponible (PASS) |

## 5. Identité et binding

| Élément | Valeur |
|---|---|
| Appareil Relay actif | `pixel-v7`, unique parmi 20 entrées (19 révoquées), ID conservé hors document |
| Principal Android | `prn_fHH…C8L` (référence tronquée), `device_ref` aléatoire, aucun consentement historique transféré |
| Credential RELAY | généré, 0600 propriétaire 1000, installé dans `~/.local/state/atlas-relay/v7-12/relay-credential` (non affiché) |
| Binding | 1 ligne `ACTIVE` vers l'ID Relay réel de `pixel-v7` ; aucun binding TOOL |
| Principal / credential TOOL | `prn_Z0t…2AQ`, audience TOOL, token outil historique préservé (dormant, `atlas_core_tools` désactivé) |
| Capacités Android | 14 opérations produit ; 6 pour TOOL ; aucune capability DIRECT |
| Registre V4 live | 2 principaux, 2 credentials, 1 binding, 20 grants, 25 événements (`4 PROVISIONED`, `20 CAPABILITY_CHANGED`, `1 RELAY_BOUND`) ; vérificateur 22/22 PASS |
| Consentements / propositions | `consent_states_v4=2`, `agent_proposals_v4=3` inchangés (snapshot hash-only identique) ; `ACTIVITY_STATUS` reste `REVOKED` ; re-consentement Pixel explicite requis |
| Legacy | bearer partagé et token outil conservés byte-identiques aux valeurs historiques ; mode `dual` maintenu ; **aucune révocation LEGACY** sans confirmation propriétaire |

## 6. État Pixel et recette réelle

| Contrôle | Résultat |
|---|---|
| Pixel connecté | `BLOCKED` (`adb devices` vide, aucun appareil) |
| APK V7-11 | APK local `app-debug.apk` SHA-256 `8e67a888…` (build du 6 nov.) ; hash installé non vérifiable sans appareil ; **aucune réinstallation** |
| LTE / Wi-Fi / Funnel TLS | Funnel public `8443/health` et `/ready` → 200 ; Pixel `BLOCKED` |
| Session Noise / Core / Gateway | `BLOCKED` sans Pixel ; Core→Gateway TCP OK, Gateway health OK, connector `relay_connected=true` |
| Coach (froid, conversation, multi-messages) | `BLOCKED` |
| Synchronisation / consentements | `BLOCKED` ; re-consentement explicite attendu avec le nouveau `device_ref` |
| Plan A0 / REJECT / APPLY / ACK | `BLOCKED` |
| Stabilité (Core, connecteur, stack, hôte) | Stack live cinq services healthy, `doctor` PASS ; redémarrages/backup/restore validés en isolation ; reboot hôte non exécuté ; Pixel `BLOCKED` |
| Utilisation quotidienne | `BLOCKED` tant que le Pixel n'a pas produit de preuve réelle |

## 7. Tailscale

- Tailscale reste sur l'hôte, non dockerisé.
- Funnel `8443 → 127.0.0.1:4400` conservé ; `/health` et `/ready` → **200 avant et après** migration.
- DNS, Serve, identité de machine et port 8770 inchangés ; aucune commande Tailscale de modification exécutée.

## 8. Tests exécutés

| Suite | SHA | Résultat |
|---|---|---|
| Backend `pytest -q` | intégré A4 + correctif A6 | **509 passed, 1 skipped** (87 tests V4 ciblés PASS) |
| Connecteur `npm run test:unit` | intégré + correctif A6 | 144 passed |
| Proxy `node --test` | intégré | 20 passed, 1 skipped (Relay isolé non lancé) |
| Audit production connecteur | intégré | 0 vulnérabilité |
| Android JVM + lint | intégré | BUILD SUCCESSFUL (2 min 20 s) |
| Revue indépendante A6 | `04a35b6` puis `589034b` | **GO** : trois constats initiaux (backup/replay temporel, map exacte, keyring dupliqué) FERMÉS ; revue delta Gateway `8e26e69` GO ; deux constats P2 sur le parsing status corrigés (`589034b`), vérification finale **GO, aucun P0/P1/P2**. |
| Gateway OpenClaw (A3) | intégré `83c1527` | **PASS** : image épinglée, healthcheck authentifié, agent `atlas`/`openai/gpt-6-luna`, profil OAuth, `atlas_core_tools` désactivé, outils refusés, read_only, aucun docker.sock, SIGTERM/restart, 10 bases SQLite intègres, 125 sessions, manifeste live inchangé (106 053 fichiers, 3 095 465 071 octets, SHA-256 `13c6fcc9…`), systemd PID 3305 inchangé. |
| Stack isolée V7-12 | intégré `8e26e69` (run 11) | **PASS complet** avec la définition Compose de production, y compris Gateway `init` + CLI directe : cinq services healthy, `doctor`/`status` PASS (`relay_connected=true`), HMAC/rejeu/signature invalide PASS, restart/persistance Core PASS, révocation credential PASS, persistance identité connecteur PASS, backup/restore PASS (intégrité SQLite, FK 0, manifeste 98 entrées, rotation 7 jours, copie de restauration), `replay_quiescent=PASS`, arrêt propre sans suppression de volume. |
| Migration live V7-12 | `8e26e69` | Anciens proxy/connecteur/Gateway systemd/Relay arrêtés ; snapshot frais ; provisioning 2/2/1/20/25 ; vérificateur **22/22 PASS** ; Core-only up + backup opérationnel vérifié ; stack complète cinq healthy ; `doctor` PASS ; Funnel 200 ; seul 4400 publié ; Core→Gateway OK ; plugin `codex` chargé ; tokens legacy préservés. |
| Recette Pixel physique | `BLOCKED` | non exécutable sans appareil |

## 9. Gates

| Gate | Statut |
|---|---|
| `GO_DOCKER_COMPOSE_UNIFIED` | **PASS** — un seul `compose.yaml`, projet `atlas-core`, cinq services, volume `atlas-core-data` conservé |
| `GO_CORE_DOCKER_NETWORK` | **PASS** — Core sans port hôte, réseau internal, joint par connecteur/proxy/Gateway |
| `GO_RELAY_DOCKER` | **PASS** — relay healthy, connecteur `relay_connected=true` via `ws://atlas-relay:4000` |
| `GO_PROXY_DOCKER` | **PASS** — proxy healthy, seul `127.0.0.1:4400`, upstream `atlas-relay:4000`, Funnel 200 |
| `GO_CONNECTOR_DOCKER` | **PASS** — connecteur healthy, identité/devices/operations persistés, map exacte, credential RELAY chargé |
| `GO_OPENCLAW_DOCKER` | **PASS** — Gateway healthy, image épinglée, agent/modèle/plugin conformes, état et auth préservés ; écart entrypoint documenté |
| `GO_TAILSCALE_INGRESS` | **PASS** — Funnel `8443 → 127.0.0.1:4400` → 200 après migration, aucune modification Tailscale |
| `GO_DOCKER_PERSISTENCE` | **PASS avec limite** — volume et état préservés, restart Core/connecteur et backup/restore validés ; reboot hôte complet non exécuté |
| `GO_DOCKER_BACKUP_RESTORE` | **PASS** — backup live quiescent vérifié, manifeste, SQLite ok, FK 0, restore-check PASS, rotation 7 j |
| `GO_REPLAY_PROTECTION` | **PASS (isolé + code live)** — HMAC accepté, rejeu refusé, signature invalide refusée, digest replay quiescent ; pas de requête Pixel réelle |
| `GO_PIXEL_PRINCIPAL_BINDING` | `BLOCKED` — principal/binding provisionnés et vérifiés 22/22, mais aucune preuve Pixel physique |
| `GO_LEGACY_REVOKED` | `BLOCKED` — confirmation propriétaire explicite non donnée ; bearer partagé conservé en `dual` |
| `GO_PIXEL_REMOTE_CHAT` | `BLOCKED` — Pixel absent |
| `GO_PIXEL_REMOTE_SYNC` | `BLOCKED` — Pixel absent |
| `GO_PIXEL_COACH_DAILY_USE` | `BLOCKED` — Pixel absent |

## 10. Rollback

- Core : image précédente `atlas-core-v7-11b-final-7efbefc` = `sha256:1e4c8e6c…` conservée ; volume `atlas-core-data` et backups v5 conservés. L'ancien conteneur a été retiré, pas l'image ni les données.
- Relay : conteneur historique `atlas-v7-relay` arrêté mais conservé ; image `atlas-paseo-relay:r3a` intacte.
- Connecteur : `identity.json`, `devices.json`, `operations.json`, tokens Pixel et `v7-12/` sauvegardés ; ancien processus hôte arrêté.
- Gateway : service systemd conservé mais **arrêté et désactivé** pour éviter un double écrivain au reboot ; état OpenClaw préservé ; captures temporaires quarantined sous `~/.openclaw.update-captures/plugin-captures-quarantine-20261109T202252Z`. Rollback : arrêter le conteneur Gateway, `systemctl --user enable --now openclaw-gateway.service`, vérifier 18789 et l'état.
- Docker : conteneurs remplaçables, volume jamais supprimé ; `down` sans `-v` uniquement.
- Pixel : APK précédent et sauvegarde app-only ; aucune désinstallation, aucun `pm clear`, rôle HOME inchangé.
- Tailscale : aucune modification ; Funnel conservé.

## 11. Verdict

**La stack Docker unifiée est déployée et supervisée** : cinq services healthy, seul le port 4400 est publié, Funnel 200, Core/Relay/Proxy/Connecteur/Gateway communiquent, l'identité RELAY est provisionnée et liée à `pixel-v7`, les backups sont cohérents et vérifiés. **Le verdict « utilisable au quotidien » reste `BLOCKED`** : aucun Pixel physique n'a été connecté, donc chat, sync, consentements, plan A0, ACK et stabilité réelle n'ont pas été prouvés. Le mode `dual` et le bearer LEGACY sont conservés ; aucune révocation LEGACY n'a été exécutée. Aucune preuve Android ne doit être revendiquée avant la recette Pixel.

## 12. Limites et travaux restants

- Pixel absent : recette réelle, re-consentement, chat, sync et plan non exécutés.
- Gateway : l'entrypoint image complet n'est pas utilisé en production à cause du refus Doctor sur l'état codex ; la CLI directe est la voie validée en live. Réévaluer à la prochaine version OpenClaw/plugin.
- WGER : pas d'accès Internet direct depuis le Core dans cette topologie ; le plan local existant reste utilisable.
- Propositions historiques `device_ref=NULL` (3) non réattribuées ; aucun backfill.
- Révocation LEGACY non exécutée ; confirmation propriétaire requise.
- Reboot hôte complet non exécuté (hôte partagé avec d'autres services) ; dépendances vérifiées, `restart: unless-stopped`, Docker actif au boot, Gateway systemd désactivé.
- La période d'usage quotidien commence après la recette Pixel et le feu vert propriétaire.
