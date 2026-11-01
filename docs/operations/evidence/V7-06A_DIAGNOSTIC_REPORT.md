# V7-06A — Rapport de diagnostic consolidé (Phase A)

Date : 2026-10-31 (UTC)
BASE_SHA : `55625299c364e1c71eba5924fcfe933af1852a2f`
Branche d’intégration : `feat/atlas-v7-06a-v4-reliability`
Audits : A1 Android, A2 Relay, A3 Connecteur/Core, tous `codex/gpt-6-luna`,
`effectiveThinkingOptionId=xhigh`, worktrees détachés `chmod -R a-w`, HEAD/arbre
vérifiés propres après exécution.

**Conclusion : cause racine du run V7-05 UNKNOWN.** Le mécanisme de blocage qui a
transformé une perte de retour en échec durable est, lui, établi par le code et les
preuves. Aucune hypothèse dominante n’est présentée comme cause.

## 1. Faits établis

### 1.1 Côté Core (preuve serveur)

- Exactement deux `POST /v4/sync/consent-state` reçus à
  `2026-10-31T00:23:51.286Z` et `.452Z`, tous deux **200** (preuve V7-05).
- Audit lecture seule du Core live : `consent_states_v4` contient `DAY_PLAN` et
  `ACTIVITY_STATUS` `GRANTED` v2 ; `consent_state_events_v4` contient 2 événements.
  Les écritures sont transactionnelles (`BEGIN IMMEDIATE`), les versions sont
  monotones (trigger), les événements sont immuables (triggers)
  (`v4_router.py:484-497`, `store.py:40-47`, `v4_projection_store.py:138-200`).
- Aucune projection personnelle V4 n’est stockée pour ce `device_ref`
  (`day_plan_snapshots_v4` = 0, etc.). Voir
  `docs/operations/evidence/V7-06A_RESIDUAL_ACCESS_AUDIT.md`.

### 1.2 Côté connecteur

- Deux opérations `v4.consent.state.upsert` distinctes `completed_redacted`
  (preuve V7-05), avec clés et hashes différents.
- Ordre du code : validation → journal durable `in_flight` → POST Core → parse 2xx →
  réponse construite et gardée en mémoire → journal `completed` (réduit en
  `completed_redacted` sur disque pour V4) → retour à `PeerSession` → tentative
  d’envoi WS (`operations.ts:187-291,346-370`, `session.ts:294-308`).
- **`completed_redacted` prouve un résultat backend parsé, pas une remise au
  client.** Aucun état durable ne distingue « réponse prête », « écriture socket
  acceptée » et « reçue par Android ».
- Après redémarrage, une entrée `completed_redacted` ne peut pas rejouer la réponse :
  `BACKEND_UNAVAILABLE` sans re-POST Core (`operations.ts:299-305`).
- En mémoire, un rejeu de la même clé/corps renvoie la réponse `completed` avec
  `replayed: true` (`operations.ts:124-143,299-305`).
- Le connecteur ne valide pas le schéma du reçu Core : un objet JSON borné est accepté
  (`backend.ts:152-158`, `protocol.ts:735`).
- Un nouveau peer pour le même appareil ferme l’ancien (4001) et annule ses requêtes ;
  la réponse de l’ancien n’est pas transférée (`session.ts:93-104,267-282`).

### 1.3 Côté Android

- Worker séquentiel : DAY_PLAN puis ACTIVITY_STATUS, même corps durable réutilisé pour
  la même version, ACK local uniquement après reçu validé
  (`CoreV4SyncWorker.kt:726-742`, `AndroidSyncStorage.kt:297-341`).
- Un échec de consentement arrête la passe avant le plan
  (`CoreV4SyncWorker.kt:114-124,196-201`).
- `Reason.TIMEOUT/EXPIRED/QUEUE_FULL → UNAVAILABLE` avec `retryable=false` ; il
  n’existe pas de catégorie `TIMEOUT` ni de champ « effet distant possible »
  (`RelayCoreV4HttpTransport.kt:194,237-251`, `CoreV4SyncClient.kt:87-110`).
- Le budget relayé de 8 s couvre l’attente en file ; `sent=true` précède l’écriture
  socket et ne prouve pas son acceptation (`RelayBackendTransport.kt:135-203,518-535`).
