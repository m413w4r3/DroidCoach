# V7-11A — Inventaire live en lecture seule

**Date de reconnaissance :** 2026-11-08 (UTC)
**Opérateur :** parent Paseo uniquement. Aucune écriture, aucun redémarrage, aucune modification de volume, aucun ADB shell/installation.
**Base Git :** `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c` (`origin/main`), branche d'intégration `feat/atlas-v7-11-migration-readiness`.

## Git et CI

| Élément | Constat |
|---|---|
| HEAD local / `origin/main` | `8dac699cdb37e2f858a76e4cf7f9fc3bcd613e9c` — identiques, arbre propre avant travaux. |
| Commit V7-10 final | `3123dba4f89579d414406d8c790609d1e4a5adf6`. |
| CI GitHub du SHA `8dac699` | Run `42467740775`, conclusion **failure**. Job `relay-node` : success. Job `verify` : failure sur l'étape `Run backend tests`. |
| Cause CI | 2 tests `scripts/v7-06c/tests/test_harness.py` : `sqlite3.OperationalError: table agent_proposals_v4 has 11 columns but 10 values were supplied`. 494 passed, 2 failed, 1 skipped au run distant. |

## Core

| Élément | Constat vérifié |
|---|---|
| Conteneur | `atlas-core-atlas-core-1`, image `atlas-core-atlas-core` construite le **2026-11-04** (antérieure au code final V7-10 du 6 nov.). |
| Code embarqué | `atlas_backend` présent ; `v4_auth.py` et `v4_assertion.py` **absents**. Le Core live ne contient donc pas l'implémentation V7-10. |
| Processus | **Un seul processus** `uvicorn atlas_backend.main:app --host 127.0.0.1 --port 8100` (PID 5661, UID 10001), sans `--workers`, sans `--reload`. |
| Réseau | `network_mode: host`, écoute `127.0.0.1:8100`. |
| Mode auth V4 | `ATLAS_V4_AUTH_MODE` absent de l'environnement du conteneur → défaut legacy du code. Les variables `ATLAS_V4_CAPABILITIES_JSON` présentes proviennent de `compose.openclaw.yaml` mais sont inertes dans l'image pré-V7-10. |
| Configuration personnelle | `ATLAS_AGENT_ADAPTER=openclaw`, `OPENCLAW_BASE_URL=http://127.0.0.1:18789`, `ATLAS_CHAT_V4_DEADLINE_S=40`, `ATLAS_AGENT_DAILY_TOKENS_ENFORCED=false`. |
| Volume | `atlas-core-data` monté sur `/data` (RW dans le conteneur). Fichiers tokens montés en lecture seule depuis `~/.local/state/atlas-core/secrets/compose/` (contenu non lu). |
| SQLite `PRAGMA user_version` | **3** — schéma antérieur à la migration V7-10 (v4/v5). |
| SQLite `PRAGMA integrity_check` | **ok**. |
| SQLite `PRAGMA foreign_key_check` | **0 violation**. |
| SQLite `journal_mode` | `delete` (pas de WAL au moment de la lecture read-only). |
| Propositions | 3 lignes : `APPLIED`=1, `PENDING_ANDROID`=1, `REJECTED`=1. |
| ACK Android | 2 lignes : `APPLIED`=1, `REJECTED`=1. |
| Audit d'expiration v5 | Table absente (schéma v3), cohérent avec l'image pré-V7-10. |
| Health | `GET /health` → 200, `{"status":"ok","mode":"openclaw"}`. `GET /ready` → 404. |

**Conséquence :** le live n'a subi aucune migration V7-10. La migration v3→v5, le backfill LEGACY et le cutover restent entièrement à qualifier et à exécuter en V7-11B.

## Relay, connecteur, proxy et Gateway

| Composant | Constat vérifié |
|---|---|
| Relay | Conteneur `atlas-v7-relay`, image `atlas-paseo-relay:r3a` construite le **2026-10-26**, écoute `127.0.0.1:4402→4000`. Processus Elixir `paseo_relay`. |
| Connecteur PC (métriques 4401) | **Aucun listener** sur 4400/4401 au moment de la reconnaissance ; aucun processus `connector`/proxy identifié. |
| Proxy 4400 | **Aucun listener** sur `127.0.0.1:4400`. |
| Funnel | `tailscale funnel status` : `tcp://all.taildc7886.ts.net:8443` → `tcp://127.0.0.1:4400`. La destination étant sans listener, le chemin public Funnel est **inopérant** dans cet état. |
| Tailscale Serve privé | `https://all.taildc7886.ts.net` (tailnet only) → `http://127.0.0.1:8770`. Aucun listener local 8770 observé. |
| Gateway OpenClaw | `openclaw-gateway.service` (systemd user) actif, `127.0.0.1:18789`, processus node PID 3305. |
| Pixel Tailscale | Nœud `<PIXEL_TAILSCALE_NODE>` vu **offline, last seen 33 days ago** par Tailscale. Le Pixel est néanmoins présent sur ADB USB (`<PIXEL_SERIAL>`). |

**Conséquence :** l'état live n'est pas celui d'une chaîne Relay/connecteur active. Toute procédure V7-11B doit d'abord rétablir et qualifier le connecteur/proxy et le chemin Funnel, ou constater explicitement l'absence de service avant de modifier Core/Relay.

## ADB et Pixel

| Élément | Constat |
|---|---|
| Énumération | `adb devices -l` exécuté une seule fois, en lecture : Pixel `<PIXEL_SERIAL>` (model `Pixel_9_Pro_XL`, device `komodo`) présent en USB. |
| Commandes Pixel | **Aucun** `adb shell`, `install`, `run-as`, `pm`, `am` ou action sur le Pixel. |
| Autorisation utilisateur | L'utilisateur a indiqué que le Pixel est branché et a autorisé l'écrasement de l'application Atlas. Cette autorisation est **notée pour V7-11B** mais **non exercée en V7-11A**, conformément à la frontière de mission. |

## État de la frontière V7-11A/V7-11B

- Aucun arrêt/modification Core/Relay/Gateway.
- Aucune promotion de base, aucun backfill, aucune réconciliation sur le volume live.
- Aucune modification de secret, bearer, credential ou consentement réel.
- Aucune installation APK, aucun ADB shell vers le Pixel.
- Aucun push/PR/merge distant.

Toute opération listée ci-dessus reste soumise à l'autorisation ciblée V7-11B.
