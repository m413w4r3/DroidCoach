# V7-06D — Révocation A4-4 : refus serveur et observabilité Android

**Date :** 2026-11-01
**Base :** `9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`
**Audit :** `codex/gpt-6-luna`, effort effectif `xhigh`, worktree
`/tmp/atlas-v7-06d-a2-revocation-ro`, lecture seule (`collaborationMode=Plan`).
**Statut :** cause racine établie ; correctifs et tests détaillés ci-dessous.
**Sonde physique :** `NOT_RUN` — aucune autorisation ADB/Pixel nouvelle dans cette
campagne. Le verdict `GO_A4_4_CLIENT` n'est donc **pas** accordé.

## 1. Symptôme V7-06C

Après révocation du device QA dans le registre du connecteur, la reconnexion est
refusée côté serveur (aucune nouvelle session, `errors_total=1`) mais l'app
observe `state=ERROR`, `close_code=null` ; la sonde instrumentée
`V7_06C_RevokedDeviceReconnectInstrumentedTest` échoue
(`explicit_refusal_not_observed`). Le refus serveur était donc prouvé, sa
restitution Android ne l'était pas.

## 2. Cause racine

### 2.1 Trame `DEVICE_REVOKED` pré-authentification émise en V1

Dans `PeerSession.receive`, la branche pré-authentification d'un appareil révoqué
appelle :

```ts
await this.sendError(remoteError, currentRequestId);   // session.ts:245
```

sans transmettre `incomingProtocol`. `sendError` choisit alors
`this.auth?.protocol ?? protocol ?? PROTOCOL` ; `auth` est nul et `PROTOCOL`
vaut `atlas.remote.v1` (`integrations/relay/pc/src/protocol.ts:3`). Un client
Android V4/V2, qui a envoyé un hello `atlas.remote.v2`, reçoit donc une enveloppe
d'erreur V1.

Le parseur Android est strict : `AtlasRemoteProtocol.parse(bytes, expectedProtocol)`
exige l'égalité exacte du champ `protocol` (`AtlasRemoteProtocol.kt:62-93`). La
trame est rejetée, `RelayBackendTransport.failProtocolLocked()` passe l'état à
`ERROR` et appelle `client.abort(4003)` (`RelayBackendTransport.kt:286-298`,
`:694-700`). `PaseoRelayClient.abort()` incrémente la génération et empêche le
`onClosed` ultérieur d'alimenter `lastCloseCode` (`PaseoRelayClient.kt:81-94`,
`:168-171`). D'où exactement `state=ERROR`, `close_code=null`.

### 2.2 Le relais ne transmet pas 4001

Le connecteur ferme son canal Noise avec 4001 après la trame d'erreur. Le relais
Paseo, lorsqu'il détache le socket data du connecteur (role `server`), ferme les
clients appairés avec **1012 « Server disconnected »**, pas avec le code du
connecteur (`references/repos/paseo-relay/lib/paseo_relay/ownership.ex:393-403`).
Le proxy local de test préserve 4001, mais le relais déployé ne le fait pas. La
seule preuve de révocation authentifiée qui atteint Android est donc la trame
applicative `DEVICE_REVOKED`, protégée par Noise.

### 2.3 Le close 1012 écrase `REVOKED`

Après correction du protocole, Android parse la trame et passe en `REVOKED`.
Le close 1012 déclenche ensuite une reconnexion/`OFFLINE`
(`PaseoRelayClient.kt:343-363`) et `RelayBackendTransport.onClientState()`
remplace `REVOKED` par `OFFLINE` (`RelayBackendTransport.kt:702-727`). L'état
terminal doit être rendu collant côté présentation, sans transformer une panne
réseau en révocation.

### 2.4 Ordre identité / token

`SessionManager.authenticate()` vérifie `record.revoked_at` **avant** de comparer
le hash du token (`session.ts:80-85`). Un pair qui peut établir le canal Noise et
connaît un `device_id` peut donc distinguer « révoqué » de « mauvais token » sans
prouver le token. Ce n'est pas un contournement d'autorisation (aucune session
n'est ouverte), mais c'est une divulgation d'état contraire à l'exigence de la
mission.

## 3. Matrice des refus

« Via relais » = connexion data Relay v2 appairée normale. Les cases `UNKNOWN`
sont explicitement non prouvées par le code.

| Cas | Événement serveur / transport | Événement Android réel | État Android actuel | Distinguable d'une panne réseau ? |
|---|---|---|---|---|
| **Révoqué, token valide, client V2** | Trame Noise `DEVICE_REVOKED` (V1 par bug), puis close connecteur 4001 → relay 1012 | Trame rejetée par le parseur V2 ; `abort(4003)` local ; `lastCloseCode=null` | `ERROR`, `close_code=null` | **Non** (corrigé) |
| **Mauvais token, appareil connu** | Trame V2 `AUTH_FAILED`, close 4001 → relay 1012 | Trame parsée → `AUTH_REQUIRED`, puis 1012 | `AUTH_REQUIRED` puis `OFFLINE` | Oui par l'échec typé ; l'état final peut être `OFFLINE` |
| **Mauvais token, appareil révoqué** | Aujourd'hui `DEVICE_REVOKED` avant le token, V1 | Trame rejetée en V2 ; lisible en V1 | `ERROR` en V2 ; **fuite de statut** en V1 | **Non** (corrigé : `AUTH_FAILED`) |
| **Appareil inconnu** | `AUTH_FAILED` V2, close 4001 → 1012 | Trame parsée → `AUTH_REQUIRED`, puis 1012 | `AUTH_REQUIRED` puis `OFFLINE` | Oui par l'échec typé |
| **Refus Noise** | Close 4002/autre, aucune trame applicative | `onFailure` ou close 1012 | `ERROR`/`OFFLINE` | Non ; ne doit jamais devenir `REVOKED` |
| **Erreur TLS** | Échec avant WebSocket | `onFailure` TLS | `ERROR` typé | Oui par le type, pas `REVOKED` |
| **Coupure réseau** | Aucun frame de fermeture propre | `onFailure`/1012 | `OFFLINE`/`ERROR` | Non ; ne doit jamais devenir `REVOKED` |
| **Close relay 1012** | `1012 Server disconnected` | `onClosed(1012)` | `OFFLINE` + reconnexion | Non ; ne doit jamais devenir `REVOKED` |
| **Close WebSocket 4001 direct** | 4001 (proxy local uniquement) | `onClosed(4001)` | `AUTH_REQUIRED`, préservé en `REVOKED` si déjà révoqué | Oui, mais **absent du relais déployé** |
| **Erreur de protocole** | Trame non conforme | Parse failure | `ERROR` | Non ; ne doit jamais devenir `REVOKED` |

## 4. Correctifs

### 4.1 Connecteur

1. La branche pré-auth `DEVICE_REVOKED` transmet `incomingProtocol` à
   `sendError`, afin que l'enveloppe porte le protocole du hello (V1→V1,
   V2→V2). Le close 4001 et la grâce de 200 ms sont conservés.
