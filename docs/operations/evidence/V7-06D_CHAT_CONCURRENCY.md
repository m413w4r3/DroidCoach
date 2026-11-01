# V7-06D — Concurrence du chat Android : deadlock G↔C et correction

**Date :** 2026-11-01
**Base :** `9ad90c7a079736ffc5f7bb6a7f0d4ee3eed70580`
**Audit :** `codex/gpt-6-luna`, effort effectif `xhigh`, worktree
`/tmp/atlas-v7-06d-a1-android-ro`, lecture seule (`collaborationMode=Plan`).
**Statut :** cycle de deadlock atteignable prouvé statiquement ; test rouge,
correctif et résultats détaillés ci-dessous.
**Cause historique V7-06C :** `UNKNOWN` — le cycle est cohérent avec le symptôme,
mais aucun thread dump ne permet de l'attribuer rétroactivement avec certitude.

## 1. Graphe des verrous

| Verrou | Rôle | Sites principaux |
|---|---|---|
| **G** `AgentConsentGate` | sérialise consentement/révocation et l'écriture finale du payload | `AtlasController`, `RevocationBarrier`, `AgentContextCoordinator`, `RelayBackendTransport.pump()` |
| **T** `RelayBackendTransport.lock` | file, in-flight, session, génération, état | `RelayBackendTransport.kt:50` |
| **C** `PaseoRelayClient.lock` | socket, handshake Noise, cipher states, état | `PaseoRelayClient.kt:42` |
| **M** moniteur du coordinateur | timestamps de consentement et objectifs | `AgentContextCoordinator.kt` |

Ordre du chemin de dispatch : **G → T → `beforeDispatch` → G réentrant → M**,
puis, après relâchement de T, **G → C** dans `sendTextFrame`.
Ordre des callbacks OkHttp : **C → T**, puis `pump()` attend **G**.

## 2. Cycle complet

**Thread B** (`atlas-sync-worker`) : `executeDeferred()` → `pump()` acquiert **G**,
puis **T**, construit le payload, relâche **T**, puis `client.sendTextFrame()`
attend **C**. **G** reste tenu pendant toute l'opération.
**Thread A** (callback OkHttp) : `receiveText` tient **C**, appelle
`listener.onApplicationFrame`, `handleResponse()` acquiert/relâche **T**, puis
`pump()` attend **G**.

A attend G, B attend C : deadlock. B n'atteint jamais l'écriture, donc aucune
requête chat ne parvient au connecteur.

Le timeout de 55 s ne protège pas : `executeDeferred()` appelle `pump()`
**avant** `pending.done.await()` (`RelayBackendTransport.kt:200-202`). Un blocage
sur G, T, C ou un callback empêche d'atteindre l'attente bornée.
`Future.cancel(true)` n'interrompt ni `ReentrantLock.lock()` ni un moniteur Java.

