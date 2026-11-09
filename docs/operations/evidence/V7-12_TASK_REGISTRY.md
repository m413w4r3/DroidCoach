# V7-12 — Registre de mission « Docker Stack unifiée »

**Date d'ouverture :** 2026-11-09
**Parent / intégrateur :** Paseo (session principale)
**Branche d'intégration :** `feat/atlas-v7-12-docker-runtime`
**Base d'intégration retenue :** `b44d0ffd467b5b90b606b8ccb08a370af4629df9`
**Profil enfants obligatoire :** `codex:gpt-6-luna`, effort `xhigh`, aucun fallback.

## 1. Réconciliation Git réelle

| Référence | SHA réel | Constat |
|---|---|---|
| `origin/main` annoncé `8dac699` | `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c` | Ancien `main`, contient V7-10 et la CI en échec sur le harness V7-11. |
| PR #3 annoncée `256d517` | `256d517e1f97856dcd9e3431c75ae564f352919c` | Tête de la PR #3, intégrée par le merge `417b335`. |
| `origin/main` courant | `417b3353809b7f0f728848d44b7bf1497f8fdaa6` | Merge PR #3 ; **ne contient pas** B16/B17. |
| B16 | `b73c335b70667702c36ee090b34781feda8fe3e7` | Design binding, non ancêtre de `main`. |
| B17 | `e5e67d2ebd841bdac9a2c98d4e08c1d07b0ed1b4` | Revue finale binding, non ancêtre de `main`. |
| Intégration locale B16 | `16f85cb2f8bbdef223cc10e22a162ee0d253882f` | Sur `feat/atlas-v7-11-migration-readiness`. |
| Intégration locale B17 | `b44d0ffd467b5b90b606b8ccb08a370af4629df9` | Tête de la branche V7-11 ; contient `8dac699`, `256d517`, B16 et B17. |

**Décision :** la branche V7-12 part de `b44d0ff`, seul état qui conserve intégralement les correctifs V7-11 et les livrables B16/B17. `417b335` n'ajoute aucun contenu hors `256d517` + `8dac699`, déjà ancêtres de `b44d0ff`. Aucune branche utile n'est écrasée, aucun force-push, aucun merge direct sur `main`.

## 2. Inventaire runtime réel (2026-11-09, lecture seule)

| Élément | État constaté |
|---|---|
| Core | Conteneur `atlas-core-atlas-core-1`, image `atlas-core-v7-11b-final-7efbefc` (`1e4c8e6c…`), `network_mode: host`, `127.0.0.1:8100`, healthy. |
| Volume Core | `atlas-core-data`, base `user_version=5`, `integrity_check=ok`, `foreign_key_check=0`, registre V4 vide (0 principal/credential/binding), 3 propositions, 2 consentements. |
| Relay | Conteneur `atlas-v7-relay`, image `atlas-paseo-relay:r3a` (`75d68fd8…`), `127.0.0.1:4402→4000`, sans volume. |
| Proxy | Processus host `node integrations/relay/proxy/src/server.mjs`, `127.0.0.1:4400`. |
| Connecteur | Processus host `node dist/main.js start`, métriques `127.0.0.1:4401`, `relay_connected=true`, 0 session active au relevé. |
| Gateway | `openclaw-gateway.service` (systemd user), node PID 3305, `127.0.0.1:18789`, health OK. Image officielle `ghcr.io/openclaw/openclaw:2026.10.5`, digest index `sha256:f40d1e66fd14d8c5b49c832f4100f83b53fdc01f5108f7db11de38fd0ee6dbe5`. |
| État OpenClaw | `~/.openclaw` complet, 1,6 Go, propriétaire UID 1000, configuration `openclaw.json` 0600, agent `atlas`, modèle `openai/gpt-6-luna`, plugin `atlas_core_tools` désactivé. |
| Registre Relay | 20 entrées, **1 seule active** : `pixel-v7` (ID conservé hors document), capacités V4 présentes. |
| Identité Relay | `identity.json` 0600, empreinte préfixe `7c7df954…` ; ne pas régénérer, ne pas réappairer. |
| Connecteur état | `identity.json`, `devices.json`, `operations.json` présents 0600 ; `operations.json` contient 2 entrées. |
| Tailscale | Funnel `tcp://all.taildc7886.ts.net:8443 → 127.0.0.1:4400` ; Serve 8770 hors périmètre ; inchangés. |
| Pixel | **Non connecté** : `adb devices` vide ; nœud Tailscale offline depuis 35 jours. |
| Chiffrement au repos | `/home/nill` sur ZFS `rpool/USERDATA/nill_y4x48k`, `encryption=aes-256-gcm`, `keystatus=available`. |
| Secrets Core | `~/.local/state/atlas-core/secrets/compose/*.token`, 0444 en lecture seule, distincts. |
| Secrets connecteur | `~/.config/atlas/relay.env` 0600, `~/.local/state/atlas-relay/android-token` 0600. |

