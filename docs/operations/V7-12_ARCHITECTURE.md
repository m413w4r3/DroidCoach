# V7-12 — Architecture Docker de la stack ATLAS

**Statut :** référence de la mission V7-12, alignée sur le registre `docs/operations/evidence/V7-12_TASK_REGISTRY.md`.
**Projet Compose :** `atlas-core`.
**Volume de données historique :** `atlas-core-data` (conservé sans renommage ni recréation).

## 1. Objectif

Remplacer la chaîne hôte actuelle (Core `network_mode: host`, Relay, proxy et connecteur lancés à la main, Gateway systemd) par **une seule stack Docker Compose supervisée**, sans changer le chemin public du Pixel ni l'identité Relay existante.

La stack doit permettre :

- `./scripts/atlas-stack up` : démarrage supervisé des cinq services ;
- `./scripts/atlas-stack status` : un écran de diagnostic lisible ;
- `./scripts/atlas-stack backup` : sauvegarde cohérente et vérifiée ;
- `down` sans suppression de volume ;
- un rollback documenté vers les images et états précédents.

## 2. Topologie

```text
Pixel 9 Pro XL (Android 17)
  │  wss://all.taildc7886.ts.net:8443/ws (Noise)
  ▼
Tailscale Funnel (hôte Linux, inchangé)
  │  tcp 8443 → 127.0.0.1:4400
  ▼
atlas-proxy (conteneur, réseaux ingress + interne)
  │  http/ws atlas-relay:4000
  ▼
atlas-relay (conteneur, réseau interne)
  │  session Relay v2
  ▼
atlas-connector (conteneur, réseau interne)
  │  HTTP + assertion HMAC
  ▼
atlas-core (conteneur, réseau interne, 0.0.0.0:8100)
  │  HTTP /v1/chat/completions
  ▼
openclaw-gateway (conteneur, interne + sortie Internet)
  │  modèle openai/gpt-6-luna
  ▼
Internet (provider uniquement pour le Gateway)
```

Aucun autre service n'a d'accès Internet direct. Aucun `network_mode: host`.

## 3. Services

| Service | Image / construction | Réseau | Port hôte | Utilisateur | Persistance | Healthcheck |
|---|---|---|---|---|---|---|
| `atlas-core` | `backend/Dockerfile`, base Python épinglée | `atlas-internal` | aucun | `10001:10001` | volume `atlas-core-data:/data` | `GET http://127.0.0.1:8100/health` |
| `atlas-relay` | `deploy/relay/Dockerfile` (FROM image Relay qualifiée + busybox) | `atlas-internal` | aucun | image (root applicatif) | aucun état persistant | `GET http://127.0.0.1:4000/health` |
| `atlas-proxy` | `integrations/relay/proxy/Dockerfile`, Node 24 épinglé | `atlas-internal` + `atlas-edge` | `127.0.0.1:4400:4400` **seul port publié** | `node` (UID 1000) | aucun | `GET http://127.0.0.1:4400/health` |
| `atlas-connector` | `integrations/relay/pc/Dockerfile`, Node 24 épinglé | `atlas-internal` | aucun (métriques 4401 internes) | `1000:1000` | bind `~/.local/state/atlas-relay:/state` + secrets read-only | `GET http://127.0.0.1:4401/metrics` |
| `openclaw-gateway` | `ghcr.io/openclaw/openclaw@sha256:f40d1e66…` (2026.10.5) | `atlas-internal` + `atlas-egress` | aucun | `1000:1000` | bind `~/.openclaw:/home/nill/.openclaw` | `openclaw gateway health --port 18789` |

Chaque service applique, autant que compatible :

- `read_only: true` ;
- `cap_drop: [ALL]` ;
- `security_opt: ["no-new-privileges:true"]` ;
- `restart: unless-stopped` ;
- limites de journaux (`max-size`, `max-file`) ;
- healthcheck ;
- aucun `privileged`, aucun `network_mode: host`, aucun socket Docker monté.

Le Core conserve **un seul worker**, un seul écrivain SQLite, le verrou SQLite, `FileReplayNonceCache` sous `/data/replay` (0700) et les secrets HMAC distincts par `key_id`.

