# V7-06A-DIAG-A2 — Transport Relay Android (audit lecture seule)

Date : 2026-10-31
BASE_SHA : `55625299c364e1c71eba5924fcfe933af1852a2f`
Session Paseo : `1b325742-6f12-49f3-a77b-3068b2a35461`
Profil vérifié par le parent : `provider=codex`, `model=gpt-6-luna`,
`effectiveThinkingOptionId=xhigh`, mode `auto`.
Worktree : `/tmp/atlas-v7-06a-a2-relay-ro` (détaché, `chmod -R a-w`), HEAD et arbre
propres après audit. Aucun test, réseau, ADB ni écriture.

## Faits établis

1. **Budget de 8 s.** `RelayCoreV4HttpTransport` appelle `executeDeferred` avec
   `REQUEST_TIMEOUT_MILLIS=8_000`. Le délai monotone démarre à l’admission et couvre
   l’attente en file. Un `AtomicReference<Thread>` limite **chaque instance** de cet
   adaptateur à un appel simultané ; une seconde tentative reçoit
   `UNAVAILABLE/QUEUE_FULL` avant envoi (`RelayCoreV4HttpTransport.kt:97,121,135-139,194`).
2. **Séquence exacte.** `Pending` ajouté à la file sous verrou → `pump` sous
   `consentGate` → vérifications policy/expiration/capacité → enveloppe liée au
   `session_id` courant → `pending.sent=true` → `inFlight[request_id]` → chiffrement →
   `sendText`. `sent=true` précède l’écriture socket et ne prouve pas son acceptation
   (`RelayBackendTransport.kt:463-535`, `PaseoRelayClient.kt:68-79`).
3. **Retour.** `handleResponse` cherche par `request_id`, vérifie `session_id` et
   l’opération, retire l’attente, fixe le résultat, libère le latch. Une réponse
   inconnue (hors `timedOutIds`) déclenche une erreur protocolaire et un abort 4003
   (`RelayBackendTransport.kt:313-337,580-586`).
4. **Timeouts et tardives.** À l’expiration : retrait de `inFlight`, mémorisation dans
   `timedOutIds` (borne 256), cancel best effort, `UNAVAILABLE/TIMEOUT`. La première
   réponse tardive mémorisée est ignorée ; au-delà de la borne, une réponse dupliquée
   redevient « inconnue » et peut abort la session (`RelayBackendTransport.kt:185-201,
   319-321,375-377,677-680`).
5. **Classification.** `POLICY_DENIED → CONSENT`, `PROTOCOL → PROTOCOL`,
   `NOT_STARTED/OFFLINE/CANCELLED → OFFLINE`,
   `QUEUE_FULL/EXPIRED/TIMEOUT → UNAVAILABLE` ; tous avec `retryable=false`.
   `CoreV4SyncException` n’expose pas de champ « effet distant possible »
   (`RelayCoreV4HttpTransport.kt:237-251`, `CoreV4SyncClient.kt:87-110`).
6. **Collision de `request_id`.** `inFlight` est indexé par `request_id` sans rejet
   explicite d’une admission dupliquée ; deux attentes concurrentes de même identifiant
   peuvent se remplacer. Le stockage réutilise le même corps pour retenter une version,
   mais le transport ne vérifie pas l’égalité de contenu (`RelayBackendTransport.kt:144,
   519`, `AndroidSyncStorage.kt:297-319`).
7. **Génération/session.** `PaseoRelayClient.generation` est un champ ordinaire
   incrémenté sous verrou mais lu hors verrou dans `onText`, `onClosed`, `onFailure` ;
   la visibilité inter-thread n’est pas garantie. La session applicative est un UUID
   aléatoire, pas un compteur monotone. Une nouvelle authentification ferme l’ancien
   peer en 4001. `RelaySettingsController.transportGeneration` n’est pas transmis aux
   requêtes (`PaseoRelayClient.kt:45,57-65,123-169`, `session.ts:93-104`,
   `RelaySettingsController.kt:296-390`).
8. **Double démarrage.** `setV4ChatEnabled(true)` redémarre le transport si l’état est
   `RELAY_CONNECTED`/`RELAY_CONNECTING` ; `connect()` force toujours `startTransport`.
   Deux appels successifs peuvent créer deux transports et deux upgrades
   (`RelaySettingsController.kt:213-240,296-373`). Le parent a toutefois vérifié dans
   `proxy.log` que les upgrades vont **par paires normales** (contrôle client +
   connexion data connecteur) à chaque session, y compris à 15:40 ; les deux upgrades
   de V7-05 ne sont donc pas une anomalie à elles seules.
