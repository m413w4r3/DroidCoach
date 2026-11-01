# ATLAS V7-06D — Diagnostic des deux P1 et du P2 de provenance

**Date :** 2026-11-01
**BASE_SHA :** `9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`
**Branche :** `feat/atlas-v7-06d-remote-hardening`
**Statut :** diagnostic établi par trois audits indépendants en lecture seule
(`codex/gpt-6-luna`, effort effectif `xhigh`, `collaborationMode=Plan`), puis
vérifié par le parent sur le code source. Aucun appareil, service live, conteneur,
réseau ou test n'a été utilisé pour ce diagnostic.

## 1. Objet

V7-06C a clôturé `ACCEPTED_WITH_LIMITATIONS` avec deux P1 :

1. **Fiabilité chat** : un run public APPLY a persisté le message USER puis
   n'a émis aucune requête `v4.coach.chat.request` vers le connecteur ; relance
   PASS sur stack neuve, cause racine historique `UNKNOWN`.
2. **Observabilité A4-4** : le connecteur refuse bien un appareil révoqué, mais
   Android affichait `state=ERROR`, `close_code=null` au lieu d'un refus
   explicitement observable ; la sonde instrumentée était `FAIL`.

Le P2 de traçabilité APK↔SHA consistait à relier cryptographiquement la source
compilée, les APK produits/installés et les preuves d'exécution.

## 2. Audit A1 — concurrence Android (P1 chat)

### 2.1 Graphe des verrous

Trois moniteurs dominent le chemin d'envoi :

- **G** : `AgentConsentGate` (`ReentrantLock`), partagé par les mutations de
  consentement/révocation, `RevocationBarrier` et le transport.
- **T** : `RelayBackendTransport.lock`, protège file, in-flight, génération,
  session et état.
- **C** : `PaseoRelayClient.lock`, protège le socket, le handshake Noise, les
  cipher states et l'état de connexion.

Chemins observés :

- `RelayBackendTransport.pump()` acquiert **G**, puis **T** dans
  `pumpUnderConsentGate()`. Sous **T**, il exécute `beforeDispatch()` (qui
  réacquiert **G** de façon réentrante puis le moniteur du coordinateur), construit
  le payload et marque la requête `sent`. **T** est relâché avant l'écriture, mais
  **G** reste tenu pendant `client.sendTextFrame()`, qui acquiert **C**
  (`RelayBackendTransport.kt:550-648`).
- `PaseoRelayClient` invoque ses listeners alors que **C** est tenu :
  `onApplicationFrame` (`PaseoRelayClient.kt:255`), `onSecureChannelReady`
  (`:302`), `onProtocolFailure`/`onRelayFailure` (`:339-340`),
  `onSocketClosed` (`:349`), `onStateChanged` (`:418`).
- Les callbacks de réponse (`handleResponse`, `handleHelloOk`, `handleError`)
  acquièrent **T**, puis appellent `pump()` — qui attend **G** — avant que le
  callback OkHttp ne relâche **C**.

### 2.2 Cycle complet

**FACT — cycle inter-threads atteignable :**

1. Thread B (`atlas-sync-worker`) entre dans `pump()`, tient **G**, dispatche la
   requête sous **T**, relâche **T**, puis attend **C** dans `sendTextFrame()`.
2. Thread A (callback OkHttp) tient **C**, traite une réponse, relâche **T**
   puis appelle `pump()` et attend **G**.
3. A attend G, B attend C : aucun ne progresse. B n'atteint jamais l'écriture
   socket, donc le connecteur ne reçoit aucune requête chat.

Ce cycle est prouvé par le graphe de verrous et les sites d'acquisition, pas
seulement par un ordre local. Il n'est **pas** prouvé comme cause du run
historique V7-06C : aucun thread dump n'a été capturé et la relance a PASS. La
cause historique reste **UNKNOWN** ; le correctif est justifié comme suppression
d'un deadlock atteignable, pas comme attribution rétroactive certaine.

### 2.3 Pourquoi le timeout ne protège pas