**Premier point de divergence observable :** la trace montre `QUEUED`, puis
`DISPATCHED` (marquage `sent=true` et enregistrement du diagnostic avant
l'écriture), mais **jamais** `SOCKET_SEND_ACCEPTED` ; le connecteur ne reçoit
aucune trame. C'est la signature attendue du blocage dans `sendTextFrame`.

## 3. Test rouge déterministe

Le test doit forcer le cycle avec des barrières explicites, pas avec des
tentatives aléatoires :

1. Transport connecté, `FakeRelaySocketFactory(dropRequestResponses = true)`,
   première requête chat en vol et capturée.
2. Le thread M acquiert G et le signale.
3. Le thread R délivre une réponse valide (`sendResponseForRequest`) : il entre
   dans `onText`, tient C, puis `handleResponse` → `pump()` attend G.
4. Un crochet de test (ou une lecture bornée de la pile) confirme que R attend G.
5. M, tenant toujours G, appelle `executeDeferred` avec un timeout court.
6. Sur BASE : M attend C dans `sendTextFrame`, R attend G → deadlock ; M ne
   revient jamais, la requête n'est pas capturée, le test échoue par timeout
   borné avec les noms/états des threads.
7. Après correctif : R ne tient pas C en attendant G (ou `pump()` est déféré),
   M obtient C, la requête est capturée, le test passe.

Le test doit utiliser des threads daemon et un timeout JUnit borné pour ne pas
bloquer la suite ; un test qui pend indéfiniment n'est pas un oracle acceptable.

## 4. Correctif

Invariants :

1. G couvre toujours contrôle final → payload → écriture socket.
2. Aucun thread ne tient C en attendant G ; les callbacks déclenchant `pump()`
   sont différés sur un exécuteur dédié (ou les callbacks sont dispatchés hors C
   avec garde de génération).
3. Aucun thread ne tient T en appelant dans C (`client.abort/stop/reconnect/
   sendTextFrame`) ; `client.abort(4003)` est sorti de la section T.
4. L'attente de G avant dispatch est bornée par la deadline de la requête
   (`tryLock`) ; un timeout avant envoi est définitif (`TIMEOUT_NOT_SENT`) et
   n'écrit rien ; un timeout après acceptation reste `TIMEOUT`/incertain.
   Les pumps de callback sont eux-mêmes exécutés sur un exécuteur dédié avec une
   attente bornée de G et une reprise asynchrone différée ; ils ne bloquent
   jamais le thread OkHttp qui tient C. `close()` arrête l'exécuteur et
   interrompt l'attente.
5. Une requête déjà acceptée par le socket n'est jamais rejouée silencieusement ;
   une requête non envoyée peut rester en file et être dispatchée après
   reconnexion ; l'identité `request_id`/idempotence ne change pas.
6. Révocation locale immédiate : aucun envoi après refus, y compris si le close
   ou la reconnexion survient pendant `beforeDispatch`.
7. Traces bornées : `USER_PERSISTED`, `WORK_SUBMITTED`, `QUEUED`,
   `BEFORE_DISPATCH`, `PAYLOAD_READY`, `SOCKET_SEND_ACCEPTED`, `RESPONSE_RECEIVED`,
   `CORRELATED`, `UI_RESULT`, avec tag de corrélation haché et aucun contenu.

## 5. Résultats

| Oracle | Résultat |
|---|---|
| Test rouge G↔C sur BASE | **PASS (rouge reproduit)** — commit `f6e8659`, exécuté par le parent sur un worktree détaché : `RelayGateCycleTest.callbackWaitingForConsentGateCannotBlockTheNextSocketWrite` échoue en 2,25 s ; le thread `relay-gate-holder` tient le `ReentrantLock` de `AgentConsentGate` et `relay-cycle-reader` est `WAITING` sur ce verrou dans `RelayBackendTransport.pump()` |
| Test vert après correctif | **PASS (ciblé)** — le même `RelayGateCycleTest` passe sur le code corrigé |
| Tests ciblés de non-régression | **PASS** — `RelayConcurrencyGateTest` 3/3, `RelayBackendTransportTest` 24/24, `CoreV4DiagnosticsTest` 4/4, `RelayCoreV4HttpTransportTest` 12/12 (résultats locaux de la branche X) |
| Suite JVM complète | **PASS (branche X)** — 118 fichiers, **903/903**, 0 échec/erreur/ignoré ; à rejouer sur le SHA intégré |
| Lint | **EN ATTENTE** |
| Test timeout avant envoi (`TIMEOUT_NOT_SENT`, aucun octet) | **PASS (ciblé)** — `timeoutWaitingForConsentGateReturnsNotSentWithinTheRequestDeadline` |
| Test timeout après acceptation (une seule requête, incertain) | **PASS (ciblé)** — `expiresTimedOutRequestAndSendsOnlyBestEffortCancel` |
| Test révocation/close pendant `beforeDispatch` | **PASS (ciblé)** — `closeDuringBeforeDispatchNeverWritesTheRequest` |
| Test callbacks d'ancienne génération | **PASS (ciblé)** — `staleGenerationResponseCannotCompleteCurrentInflightRequest` |
| Test reprise sans nouvelle identité (`request_id`/idempotence) | **PASS (ciblé)** — `queuedNotSentRequestKeepsItsIdentityAcrossReconnect` |
| Absence de contenu/secret dans les traces | **PASS (ciblé)** — `CoreV4DiagnosticsTest` |
| Qualification physique de fiabilité | **NOT_RUN** — pas d'autorisation |

## 6. Limites

- Le deadlock est prouvé comme mécanisme atteignable, pas comme cause certaine du
  run V7-06C (pas de thread dump).
- Les autres hypothèses A1 (assemblage de contexte synchrone, famine du worker,
  garde de génération sur révocation de catégorie, transition de session,
  acceptation locale sans réception connecteur) ne sont pas toutes corrigées ;
  elles sont documentées comme risques résiduels.
- Aucun test sur téléphone n'a été exécuté ; `GO_CHAT_RELIABILITY_QA` reste
  `NOT_RUN`.
