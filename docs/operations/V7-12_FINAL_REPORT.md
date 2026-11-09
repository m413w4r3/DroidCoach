# V7-12 — Rapport final « Docker Stack unifiée »

**Statut :** en cours de clôture. Les valeurs marquées `PENDING` sont renseignées après intégration, déploiement et preuves.
**Date :** 2026-11-09
**Branche :** `feat/atlas-v7-12-docker-runtime`
**Base réconciliée :** `b44d0ffd467b5b90b606b8ccb08a370af4629df9`
**SHA final :** `PENDING`
**CI finale :** `PENDING`

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
| Core V7-12 | build `backend/Dockerfile` | `atlas-core-v7-12:v7-12-local` = `sha256:16d1f708d29f…` (A4 inclus) |
| Relay | wrapper `deploy/relay/Dockerfile` sur `atlas-paseo-relay:r3a` | base `sha256:75d68fd8…` ; wrapper `atlas-relay-v7-12:v7-12-local` = `sha256:08d1d0500e47…` |
| Proxy | build `integrations/relay/proxy/Dockerfile` | `atlas-proxy-v7-12:v7-12-local` = `sha256:e0b23fef0c13…` |
| Connecteur | build `integrations/relay/pc/Dockerfile` | `atlas-connector-v7-12:v7-12-local` = `sha256:8badcb86d618…` |
| Gateway OpenClaw | `ghcr.io/openclaw/openclaw@sha256:f40d1e66…` | `2026.10.5` |
| Python base | `python:3.12-slim@sha256:5e2bdb1b…` | épinglé |
| Node base | `node:24-bookworm-slim@sha256:01d566e8…` | épinglé |
| BusyBox healthcheck | `busybox:1.36.1-musl@sha256:3c6ae800…` | épinglé |

## 3. Services et exposition

| Service | État final | Port hôte | Réseau |
|---|---|---|---|
| `atlas-core` | `PENDING` | aucun | `atlas-internal` |
| `atlas-relay` | `PENDING` | aucun | `atlas-internal` |
| `atlas-proxy` | `PENDING` | `127.0.0.1:4400` | `atlas-internal` + `atlas-edge` |
| `atlas-connector` | `PENDING` | aucun (4401 interne) | `atlas-internal` |
| `openclaw-gateway` | `PENDING` | aucun | `atlas-internal` + `atlas-egress` |

Aucun `network_mode: host`, aucun socket Docker, aucun port Core/Gateway/Relay/métriques publié.

## 4. Persistance et sauvegardes

| Élément | Preuve |
|---|---|
| Volume Core | `atlas-core-data`, conservé, `user_version=5`, intégrité `ok`, FK 0 |
| Base et sidecars | 0600, propriétaire 10001 ; `/data/replay` 0700 |
| Backup pré-migration | `~/.local/state/atlas-stack/backups/pre-v7-12-20261109T181021Z` (287 Mo, manifeste SHA-256) |
| Backup opérationnel | `./scripts/atlas-stack backup`, manifeste, rotation 7 jours, `PENDING` exécution live |
| Chiffrement au repos | ZFS `aes-256-gcm`, clé disponible (PASS) |
| Restore vérifié | copie isolée `PENDING` |

## 5. Identité et binding

| Élément | Valeur |
|---|---|
| Appareil Relay actif | `pixel-v7`, unique (ID conservé hors document) |
| Principal Android | `PENDING` (nouveau `device_ref`, aucun consentement transféré) |
| Credential RELAY | `PENDING` |
| Binding | `PENDING` |
| Principal / credential TOOL | `PENDING` (dormant, plugin `atlas_core_tools` désactivé) |
| Capacités Android | 14 opérations produit ; aucune capability DIRECT |
| Consentements | `DAY_PLAN` conservé ; `ACTIVITY_STATUS` reste `REVOKED` ; re-consentement Pixel explicite requis après cutover |
| Legacy | `PENDING` ; révocation soumise à confirmation propriétaire |

## 6. État Pixel et recette réelle

| Contrôle | Résultat |
|---|---|
| Pixel connecté | `BLOCKED` au moment de l'ouverture (`adb devices` vide, Tailscale offline) |
| APK V7-11 | hash documenté `bb703ec7…` ; non réinstallé |
| LTE / Wi-Fi / Funnel TLS | Funnel public 200 vérifié ; Pixel `BLOCKED` |
| Session Noise / Core / Gateway | `BLOCKED` sans Pixel |
| Coach (froid, conversation, multi-messages) | `BLOCKED` |
| Synchronisation / consentements | `BLOCKED` ; re-consentement explicite attendu |
| Plan A0 / REJECT / APPLY / ACK | `BLOCKED` |
| Stabilité (Core, connecteur, stack, hôte) | `PENDING` (backend) ; Pixel `BLOCKED` |
| Utilisation quotidienne | `BLOCKED` tant que le Pixel n'a pas produit de preuve réelle |