`RelayBackendTransport.executeDeferred()` calcule la deadline puis appelle
`pump()` **avant** `pending.done.await()` (`RelayBackendTransport.kt:200-202`).
Si `pump()` bloque sur **G**, **T**, **C** ou un callback, l'attente bornée de
55 s n'est jamais atteinte. `Future.cancel(true)` ne peut pas interrompre
`ReentrantLock.lock()` ni l'acquisition d'un moniteur Java. C'est cohérent avec
un run coupé à 540 s.

### 2.4 Autres hypothèses classées (non corrigées dans ce seul ticket)

1. Assemblage synchrone du contexte après persistance USER et avant soumission
   au worker (`AtlasController.kt:2216`, `:2253`) : une lecture de domaine lente
   ou bloquée peut laisser le message persisté sans requête.
2. Famine du worker unique ou attente de l'audit de divulgation (jusqu'à 20 s,
   borné).
3. Révocation de catégorie : `beforeDispatch` refuse, mais le garde de
   génération peut masquer la complétion et laisser l'UI en `SENDING`
   (`AtlasController.kt:1757`, `:2291`, `:2902`).
4. Transition de session : requête en file sans `hello_ok` courant ; elle doit
   normalement expirer une fois `pump()` revenu.
5. Acceptation socket locale sans réception connecteur : `SOCKET_SEND_ACCEPTED`
   ne prouve pas la réception aval.

## 3. Audit A2 — refus et fermeture de session (A4-4)

### 3.1 Cause du `ERROR` / `close_code=null`

**FACT — bug de protocole côté connecteur :** dans la branche de refus
pré-authentification d'un appareil révoqué, `session.ts` appelle
`sendError(remoteError, currentRequestId)` **sans** transmettre
`incomingProtocol` (`integrations/relay/pc/src/session.ts:241-246`). `sendError`
retombe alors sur `PROTOCOL` = `atlas.remote.v1`
(`:389-395`, `protocol.ts:3`). Un client V4/V2 a envoyé un hello
`atlas.remote.v2` ; Android parse la trame avec `expectedProtocol` V2
(`AtlasRemoteProtocol.kt:62-93`), constate le mismatch, lève
`RelayProtocolException`, passe par `failProtocol` → `failProtocolLocked` →
`ERROR` + `client.abort(4003)` (`RelayBackendTransport.kt:286-298`, `:694-700`).
`abort` invalide la génération et empêche `onClosed` d'alimenter
`lastCloseCode`, d'où `close_code=null` (`PaseoRelayClient.kt:81-94`, `:168-171`).
Ce chemin explique exactement l'observation V7-06C.

**FACT — le relais ne transmet pas 4001 :** quand le socket data du connecteur
(role `server`) se détache, le relais Paseo ferme les clients appairés avec
`1012 "Server disconnected"`, pas avec le code 4001 du connecteur
(`references/repos/paseo-relay/lib/paseo_relay/ownership.ex:393-403`). Le proxy
local de test, lui, préserve 4001 ; le relais déployé ne le fait pas. La seule
preuve authentifiée de révocation qui atteint Android est donc la trame
applicative `DEVICE_REVOKED`, chiffrée par Noise.

**FACT — seconde cause après correction du protocole :** même si Android parse
correctement la trame `DEVICE_REVOKED` et passe en `REVOKED`, le close 1012
ultérieur est mappé par `PaseoRelayClient.connectionClosed()` vers une
reconnexion/`OFFLINE`, et `RelayBackendTransport.onClientState()` remplace
`REVOKED` par `OFFLINE` (`PaseoRelayClient.kt:343-363`,
`RelayBackendTransport.kt:702-727`). L'état terminal doit donc être rendu
**collant** côté présentation, sans transformer une panne réseau en révocation.

### 3.2 Ordre identité / token

**FACT :** `SessionManager.authenticate()` vérifie `record.revoked_at` **avant**
de comparer le hash du token (`session.ts:80-85`). Un pair qui peut établir le
canal Noise et connaît un `device_id` peut donc distinguer « révoqué » de
« mauvais token » sans prouver le token. Ce n'est pas un contournement
d'autorisation (aucune session n'est ouverte), mais c'est une divulgation
d'état contraire à l'exigence de la mission. Le contrôle token doit précéder le
contrôle de révocation ; un appareil révoqué avec le bon token reste
`DEVICE_REVOKED`, un mauvais token devient `AUTH_FAILED`.

### 3.3 Matrice des refus (synthèse)