## 4. Réseaux et DNS

| Réseau | Type | Membres | Rôle |
|---|---|---|---|
| `atlas-internal` | bridge `internal: true` | les cinq services | communications privées, DNS Docker |
| `atlas-edge` | bridge avec `com.docker.network.bridge.enable_ip_masquerade: "false"` | `atlas-proxy` uniquement | ingress hôte `127.0.0.1:4400`, sans NAT donc sans accès Internet |
| `atlas-egress` | bridge avec NAT | `openclaw-gateway` uniquement | sortie Internet du provider modèle |

**Pourquoi `atlas-edge` n'est pas `internal: true` :** Docker bloque la publication de port hôte sur un réseau `internal: true` (vérifié : `127.0.0.1:4400` injoignable). `atlas-edge` est un bridge sans masquerade : l'hôte atteint le proxy par le port publié, mais le proxy n'a pas d'accès Internet (testé : sortie vers `1.1.1.1` en timeout). Il n'est donc pas un réseau de sortie Internet ; c'est uniquement le chemin d'ingress du Funnel.

Adresses stables :

- Core : `http://atlas-core:8100` ;
- Relay : `ws://atlas-relay:4000` ;
- Gateway : `http://openclaw-gateway:18789` ;
- Proxy amont : `atlas-relay:4000`.

Le proxy et le connecteur n'acceptent ces noms DNS **que** dans un mode Docker explicite (`ATLAS_PROXY_DOCKER_NETWORK=1`, `ATLAS_DOCKER_NETWORK=1`). Hors de ce mode, les protections loopback historiques restent actives. Aucune URL, aucun nom DNS et aucun chemin fournis par le client ne peuvent modifier la destination.

## 5. Persistance

### Core

- Volume nommé `atlas-core-data` monté sur `/data`.
- Base SQLite v5 existante, `atlas.sqlite3`, son verrou, `atlas.sqlite3.pre-v4.bak` et le répertoire `replay`.
- Aucune migration de schéma V7-12. Aucun backfill. Aucune réécriture de données.
- Le volume n'est jamais supprimé par `./scripts/atlas-stack down`.

### Connecteur

- Bind mount du répertoire d'état existant `~/.local/state/atlas-relay` vers `/state`.
- Fichiers préservés et sauvegardés : `identity.json`, `devices.json`, `operations.json`.
- Secrets V7-12 dans `~/.local/state/atlas-relay/v7-12/`, montés read-only.

### OpenClaw

- Bind mount complet de `~/.openclaw` vers le **même chemin absolu** `/home/nill/.openclaw`.
- `HOME=/home/nill`, UID/GID 1000 : les chemins absolus déjà présents dans `openclaw.json` restent valides.
- Préservés : configuration, agent `atlas`, sessions, bases SQLite, authentification provider, workspace, répertoire de secrets et clé de chiffrement des profils.
- Le service systemd `openclaw-gateway.service` reste installé pour rollback mais est arrêté avant le démarrage du conteneur. Deux Gateways ne doivent jamais écrire dans le même état.

## 6. Secrets

Tous les secrets restent hors Git, dans des fichiers privés :

| Secret | Source | Propriétaire / mode | Consommateur |
|---|---|---|---|
| `ATLAS_TOKEN` | `~/.local/state/atlas-core/secrets/compose/atlas-core.token` | existant, 0444 | Core, connecteur (service token) |
| `ATLAS_TOOL_TOKEN` | `.../atlas-tool.token` | existant, 0444 | Core |
| Gateway token | `.../openclaw-gateway.token` | existant, 0444 | Core → Gateway |
| Keyring assertion Relay | `~/.local/state/atlas-core/secrets/v7-12/relay-assertion-keys.json` | `10001:10001`, 0600 | Core |
| Cursor secret | `.../v7-12/cursor-secret` | `10001:10001`, 0600 | Core |
| Assertion connecteur | `~/.local/state/atlas-relay/v7-12/relay-assertion-secret` | `1000:1000`, 0600 | connecteur |
| Credential RELAY | `.../v7-12/relay-credential` | `1000:1000`, 0600 | connecteur |
| Principal map | `.../v7-12/principal-map.json` | `1000:1000`, 0600 | connecteur |
| Service token fichier | `.../v7-12/service-token` | `1000:1000`, 0600 | connecteur |