9. **Course d’interruption.** Si `latch.await` lève `InterruptedException`, le code
   retourne `CANCELLED` sans relire un résultat éventuellement déjà fixé ;
   `finishCancelledRequest` ne fait rien si le résultat est déjà posé. Possible, non
   démontré pour V7-05 (`RelayBackendTransport.kt:180-184,551-567`).
10. **Reconnexion.** Les requêtes en vol sont terminées `OFFLINE` et ne sont pas
    rejouées ; les requêtes en file peuvent survivre et partir sur la nouvelle session.
    `close()` explicite vide file et attentes (`RelayBackendTransport.kt:27,206-225,
    598-604`).

## Matrice d’hypothèses

| # | Hypothèse | Compatible | Fragilisée par | Test discriminant |
|---|---|---|---|---|
| H1 | Délai Android expiré après effet PC ; reçu tardif ignoré | effet Core + journal connecteur terminés, code final `UNAVAILABLE` | aucun événement de timeout observé | transport simulé : retarder le reçu au-delà du budget, injecter le reçu tardif, vérifier classification et reprise |
| H2 | Fermeture/remplacement de session entre effet et reçu | coupure possible après POST | une fermeture seule donne `OFFLINE` ou 4001, pas `UNAVAILABLE` | fermer/remplacer la session en vol, vérifier code exact et comportement worker |
| H3 | Erreur distante après effet Core | 5xx/408/429 → `UNAVAILABLE` ; connecteur peut signaler une incertitude | aucun code relayé enregistré | simuler succès Core puis erreur amont incertaine, vérifier classification |
| H4 | Réponse reçue mais rejetée (corrélation/reçu) | ces chemins existent | produisent `PROTOCOL`, pas `UNAVAILABLE` | injecter session/opération/request_id erronés et reçu mal formé |
| H5 | File saturée/expirée avant envoi | chemins existent | ne peut pas produire l’effet Core du même POST sans autre tentative | horloge/file contrôlées : zéro écriture socket, zéro effet |
| H6 | Double démarrage remplaçant le premier transport | deux upgrades compatibles | un remplacement seul donne `OFFLINE` ; la paire d’upgrades est normale | fabrique comptant les transports, appels pendant `RELAY_CONNECTING` |

**Aucune hypothèse n’est établie comme cause du run V7-05.**

## Fenêtres de perte / duplication

- Après écriture socket, avant retour : timeout, fermeture ou annulation peut empêcher
  le reçu d’atteindre le worker ; le cancel est best effort.
- Réponse tardive : ignorée une fois via `timedOutIds`, borne 256.
- Corrélation : une réponse inconnue abort la session ; les requêtes en file ne sont
  pas immédiatement vidées.
- `request_id` dupliqué : remplacement possible d’une attente.
- Ancienne génération : filtre non formellement robuste (lecture hors verrou).
- Rejeu durable : le connecteur ne peut pas reconstruire un reçu `completed_redacted`.

## Tests Phase C proposés

- C1 : deux opérations séquentielles, ordre queue → socket → corrélation → reçu →
  ACK local.
- C2 : effet simulé, reçu retardé au-delà du budget, reçu tardif, reprise même
  corps/clé.
- C4 : requête en file vs écrite, fermeture/reconnexion, callback d’ancienne
  génération.
- C5 : double démarrage contrôleur vs reconnexion réseau.
- C8 : matrice adversariale réponses inversées/inconnues/dupliquées/tardives et
  `request_id` réutilisé.

## Instrumentation minimale

Compteur de transport, génération WebSocket, numéro de session applicative (sans UUID),
numéro d’opération éphémère, empreinte tronquée d’identifiant pour la corrélation
inter-couches, événements monotones `queued`, `dispatched`,
`socket_send_accepted/rejected`, `response_received`, `correlation_result`, `completed`,
`returned_to_worker`, `receipt_validated`, `local_ack`, `queue_full`, `expired`,
`timeout`, `cancel`, remplacement, fermeture avec code et origine. Aucun bearer, UUID
réel ni payload.