- La révocation v3 n’est pas écrasée par un reçu v2 : l’ACK vérifie catégorie, version
  et état courants (`AndroidSyncStorage.kt:321-341`).
- Deux voies WorkManager indépendantes (immédiat `REPLACE`, périodique `REPLACE`),
  sans verrou commun ; un snapshot de consentements peut être envoyé deux fois car la
  relecture ne compare pas `syncedVersion`
  (`CoreV4SyncWorker.kt:944-970`, `AndroidSyncStorage.kt:290-295`).
- `inFlight` est indexé par `requestId` sans refus de doublon ; deux appels simultanés
  du même identifiant peuvent se remplacer (`RelayBackendTransport.kt:313-335,519`).
- `PaseoRelayClient.generation` est lu hors verrou dans les callbacks
  (`PaseoRelayClient.kt:45,154-168`).

### 1.4 Faits parent (environnement live)

- Le proxy journalise **une paire normale** d’upgrades par session (contrôle client +
  connexion data du connecteur), y compris à 00:23:50.829/.832, 15:40 et 15:43. Les
  deux upgrades de V7-05 ne sont pas une anomalie à elles seules.
  `proxy.log` ne montre aucune fermeture autour de 00:23:51.
- Les métriques live (`latency_max_ms=15034`) ne sont **pas attribuables** à V7-05 :
  trafic V2 et chat postérieurs. Elles ne sont pas utilisées comme preuve.
- Le journal connecteur live ne contient plus les entrées V7-05 (TTL 10 min expiré).
  Un chat V2 `completed` du device `pixel-v7` existe à 15:40Z ; le jeton était donc
  encore utilisé après le run. Aucun contenu lu.

## 2. Mécanisme de blocage établi (indépendant de la fenêtre de perte)

Quelle que soit la fenêtre exacte, le code transforme une **requête envoyée sans
réponse** en échec terminal :

1. `RelayBackendTransport` classe `TIMEOUT`/`EXPIRED`/`QUEUE_FULL` en
   `UNAVAILABLE` avec `retryable=false`.
2. Le worker enregistre `UNAVAILABLE`, retourne `Result.failure()` et s’arrête avant
   le plan ; le consentement reste pending avec `syncedVersion < version`.
3. Aucune reprise immédiate n’est planifiée. La seule reprise automatique est la passe
   périodique (15 min), voire une action UI.
4. Si le connecteur a redémarré entre-temps, le rejeu est bloqué par
   `completed_redacted` jusqu’à expiration du TTL (10 min) et ne produit aucun reçu.
5. Le corps et la clé d’idempotence sont durables : un rejeu sûr est possible sans
   nouvel effet métier, mais aucun mécanisme ne l’exploite dans la fenêtre utile.

Ce mécanisme est compatible avec toutes les fenêtres H1–H5 ci-dessous et explique
l’état final observé (Core accepté, Android non acquitté, plan non envoyé) **sans**
prouver laquelle a perdu le retour.

## 3. Matrice consolidée des hypothèses

| # | Hypothèse | Couche | Compatible avec | Incompatible / limitée par | Test discriminant |
|---|---|---|---|---|---|
| H1 | Retour perdu : Core/connecteur terminent, la réponse n’atteint pas Android (timeout 8 s, frame perdue, fermeture, envoi WS non démontré) | Relay/Connecteur | 2 POST 200, 2 `completed_redacted`, ACK 2/2 vs 0/2, `UNAVAILABLE` | aucune trace de timeout ni de livraison ; fermeture seule donnerait plutôt `OFFLINE` | C1/C2 : accepter côté faux Core, supprimer/retarder la réponse ; vérifier classification, état et reprise |
| H2 | Session remplacée/fermée pendant l’opération ; réponse de l’ancien peer non transférée | Connecteur/Relay | 2 upgrades avant les POST, remplacement possible | la paire d’upgrades est normale (fait parent) ; un remplacement seul donne `OFFLINE`/4001, pas `UNAVAILABLE` | C8 : bloquer le POST, authentifier un 2e peer, libérer ; vérifier absence de transfert et réconciliation |
| H3 | Erreur distante après effet Core (`BACKEND_UNAVAILABLE`, `INTERNAL`, 5xx/408/429) classée `UNAVAILABLE` | Connecteur/Relay | classification code | aucun code relayé enregistré ; les entrées sont `completed_redacted`, pas `uncertain` | C1 : succès Core puis erreur amont incertaine ; vérifier code exact et reçu local |
| H4 | Redémarrage connecteur : rejeu bloqué par `completed_redacted` | Connecteur | renvoie `BACKEND_UNAVAILABLE` sans POST | aucun redémarrage établi pendant le run | C5 : POST réussi, redémarrage, même clé ; oracle actuel = blocage sans seconde mutation |
| H5 | Chevauchement immédiat/périodique ou remplacement `REPLACE` : double envoi, collision de `requestId`, attente orpheline | Android | deux voies indépendantes, snapshot sans `syncedVersion`, `inFlight` sans rejet de doublon | aucun ordinal worker ni trace d’ordonnancement ; le run a appelé `schedule()` une fois | C3/C4 : deux passes concurrentes, `requestId` dupliqué, remplacement après acceptation |
| H6 | Reçu reçu mais rejeté/non appliqué (validation, version, commit) | Android | chemins existent | produirait `PROTOCOL`/`LOCAL_ERROR`, pas `UNAVAILABLE` ; le no-op de version ne produit pas `UNAVAILABLE` | C6/C7 : reçu tardif, révocation v3, échec de commit |
| H7 | Course v2/v3 : révocation concurrente | Android/Core | possible en général | le Core rejette stale et l’ACK local protège ; deux 200 ne concordent pas avec un v2 stale | C2/C6 : courses dans les deux ordres |

