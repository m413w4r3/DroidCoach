# ATLAS V6.3 R3-A — Transport relayé fondé sur Paseo

Statut : **contrat de vague 0, figé avant délégation**. Toute divergence doit être
inscrite dans `DECISIONS_ARCHITECTURE.md` et validée par le parent.

## 1. Provenance réellement utilisée

| Composant | Révision | Constat |
|---|---|---|
| Paseo | `902b0c7a1637cc7a6c2ee268dca8161965a35def` (0.13.1) | clone local non shallow ; le SHA `65ce4b5…` cité par la mission **n'existe pas** dans ce clone |
| Paseo Relay | `3fc41c96c8c63f3a7109e832899cc57d473c4531` | identique à la référence mission |
| Dépendance PC | `@getpaseo/relay/e2ee` construit depuis le clone | API publique uniquement |
| Licence | Apache-2.0 (Paseo, Paseo Relay) | attribution dans `THIRD_PARTY_NOTICES.md` |

Le clone n'est pas modifié dans ses sources. Seul `dist/` est produit localement.
Aucun code du daemon Paseo n'est réutilisé.

## 2. Topologie

```text
Android ATLAS (Kotlin)                        PC
┌──────────────────────┐          ┌──────────────────────────────┐
│ RelayBackendTransport│          │ atlas-relay-connector (Node) │
│  WebSocket client    │          │  WebSocket contrôle serveur  │
│  Noise NK initiateur │◄────────►│  Noise NK répondant          │
│  auth appareil       │  relais  │  auth appareil + ops        │
└──────────────────────┘  local   │  HTTP loopback → FastAPI     │
                                   └──────────────────────────────┘
        relais Paseo v2 (Elixir, loopback ou conteneur local)
```

Le relais ne voit que des trames chiffrées, `serverId`, `connectionId` et le rôle.
Il ne constitue **pas** une authentification. FastAPI reste sur `127.0.0.1`.

## 3. Protocole de relais Paseo v2

### 3.1 URL

- Client : `ws://<host>:<port>/ws?serverId=<id>&role=client&v=2`
  (le relais attribue `connectionId`).
- Contrôle PC : `ws://<host>:<port>/ws?serverId=<id>&role=server&v=2`
  (sans `connectionId`).
- Data PC par client : même URL avec `&connectionId=<id>`.

### 3.2 Cycle de vie

1. Le PC ouvre le socket de contrôle et envoie un `ping` JSON puis toutes les 10 s.
2. Le relais envoie `sync`/`connected` avec les `connectionId` clients.
3. Le PC ouvre un socket data par `connectionId` et y attache le canal Noise.
4. Le client Android envoie `e2ee_hello` sur son socket.
5. Le PC répond `e2ee_ready`, puis les deux extrémités échangent des records protégés.

Le client doit renvoyer `e2ee_hello` toutes les secondes tant qu'il n'a pas reçu
`e2ee_ready`. Un `ready` sans `protected` est refusé : **aucun fallback legacy**.

### 3.3 Handshake Noise

- Pattern : `Noise_NK_25519_ChaChaPoly_SHA256` (revision 34).
- Prologue ASCII : `paseo-relay-e2ee/1`.
- Message 1 : `-> e, es` ; payload = `[0x01][flags]`.
- Message 2 : `<- e, ee` ; payload = `[0x01][flags]`.
- `flags` bit 0 = `binaryCiphertext`. Bit inconnu = échec.
- Les messages voyagent en base64 standard dans :
  - `{"type":"e2ee_hello","key":"<X25519 32o base64>","capabilities":{"binaryCiphertext":true},"protected":{"v":1,"message":"<base64>"}}`
  - `{"type":"e2ee_ready","protected":{"v":1,"message":"<base64>"}}`
- `key` est une clé X25519 jetable distincte, uniquement pour la validation de
  forme par le relais. Elle n'est jamais utilisée par le mode protégé.
- Le client est initiateur ; le connecteur PC est répondant.
- Les deux extrémités imposent `minimumMode: "protected"`.

### 3.4 Records protégés

```text
plaintext de chunk = kind(1) || flags(1) || totalLength(u32 BE) || data
record filaire     = (u16 BE ciphertextLength || ciphertext)+
kind : 0x00 texte, 0x01 binaire
flags : bit0 FIRST, bit1 FINAL
MAX_CHUNK_DATA_BYTES = 65535 - 16 - 6 = 65513
nonce ChaChaPoly = 4 octets nuls || compteur u64 little-endian
```

Les records texte sont envoyés en **frames WebSocket texte base64** ; les records
binaires en frames binaires. ATLAS n'utilise que des records texte au niveau
applicatif. Tout record mal formé, dupliqué, réordonné, tronqué ou mal authentifié
ferme le canal. Un record ne contient qu'une frame applicative.

