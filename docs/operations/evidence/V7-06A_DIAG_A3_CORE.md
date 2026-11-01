# V7-06A-DIAG-A3 — Connecteur PC et Core V4 (audit lecture seule)

Date : 2026-10-31
BASE_SHA : `55625299c364e1c71eba5924fcfe933af1852a2f`
Session Paseo : `0f3c41b5-ebb5-4dfb-9903-465ebe781fc3`
Profil vérifié par le parent : `provider=codex`, `model=gpt-6-luna`,
`effectiveThinkingOptionId=xhigh`, mode `auto`.
Worktree : `/tmp/atlas-v7-06a-a3-core-ro` (détaché, `chmod -R a-w`), HEAD et arbre
propres après audit. Aucun test, réseau, ADB ni écriture.

## Faits établis

1. **Ordre connecteur.** Pour une opération à effet, le connecteur :
   valide l’enveloppe → écrit durablement `in_flight` → POST Core → parse la réponse
   2xx → construit la réponse → persiste `completed` (transformé en
   `completed_redacted` sur disque pour V4, réponse supprimée) → retour à
   `PeerSession` → tentative d’envoi WS. `completed_redacted` prouve un résultat
   backend parsé, **pas** une remise au client
   (`operations.ts:187,213,278,354`, `session.ts:300-304`).
2. **Aucun suivi de livraison.** La session n’enregistre ni tentative, ni résultat
   d’écriture socket, ni ACK Android. Si l’envoi lève, elle tente une erreur de même
   `request_id` ; si le peer est fermé, rien n’est envoyé. `socket.send` est appelé
   sans callback ni contrôle de `bufferedAmount` (`relay-link.ts:385-388`).
3. **Cache d’idempotence.** Clé = appareil + `idempotency_key` ; TTL 10 min.
   Rejeu en mémoire d’une entrée `completed` → réponse `replayed: true`. Même clé
   avec un autre contenu/opération → `IDEMPOTENCY_CONFLICT`
   (`operations.ts:92-143,299-305`).
4. **Redémarrage.** Une entrée V4 `completed_redacted` ne conserve pas la réponse ;
   un rejeu renvoie `BACKEND_UNAVAILABLE` sans re-POST Core. Une entrée `in_flight`
   devient `uncertain`, également bloquée (`operations.ts:300,332`).
5. **Validation du reçu.** Le connecteur relie `request_id`/`idempotency_key`
   payload ↔ enveloppe, mais ne valide pas le schéma du reçu Core : un objet JSON
   borné est accepté (`protocol.ts:277`, `backend.ts:152`).
6. **Sessions.** Un nouveau peer pour le même appareil ferme l’ancien et annule ses
   requêtes en vol ; la réponse n’est pas transférée vers le nouveau canal. Aucune
   génération de session dans la réponse, aucun `checkActive` après l’appel Core
   (`session.ts:93-104,294-308`).
7. **Core.** `/v4/sync/consent-state` est sérialisé par transaction SQLite
   `BEGIN IMMEDIATE`, met à jour `consent_states_v4` et insère
   `consent_state_events_v4` dans la même transaction. Triggers : version
   strictement croissante, événements immuables, pas de suppression
   (`v4_router.py:484-497`, `store.py:40-47`, `v4_projection_store.py:138-200`).
8. **Sémantique Core.** Version inférieure → stale ; même version + état différent
   → conflit ; même version + même état → doublon. Le reçu expose
   `ACCEPTED`/`DUPLICATE` (`result_code`). `APPLIED`/`REJECTED`/`RECONCILED` sont des
   états d’ACK de proposition, pas des reçus de consentement (`v4_router.py:706-752`).
9. **Preuve serveur.** `consent_state_events_v4` conserve la clé, le device_ref
   pseudonymisé, catégorie, version, état, hash ; aucune route ne permet de relire un
   reçu par clé. Un HTTP 200 ne contient pas le reçu côté client.

## Matrice d’hypothèses

| # | Hypothèse | Compatible | Incompatible / manquant | Test discriminant |
|---|---|---|---|---|
| H1 | Core accepte, réponse WS perdue avant Android | 2 POST 200, 2 `completed_redacted`, pas d’ACK du 2e | aucune trace de tentative WS | barrière après persistance, fermeture/replacement du canal ; une seule mutation Core, reçu récupérable |
| H2 | Session remplacée : réponse de l’ancien peer annulée | 2 upgrades avant les POST | aucun remplacement pendant l’opération établi | bloquer le POST, authentifier un 2e peer, libérer Core ; vérifier absence de transfert et réconciliation |
| H3 | Réponse HTTP Core jamais parsée | timeout après commit → `uncertain` | incompatible avec 2 `completed_redacted` si ce sont ces POST | couper HTTP après commit → `uncertain` ; reçu JSON valide → `completed_redacted` |
| H4 | Rejeu après redémarrage bloqué par `completed_redacted` | renvoie `BACKEND_UNAVAILABLE` sans POST | aucun redémarrage établi | POST réussi, redémarrage, même clé ; oracle actuel : blocage sans seconde mutation |
| H5 | Réponse partie du connecteur mais non reçue/appliquée par Android | aucune preuve de remise | traces absentes des deux côtés | transport contrôlé avec suppression de frame ; comparer « reçue non appliquée » vs « jamais reçue » |
| H6 | Révocation v3 concurrente rendant v2 stale | possible en général | deux 200 ne concordent pas avec un v2 stale | courses v2/v3 dans les deux ordres ; état final jamais < v3 |

**Aucune hypothèse n’est établie comme cause du run V7-05.**

## Chemin et fenêtres d’incertitude

`Android → WS request → PeerSession → OperationExecutor → journal in_flight →
POST Core → commit SQLite → reçu HTTP → journal completed_redacted → retour session →
socket.send → Android reçoit/corrèle/valide/applique`.

Fenêtres non couvertes par un état durable : commit Core avant réception HTTP ;
réception HTTP avant journal final ; journal final avant envoi WS ; fermeture entre
`closed` et écriture réseau ; frame perdue après écriture locale. Le timeout HTTP
connecteur est de 5 s ; le timeout Android relayé de 8 s.

## Tests Phase C proposés (volet connecteur/Core)

- C1 : Core commit + perte de canal après journalisation ; vérifier une seule
  mutation Core, `completed_redacted`, absence d’ACK, récupération explicite.
- C2 : course v2/v3 dans les deux ordres ; monotonie et rejet stale exacts.
- C5 : POST réussi puis redémarrage avant envoi ; exposer `BACKEND_UNAVAILABLE` et
  démontrer une récupération sûre sans seconde mutation.
- C6 : rejeu exact, même clé/corps différent, nouvelle clé même état/version, après
  redémarrage et après TTL simulé.
- C7 : remplacement de peer et échec de `channel.send` pendant une réponse.

## UNKNOWN restants

- La réponse du second POST a-t-elle été préparée, `channel.send` appelé, et avec quel
  résultat ?
- Android a-t-il reçu la frame, l’a-t-il corrélée et appliquée ?
- Un peer a-t-il été remplacé pendant le traitement ?
- Un redémarrage connecteur a-t-il eu lieu (non établi) ?
- Quel reçu exact a été parsé (non conservé, non validé par schéma) ?

Instrumentation minimale : `operation_received`, génération de session,
`core_post_start/end` + statut + `result_code`, transition journal,
`response_ready`, `ws_send_attempt/result`, `session_replaced/closed`, replay ;
côté Android `response_received` puis `receipt_applied`.