**Aucune hypothèse n’est établie comme cause.** H1 est la classe la plus compatible
avec `UNAVAILABLE` et l’état final, mais la fenêtre exacte (connecteur, WS, relay,
Android) reste inconnue.

## 4. Instrumentation nécessaire (Phase B)

Corrélateur commun : empreinte tronquée et non réversible d’un identifiant de requête
synthétique, ou ordinal éphémère ; jamais d’UUID réel, de bearer, de payload ni de
donnée personnelle.

- **Android** : ordinal worker, génération d’exécution, type immédiat/périodique,
  `runAttemptCount`, catégorie, version, état, étape, durée, résultat transport typé,
  tentative/résultat d’ACK (`ACKED/STALE/MISSING/COMMIT_FAILED`).
- **Relay** : génération de session, opération, `queued`, `dispatched`,
  `socket_send_accepted/rejected`, `response_received`, `correlation_result`,
  `timeout`, `expired`, `cancel`, fermeture + code, remplacement.
- **Connecteur** : opération reçue, début/fin POST Core + statut + `result_code`,
  durée de persistance du journal, `response_ready`, tentative/résultat WS,
  session active/changée, replay/incertitude.
- **Core** : statut et `result_code` consentement par corrélateur haché, sans
  `device_ref` brut.

Oracle B : depuis une transaction synthétique, suivre chaque étape jusqu’à l’ACK ou
identifier la dernière étape observée. Désactivé par défaut en production, borné.

## 5. Tests Phase C à construire

| Cas | Couche | Attendu |
|---|---|---|
| C1 réponse perdue | Android + Connecteur | consentement explicitement non acquitté, aucun succès fictif, reprise sûre |
| C2 réponse tardive | Android + Core | pas de corruption, pas d’attribution erronée, version cohérente |
| C3 workers concurrents | Android | pas de POST conflictuel ni attente orpheline, état final cohérent |
| C4 remplacement | Android | reçu accepté réconciliable, aucune donnée abandonnée |
| C5 redémarrage connecteur | Connecteur/Core | incertitude explicite et récupérable sans rediffusion dangereuse |
| C6 révocation concurrente | Android/Core | v2 ne réactive jamais ; v3 prime et se synchronise |
| C7 idempotence | Connecteur/Core | répétition sûre ou conflit explicite, jamais de double effet |
| C8 reconnexion | Relay/Connecteur | aucune confusion ancienne/nouvelle génération |

## 6. Décision de suite

- Ne pas appliquer la Phase D avant les tests discriminants.
- Construire d’abord l’instrumentation bornée (Phase B), puis les tests C1–C8 rouges
  sur le SHA de base, puis le correctif minimal justifié.
- La qualification VM-1 reste bloquée tant que le correctif et les tests ne sont pas
  intégrés et rejoués sur le SHA intégré.
- `GO_ANDROID_SYNC` reste **BLOCKED** ; aucun GO n’est prononcé par ce diagnostic.