Aucun bearer, aucune clé HMAC et aucun mot de passe n'est écrit dans un argument de processus, un journal ou un fichier versionné. Le wrapper `doctor` vérifie présence, mode et propriétaire sans afficher les valeurs.

Le chiffrement au repos du stockage hôte est vérifié : `/home/nill` est sur ZFS `aes-256-gcm`, clé disponible. Le backup reste local, dans un répertoire 0700 sur ce même chiffrement ; aucun stockage cloud n'est ajouté.

## 7. Identité et binding

- Un seul appareil Relay actif : `pixel-v7`. Aucun réappairage, aucune régénération d'identité.
- Principal Core : type `ANDROID`, **nouveau** `device_ref` ; les consentements historiques ne sont pas transférés.
- Credential : audience `RELAY`, généré localement, stocké haché en base.
- Binding : `relay_device_id` réel de `pixel-v7` → `principal_id`.
- Capacités : exactement les opérations produit du Pixel (lecture, chat, proposition, écritures de synchronisation, ack, consentement). Une capability ne remplace jamais un consentement.
- Le connecteur utilise sa map privée `relay_device_id → {principal_id, credential_file}` et signe chaque requête V4 avec l'assertion HMAC.
- Le Core valide signature, fenêtre temporelle, key ID, nonce anti-rejeu, principal, `device_ref` et binding actif.
- `DIRECT` n'est pas provisionné. `LEGACY` reste transitoire en mode `dual` ; le passage en `principal` et la révocation du bearer partagé sont des étapes séparées, la révocation exigeant une confirmation explicite du propriétaire.
- `ACTIVITY_STATUS` reste `REVOKED` ; `DAY_PLAN` conserve son état vérifié. Toute nouvelle synchronisation après cutover devra refléter un consentement explicite donné dans l'application.

## 8. Tailscale

Inchangé pendant V7-12 :

- Tailscale reste sur l'hôte Linux, pas dans Docker.
- `Funnel 8443 → 127.0.0.1:4400` est conservé.
- DNS, Funnel, Serve et identité de machine ne sont pas modifiés.
- Le port 8770 reste hors périmètre.
- Le téléphone utilise le transport public sans Tailscale Android.

Le proxy conteneurisé publie `127.0.0.1:4400`, donc le Funnel continue de fonctionner sans changement.

## 9. Modes et gates

| Étape | Mode Core | Mode connecteur | Condition de sortie |
|---|---|---|---|
| Stack isolée | `dual` | `dual` + map synthétique | health, HMAC, rejeu, révocation, backup/restore PASS |
| Déploiement live initial | `dual` | `dual` + map réelle | cinq services healthy, Relay connecté, Funnel 200 |
| Cutover Pixel | `dual` | `dual` | preuve Pixel réelle : chat, sync, plan |
| Principal | `principal` | `principal` | refus LEGACY prouvé, Pixel toujours fonctionnel |
| Révocation LEGACY | `principal` | `principal` | confirmation explicite propriétaire |

Les gates détaillés et leur statut réel sont tenus dans `V7-12_TASK_REGISTRY.md` puis dans `V7-12_FINAL_REPORT.md`. Un test synthétique ne qualifie pas le Pixel ; un test Pixel ne qualifie pas les autres identités.

## 10. Limites assumées

- Le Pixel est absent au moment de l'ouverture : les gates `GO_PIXEL_*` restent `BLOCKED` tant qu'aucune preuve physique n'est produite.
- Le Core n'a pas d'accès Internet direct : les imports WGER ne sont pas disponibles dans cette topologie. Le plan local existant reste utilisable ; toute réactivation WGER demandera une décision et un chemin de sortie dédié.
- Le backup est local et chiffré au repos par ZFS ; aucune copie hors site n'est ajoutée.
- La révocation du bearer partagé LEGACY n'est pas incluse dans le déploiement automatique.