## 7. Tailscale

- Tailscale reste sur l'hôte, non dockerisé.
- Funnel `8443 → 127.0.0.1:4400` conservé et vérifié 200 avant migration.
- DNS, Serve, identité de machine et port 8770 inchangés.

## 8. Tests exécutés

| Suite | SHA | Résultat |
|---|---|---|
| Backend `pytest -q` | intégré A4 + correctif A6 | **509 passed, 1 skipped** (87 tests V4 ciblés PASS) |
| Connecteur `npm run test:unit` | intégré + correctif A6 | 144 passed |
| Proxy `node --test` | intégré | 20 passed, 1 skipped (Relay isolé non lancé) |
| Audit production connecteur | intégré | 0 vulnérabilité |
| Android JVM + lint | intégré | BUILD SUCCESSFUL (2 min 20 s) |
| Gateway OpenClaw (A3) | intégré `83c1527` | **PASS** : image épinglée, healthcheck authentifié, agent `atlas`/`openai/gpt-6-luna`, profil OAuth, `atlas_core_tools` désactivé, outils refusés, read_only, aucun docker.sock, SIGTERM/restart, 10 bases SQLite intègres, 125 sessions, manifeste live inchangé (106 053 fichiers, 3 095 465 071 octets, SHA-256 `13c6fcc9…`), systemd PID 3305 inchangé. |
| Stack isolée V7-12 | intégré `8322381` (run 9, correctifs A6) | **PASS complet** : cinq services healthy, `doctor`/`status` PASS, HMAC/rejeu/signature invalide PASS, restart/persistance Core PASS, révocation credential PASS, persistance identité connecteur PASS (`identity.json`/`devices.json`/`operations.json`), backup/restore PASS (intégrité SQLite, FK 0, manifeste 98 entrées, rotation 7 jours, copie de restauration), **`replay_quiescent=PASS`** (digest du cache anti-rejeu sauvegardé identique à la source quiescée, métadonnée `replay_capture`, Core healthy après redémarrage), arrêt propre sans suppression de volume. Le lecteur strict `ATLAS_V4_CURSOR_SECRET_FILE` démarre correctement dans le conteneur. |
| Recette Pixel physique | `BLOCKED` | non exécutable sans appareil |

## 9. Gates

| Gate | Statut |
|---|---|
| `GO_DOCKER_COMPOSE_UNIFIED` | `PENDING` |
| `GO_CORE_DOCKER_NETWORK` | `PENDING` |
| `GO_RELAY_DOCKER` | `PENDING` |
| `GO_PROXY_DOCKER` | `PENDING` |
| `GO_CONNECTOR_DOCKER` | `PENDING` |
| `GO_OPENCLAW_DOCKER` | `PENDING` |
| `GO_TAILSCALE_INGRESS` | `PENDING` (baseline 200, migration à vérifier) |
| `GO_DOCKER_PERSISTENCE` | `PENDING` |
| `GO_DOCKER_BACKUP_RESTORE` | `PENDING` |
| `GO_REPLAY_PROTECTION` | `PENDING` |
| `GO_PIXEL_PRINCIPAL_BINDING` | `PENDING` |
| `GO_LEGACY_REVOKED` | `BLOCKED` — confirmation propriétaire requise |
| `GO_PIXEL_REMOTE_CHAT` | `BLOCKED` — Pixel absent |
| `GO_PIXEL_REMOTE_SYNC` | `BLOCKED` — Pixel absent |
| `GO_PIXEL_COACH_DAILY_USE` | `BLOCKED` — Pixel absent |

## 10. Rollback

- Core : image précédente `sha256:1e4c8e6c…` et backup v5 conservés.
- Relay : image `atlas-paseo-relay:r3a` et état connecteur restaurables.
- Connecteur : identité, registre et opérations sauvegardés.
- Gateway : service systemd et état OpenClaw préservés ; un seul écrivain à la fois.
- Docker : conteneurs remplaçables, volumes conservés ; jamais `down -v`.
- Pixel : APK précédent et sauvegarde app-only ; aucune désinstallation.
- Tailscale : aucune modification.

## 11. Verdict

`PENDING` — le verdict « utilisation réelle » ne sera prononcé qu'après la recette Pixel physique et la confirmation des gates. En l'absence de Pixel, la stack peut être déployée et supervisée, mais les gates Pixel restent `BLOCKED` et aucune preuve Android ne doit être revendiquée.

## 12. Limites et travaux restants

- Pixel absent : recette réelle, re-consentement, chat, sync et plan non exécutés.
- WGER : pas d'accès Internet direct depuis le Core dans cette topologie ; le plan local existant reste utilisable.
- Propositions historiques `device_ref=NULL` non réattribuées ; aucun backfill.
- Révocation LEGACY non exécutée ; confirmation propriétaire requise.
- La période d'usage quotidien commence après la recette Pixel et le feu vert propriétaire.
