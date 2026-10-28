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
| [`preuves/pc-01-validation.txt`](preuves/pc-01-validation.txt) | Résilience connecteur, SIGTERM/SIGKILL, FastAPI synthétique, rejeu incertain, rollback | `0a96281`, Node 24, relais 4402 | PASS (dynamique) ; FastAPI réel rejoué en `fastapi-restart-public.txt` |
| [`preuves/chat-01.txt`](preuves/chat-01.txt) | Contrat chat, capacité, route fixe, limites, idempotence, timeout OpenClaw, erreurs | `2548673` / `9f5c4ea`, backend 196/1, chat 12/12 | PASS (mock) |
| [`preuves/openclaw-public.txt`](preuves/openclaw-public.txt) | Chat OpenClaw personnel réel via Funnel public, latences mesurées | `2548673`, PC Linux, FastAPI 8100/OpenClaw, IP publique | PASS (`PERSONAL_OPENCLAW_PASS`) |
| [`preuves/chat-wire-01-android.txt`](preuves/chat-wire-01-android.txt) | Intégration Android relayée, JVM, timeout 55 s, audit/verrou | `a437b17` / `c5e1bea`, 688 tests JVM | PASS (JVM) + Pixel dans `pixel-instrumentation-final.txt` |
| [`preuves/android-01-implementation.txt`](preuves/android-01-implementation.txt) | Implémentation A3/A4 et corrections de tests | `c916ab8` / `7be0599` | PASS (implémentation) |
| [`preuves/pixel-install-r3b.txt`](preuves/pixel-install-r3b.txt) | Installation APK R3-B, sauvegarde, SHA | Pixel, APK `b55776a9…` | PASS |
| [`preuves/pixel-instrumentation-final.txt`](preuves/pixel-instrumentation-final.txt) | A3, A4, chat relayé, réseau, résultats Pixel | Pixel API 37, APK `b55776a9…` (avant correctif P1) | PASS (ciblé) ; re-test de l'APK final `1a877422…` bloqué par l'ingress public |
| [`preuves/pixel-network.txt`](preuves/pixel-network.txt) | Coupure/reprise, handover LTE/5G, redémarrage connecteur | Pixel API 37, Funnel, code `dc8f55e` | PASS sauf NET-07 PARTIAL |
| [`preuves/pixel-restore-final.txt`](preuves/pixel-restore-final.txt) | Restauration finale des données, 5 hashes, calendrier | Pixel API 37, sauvegarde `fbca987a…` | PASS |
| [`preuves/fastapi-restart-public.txt`](preuves/fastapi-restart-public.txt) | Arrêt/relance FastAPI réel via Funnel | `dfe6da3` | PASS (`BACKEND_UNAVAILABLE` puis `ok`) |

## Vérifications finales sur le code intégré `dc8f55e`