## 3. Décisions d'architecture verrouillées

1. Un seul `compose.yaml` racine, projet `atlas-core`, cinq services : `atlas-core`, `atlas-relay`, `atlas-proxy`, `atlas-connector`, `openclaw-gateway`.
2. Réseau Docker privé `atlas-internal` (`internal: true`) pour les cinq services ; réseau `atlas-edge` (bridge sans NAT, `enable_ip_masquerade=false`) pour le seul proxy, afin de publier `127.0.0.1:4400` sans accès Internet (un réseau `internal: true` bloque la publication de port, vérifié) ; réseau `atlas-egress` avec NAT réservé au Gateway. Aucun `network_mode: host`.
3. Seule publication hôte : `127.0.0.1:4400:4400` pour le proxy. Core, Relay, connecteur (4401) et Gateway (18789) ne sont pas publiés.
4. DNS internes : `http://atlas-core:8100`, `ws://atlas-relay:4000`, `http://openclaw-gateway:18789`. Le proxy écoute `0.0.0.0:4400` dans son conteneur et utilise strictement `atlas-relay:4000` comme amont.
5. L'adaptation Docker des URL est autorisée uniquement par un mode explicite et une allowlist de noms exacts ; aucune URL client ne peut choisir la destination.
6. Le Core garde exactement un worker et un écrivain SQLite ; `atlas-core-data`, `/data/replay` 0700, `FileReplayNonceCache` et le verrou SQLite sont conservés.
7. L'état OpenClaw est monté au même chemin absolu `/home/nill/.openclaw` avec `HOME=/home/nill`, UID/GID 1000, afin de préserver les chemins absolus de `openclaw.json` sans réécriture. Le service systemd est arrêté avant le conteneur et conservé pour rollback.
8. Les secrets restent hors Git, en fichiers privés montés read-only ; ajout d'un support `*_FILE` pour le curseur Core et le service token connecteur afin d'éviter les valeurs en environnement de processus.
9. La migration Core v3→v5 est terminée : aucune nouvelle migration de schéma, aucun backfill, aucune réécriture de la base.
10. Un seul appareil Relay actif (`pixel-v7`) ; le principal Android est nouveau, avec un nouveau `device_ref`. **Aucun consentement historique n'est transféré.** Les consentements existants restent inchangés ; le Pixel devra les renouveler explicitement via l'application.
11. Le jeu de capacités du principal couvre exactement les opérations produit du Pixel : lecture/chat/proposition et écritures de synchronisation consenties, sans DIRECT ni TOOL. Une capability ne vaut jamais consentement.
12. La révocation du bearer partagé LEGACY reste soumise à une confirmation explicite unique du propriétaire après présentation des contrôles.
13. Un credential `TOOL` distinct est provisionné avec un principal `TOOL`, la **même valeur** que `ATLAS_TOOL_TOKEN` (exigence de `require_tool`), des grants de lecture/proposition, `ATLAS_V4_TOOL_PRINCIPAL_ID` côté Core et aucun binding. Il reste dormant tant que le plugin `atlas_core_tools` est désactivé.
14. Les propositions historiques ont `device_ref=NULL` ; elles ne sont pas réattribuées au nouveau principal et aucun backfill n'est exécuté en V7-12. Les nouvelles propositions appartiennent au nouveau `device_ref`.

## 4. Tickets et propriété des fichiers

| Ticket | Rôle | Périmètre d'écriture exclusif | Dépend de | Oracle |
|---|---|---|---|---|
| `V7-12-A1` | Compose et Core | `compose.yaml`, `compose.isolated.yaml`, `backend/Dockerfile`, `backend/docker-entrypoint.py`, `scripts/atlas-stack`, `scripts/v7-12/backup*` | — | `docker compose config`, build Core, healthcheck, tests backend, backup/restore isolé |
| `V7-12-A2` | Relay, proxy, connecteur | `integrations/relay/proxy/**`, `integrations/relay/pc/**`, `deploy/relay/**` | A1 pour les noms de services | tests Node, build images, proxy→relay, connexion Relay interne, métriques 4401 |
| `V7-12-A3` | Gateway OpenClaw Docker | `integrations/openclaw/gateway/**` | — | conteneur isolé, config/agent/plugin/tools préservés, healthcheck, pas de socket Docker |
| `V7-12-A4` | Auth, principals, migration | `backend/atlas_backend/v4_router.py` (support `*_FILE`), `backend/tests/**`, `scripts/v7-12/provision*`, `scripts/v7-12/verify*` | A2 (connecteur Docker) pour le cutover | tests JVM/backend, provisioning sur copie v5, HMAC/rejeu/binding sur stack isolée |
| `V7-12-A5` | Android/Pixel, recette | Lecture seule ; aucune écriture | A1-A4 | APK/hash, transport RELAY, chat, sync, plan, rollback ; **BLOCKED** sans Pixel |
| `V7-12-A6` | Intégration et revue indépendante | Lecture seule | A1-A4 | revue sécurité/infrastructure sur SHA intégré, 0 P0/P1 |