## 4. `atlas.remote.v1` — protocole applicatif

### 4.1 Règles générales

- JSON UTF-8, texte uniquement, un objet par record.
- `protocol` obligatoire et égal à `atlas.remote.v1`.
- Taille maximale d'une frame applicative : **65 536 octets**.
- Taille maximale d'une requête : **32 768 octets** ; réponse : **262 144 octets**.
- Aucun champ libre de type chemin, URL, commande shell, package à lancer.
- Toute opération est explicitement nommée et mappée côté PC.
- Les secrets ne sont jamais dans l'URL, un log ou un message d'erreur.

### 4.2 `hello` (client → PC, dans le canal Noise)

```json
{
  "protocol": "atlas.remote.v1",
  "kind": "hello",
  "device_id": "<uuid>",
  "client_nonce": "<16 octets base64>",
  "client_version": "0.1.0-r3a",
  "capabilities": ["plan.revision"],
  "auth": {"scheme": "device-token-v1", "token": "<base64url 32 octets>"},
  "timestamp": "2026-10-26T23:00:00Z"
}
```

Le PC vérifie : protocole, horodatage ±120 s, nonce non rejoué (cache 10 min),
`device_id` connu, token comparé en temps constant au SHA-256 enregistré,
appareil non révoqué, capability autorisée. En cas d'échec : `error` puis close
WebSocket `4001`.

### 4.3 `hello_ok` (PC → client)

```json
{
  "protocol": "atlas.remote.v1",
  "kind": "hello_ok",
  "session_id": "<uuid>",
  "server_time": "2026-10-26T23:00:01Z",
  "expires_in_seconds": 3600,
  "capabilities": ["plan.revision", "coach.status"],
  "max_request_bytes": 32768,
  "max_response_bytes": 262144
}
```

### 4.4 `request`

```json
{
  "protocol": "atlas.remote.v1",
  "kind": "request",
  "request_id": "<uuid>",
  "session_id": "<uuid>",
  "operation": "plan.revision.request",
  "idempotency_key": "<uuid>",
  "created_at": "2026-10-26T23:00:02Z",
  "expires_at": "2026-10-26T23:00:32Z",
  "payload": {}
}
```

Contraintes : `request_id` et `idempotency_key` canoniques, `expires_at` ≤
`created_at + 60 s`, payload conforme au schéma de l'opération. Une requête
expirée est refusée `EXPIRED` sans effet. Un `request_id` déjà vu avec un payload
différent est refusé `IDEMPOTENCY_CONFLICT`. Un rejeu strictement identique
retourne la réponse mémorisée avec `"replayed": true` (cache 10 min).

### 4.5 `response` / `error`

```json
{
  "protocol": "atlas.remote.v1",
  "kind": "response",
  "request_id": "<uuid>",
  "session_id": "<uuid>",
  "operation": "plan.revision.request",
  "status": "OK",
  "result": {},
  "completed_at": "2026-10-26T23:00:03Z",
  "replayed": false
}
```

```json
{
  "protocol": "atlas.remote.v1",
  "kind": "error",
  "request_id": "<uuid ou null>",
  "session_id": "<uuid ou null>",
  "error": {"code": "BACKEND_UNAVAILABLE", "message": "backend unavailable", "retryable": true},
  "completed_at": "2026-10-26T23:00:03Z"
}
```

Codes fermés : `INVALID_REQUEST`, `UNKNOWN_OPERATION`, `PAYLOAD_TOO_LARGE`,
`EXPIRED`, `AUTH_REQUIRED`, `AUTH_FAILED`, `DEVICE_REVOKED`, `RATE_LIMITED`,
`IDEMPOTENCY_CONFLICT`, `BACKEND_UNAVAILABLE`, `BACKEND_REJECTED`,
`OPERATION_UNAVAILABLE`, `CANCELLED`, `INTERNAL`.

### 4.6 `cancel`, `ping`, `pong`

- `cancel` : `{"kind":"cancel","request_id":…,"target_request_id":…}`.
  Annulation au mieux ; une requête déjà envoyée à FastAPI peut aboutir.
- `ping` / `pong` applicatifs toutes les 20 s d'inactivité. Le `ping` de contrôle
  du relais est distinct.

### 4.7 Opérations autorisées (liste fermée v1)

