# V7-06A — Matrice des tests discriminants (Phase C)

Date : 2026-10-31
Base de référence : code `55625299c364e1c71eba5924fcfe933af1852a2f`
(+ instrumentation Phase B sur le SHA intégré).
Aucun test ne touche au Pixel, au Core live, ni à un service live. Transports, backend
et données synthétiques uniquement.

Légende : **ROUGE** = doit échouer sur le SHA instrumenté avant correctif ;
**VERT** = non-régression attendue dès la base ; **VERT\*** = vert après le correctif
attendu (le test peut être ajouté avec le correctif si l'API n'existe pas encore).

## 1. Tests Android JVM (transport simulé, stockage en mémoire)

| ID | Test (nom indicatif) | Scénario | Attendu | Base |
|---|---|---|---|---|
| C1a | `lostConsentResponseIsRetriedWithSameBodyAndKey` | faux transport : 1er POST ACTIVITY_STATUS accepté puis `UNAVAILABLE/TIMEOUT` ; 2e appel même corps/clé renvoie le reçu | `syncedVersion==version`, exactement 2 POST, même clé/corps, aucun succès fictif avant reçu | ROUGE |
| C1b | `lostPlanResponseIsRetriedWithSameBodyAndKey` | idem pour un snapshot DayPlan | reçu plan, même clé, pas de double version | ROUGE |
| C2a | `lateReceiptDoesNotCorruptOrCrossAttribute` | timeout puis reçu tardif injecté ; reprise même clé | reçu tardif ignoré/attribué à la bonne attente, version cohérente, aucun reçu volé | ROUGE/VERT\* |
| C3a | `duplicateInFlightRequestIdIsRejectedWithoutReplacingWaiter` | deux `executeDeferred` simultanés avec le même `request_id` | le 1er aboutit, le 2e reçoit un échec typé, aucune attente orpheline | ROUGE |
| C3b | `consentSnapshotRechecksSyncedVersionBeforeSend` | un consentement est acquitté entre le snapshot et l'envoi | aucun POST dupliqué pour la même version | ROUGE |
| C4a | `cancelledInFlightConsentKeepsPendingAndReplaysExactBody` | annulation après effet simulé, puis passe suivante | pending conservé, rejeu même corps/clé, ACK unique, aucun abandon silencieux | VERT |
| C4b | `schedulerUsesAppendOrReplaceAndUpdatesPeriodic` | politique de planification | immédiat non destructif, périodique mis à jour, une nouvelle demande survit | VERT\* |
| C6a | `revokedV3WinsOverLateGrantedV2Receipt` | reçu GRANTED v2 après écriture REVOKED v3 | état REVOKED v3, `syncedVersion` non avancé par v2, v3 reste pending | VERT |
| C6b | `acknowledgeConsentReturnsSuperseded` | reçu v2 vs ligne v3 | résultat typé `SUPERSEDED`, aucune écriture | VERT\* |
| C7a | `sameConsentKeySameBodyIsIdempotent` | deux envois identiques | un seul effet, reçu `DUPLICATE`/replayed, ACK stable | VERT |
| C7b | `sameConsentKeyDifferentBodyIsConflicting` | même clé, état/version différent | `IDEMPOTENCY_CONFLICT`, aucun écrasement | VERT |
| C8a | `staleSessionResponseDoesNotAbortNewSession` | réponse tardive `session_id` S1 pendant session S2 active | frame ignorée, S2 reste connectée, requête suivante réussit | ROUGE |
| C8b | `unknownRequestIdOnCurrentSessionStillFailsProtocol` | réponse inconnue même session | échec protocolaire explicite, pas de corruption | VERT |
| C8c | `relayClientGenerationIsVisibleAcrossThreads` | callbacks d'ancienne génération | aucun callback obsolète appliqué | VERT\* |

## 2. Tests connecteur Node (vitest, backend HTTP simulé, journal temporaire)

