# ATLAS V6.3 — R3-C — Correctifs

## R3C-COMPAT-01 — Session maintenue sur capacité négociée manquante

**Commit d'intégration :** `a162f70` (branche
`feat/atlas-v6-3-r3c-qualification`).

**Cause racine.** `RelayBackendTransport.handleHelloOk()` comparait les
capacités demandées à `hello_ok` et, si l'une manquait, vidait la file,
mettait l'état à `ERROR` et fermait le WebSocket avec `4003`. Le connecteur
Node, lui, négocie déjà l'intersection des capacités enregistrées et
demandées (`SessionManager.authenticate`). Un ancien appairage sans
`coach.chat` perdait donc **toutes** ses opérations au lieu de perdre
seulement le chat.

**Correctif.**
- `negotiatedCapabilities = serverCapabilities ∩ config.requestedCapabilities` ;
  le serveur ne peut pas élargir les droits du client.
- La session (`session_id`, expiration, limites) est établie normalement et
  l'état passe à `RELAY_CONNECTED`.
- Seules les requêtes dont la capacité requise manque sont terminées avec
  `OPERATION_UNAVAILABLE` ; le message de réappairage `coach.chat` est
  conservé.
- Une notification `CAPABILITY_UNAVAILABLE` est émise au plus une fois pour
  `coach.chat`, sans fermer la session.
- Aucun `abort(4003)` sur capacité manquante.

**Fichiers.** `RelayBackendTransport.kt`,
`RelayCapabilityNegotiationTest.kt` (nouveau),
`RelayBackendTransportTest.kt` (attente mise à jour),
`integrations/relay/pc/test/r3c-compat.test.ts` (nouveau). `session.ts` est
inchangé : son intersection était déjà correcte.

**Preuves.** JVM relay `BUILD SUCCESSFUL` (4 cas de négociation) ; Node
`1/1 PASS`. Voir `PREUVES.md` P-COMPAT-01.

**Limites.** Pas encore rejoué sur Pixel avec un véritable ancien appairage ;
la vague C doit réappairer un appareil sans `coach.chat` puis vérifier le chat
refusé et une opération ancienne acceptée.

## R3C-RACE-01 — Tests déterministes des courses R-22

**Commit d'intégration :** `22ba24f`.

**Contenu test-only, code de production inchangé.**
- Android `RelayConcurrencyGateTest` :
  1. révocation sous la porte alors qu'un chat attend son dispatch →
     `POLICY_DENIED`, payload non construit, aucune trame ;
  2. écriture WebSocket bloquée au point contrôlé, mutation de consentement
     bloquée par la même porte, puis chat suivant refusé ;
  3. `DEVICE_REVOKED` en vol → résultat `DEVICE_REVOKED`, état `REVOKED`,
     `lastCloseCode=4001`, aucune réponse tardive, aucun second effet.
- Node `r3c-race.test.ts` :
  1. chemin polling `closeRevokedSessions()` : abort immédiat, erreur
     `DEVICE_REVOKED` avant `close 4001`, réponse tardive non émise ;
  2. chemin réception directe : `checkActive` lève `DEVICE_REVOKED`, abort du
     chat en vol, nouvelle requête jamais transmise au backend ;
  3. écriture `DEVICE_REVOKED` bloquée : la fermeture attend la fin de
     l'écriture, ordre `abort → send → close` vérifié.
- Hooks de test ajoutés à `RelayTestFixtures.kt` : `beforeSendText` et
  `sendResponseForRequest`.

**Correction d'oracle.** Le chemin réception directe produit une erreur
**session-scoped** (`request_id: null`) parce que `checkActive` refuse la trame
avant l'extraction du `request_id`. C'est le contrat existant, géré par
Android qui invalide toute la file et les requêtes en vol. Le test a été
aligné sur ce contrat, pas le code de production.

**Preuves.** Vitest `3/3 PASS` ; JVM ciblé `BUILD SUCCESSFUL`. Voir
`PREUVES.md` P-RACE-01.

**Limites.** Les courses sont prouvées de façon déterministe en JVM/Node ; la
reproduction sur Pixel d'une coupure pendant un chat en vol reste
`PARTIAL` tant que la vague C n'est pas exécutée.

## R3C-CI-01 — Couverture CI Node et proxy

**Commit d'intégration :** `cd6e751`.

- Nouveau job `relay-node` (Node 24) : `npm ci`, `npm run test:unit`
  (connecteur, sans relais externe), `node --test integrations/relay/proxy/test`.
- `test:unit` exclut explicitement les deux tests qui exigent un relais réel :
  `relay.integration.test.ts` et `stale-client-timeout.test.ts`. Le premier run
  CI (`40730173153`) a échoué sur ce dernier, absent du runner ; le correctif
  rend le job déterministe sans relais.
- Le test d'intégration à relais réel et le test stale-client sont couverts par
  le harnais local épinglé.
- Harnais reproductible local :
  `integrations/relay/pc/scripts/run-isolated-relay-tests.sh` démarre l'image
  épinglée sur un port loopback libre, lance la suite complète
  (`ATLAS_TEST_RELAY_URL`), puis supprime le conteneur.