**Concurrence :** trois producteurs de code maximum simultanément. A1, A2 et A3 forment la vague 1. A4 démarre en lecture seule puis devient producteur en vague 2. A5 et A6 sont en lecture seule. Aucun enfant ne délègue.

## 5. Lots d'exécution

- **L0 — Réconciliation** : fait (ce document, branche créée).
- **L1 — Implémentation** : A1/A2/A3 en worktrees séparés, base `b44d0ff`.
- **L2 — Intégration** : parent seulement ; revue A6 des modifications sécurité/infrastructure.
- **L3 — Stack isolée** : projet Compose distinct, volumes/identités/ports séparés (14400/14401/14402, Core 18100), mêmes images et même Compose.
- **L4 — Préparation live** : backups frais, inventaire des processus, commandes de rollback.
- **L5 — Déploiement réel** : arrêt des anciens proxy/connecteur, fenêtre Core, Gateway conteneurisé, cinq services supervisés.
- **L6 — Cutover identité** : principal, credential RELAY, binding, dual, preuve Pixel réelle, puis mode principal. Révocation LEGACY seulement après accord explicite.
- **L7 — Recette quotidienne** : Pixel physique, Coach, sync, plan, stabilité, puis usage réel.

## 6. Gates

| Gate | Statut à l'ouverture |
|---|---|
| `GO_DOCKER_COMPOSE_UNIFIED` | NOT_RUN |
| `GO_CORE_DOCKER_NETWORK` | NOT_RUN |
| `GO_RELAY_DOCKER` | NOT_RUN |
| `GO_PROXY_DOCKER` | NOT_RUN |
| `GO_CONNECTOR_DOCKER` | NOT_RUN |
| `GO_OPENCLAW_DOCKER` | NOT_RUN |
| `GO_TAILSCALE_INGRESS` | NOT_RUN (Funnel existant, non modifié) |
| `GO_DOCKER_PERSISTENCE` | NOT_RUN |
| `GO_DOCKER_BACKUP_RESTORE` | NOT_RUN |
| `GO_REPLAY_PROTECTION` | NOT_RUN |
| `GO_PIXEL_PRINCIPAL_BINDING` | NOT_RUN |
| `GO_LEGACY_REVOKED` | NOT_RUN — confirmation propriétaire requise |
| `GO_PIXEL_REMOTE_CHAT` | BLOCKED — Pixel non connecté |
| `GO_PIXEL_REMOTE_SYNC` | BLOCKED — Pixel non connecté |
| `GO_PIXEL_COACH_DAILY_USE` | BLOCKED — Pixel non connecté |

## 7. Preuves parent à l'ouverture