| ID | Test | Scénario | Attendu | Base |
|---|---|---|---|---|
| C1c | `journalIsCompletedBeforeWebSocketSend` | succès backend, canal WS en échec | journal `completed_redacted`, événement `ws_send_result=rejected`, aucun ACK client simulé | ROUGE (trace absente) |
| C5a | `consentReceiptSurvivesRestartAndReplays` | POST réussi, journal écrit, nouvel exécuteur sur le même journal, même requête | reçu rejoué `replayed=true`, compteur backend inchangé | ROUGE |
| C5b | `uncertainConsentCanBeSafelyReexecutedAfterRestart` | entrée `in_flight` au redémarrage → `uncertain`, client renvoie le même corps/clé | ré-exécution autorisée pour consentement seulement, même clé, un seul effet Core | ROUGE |
| C5c | `otherV4OperationsRemainRedactedAfterRestart` | chat V4 complété puis redémarrage | `BACKEND_UNAVAILABLE`, pas de rejeu | VERT |
| C5d | `replaysV4ConsentWhenRelayRenewsTransportEnvelope` | même corps/clé/`request_id`, nouvelle session et horodatages Relay | reçu rejoué, un seul POST, aucun conflit | ROUGE → VERT (R2) |
| C5e | `replaysRestartedV4ConsentWhenRelayRenewsTransportEnvelope` | idem après redémarrage sur le journal | reçu reconstruit rejoué, un seul POST total | ROUGE → VERT (R2) |
| C7c | `connectorReplayUsesSameKeyAndBody` | rejeu en mémoire | `replayed=true`, aucun second POST backend | VERT |
| C7d | `connectorRejectsSameKeyDifferentPayload` | conflit | `IDEMPOTENCY_CONFLICT` | VERT |
| C7h | `rejectsV4ConsentReplayWhenBusinessPayloadChanges` | payload modifié ou clé d'enveloppe divergente | `IDEMPOTENCY_CONFLICT` / `INVALID_REQUEST`, backend non rappelé | VERT (R2) |
| C8d | `replacementSessionDoesNotReceiveOldResponse` | remplacement de peer pendant une opération | aucune réponse de l'ancien canal sur le nouveau | VERT |

## 3. Tests backend Python (pytest, base temporaire)

| ID | Test | Scénario | Attendu | Base |
|---|---|---|---|---|
| C6c | `consent_v3_revocation_beats_late_v2_commit` | v2 puis v3, et v3 puis v2 | monotonie, `STALE_VERSION` pour le v2 tardif, état final REVOKED v3 | VERT |
| C7e | `consent_same_key_same_body_duplicate` | rejeu exact | `DUPLICATE`, un seul événement | VERT |
| C7f | `consent_same_key_different_body_conflict` | conflit | `IDEMPOTENCY_CONFLICT`, un seul événement | VERT |
| C7g | `consent_replay_after_newer_version_is_stale` | ancienne clé après v3 | `STALE_VERSION`, état v3 conservé | VERT |

## 4. Tests instrumentés AVD (VM-1, après correctif)

| ID | Scénario | Attendu |
|---|---|---|
| VM1-1 | appairage synthétique + connexion | `RELAY_CONNECTED`, génération tracée |
| VM1-2 | deux consentements | deux ACK `APPLIED`, `syncedVersion==version` |
| VM1-3 | DayPlan approuvé | reçu, version, provenance, une ligne Core isolée |
| VM1-4 | passe sans doublon | rejeu `DUPLICATE`, aucun nouvel effet |
| VM1-5 | coupure/reprise | pending explicite puis récupération, aucun ACK fictif |
| VM1-6 | révocation | `REVOKED` local et Core, versions cohérentes |
| VM1-7 | réponse perdue injectée (si harnais) | reprise sûre, même clé, aucun succès fictif |
| VM1-8 | redémarrage connecteur isolé | reçu consentement récupéré sans second effet |

**Limite VM-1** : le harnais AVD ne dispose pas d'injection déterministe de perte de
réponse WS ni d'un redémarrage du connecteur au milieu d'un consentement. Les
scénarios VM1-7 et VM1-8 ne sont donc **pas revendiqués sur VM** ; ils sont couverts
par les tests Node C5d/C5e sur journal et enveloppe renouvelée, et par les tests JVM
C1a/C1b. VM-1 couvre l'intégration réelle consentements/DayPlan/reçu, la coupure
réseau, l'absence d'ACK fictif et la réconciliation de révocation.

## 5. Règles d'exécution

- Les tests C1–C8 sans appareil tournent sur JVM/Node/pytest, avec des données
  synthétiques et aucune connexion au Core personnel.
- Un test ROUGE doit échouer pour la raison attendue, pas par erreur de compilation ou
  de configuration ; le rapport doit inclure la sortie d'échec.
- Après correctif, chaque test ROUGE devient VERT et les suites complètes
  JVM/Node/backend restent vertes.
- Les tests VERT\* peuvent être ajoutés avec la petite API qu'ils vérifient
  (`acknowledgeConsent` typé, politique de scheduling) ; ils ne remplacent pas les
  tests ROUGE de reproduction.
- Aucun test ne doit journaliser de bearer, token, UUID réel, payload ou donnée
  personnelle.