| Cas | Événement serveur | Événement client réel | État Android actuel | Distinguable d'une panne réseau ? |
|---|---|---|---|---|
| Révoqué, token valide, client V2 | Trame Noise `DEVICE_REVOKED` (protocol V1 par bug), puis close connecteur 4001 | Trame rejetée par le parseur V2 ; close relay 1012 | `ERROR`, `close_code=null` | Non (à corriger) |
| Mauvais token, appareil connu | Trame V2 `AUTH_FAILED`, close 4001 | Trame parsée, puis 1012 | `AUTH_REQUIRED` puis `OFFLINE` | Oui par l'échec typé, l'état final peut être `OFFLINE` |
| Mauvais token, appareil révoqué | Aujourd'hui `DEVICE_REVOKED` avant le token, V1 | Trame rejetée V2 / lisible en V1 | `ERROR` (V2) ; fuite de statut en V1 | Non (à corriger : `AUTH_FAILED`) |
| Appareil inconnu | `AUTH_FAILED` V2 | Trame parsée, puis 1012 | `AUTH_REQUIRED` puis `OFFLINE` | Oui par l'échec typé |
| Refus Noise | Close 4002/autre, aucune trame applicative | `onFailure`/close 1012 | `ERROR`/`OFFLINE` | Non, et ne doit pas devenir `REVOKED` |
| Erreur TLS | Échec avant WebSocket | `onFailure` TLS | `ERROR` typé | Oui par le type, pas `REVOKED` |
| Coupure réseau | Aucun frame de fermeture propre | `onFailure`/1012 | `OFFLINE`/`ERROR` | Non, et ne doit pas devenir `REVOKED` |
| Close relay 1012 | `1012 Server disconnected` | `onClosed(1012)` | `OFFLINE` (reconnexion) | Non, ne doit pas devenir `REVOKED` |
| Close WebSocket 4001 direct | 4001 (proxy local uniquement) | `onClosed(4001)` | `AUTH_REQUIRED`, préservé en `REVOKED` si déjà révoqué | Oui, mais absent du relais déployé |
| Erreur de protocole | Trame non conforme | Parse failure | `ERROR` | Non, et ne doit pas devenir `REVOKED` |

## 4. Audit A3 — fiabilité du harnais et provenance

### 4.1 Durabilité et corrélation

- Le message USER est persisté **avant** toute soumission distante
  (`AtlasController.kt:2213-2221`) ; une écriture durable en échec empêche
  l'envoi.
- L'identité de requête (`request_id` / clé d'idempotence) n'est **pas**
  persistée avec la conversation ; un nouvel envoi manuel génère une nouvelle
  identité. Il n'existe pas de reprise automatique : une réponse perdue reste
  `RESULT_UNCERTAIN` et n'est pas rejouée silencieusement. Le risque résiduel
  est qu'un renvoi manuel du même texte crée un nouveau tour de modèle et
  éventuellement une seconde proposition. Ce point est documenté comme limite,
  pas comme correction de cette campagne.
- Le chemin V4 corrèle correctement le `request_id` du corps, la clé
  d'idempotence de l'enveloppe et la réponse. Le chemin HTTP direct (hors
  incident relayé) ignore la clé d'idempotence ; c'est un P2 séparé.
- Les délais sont bornés côté transport (55 s chat, TTL 60 s), mais pas
  l'attente pré-dispatch de **G** (voir A1 §2.3). Le harnais borne le runner à
  1 800 s, mais les sous-processus ADB du runner n'ont pas tous de timeout
  propre ; le gate d'instrumentation accepte `INSTRUMENTATION_CODE: -1` sans
  exiger le nombre exact de tests.

### 4.2 Provenance APK ↔ source

Les preuves V7-06C contiennent des hashes d'APK calculés après le run, mais pas
le SHA source, l'état propre/sale, le variant, l'`applicationId` test, les
versions, le SDK de compilation, l'empreinte de signature ni un manifeste de
build. Aucun contrôle ne compare les APK **installés** aux APK construits avant
l'instrumentation. Le lien APK↔source est donc documentaire, pas
cryptographique. La correction doit générer un manifeste au build, l'embarquer
dans les deux APK, le vérifier avant instrumentation et sérialiser les champs
dans la preuve ; à défaut, marquer `INCONCLUSIVE`.