| Vérification | Commande | Résultat |
|---|---|---|
| Backend | `backend/.venv/bin/python -m pytest -q` | 196 passed, 1 skipped, 1 warning |
| Node connecteur | `integrations/relay/pc: npm test` (proxy 4400, relais 4402) | 15 fichiers, 77 tests, 0 échec (`91e7aa6`) |
| Proxy filtrant | `node --test integrations/relay/proxy/test` | 18/18 |
| JVM Android | `./gradlew :app:testDebugUnitTest --rerun-tasks --max-workers=1` | 97 suites, 691 tests, 0 échec (`91e7aa6`) |
| Lint | `./gradlew :app:lintDebug` | 0 erreur, 71 avertissements |
| Builds | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` | APK final `20672e07…`, androidTest `5aef1321…` ; APK installé `1a877422…` ; APK Pixel testé `b55776a9…` |
| Proxy/relais redémarrés | arrêt/relance proxy, `docker restart atlas-relay-r3b-probe` | `/health` 200, `/ready` 200, `/metrics` 404 en local et via MagicDNS |

## Preuves publiques complémentaires (PC)

Qualification publique initiale, sur `dfe6da3`, avec le connecteur personnel
connecté au Funnel public, FastAPI mock sur `127.0.0.1:8100`, proxy sur `4400`
et relais sur `4402` :

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
- `coach_chat_relayed` : PASS (`agent_status=mock`, propositions)
- `coach_chat_replay_no_duplicate` : PASS (`replayed=true`)
- `coach_chat_idempotency_conflict` : PASS (`IDEMPOTENCY_CONFLICT`)
- `coach_chat_payload_limit` : PASS (`INVALID_REQUEST`)

Test de capacité négative avec un appareil appairé sans `coach.chat` :
`chat_requires_capability` : PASS (`OPERATION_UNAVAILABLE`).

### Rejeu final via MagicDNS (2026-10-28 15:16)

Le recontrôle par l'IP publique a échoué au TLS : `tailscaled` ne joignait plus
le plan de contrôle Tailscale (`network is unreachable`), sans changement de
configuration. Le même E2E a été rejoué via le nom Funnel résolu par MagicDNS
(`100.119.62.63:8443`), sur l'arbre final, avec l'appareil synthétique device6 :

- `noise_protected_and_device_auth` : PASS
- `backend_health_via_funnel` : PASS
- `sync_status_via_funnel` : PASS
- `coach_chat_relayed` : PASS (`agent_status=connected`, 1 proposition)
- `coach_chat_replay_no_duplicate` : PASS (`replayed=true`)
- `coach_chat_idempotency_conflict` : PASS (`IDEMPOTENCY_CONFLICT`)
- `coach_chat_payload_limit` : PASS (`INVALID_REQUEST`)

Limite : ce rejeu prouve le chemin Funnel/TLS/Noise/relais/connecteur via
MagicDNS, pas l'ingress public Internet, indisponible au moment du rejeu à
cause de la connectivité Tailscale du PC (R-18).

## OpenClaw personnel isolé

Instance FastAPI isolée sur `127.0.0.1:8102`, adaptateur `openclaw`, jeton
existant `~/.config/atlas/openclaw-gateway-token`, aucune modification de la
configuration OpenClaw. Requête synthétique :
- `/v2/agent/status` : 200, adaptateur `openclaw`, breaker `closed`
- `/v2/agent/chat` : 200, `agent_status=connected`, réponse de 120 caractères,
  1 proposition.
Statut : **PERSONAL_OPENCLAW_PASS**, confirmé via le chemin relayé public dans
[`preuves/openclaw-public.txt`](preuves/openclaw-public.txt) et par le rejeu
final `agent_status=connected`.

## Revue indépendante

Voir [`REVUE_INDEPENDANTE.md`](REVUE_INDEPENDANTE.md). La revue 1
(`codex/gpt-6-luna`, `xhigh`, SHA `beb8f33`) a rendu **BLOQUÉ** sur un P1 de
consentement périmé, sans P0. Le correctif `7754005` ajoute la revalidation du
snapshot au dispatch, la grâce de révocation sur le chemin `receive()`,
l'intersection des capacités pour les anciens appairages, l'assertion du close
4001 et la suppression de l'alias Keystore. Tests : Node 76/76, JVM 690/690.
Le re-test Pixel de l'APK final est bloqué par la panne d'ingress public ; la
re-revue du SHA final est en cours.

## Corrections parentales documentées

| Commit | Nature | Vérification |
|---|---|---|
| `dc8f55e` | Délai borné de 200 ms avant fermeture 4001, pour laisser le relais router `DEVICE_REVOKED` | A4 Pixel PASS ; Node 74/74 ; code revu |
| `f266416`, `937b95a`, `790b476` | Corrections test-only : sélecteurs Compose, historique long, état `RELAY_CONNECTING` pendant l'arrêt du connecteur | Tests Pixel rejoués ; APK principal inchangé |
| `7754005` | Consentement revalidé au dispatch, grâce sur `receive()`, intersection de capacités, close 4001/alias Keystore | Node 76/76 ; JVM 690/690 ; lint 0 erreur ; Pixel final bloqué par l'ingress |
| `91e7aa6` | Verrou de consentement tenu jusqu'à l'écriture WebSocket, annulation des opérations en vol, test d'ancien appairage renforcé | Node 77/77 ; JVM 691/691 ; lint 0 erreur ; Pixel final non installé/testé (appareil déconnecté) |

## Règles

- Une preuve loopback ne vaut pas preuve Funnel public.
- Une preuve PC ne vaut pas preuve Android/LTE/5G.
- Une preuve mock ne vaut pas preuve OpenClaw réel.
- Une notification d'agent ne vaut pas acceptation : le parent rejoue les
  oracles sur le SHA intégré.
