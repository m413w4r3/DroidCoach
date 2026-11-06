# Cycle de vie des propositions V4 expirées

**État :** implémenté localement sur `v7-10/a2-expiration`; aucune réconciliation sur une base réelle.
**Migration SQLite :** version 5 (`v4_proposal_expiration_audit`). La version de contrat API reste 4.
**Référence de conception :** [V7-10 S2 — conception de l’expiration](evidence/V7-10_S2_EXPIRATION_DESIGN.md).

## États et autorité

Le Core fait passer une proposition échue à `EXPIRED` si son état est `PENDING_ANDROID`, `RECEIVED` ou `AWAITING_USER` et si `expires_at <= now`. `EXPIRED` est une décision temporelle du serveur. Elle ne signifie pas qu’Android a reçu, affiché, refusé ou appliqué la proposition. Aucun ACK Android synthétique ni `ack_id` fictif n’est créé.

`UNCERTAIN` n’est pas automatiquement expiré. Son effet peut déjà avoir eu lieu; l’effacer par une règle TTL inventerait un résultat terminal. Il reste hors du balayeur et de l’expiration paresseuse. Quand son échéance est passée, le filtre normal du pull ne le retourne plus, mais son état persistant reste `UNCERTAIN`. Seul un ACK `RECONCILED` après résolution explicite est admis depuis cet état. Cette limite reste un point P2 à traiter par une décision de réconciliation dédiée.

| État courant | Échéance atteinte | Résultat Core |
|---|---:|---|
| `PENDING_ANDROID` | Oui | `EXPIRED` + audit serveur |
| `RECEIVED` | Oui | `EXPIRED` + audit serveur |
| `AWAITING_USER` | Oui | `EXPIRED` + audit serveur |
| `UNCERTAIN` | Oui ou non | Inchangé; aucune expiration automatique |
| État terminal | — | Inchangé par le balayeur |

## Audit serveur séparé

La migration additive v5 crée `agent_proposal_expiration_events_v4`, avec `proposal_id` comme clé primaire et clé étrangère vers `agent_proposals_v4`. L’événement contient `from_state`, `to_state='EXPIRED'`, `expires_at`, `occurred_at`, `source` (`PULL`, `ACK`, `SWEEPER` ou `RECONCILIATION`) et `event_version=1`. Les états source sont limités aux trois états automatiquement expirables.

Des triggers refusent toute mise à jour ou suppression de l’audit. La contrainte de clé primaire garantit au plus une ligne de transition serveur par proposition. La table Android `agent_proposal_events_v4` reste réservée aux ACK réellement reçus et à leurs hashes/séquences. Un ACK `EXPIRED` reçu après l’expiration serveur est un reçu Android réel; il peut donc être ajouté dans cette table sans produire une seconde transition ni une seconde ligne d’audit serveur.

La migration v5 conserve le registre, les checksums et les sauvegardes/intégrity checks du store. Les migrations 1 à 4 ne sont pas modifiées. Le mode `dual`/`principal` exige le schéma v5; le mode `legacy` conserve sa compatibilité. Les routes et corps HTTP gardent `schema_version=4`.

## Déclenchement de l’expiration

Un seul helper `expire_due_proposals` sert les deux routes et le balayeur. Il reçoit la connexion, une valeur `now` UTC, un batch borné à 1–500 et la source. Il sélectionne les candidats en ordre déterministe `(expires_at, proposal_id)`, puis fait un `UPDATE` conditionnel limité aux états actifs et à `expires_at <= now`. Si `rowcount == 1`, l’audit est inséré dans la même transaction. Si `rowcount == 0`, aucune ligne d’audit n’est ajoutée. Une erreur d’insertion annule aussi la transition d’état.

Les appels s’exécutent sous `BEGIN IMMEDIATE`; les appels concurrents ACK/balayeur sont ainsi sérialisés par SQLite. La vérification conditionnelle et la clé primaire d’audit rendent les répétitions idempotentes.

- **Pull paresseux :** après authentification Android, validation du corps/schéma, capacités, consentement courant, projection requise et validation du curseur; avant la lecture de page. La sélection garde le filtre `device_ref`, l’ordre, le curseur et la taille de page. Les propositions échues sont omises, y compris une proposition jamais tirée. Le balayeur reprend les lignes si le pull retourne ensuite une erreur et que le décorateur annule sa transaction.
- **ACK paresseux :** après authentification, validation de schéma, capacités/consentement/projection et vérification de propriété `device_ref`; le conflit `ack_id` est évalué avant la transition. Le helper tourne avant la décision ACK finale.
- **Balayeur :** tâche distincte du lifespan FastAPI, premier tick au démarrage, appels DB via `asyncio.to_thread`, arrêt propre et une erreur de tick n’arrête pas l’API. Les erreurs sont journalisées sans contenu de proposition ou identifiant personnel. Le verrou du balayeur expire après 0,25 s; un tick suivant réessaie.

Configuration :