## 5. Synthèse : premier point de divergence

| P1 / P2 | Premier point où l'état diverge | Preuve |
|---|---|---|
| Chat bloqué | Requête `QUEUED` mais jamais `DISPATCHED`/`SOCKET_SEND_ACCEPTED` ; thread bloqué dans `pump()` avant `await` | Cycle G↔C A1 ; timeout inopérant |
| A4-4 | Trame `DEVICE_REVOKED` sérialisée en V1 alors que le client attend V2 ; rejet strict côté Android | `session.ts:241-246` + parseur strict |
| A4-4 (après parse) | Close relay 1012 remplace `REVOKED` par `OFFLINE` | `ownership.ex:393-403` + mapping Android |
| Provenance | Aucun champ source/manifeste dans le proof ; hashes APK non comparés aux APK installés | `run_scenario.py:781` ; `run_qa.py:363` |

## 6. Invariants de correction

1. **G atomique** : vérification finale du consentement → construction du payload
   → écriture socket, sans relâcher G entre les trois.
2. **Aucun callback sous C ne doit attendre G**, et aucun thread tenant T ne doit
   appeler dans C. L'ordre global doit être acyclique et documenté.
3. **Révocation locale immédiate** : aucune requête non envoyée ne doit partir
   après révocation ; une requête déjà acceptée par le socket n'est pas rejouée
   silencieusement.
4. **Timeout borné** : l'attente pré-dispatch est bornée ; un timeout avant envoi
   est distingué d'un timeout après acceptation (`UNKNOWN`).
5. **A4-4** : `REVOKED` uniquement après une trame `DEVICE_REVOKED` authentifiée
   et parsée sous le protocole négocié ; panne réseau/TLS/Noise/1012 ne devient
   jamais `REVOKED` ; `AUTH_REQUIRED` et `REVOKED` restent distincts.
6. **Pas de fuite de révocation** avant preuve du token côté connecteur.
7. **Provenance** : manifeste généré au build depuis la source réellement
   compilée, embarqué dans les APK, vérifié avant instrumentation ; mismatch ou
   champ absent → `INCONCLUSIVE`, jamais `PASS`.
8. **Aucune donnée sensible** dans les traces, manifestes ou preuves.

## 7. Limites du diagnostic

- Aucun thread dump du run historique V7-06C ; la cause historique reste
  `UNKNOWN`, même si le cycle G↔C est atteignable.
- Aucun test runtime n'a été exécuté pendant les audits ; seules les sources,
  tests existants, preuves et la source du relais ont été lues.
- L'image du relais déployé est épinglée par digest ; la correspondance avec
  `references/repos/paseo-relay` est documentée par la campagne V6.3/V7-06C,
  mais n'est pas re-vérifiée cryptographiquement ici.
- Les corrections et leurs tests sont décrits dans
  `V7-06D_FINAL_REPORT.md` et les preuves associées.

## 8. Phase physique complémentaire (2026-11-01)

Sur Pixel 9 Pro XL API 37.1, la sonde A4-4 est **PASS** et le gate APK installé
est exercé. La série de 5 démarrages froids donne **3 PASS / 2 FAIL**. Les
échecs #2/#3 ne reproduisent pas le blocage G↔C : la requête chat est émise
(`SOCKET_SEND_ACCEPTED`) et la réponse revient (`RESPONSE_RECEIVED`,
`CORRELATED`, `UI_RESULT=SUCCESS`). L'app-state de #3 montre une réponse
assistant valide avec `tool_state=completed`, `tool_call_count=2` et **zéro
proposition** ; le modèle indique que `atlas_propose_plan_revision` a été refusé
localement puis rejeté par le service. Le test échoue ensuite dans
`parseToolMetadata` (ligne 641), qui ne sait pas lire la forme légitime « Aucune
proposition (0) ».

Ce comportement est enregistré comme **F1** : nouveau blocage de fiabilité du
parcours coach, distinct de la deadlock G↔C. Il justifie `GO_CHAT_RELIABILITY_QA
= NO GO`. Le parseur de test doit être durci (`PROPOSAL_MISSING`) et le rejet de
l'outil diagnostiqué avant une nouvelle série. Détails :
`evidence/V7-06D_PHYSICAL_QA.md`.