2. `authenticate()` compare le token avant `revoked_at`. Sémantique :
   - token correct + révoqué → `DEVICE_REVOKED` ;
   - token incorrect + révoqué → `AUTH_FAILED` (aucune divulgation) ;
   - appareil inconnu → `AUTH_FAILED` ;
   - token incorrect + non révoqué → `AUTH_FAILED`.
   La protection anti-rejeu du nonce reste après ces contrôles.
3. Aucun changement du protocole du relais ni de son image épinglée.

### 4.2 Android

1. `RelaySettingsController` applique une politique d'état terminale : après un
   `REVOKED` issu d'une trame `DEVICE_REVOKED` authentifiée, les états
   `OFFLINE`/`ERROR`/`AUTH_REQUIRED` ultérieurs (dont le close 1012 et les
   reconnexions) ne remplacent pas `REVOKED`. Une nouvelle connexion explicite
   (`connect()`, `startTransport`) ou une déconnexion/reconfiguration explicite
   réinitialise.
2. `lastFailureKind` est exposé et préservé contre les échecs non-REVOKED
   ultérieurs ; la sonde peut ainsi exiger `(REVOKED, REVOKED)` ou
   `(AUTH_REQUIRED, AUTHENTICATION)`.
3. La sonde instrumentée est **renforcée** (pas affaiblie) : elle n'accepte plus
   un simple état sans type d'échec ; `ERROR`/`close_code=null` reste un échec.
4. Aucune trame réseau générique n'est convertie en `REVOKED` : `REVOKED`
   n'est atteint que par le code `DEVICE_REVOKED` d'une trame applicative
   protégée et parsée sous le protocole négocié.

## 5. Preuves et tests

| Oracle | Résultat |
|---|---|
| Test rouge connecteur : V2 hello révoqué → enveloppe V1 | **PASS (rouge reproduit)** — sur `686d437`/BASE, le parent a observé `protocol: atlas.remote.v1` au lieu de `atlas.remote.v2` |
| Test vert connecteur : enveloppe V2 `DEVICE_REVOKED`, close 4001 | **PASS** — `v7-06d-revocation.test.ts` |
| Test mauvais token + révoqué → `AUTH_FAILED`, jamais `DEVICE_REVOKED` | **PASS** — test dédié |
| Test token correct + révoqué → `DEVICE_REVOKED` | **PASS** — V1 et V2 |
| Tests Node complets | **PASS** — parent a rejoué `npm run test:unit` : 18 fichiers, **114/114** |
| Tests JVM politique d'état / type d'échec | **PASS** — inclus dans la suite intégrée **909/909** |
| Test JVM enveloppe V1 sur transport V2 rejetée (pas de downgrade) | **PASS** — `RevocationEnvelopeProtocolTest` dans la suite intégrée |
| Compilation `assembleQaAndroidTest` (sonde renforcée) | **PASS** — build QA final `cf82cd0`, sonde compilée |
| Sonde instrumentée stricte sur Pixel/AVD | **NOT_RUN** — pas d'autorisation |

## 6. Intégration et verdict

- Connecteur : `686d437` (rouge), `b822192` (fix), intégrés `c6de88f`, `d3619d4`.
- Android : `207bea2` (fix), intégré `c1bb1d1`.
- Node : **114/114 PASS** ; JVM intégrée : **909/909 PASS** ; QA AndroidTest
  compilé.
- **`GO_REVOKED_DEVICE_SERVER` = GO** : le refus serveur reste effectif et testé.
- **`GO_A4_4_CLIENT` = NO GO — NOT_RUN`** : la sonde stricte renforcée n'a pas
  été exécutée sur appareil. Les tests déterministes prouvent la correction du
  protocole et de l'état, pas le PASS instrumenté exigé.

## 7. Limites

- Le relais déployé transforme le close 4001 en 1012. La preuve A4-4 repose sur
  la trame applicative authentifiée, pas sur le code de fermeture. Aucune
  modification du relais n'est faite.
- La sonde physique n'a pas été exécutée ; `GO_A4_4_CLIENT` reste **NO GO /
  NOT_RUN** malgré les tests déterministes.
- Le refus serveur des appareils révoqués reste effectif ; `GO_REVOKED_DEVICE_SERVER`
  peut être maintenu sur la base des tests connecteur et des preuves V7-06C.