| Variable | Défaut | Limite |
|---|---:|---:|
| `ATLAS_V4_PROPOSAL_EXPIRY_SWEEPER_ENABLED` | activé | booléen; `0` le désactive |
| `ATLAS_V4_PROPOSAL_EXPIRY_INTERVAL_SECONDS` | 30 s | 1–3600 s |
| `ATLAS_V4_PROPOSAL_EXPIRY_BATCH_SIZE` | 100 | 1–500 |

Les tests désactivent le tick automatique par défaut dans `backend/tests/conftest.py`; les tests de lifespan l’activent explicitement pour vérifier le démarrage, l’erreur isolée et l’arrêt.

## Précédence et résultats ACK

Le corps canonique est hashé. Après authentification, consentement et contrôle de propriété :

1. Même `ack_id` et même hash : `200`, `X-Atlas-Replayed: true`, sans nouvel événement ACK.
2. Même `ack_id`, corps/hash différent : `409 ACK_ID_CONFLICT`, y compris si la proposition a expiré.
3. Nouvel ACK `APPLIED` ou `REJECTED` après échéance : le Core écrit `EXPIRED` et son audit, puis rend `409 PROPOSAL_EXPIRED`. Le marqueur commit-on-error est associé à cette réponse et à ce code seulement; le décorateur ne valide pas de refus d’authentification, de schéma, de propriété ou de consentement. Aucun ACK Android n’est enregistré pour ce refus tardif.
4. ACK `EXPIRED` avant `expires_at` : `409 PROPOSAL_NOT_EXPIRED`, sans transition ni audit. Le code est présent dans `contracts/v4/error.schema.json` et dans la liste d’erreurs distante du connecteur Relay.
5. ACK `EXPIRED` réel après échéance, notamment après le balayeur : `200`; l’ACK est ajouté à la séquence Android suivante et l’outbox peut le marquer `DELIVERED`. L’audit/transition serveur existant n’est pas dupliqué.
6. `RECONCILED` n’est valide que depuis `UNCERTAIN`.

Les tests couvrent l’absence de fuite entre propriétaires : après expiration, le device A ne peut toujours ni tirer ni ACKer la proposition de B; l’ACK hors propriétaire reste `404`. Le balayeur ne change ni `device_ref`, ni les consentements, ni les capabilities. Les contrôles d’accès et les tombstones de révocation restent appliqués aux routes Android.

Dans le chemin chat, un échec d’outil `STALE_DATA` est converti en enveloppe d’erreur standard `409` avec `error.code=STALE_DATA`; l’enveloppe ne copie ni arguments d’outil, ni prompt, ni exception provider, ni token, ni donnée personnelle.

## Réconciliation hors ligne

`backend/scripts/atlas_v4_expiration_reconcile.py` lit uniquement une base SQLite locale existante fournie par chemin absolu et exige une migration v5/checksum conforme. Par défaut, `--db /chemin/absolu.sqlite` effectue un dry-run en lecture seule : comptages par état et ancienneté, candidats échus, candidats avec ACK et anomalies d’audit/état/horodatage.

Pour appliquer une réconciliation locale, `--apply` exige aussi `--backup /chemin/absolu/inexistant.sqlite`. L’outil refuse un chemin de backup existant ou relatif; il crée le backup de manière exclusive avec l’API SQLite, puis vérifie `integrity_check`, `foreign_key_check`, `user_version=5`, taille non nulle et SHA-256. Il rapporte les comptes avant/après et applique par batches transactionnels. Le mode dry-run n’a pas été exécuté sur une base réelle et aucun `--apply` réel n’a été lancé.

### Retour arrière opérateur

Le script ne restaure pas automatiquement une base si une exécution `--apply` est interrompue après une ou plusieurs transactions. Pour revenir au snapshot : arrêter l’instance Core qui utilise la base, conserver la base affectée et ses fichiers SQLite associés pour diagnostic, restaurer le backup vérifié vers un nouveau chemin sur le même volume, puis vérifier `PRAGMA integrity_check`, `PRAGMA foreign_key_check` et `PRAGMA user_version` avant tout remplacement. Ne remplacer la base active qu’après arrêt confirmé de l’instance et validation du snapshot; conserver l’ancienne base jusqu’à vérification du redémarrage par l’opérateur. Cette procédure n’a pas été exécutée sur une base réelle.

## Limites et exploitation

- Le balayeur est activé par défaut et commence au démarrage; les opérateurs peuvent le désactiver par variable d’environnement, mais l’expiration se fera alors à l’activité pull/ACK ou à une réconciliation hors ligne.
- `UNCERTAIN` n’a pas de terminaison automatique; une décision explicite est nécessaire avant de le réconcilier.
- La réconciliation apply est une opération de maintenance hors ligne, après sauvegarde vérifiée; elle ne doit pas servir à réparer une base active sans arrêt et procédure de retour arrière.
- Aucun service/base live n’a servi à valider ou exécuter la réconciliation. Aucune QA Android, AVD ou Pixel n’est revendiquée.
