# ATLAS V6.3 — R3-B — Index des preuves

Chaque preuve est liée à un SHA, un environnement, une commande et une limite.
Aucun token, clé privée, message personnel ou donnée intime n'est versionné.

## Preuves versionnées

| Fichier | Sujet | SHA / environnement | Statut |
|---|---|---|---|
| [`preuves/pixel-preflight.txt`](preuves/pixel-preflight.txt) | Pré-vol Pixel, sauvegarde app-only, hashes ATLAS, compteurs calendrier | Pixel 9 Pro XL, Android 17 / API 37, base `5795d37` | PASS |
| [`preuves/funnel-precheck.txt`](preuves/funnel-precheck.txt) | État Funnel avant proxy | `5795d37`, Tailscale 1.104.4 | PARTIAL — TLS non qualifié avant listener 4400 |
| [`preuves/funnel-public.txt`](preuves/funnel-public.txt) | DNS/TLS public, HTTP `/health` `/ready`, refus `/metrics`, WSS 101, refus v=1/query, Noise protégé via Funnel | `dfe6da3`, PC Linux, IP publique 176.58.90.46 | PASS (PC) |
| [`preuves/net-01.txt`](preuves/net-01.txt) | Proxy filtrant, 18 tests, intégration relais réel, limits, logs, arrêt | `4363d8f`, Node 24 | PASS |
| [`preuves/pc-01-validation.txt`](preuves/pc-01-validation.txt) | Résilience connecteur, SIGTERM/SIGKILL, FastAPI synthétique, rejeu incertain, rollback | `0a96281`, Node 24, relais 4402 | PASS (dynamique) ; FastAPI réel NOT_RUN |
| [`preuves/chat-01.txt`](preuves/chat-01.txt) | Contrat chat, capacité, route fixe, limites, idempotence, erreurs | `1b2e8f4`, backend 196/1, Node 71/71 | PASS (mock) |
| [`preuves/openclaw-public.txt`](preuves/openclaw-public.txt) | Chat OpenClaw personnel réel via Funnel public, latences mesurées | `2548673`, PC Linux, FastAPI 8100/OpenClaw, IP publique | PASS (`PERSONAL_OPENCLAW_PASS`) |
| [`preuves/chat-wire-01*.txt`](preuves/) | Intégration Android relayée | à produire par `R3B-CHAT-WIRE-01` | NOT_RUN |
| [`preuves/android-01*.txt`](preuves/) | A3 Keystore / A4 révocation | à produire par `R3B-ANDROID-01` | NOT_RUN |

## Preuves publiques complémentaires (PC)

Commande exécutée depuis `integrations/relay/pc` sur `dfe6da3`, avec le
connecteur personnel connecté au Funnel public, FastAPI mock sur
`127.0.0.1:8100`, proxy sur `4400` et relais sur `4402` :

```sh
ATLAS_E2E_SERVER_ID=atlas-pc-<empreinte> \
ATLAS_E2E_PUBLIC_KEY=<clé publique X25519> \
ATLAS_E2E_DEVICE_ID=<uuid synthétique> \
ATLAS_E2E_TOKEN_FILE=<fichier 0600 synthétique> \
ATLAS_E2E_PUBLIC_IP=176.58.90.46 \
node scripts/r3b-public-e2e.mjs
```

Résultats observés :
- `noise_protected_and_device_auth` : PASS
- `backend_health_via_funnel` : PASS (`status=ok`)
- `sync_status_via_funnel` : PASS (`relay_connected=true`, `backend_configured=true`)
- `coach_chat_relayed` : PASS (`agent_status=mock`, 2 propositions)
- `coach_chat_replay_no_duplicate` : PASS (`replayed=true`)
- `coach_chat_idempotency_conflict` : PASS (`IDEMPOTENCY_CONFLICT`)
- `coach_chat_payload_limit` : PASS (`INVALID_REQUEST`)

Test de capacité négative avec un appareil appairé sans `coach.chat` :
`chat_requires_capability` : PASS (`OPERATION_UNAVAILABLE`).

## OpenClaw personnel isolé

Instance FastAPI isolée sur `127.0.0.1:8102`, adaptateur `openclaw`, jeton
existant `~/.config/atlas/openclaw-gateway-token`, aucune modification de la
configuration OpenClaw. Requête synthétique :
- `/v2/agent/status` : 200, adaptateur `openclaw`, breaker `closed`
- `/v2/agent/chat` : 200, `agent_status=connected`, réponse de 120 caractères,
  1 proposition.
Statut : **PERSONAL_OPENCLAW_PASS**, confirmé via le chemin relayé public dans
[`preuves/openclaw-public.txt`](preuves/openclaw-public.txt) après la correction
du timeout backend chat.

## Règles

- Une preuve loopback ne vaut pas preuve Funnel public.
- Une preuve PC ne vaut pas preuve Android/LTE/5G.
- Une preuve mock ne vaut pas preuve OpenClaw réel.
- Une notification d'agent ne vaut pas acceptation : le parent rejoue les
  oracles sur le SHA intégré.