| Élément | Preuve |
|---|---|
| Backup de sécurité live | `~/.local/state/atlas-stack/backups/pre-v7-12-20261109T181021Z` (0700, 287 Mo), Core SQLite cohérente, `identity/devices/operations.json` valides, OpenClaw SQLite cohérente, manifeste SHA-256. |
| Secrets V7-12 | `~/.local/state/atlas-core/secrets/v7-12/` (keyring + cursor, 0600, UID 10001) et `~/.local/state/atlas-relay/v7-12/` (assertion + service token, 0600, UID 1000). Key ID non secret : `v7-12-relay-20261109`. |
| Env file privé | `~/.config/atlas/stack.env` 0600 ; aucun secret en valeur, uniquement des chemins. |
| Base SQLite live | `user_version=5`, `integrity_check=ok`, FK 0 ; base passée en mode 0600, verrou 0600, replay 0700. |
| Tests backend | `pytest -q` : **474 passed, 1 skipped** ; le correctif harness V7-11 `850f6a2` est présent. |
| Tests connecteur | `npm run test:unit` : **131 passed**. |
| Tests proxy | `node --test integrations/relay/proxy/test` : **19 passed**. |
| Funnel public | `https://all.taildc7886.ts.net:8443/health` et `/ready` → 200 ; certificat Let's Encrypt valide. |
| Pixel | Absent : `adb devices` vide, nœud Tailscale offline. |
| Branche | `feat/atlas-v7-12-docker-runtime` : `931b028` (registre), `018fc91` (architecture), `65664e5` (migration/rollback), `026e994` (A2 intégré). |
| A2 intégré | `026e994a9c1265b7da1837576eff8a68ddb12cea` ; tests connecteur **140 passed**, proxy **20 passed / 1 skipped** (Relay isolé non lancé), audit production **0 vulnérabilité**. |
| Relay non-root | Image `atlas-relay-v7-12:local` testée avec `--user 1000:1000 --read-only --tmpfs /tmp`, health `/health` = 200 ; le Compose doit fixer `user: "1000:1000"`. |
| Service token | `~/.local/state/atlas-relay/v7-12/service-token` : 64 octets, sans newline final, 0600 UID 1000, conforme au lecteur A2. |
| Android JVM + lint | `./gradlew :app:testDebugUnitTest :app:lintDebug --max-workers=1` : **BUILD SUCCESSFUL** (2 min 20 s) sur le SHA intégré. |
| Propositions historiques | 3 lignes, toutes `device_ref IS NULL` ; aucune réattribution au nouveau principal, aucun backfill. |
| A1 intégré | `010ff1511f8e4951ab9610119540dd8fc7e78f44`, cherry-pick `8898cfa1…` ; 8 fichiers, 2 244 insertions. Compose/config PASS, image Core `sha256:a2febe6b…` (10001, `0.0.0.0:8100`, 1 worker), health mock 200, backend 474 passed/1 skipped, backup/restore synthétique PASS. |
| A2 fix intégré | `d29cb29667dcd1692430a71a148c74aa6a59bcf0`, cherry-pick `0542cc7…` ; map partielle → V4 désactivé sans fallback, V2 conservé. Connecteur **144 passed**, proxy 20 passed/1 skipped. Images `:local` reconstruites : relay `08d1d0500e47`, proxy `e0b23fef0c13`, connector `1df5d2d3ab3d`. |
| Stack isolée | Les cinq services sont **healthy** ; `doctor`/`status` PASS (compose, secrets, volume, réseaux internal/edge/egress). HMAC accepté, rejeu refusé, signature invalide refusée, redémarrage Core et persistance du volume PASS, révocation du credential PASS. Le harness a été corrigé pour exécuter le seeding en UID 10001 (`8b542bd`) et créer un `operations.json` vide (`063feaa`). Backup/restore et persistance identité connecteur : run final `PENDING`. |
| A3 intégré | `83c1527` (cherry-pick `7b1de29`) : `integrations/openclaw/gateway/README.md` + `validate-container.sh`, 554 lignes. Validation isolée du Gateway avec copie de l'état réel, healthcheck authentifié, agent/modèle/OAuth/tools, read_only, aucun docker.sock, état live/systemd non modifiés. |
| A2 image fix intégré | `09b0637` (cherry-pick `613ac0c`) : `package.json`/`package-lock.json`/`dist` lisibles par l'utilisateur node ; `node -e require('/app/package.json')` PASS ; 144 tests connecteur PASS. |

## 8. Scout A4 et A5 — conclusions

| Ticket | Conclusion |
|---|---|
| A4 scout | Plan complet accepté. Provisioning atomique retenu : 2 principals (ANDROID/TOOL), 2 credentials (RELAY/TOOL), 1 binding Android, 20 grants (14+6), 25 événements (4 PROVISIONED + 20 CAPABILITY_CHANGED + 1 RELAY_BOUND). Le credential TOOL réutilise `ATLAS_TOOL_TOKEN`. Le parser connecteur ne rejette pas encore une map partielle : suivi P1 A2. |
| A4 producteur | Démarré en full-access : support `ATLAS_V4_CURSOR_SECRET_FILE`, méthode batch atomique, script de provisioning, vérificateur read-only. |
| A5 scout | Pixel absent (0 appareil ADB). Deux points bloquants pour la recette : l'app propose encore DIRECT et le sélectionne par défaut ; le store de consentement local ne lie pas `version/synced_version` à un `device_ref`, donc le re-consentement doit être déclenché par une action UI explicite, pas par une simple sync. |
| A5 producteur | Différé ; la limite de 3 producteurs est atteinte (A1/A3/A4). Ticket Android séparé après leurs commits. |

## 9. Notes de reprise

- Ne jamais utiliser `docker compose down -v`.
- Ne jamais recréer `atlas-core-data`.
- Ne jamais réappairer `pixel-v7` ni régénérer `identity.json`.
- Ne pas lancer `scripts/r3a/start-relay.sh` sur le Relay live pendant la migration.
- Ne pas modifier Tailscale/Funnel/Serve/8770.
- Une notification d'agent n'est pas une acceptation ; le parent relit le diff et relance les oracles sur le SHA intégré.