- Preuves locales : `test:unit` **15 fichiers / 79 tests PASS** sans relais ;
  suite complète **17 fichiers / 81 tests PASS** avec le relais réel sur
  `127.0.0.1:4402`.

**Limite.** La CI distante a échoué une première fois sur
`stale-client-timeout.test.ts` ; le rejeu après correctif doit être terminé
avec `success` avant tout PASS. Un push supplémentaire est nécessaire.

## R3C-ANDROID-NET-01 — OFFLINE / ERROR

**Commit d'intégration :** `d2419b3`.

**Cause racine.** `PaseoRelayClient.connectionFailed()` appelait
`notifyFailure()`, qui invoquait `onProtocolFailure()` ; le transport
traduisait immédiatement cela en `RelayConnectionState.ERROR`, y compris pour
`UnknownHostException` / `ConnectException`. Une panne réseau était donc
présentée comme une erreur de protocole.

**Correctif.**
- Classification unique via `RelayFailure.fromThrowable` dans
  `handleConnectionFailureLocked`.
- `NETWORK_UNREACHABLE` → `OFFLINE` immédiat, puis `RELAY_CONNECTING` au
  début de la tentative suivante ; budget de reconnexion épuisé → `OFFLINE`.
- `TLS_CERTIFICATE`, `TLS_HOSTNAME_MISMATCH`, `NOISE_IDENTITY_MISMATCH`,
  `PERMISSION_DENIED` et `UNKNOWN` → `ERROR` **terminal**, sans reconnexion
  qui pourrait finir en `OFFLINE`.
- `AUTH_REQUIRED`/`AUTH_FAILED` et `DEVICE_REVOKED` conservent leurs états.
- Une erreur distante `BACKEND_UNAVAILABLE` laisse la session
  `RELAY_CONNECTED`.
- Les closes protégés `4002`/`4003` sont traités comme des erreurs terminales.

**Fichiers.** `PaseoRelayClient.kt`, `RelayNetworkClassificationTest.kt`
(nouveau, 14 cas). Aucune modification de `RelayBackendTransport.kt` ni du
schéma de protocole.

**Preuves.** `BUILD SUCCESSFUL` sur la matrice relay complète ; gates
complètes 712 tests JVM / 0 échec. Voir `PREUVES.md` P-NET-01 et P-GATES-01.

**Limites.** La classification est prouvée en JVM ; la reproduction Pixel
réelle (mode avion, Wi-Fi/LTE, TLS invalide) reste à exécuter en vague C.

## R3C-A4-CLOSE-01 — Fermeture 4001 non propagée par le relais

**Statut : FAIL de la gate A4-4, sans correction de production.**

**Constat Pixel.** `R3bA4RevocationInstrumentedTest` reçoit `DEVICE_REVOKED`,
passe à `REVOKED` et expose l'erreur de révocation, mais `lastCloseCode` reste
nul dans la fenêtre de 5 s. Un probe Node synthétique, sur trois chemins
(public Funnel, proxy local, relais direct `4402`), observe :

```text
0 ms     WS_OPEN
~1300 ms APP error DEVICE_REVOKED
~1530 ms WS_CLOSE 1012 "Server disconnected"
```

Le connecteur appelle bien `channel.close(4001, …)` sur son socket serveur,
mais le relais épinglé ferme le socket client avec **1012** et ne propage pas
le code applicatif 4001. Le même comportement est observé sans Funnel et sans
proxy : c'est le relais, pas le transport public.

**Impact.** La révocation reste effective (`DEVICE_REVOKED`, `REVOKED`, aucune
reconnexion authentifiée, effacement explicite, `PAIRING_REQUIRED`,
réappairage). A4-5 à A4-10 sont PASS. Seule l'assertion diagnostique 4001
échoue.

**Contrôle d'effacement fail-closed.** Le test R3-C d'effacement vérifie
l'alias Keystore **avant** et **après** `clearCredentials()`. Le helper
`r3bRelayKeystoreAliasPresent()` lève désormais une `AssertionError` si le
Keystore est illisible, au lieu de retourner `false` et de faire passer
l'assertion d'absence à tort. Rejeu : **PASS (0,192 s)**, alias présent avant,
absent après, credential et préférence chiffrée supprimés, `PAIRING_REQUIRED`.

**Options.** Corriger la propagation du code de fermeture dans le relais
épinglé (nécessite la source amont et une revue, hors périmètre R3-C), ou
accepter explicitement 1012 comme fermeture de transport après révocation et
adapter le contrat A4-4. Aucune de ces décisions n'a été prise unilatéralement.

## R3C-PIXEL-SAFETY-01 — Runbook non destructif

Le runbook R3-B est remplacé pour la qualification par
`RUNBOOK_PIXEL_FINAL.md`. Interdits par défaut : `pm clear`, `uninstall`,
factory reset, `rm -rf` des répertoires applicatifs, écrasement global des
préférences/bases, suppression de l'alias Keystore hors scénario
d'appairage explicite. Le rollback APK tient compte du `versionCode`, de la
signature et de la compatibilité des données ; `install -r` n'est pas une
garantie de downgrade.