| Opération | Backend | Payload | Résultat |
|---|---|---|---|
| `backend.health` | `GET /health` | `{}` | `{"status":"ok"}` |
| `coach.status` | `GET /v2/agent/status` | `{}` | réponse backend bornée |
| `plan.today.read` | `GET /v1/plan/today` | `{}` | réponse backend bornée |
| `plan.revision.request` | `POST /v3/agent/plan-revision` | corps `PlanRevisionRequestV3` | proposition V3 |
| `plan.revision.result` | cache local du connecteur | `{"request_id":"<uuid>"}` | réponse mémorisée |
| `sport.plan.read` | `GET /v1/sport/plan` | `{"local_date":"YYYY-MM-DD"}` | projection sportive |
| `sync.status` | état local connecteur | `{}` | état backend/relais, sans donnée personnelle |

Toute autre valeur est refusée `UNKNOWN_OPERATION`. Il n'existe **aucune** route
générique de type `GET /<path>`, proxy HTTP ou forward loopback libre.

### 4.8 Authentification et cycle de vie

- Token d'appareil : 32 octets aléatoires, base64url, généré sur le PC lors de
  l'appairage, remis une seule fois hors bande. Le PC ne stocke que son SHA-256.
- L'appairage R3-A est explicite (`pair-device`) avec une identité synthétique ;
  un flux QR/code pourra être ajouté sans changer le protocole.
- Révocation : `revoke-device` marque l'appareil ; toute nouvelle session est
  refusée et la session active est fermée au message suivant (`4001`).
- Une session expire après 1 h ; le client refait un `hello` avec un nouveau nonce.
- Quotas : 60 requêtes/min/appareil, 4 requêtes concurrentes, 1 session active
  par appareil (la nouvelle remplace l'ancienne).
- Le connecteur ne conserve ni payload ni résultat au-delà de 10 min.

## 5. Frontières de modules

### 5.1 PC — `integrations/relay/pc/**`

- `src/main.ts` : CLI `start`, `pair-device`, `revoke-device`, `show-identity`.
- `src/identity.ts` : paire X25519 persistée 0600, empreinte publique.
- `src/deviceRegistry.ts` : registre JSON 0600, tokens hashés, révocation.
- `src/relayLink.ts` : sockets contrôle/data, reconnexion, routage.
- `src/session.ts` : `openDaemonSecureChannel`, auth applicative, quotas.
- `src/protocol.ts` : validation stricte des enveloppes.
- `src/operations.ts` : liste blanche, mapping FastAPI, cache d'idempotence.
- `src/backend.ts` : HTTP loopback avec Bearer de service.
- `test/**` : tests Node. Aucun accès shell/fichier exposé par le service.

Le connecteur n'importe **que** `@getpaseo/relay/e2ee`. Il n'importe ni le daemon,
ni `relay-transport.ts`, ni `daemon-client-relay-e2ee-transport.ts`.

### 5.2 Android — `android/app/src/main/java/dev/atlas/coach/sync/relay/**`

- `RelayConfig`, `RelayConnectionState`, `RelayCredentials`, `RelayCredentialStore`.
- `AtlasRemoteProtocol` : enveloppes, tailles, validation.
- `NoiseNkInitiator`, `NoiseCipherState`, `SymmetricState`, `ProtectedRecordCodec`.
- `PaseoRelayClient` : WebSocket (OkHttp), handshake, records, reconnexion.
- `RelayBackendTransport` : `execute(operation, payload, …)` avec files bornées.
- `RelayPlanRevisionClient` : adapte le transport à `PlanRevisionProposalClient`.
- Tests JVM : vecteurs Noise upstream, records malformés, protocole, reconnexion
  simulée, idempotence, expiration.
- Tests instrumentés : socket réel contre un relais local lorsque disponible.

Aucun écran métier partagé n'est modifié par ce ticket.

## 6. Recette locale

- Relais Paseo réel, lié à `127.0.0.1:4400` (conteneur local, image épinglée).
- FastAPI `127.0.0.1:8000`.
- Connecteur `127.0.0.1:4401` (métriques uniquement).
- Android `emulator-5554` joint le relais via `10.0.2.2:4400`.
- **Aucun `adb reverse`** pour le scénario applicatif principal.
- Identités, appareil, token et plan : strictement synthétiques.

Le test majeur exige la chaîne Android Kotlin → relais → connecteur Node →
FastAPI → réponse → Android. Un échange Node↔Node n'est pas une preuve.

## 7. Limites explicites

- Un test loopback/émulateur ne prouve pas une disponibilité Internet 24 h/24.
- Le listener du relais Paseo est `cowboy_clear` : un déploiement distant exige
  une terminaison TLS/WSS distincte, hors R3-A.
- Android ne garantit pas un WebSocket permanent en Doze ; la reconnexion à la
  reprise de l'application est la stratégie R3-A.
- Aucun push, aucune exposition publique, aucune modification du Gateway OpenClaw.
