# ATLAS V7-10 S2 — Conception de l’expiration des propositions A0

**Statut :** conception statique, aucune modification applicative
**TASK_ID :** V7-10-S2-EXPIRATION-DESIGN
**BASE_SHA :** a5aca4b54088d43f4829adf31049417dd8cf32a6
**Branche / worktree :** v7-10/s2-expiration-design
**Profil constaté dans Paseo :** provider codex, modèle gpt-6-luna, effort effectif xhigh
**Portée d’écriture :** ce document seulement. Aucun appareil, service ou stockage live consulté.

## 1. Décision

Retenir une combinaison de transition paresseuse transactionnelle et de balayeur périodique. Les deux appellent le même service Core d’expiration. Le pull et l’ACK déclenchent une petite expiration paresseuse afin de réparer immédiatement les cas rencontrés; le balayeur termine le travail sans attendre une activité Android. Une création de proposition ou un tour de chat ne déclenche pas ce traitement.

L’expiration est une décision temporelle du Core, pas un reçu Android. Elle écrit une ligne append-only dans une nouvelle table d’audit, distincte de agent_proposal_events_v4, puis met à jour l’état dans la même transaction. Elle ne crée jamais d’ack_id et ne prétend jamais qu’Android a reçu ou refusé la proposition.

La garantie d’unicité porte sur l’événement serveur de transition : au plus une ligne d’expiration par proposal_id. Les ACK Android restent une autre piste, ne sont ajoutés que si un vrai ACK a été reçu, et ne rejouent pas la transition déjà effectuée par le Core.

### Invariants

1. À l’instant now égal ou postérieur à expires_at, une proposition échue encore active ne peut plus être appliquée.
2. Une transition serveur vers EXPIRED et sa ligne d’audit sont atomiques; aucune des deux ne peut subsister seule.
3. Une proposition a au plus un événement d’audit serveur d’expiration, assuré par la clé primaire proposal_id.
4. Aucun ACK Android fictif n’est ajouté. Un ACK EXPIRED reçu après une expiration système est enregistré comme ACK réel, sans seconde transition ni seconde ligne d’audit d’expiration.
5. Toutes les mutations concurrentes passent par SQLite BEGIN IMMEDIATE, une mise à jour conditionnelle et la vérification de rowcount.
6. L’authentification et les gardes DAY_PLAN existantes continuent de s’appliquer aux routes Android. Le balayeur ne lit aucune projection de plan et n’exécute aucun effet.
7. Le contrat de pull et le schéma des ACK Android restent compatibles; aucune nouvelle propriété n’est requise par le client.

## 2. Cartographie vérifiée

Références relevées statiquement à BASE_SHA. Les lignes identifient l’implémentation actuelle, pas le code proposé.

| Fichier : lignes | Comportement actuel | Conséquence de conception |
|---|---|---|
| backend/atlas_backend/v4_tool_service.py:84-101, 381-485, 881-902 | TTL injectable de 24 h par défaut; le service calcule expires_at à la création, sérialise en UTC avec Z et crée l’état PENDING_ANDROID. | Garder le TTL et la création inchangés; comparer des horodatages UTC de même format. |
| backend/atlas_backend/v4_router.py:552-557 | Le routeur construit V4ToolService avec store.clock et sans override de TTL. | Aucun changement de contrat de création n’est requis. |
| backend/atlas_backend/v4_router.py:261-305, 448-465 | PROPOSAL_EXPIRED et ACK_ID_CONFLICT sont des erreurs HTTP 409. | Garder ces statuts; ajouter un code stable uniquement pour l’ACK EXPIRED prématuré. |
| backend/atlas_backend/v4_router.py:485-515 | serialized_consent_route ouvre BEGIN IMMEDIATE et annule la transaction si la réponse a un statut >= 400. | Un ACK tardif doit pouvoir rendre 409 tout en validant l’expiration; prévoir un résultat explicite « commit puis erreur » pour ce seul chemin. |
| backend/atlas_backend/v4_router.py:627-679 | Vérifie le consentement courant, sa version et l’alignement de la projection; relit le tombstone après l’opération. | Conserver ces contrôles et leur sérialisation pour les routes; ne pas faire dépendre la maintenance système d’un consentement Android encore GRANTED. |
| backend/atlas_backend/v4_router.py:1536-1631 | Pull strict, page 1–32, curseur (created_at, proposal_id), filtre les états actifs et expires_at > now; le corps de réponse est fixe. | Faire l’expiration avant la lecture de page, dans la transaction déjà ouverte. Ne modifier ni clés, ni pagination, ni ordre. |
| backend/atlas_backend/v4_router.py:1608-1612, 1756-1791 | Le curseur suivant est signé et lié au token Android et au device_ref. | L’expiration ne doit ni reconstruire ni avancer le curseur. |
| backend/atlas_backend/v4_router.py:1633-1700 | L’ACK lit l’état, expires_at et ack_id; un APPLIED/REJECTED tardif reçoit 409. EXPIRED et RECONCILED passent la garde temporelle sans exiger expires_at <= now. | Corriger la précédence d’ack_id et valider la date d’EXPIRED dans la transaction. |
| backend/atlas_backend/v4_projection_store.py:63-68, 747-816 | Hash canonique SHA-256; ack_id + hash identique est rejoué, corps différent conflit; l’ACK et l’état sont insérés/mis à jour dans une transaction. | Réutiliser le hash et la piste ACK existante; ne pas y inventer un ACK pour le serveur. Ajouter la transition serveur séparément. |
| backend/atlas_backend/v4_projection_store.py:781-839 | Verrou immédiat, transitions autorisées, validations d’état et de résultat; UNCERTAIN ne mène qu’à RECONCILED. | Conserver la règle de réconciliation; ne pas effacer silencieusement un résultat d’application incertain par un balayage TTL. |
| backend/atlas_backend/store.py:15-47 | Migrations V4 1–3; index agent_proposals_v4(state, expires_at); événements ACK append-only. | Ajouter une migration 4 additive et ses propres déclencheurs append-only. L’index existant sert au balayage. |
| backend/atlas_backend/store.py:69-109, 175-250 | Connexion SQLite timeout 5 s; transaction immédiate par défaut; migrations contrôlées par version et checksum, sauvegarde pré-migration et integrity_check. | Garder un seul écrivain logique et étendre le registre avec un checksum versionné. Fixer explicitement foreign_keys et busy_timeout par connexion si une FK est ajoutée. |
| backend/atlas_backend/main.py:276-335 | Le lifespan lance déjà un entretien périodique du cache via asyncio.to_thread, intercepte ses erreurs et annule sa tâche à l’arrêt. | Héberger le balayeur sur ce même cycle de vie, avec une tâche distincte et un arrêt borné. |
| backend/tests/test_v4_proposals.py:271-367 | Pagination, ACK, rejeu, conflit d’ID, ACK APPLIED tardif, puis ACK EXPIRED et rejeu exact. Les tests ne couvrent pas le refus EXPIRED prématuré ni le conflit après expiration. | Étendre les tests API et garder leurs assertions de réponse et d’événements. |
| backend/tests/test_v4_proposals.py:387-535, 629-895, 899-930 | Révocation/tombstone, sérialisation des lectures et effets, ordre ACK/révocation et SQLite busy. | Ajouter les courses expiration/révocation et expiration/ACK au même oracle de sérialisation. |
| backend/tests/test_v7_08_a0_protocol.py:365-550, 555-647 | Parcours A0, persistance/rejeu après redémarrage, refus sans mutation et refus d’ACK inconnu; le fichier fournit un FakeClock installé dans le store et le service. | Réutiliser l’horloge contrôlée pour la compatibilité du flux Android simulé. |
| backend/tests/test_v4_projection_store.py:284-328, 330-379 | Idempotence d’ACK, ACK_ID_CONFLICT, séquence et validation de preuve. | Tester aussi les invariants depuis la couche de stockage, sans dépendre du routeur. |
| backend/tests/test_v4_migrations.py:20-54, 100-135 | La version actuelle est 3; réouverture idempotente, migrations additives, sauvegarde, checksum et quarantaine. | Adapter les attentes de version à 4 et prouver fraîcheur, upgrade v3, réouverture et intégrité. |
| android/app/src/main/java/dev/atlas/coach/sync/agent/inbox/v4/InboxV4Repository.kt:94-152, 326-350 | mergePulled jette une proposition déjà échue sans créer d’ACK; expireDue crée un ACK EXPIRED durable seulement pour une entrée locale active. | Le Core ne peut pas demander à Android de produire un ACK pour une proposition jamais tirée. |
| android/app/src/main/java/dev/atlas/coach/sync/agent/inbox/v4/InboxV4Repository.kt:353-389 | L’outbox conserve les ACK non livrés; un succès HTTP les marque DELIVERED. | Une vraie réponse 2xx pour EXPIRED postérieur à l’expiration serveur libère l’outbox. |
| android/app/src/main/java/dev/atlas/coach/sync/v4/CoreV4SyncWorker.kt:378-410 | Le worker expire d’abord les entrées locales, rejoue l’outbox, puis tire les pages. | Le nouvel état Core termine le cas jamais tiré; l’ordre Android existant reste valable. |
| android/app/src/main/java/dev/atlas/coach/app/PlanController.kt:460-479, 517-522, 717-725 | Refuser après échéance émet EXPIRED; appliquer vérifie l’échéance avant mutation et n’applique pas une entrée échue. | Maintenir cette barrière locale; l’autorité terminale serveur est complémentaire. |

Le défaut historique donné pour cette mission est la proposition 2da9c5e2-57ed-4bac-8f5f-01eb0e4b780e, échue le 2026-11-05T19:28:58Z, jamais livrée et encore PENDING_ANDROID selon les preuves V7-09. Cette situation est rapportée comme contexte fourni, pas comme nouvelle lecture de base live.

## 3. Options examinées

| Option | Atouts | Limites |
|---|---|---|
| Expiration paresseuse seule | Répare une proposition lorsqu’un client fait un pull ou présente un ACK; une seule logique transactionnelle. | Sans requête, l’état reste indéfiniment non terminal. La réponse 409 tardive doit malgré tout committer la transition, alors que le décorateur actuel annule toutes les réponses d’erreur. |
| Balayeur seul | Termine les propositions sans dépendre d’Android; redémarrage et répétition sont naturels avec une transition conditionnelle. | La transition attend le prochain tick; le pull et l’ACK doivent rester protégés pendant cette fenêtre. La concurrence avec l’API et la durée de verrou doivent être bornées. |
| Combinaison recommandée | Le balayeur assure la progression indépendante; pull et ACK effectuent une réparation immédiate avec le même helper et les mêmes règles. | Trois points d’appel, mais un seul algorithme; nécessite un helper transactionnel réutilisable et des tests d’idempotence. |

### Points de déclenchement

| Entrée | Décision | Motif |
|---|---|---|
| Pull V4 | Oui, après auth, schéma et gardes de consentement, avant la sélection paginée. | Termine immédiatement les expirations visibles au client et garantit que la page filtre ensuite l’état terminal. |
| ACK V4 | Oui, après autorisation et contrôle de l’ack_id, avant d’accepter ou refuser le nouvel ACK. | Un ACK tardif provoque la transition système, puis retourne le résultat stable prévu. |
| Création de proposition / outil de chat | Non. | Le chat n’est pas un mécanisme de maintenance; ajouter une écriture à un tour sans rapport brouillerait l’autorité et n’assurerait pas le traitement hors trafic. La création conserve son TTL actuel. |
| Balayeur du lifespan FastAPI | Oui, source de progression principale. | Fonctionne même si aucun téléphone ne tire l’inbox et survit à un redémarrage par répétition idempotente. |

La correction distincte de la réponse STALE_DATA du chemin chat reste le ticket backend A2B, avec le même propriétaire de v4_router.py. Elle n’est pas conçue ici.

## 4. Schéma d’audit serveur et migration

Ajouter la migration V4 numéro 4, par exemple v4_proposal_expiration_audit. Elle crée uniquement le journal d’expiration, ses index éventuels et ses protections d’immutabilité; elle ne modifie pas le schéma HTTP ni les tables ACK Android.

Schéma logique exact proposé :

    CREATE TABLE agent_proposal_expiration_events_v4 (
        proposal_id TEXT PRIMARY KEY NOT NULL
            REFERENCES agent_proposals_v4(proposal_id),
        event_type TEXT NOT NULL
            CHECK (event_type = 'PROPOSAL_EXPIRED'),
        from_state TEXT NOT NULL
            CHECK (from_state IN ('PENDING_ANDROID','RECEIVED','AWAITING_USER')),
        to_state TEXT NOT NULL CHECK (to_state = 'EXPIRED'),
        expires_at TEXT NOT NULL,
        occurred_at TEXT NOT NULL,
        source TEXT NOT NULL
            CHECK (source IN ('PULL','ACK','SWEEPER','RECONCILIATION')),
        event_version INTEGER NOT NULL CHECK (event_version = 1)
    );
    CREATE TRIGGER agent_proposal_expiration_events_v4_no_update
        BEFORE UPDATE ON agent_proposal_expiration_events_v4
        BEGIN SELECT RAISE(ABORT, 'append-only proposal expiration event'); END;
    CREATE TRIGGER agent_proposal_expiration_events_v4_no_delete
        BEFORE DELETE ON agent_proposal_expiration_events_v4
        BEGIN SELECT RAISE(ABORT, 'append-only proposal expiration event'); END;

proposal_id est la clé d’unicité de l’événement terminal serveur; il n’y a ni ack_id ni event fabriqué dans agent_proposal_events_v4. occurred_at est l’heure UTC du Core, expires_at la valeur déjà stockée, et source explique quel chemin a constaté l’échéance. Aucun texte de justification, contenu de plan, bearer, consentement ou donnée utilisateur n’est copié dans ce journal.

Dans cette conception, « un seul événement terminal » désigne une seule transition terminale et sa seule ligne d’audit serveur. Un ACK Android effectivement reçu reste un reçu dans la table ACK séparée; il ne crée pas une deuxième transition terminale. Cette distinction est nécessaire pour garder le hash du corps et la détection des conflits ack_id tout en ne fabriquant aucun ACK.

L’insertion de la migration passe par le registre existant : version, nom, checksum, PRAGMA user_version, BEGIN IMMEDIATE, sauvegarde cohérente préalable et integrity_check. Le test de migration doit confirmer que la FK est appliquée sur les connexions SQLite; store.connect ne configure actuellement pas explicitement PRAGMA foreign_keys. Si la FK est retenue dans le DDL final, l’activer par connexion et couvrir les tables existantes avant déploiement.

## 5. Transition atomique et concurrence SQLite

Le helper unique prend une connexion existante ou ouvre store.transaction(immediate=True). Il capture now une fois, en UTC, et cherche au plus le batch configuré parmi les états PENDING_ANDROID, RECEIVED et AWAITING_USER dont expires_at <= now, ordonné par expires_at puis proposal_id.

Pour chaque candidat, dans la même transaction :

1. Lire l’état et expires_at après l’acquisition du verrou d’écriture.
2. Exécuter une mise à jour conditionnelle : UPDATE agent_proposals_v4 SET state='EXPIRED' WHERE proposal_id=? AND state IN ('PENDING_ANDROID','RECEIVED','AWAITING_USER') AND expires_at<=?.
3. Si rowcount vaut 1, insérer exactement une ligne dans agent_proposal_expiration_events_v4 avec l’ancien état, EXPIRED, expires_at, now et la source.
4. Si rowcount vaut 0, ne rien insérer; relire l’état et laisser le propriétaire gagnant de la course décider. Une violation d’unicité ou erreur d’insertion annule la transaction entière.

BEGIN IMMEDIATE sérialise le balayeur et les routes sur SQLite, y compris entre processus utilisant des connexions distinctes. L’UPDATE conditionnel protège contre un état devenu terminal; rowcount évite d’enregistrer un événement quand aucune transition n’a eu lieu; la PK proposal_id empêche un double audit même si le helper est rappelé. Pour une ACK normale, appliquer également un UPDATE conditionnel sur l’état source et exiger rowcount == 1.

La route pull s’exécute déjà dans le décorateur transactionnel; le helper utilise la connexion empruntée, sans ouvrir une transaction imbriquée. L’ACK suit le même ordre. Le décorateur annule aujourd’hui tout statut >= 400 : introduire un résultat explicite commit-on-error pour le cas où le helper expire la proposition puis le nouvel ACK APPLIED/REJECTED doit recevoir 409 PROPOSAL_EXPIRED. Les erreurs de validation et d’autorisation ordinaires conservent le rollback actuel.

Le store fixe aujourd’hui timeout=5 s et BEGIN IMMEDIATE par défaut. Garder le busy timeout API existant à 5 s et définir explicitement PRAGMA busy_timeout sur chaque connexion pour éviter une divergence entre les chemins. Pour le balayeur, ouvrir sa transaction avec un délai d’acquisition plus court, par exemple 250 ms, puis abandonner ce tick sur SQLITE_BUSY; le batch borné et le commit rapide évitent qu’il monopolise le writer. Un conflit SQLite sur une route reste un 503 UPSTREAM_UNAVAILABLE existant et réessayable. asyncio.to_thread empêche le travail SQLite de bloquer la boucle ASGI; SQLite reste un writer unique, donc une contention courte demeure possible et doit être mesurée.

UNCERTAIN est volontairement exclu de la transition automatique : le protocole actuel n’autorise que UNCERTAIN vers RECONCILED, car un effet local peut avoir eu lieu malgré un ACK perdu. Il n’est pas applicable de nouveau après échéance, mais sa preuve doit être réconciliée avant de déclarer le résultat. L’extension d’une transition UNCERTAIN vers EXPIRED serait une décision de protocole séparée, puisqu’elle pourrait confondre « aucune application » et « application au résultat incertain ».

## 6. Sémantique HTTP et Android

Les routes continuent à exiger l’authentification Android, le schéma V4, la capability déclarée par la requête, le consentement DAY_PLAN courant et la projection synchronisée. Le pull vérifie aussi que la capability est activée. La vérification du consentement précède les réponses de rejeu : une révocation n’est pas contournée par un ancien ack_id. Le balayeur, lui, ne lit que des métadonnées de cycle de vie et peut terminer une proposition échue même si DAY_PLAN est révoqué; il ne donne aucun droit d’accès au contenu ni d’application d’effet.

| Cas | Résultat stable proposé |
|---|---|
| now < expires_at; pull | Proposition active visible si elle respecte déjà les filtres et la page. |
| now < expires_at; ACK EXPIRED neuf | 409 PROPOSAL_NOT_EXPIRED, sans ACK, transition ou événement. L’outbox locale reste durable et peut être rejouée à l’échéance. |
| now == expires_at ou après; pull | Expiration système committée avant le SELECT; proposition absente; page, next_cursor et ordre inchangés. |
| ACK APPLIED ou REJECTED neuf, proposition encore active mais échue | Expirer et auditer dans la transaction, puis répondre 409 PROPOSAL_EXPIRED en committant explicitement cet effet. Aucun ACK Android n’est ajouté. |
| Même ack_id et même hash, même après expiration | Réponse 200, X-Atlas-Replayed: true; aucune nouvelle ligne ACK ni audit. L’expiration paresseuse peut quand même fermer une proposition encore active. |
| Même ack_id, corps différent, même après expiration | 409 ACK_ID_CONFLICT avant PROPOSAL_EXPIRED. Aucune réutilisation silencieuse d’identité. |
| ACK EXPIRED réel, à partir de l’échéance | Le Core garantit d’abord l’état EXPIRED. Il enregistre ce véritable reçu Android dans agent_proposal_events_v4 sans changer de nouveau l’état ni ajouter une seconde ligne d’audit serveur. Réponse 200; le rejeu exact est replayed et un corps différent sous le même ack_id reste ACK_ID_CONFLICT. |
| ACK RECONCILED | Autorisé seulement depuis UNCERTAIN, pour documenter l’issue d’un effet déjà tenté; ce n’est pas une nouvelle application. |
| Consentement révoqué, absent ou version désynchronisée | Les réponses existantes restent 403 CONSENT_REQUIRED, 409 STALE_VERSION ou 409 STALE_DATA; aucun reçu ACK n’est inséré par la route refusée. |
| Proposition déjà terminale APPLIED/REJECTED et ACK contradictoire | Conserver le conflit de transition existant; une expiration ultérieure ne réécrit pas un ACK terminal valide. |

PROPOSAL_NOT_EXPIRED est un nouveau code d’erreur 409, ajouté au mapping JSON existant, sans modifier le schéma de requête ou de réponse de succès. Le client V4 classe déjà les 409 comme conflit et conserve les ACK non livrés dans son outbox; un ACK EXPIRED après l’expiration système doit recevoir 2xx afin de pouvoir passer à DELIVERED. Aucun champ de succès, curseur, capability ou version Android n’est ajouté.

Le timestamp est comparé avec la règle inclusive expires_at <= now, identique au contrôle Android local. Les tests fixent la même horloge pour store, service, route et balayeur; ils ne dépendent pas de l’horloge murale de la machine.

## 7. Balayeur FastAPI

Lancer une tâche de maintenance distincte depuis main.py lifespan, sur le modèle existant de nettoyage du cache. Paramètres proposés :

- ATLAS_V4_PROPOSAL_EXPIRY_SWEEPER_ENABLED, activé par défaut, désactivable explicitement en tests;
- ATLAS_V4_PROPOSAL_EXPIRY_INTERVAL_SECONDS, défaut 30 s, avec validation et bornes raisonnables;
- ATLAS_V4_PROPOSAL_EXPIRY_BATCH_SIZE, défaut 100, limité par exemple à 500;
- délai de verrou propre au balayeur, défaut 250 ms.

À chaque tick, appeler le helper via asyncio.to_thread, traiter un seul batch dans un BEGIN IMMEDIATE, puis attendre l’intervalle. Ne pas lancer de tâche par proposition et ne pas parcourir la base en boucle serrée. Si le lot est plein, le tick suivant continue le rattrapage; le pull n’inclut jamais les lignes échues, même si le balayeur n’a pas encore atteint leur batch.

Le lifespan doit démarrer et arrêter la tâche proprement. À l’arrêt, signaler un Event, attendre la fin du batch borné, puis fermer; ne pas laisser un thread écrire après l’arrêt de l’application. Propager CancelledError. Intercepter les erreurs SQLite ou d’exécution d’un tick, produire un log borné sans donnée sensible, abandonner le lot transactionnel et continuer au tick suivant. Une base vide produit zéro transition et zéro ligne audit. Les tests peuvent désactiver la tâche et appeler le helper directement; un test lifespan séparé couvre démarrage, erreur récupérable et fermeture.

## 8. Réconciliation historique

Prévoir un outil opératoire contrôlé, par exemple backend/scripts/atlas_v4_expiration_reconcile.py, utilisant le même helper que le service. Le mode par défaut est dry-run et ne fait aucune écriture. Il rapporte des comptages par état et tranche d’ancienneté, le nombre de candidats, d’ACK déjà présents et d’anomalies (audit absent/doublé ou proposition déjà terminale). N’imprime pas le contenu des propositions ni de données de plan; les identifiants ne sont exposés que dans un export explicitement demandé.

Le mode apply exige un chemin de sauvegarde fourni. Avant mutation, créer une sauvegarde cohérente SQLite, vérifier son intégrité, son user_version, sa taille non nulle et son hash; refuser l’application si une vérification échoue. Appliquer par lots bornés dans les transactions décrites ci-dessus, puis réexécuter les contrôles d’intégrité et produire les comptages avant/après. Le mode rollback exige l’arrêt contrôlé du Core, une seconde sauvegarde de l’état courant, la restauration de la sauvegarde vérifiée et une vérification post-restauration. Le rapport de rollback doit signaler toute écriture postérieure à la sauvegarde qui serait perdue.

Aucune requête SQL manuelle n’est une procédure de réconciliation. Cette conception ne lance ni outil, ni sauvegarde, ni accès à une base live.

## 9. Matrice de tests proposée

Tous les tests utilisent un FakeClock contrôlé. Les lignes marquées « à ajouter » décrivent des oracles, elles ne signifient pas que les tests ont été écrits ou exécutés.

| Scénario / emplacement proposé | Oracle discriminant |
|---|---|
| Avant, exactement à et après expires_at — nouveau backend/tests/test_v4_proposal_expiration.py | Juste avant : état actif, zéro audit; à l’égalité : état EXPIRED et un audit; après : aucun second audit. |
| Pull et pagination — extension backend/tests/test_v4_proposals.py | Les propositions échues disparaissent avant lecture; les valides gardent le même ordre et le curseur signé continue sans doublon ni saut. L’enveloppe garde exactement ses clés. |
| Pull d’une proposition jamais livrée — test V7-08 A0 | Pull vide; état Core EXPIRED; une ligne d’audit serveur; zéro ACK Android, conformément à mergePulled qui n’invente rien. |
| ACK APPLIED et REJECTED tardifs — extension test_v4_proposals.py | 409 PROPOSAL_EXPIRED, aucun effet ni ACK Android; état et une ligne d’audit sont committés malgré la réponse 409. |
| ACK EXPIRED prématuré | 409 PROPOSAL_NOT_EXPIRED; état actif, table d’audit et événements ACK inchangés; l’outbox reste non livrée. |
| ACK EXPIRED après balayeur | 200; état EXPIRED inchangé; un audit serveur total; un ACK Android seulement si le corps réel a été reçu; rejeu exact 200 replayed; pas de doublon. |
| Rejeu et conflit après expiration | Même ack_id/hash retourne replayed; même ack_id/corps modifié retourne 409 ACK_ID_CONFLICT avant PROPOSAL_EXPIRED. |
| Course APPLY vs balayeur, puis REJECT vs balayeur — test_v4_proposals.py | BEGIN IMMEDIATE impose un ordre total : soit ACK terminal avant échéance et aucun audit d’expiration, soit EXPIRED gagne et l’ACK reçoit PROPOSAL_EXPIRED; jamais deux transitions. |
| Course ACK EXPIRED et balayeur | Un seul état terminal et une seule ligne d’audit; le reçu Android réel, s’il arrive, est idempotent et ne bloque pas l’outbox. |
| Reprise après redémarrage et balayeur répété — nouveau test_v4_expiration_sweeper.py | Les propositions échues sont traitées après redémarrage; une deuxième exécution fait rowcount 0 et ne crée rien. |
| Transaction UPDATE + insert audit | Faire échouer l’insert audit dans un test isolé; vérifier rollback de l’état à sa valeur initiale. Tester aussi qu’un UPDATE à rowcount 0 ne crée pas d’audit. |
| SQLite busy_timeout | Maintenir un writer concurrent; le balayeur respecte son délai court, ne laisse aucun état partiel et réessaie au tick suivant; le chemin API conserve son 503 contrôlé. |
| Batch et base vide | Le helper ne traite jamais plus de la limite par tick; une base sans proposition n’écrit ni proposition ni événement et n’empêche pas le lifespan de s’arrêter. |
| Migration — extension backend/tests/test_v4_migrations.py | Base fraîche et base version 3 atteignent version 4; réouverture stable; checksum, triggers append-only, foreign_key_check et integrity_check conformes; les tables v1-v3 sont préservées. |
| Consentement/révocation — extension test_v4_proposals.py | Routes ACK et pull refusées tant que DAY_PLAN est révoqué; le balayeur expire uniquement les métadonnées; après regrant, un ACK EXPIRED retardé reçoit 2xx sans seconde transition. |
| UNCERTAIN | La proposition échue reste non applicable et non visible au pull, mais aucun audit EXPIRED trompeur n’est créé; seul RECONCILED peut établir son issue. |
| Lifespan | Avec balayeur désactivé, aucune tâche n’est lancée; activé, une erreur d’un tick est absorbée, le tick suivant fonctionne et l’arrêt attend la fin d’un lot borné. |

Les invariants 1 à 5 sont couverts par les tests d’horloge, de transition atomique, de concurrence et de répétition. Les droits/consentements sont couverts par les tests de révocation; les garanties de non-fabrication d’ACK sont couvertes par le scénario jamais livré et par le comptage distinct des deux tables.

## 10. Risques, décisions et limites

### Risques

| Niveau | Risque | Mesure |
|---|---|---|
| P0 | Aucun défaut P0 identifié dans cette conception statique. | Revue d’implémentation et tests avant intégration. |
| P1 | Une réponse HTTP 409 provoque actuellement le rollback du décorateur. Sans chemin commit-on-error dédié, la transition paresseuse serait annulée. | Test API vérifiant simultanément le code 409, l’état EXPIRED et l’unique audit. |
| P1 | SQLite n’autorise qu’un writer à la fois; un balayage trop long ferait attendre les routes. | Batch 100, transaction courte, timeout du balayeur court, traitement en thread et 503 existant sur contention API. |
| P1 | UNCERTAIN peut représenter une application déjà effectuée dont la réponse s’est perdue. | Le laisser au mécanisme RECONCILED; décision distincte requise avant d’autoriser sa transition automatique vers EXPIRED. |
| P2 | Le timestamp créé a une précision à la seconde; frontière strictement inclusive et horloge contrôlée indispensables. | Réutiliser le format UTC existant et couvrir avant/à/après. |
| P2 | Restaurer une sauvegarde après des écritures ultérieures peut perdre ces écritures. | Arrêter le service, faire une sauvegarde de l’état courant et documenter la fenêtre de rollback. |
| P2 | Un nouveau code PROPOSAL_NOT_EXPIRED devra rester une erreur retryable du point de vue de l’outbox locale. | Vérifier que le client garde l’ACK durable et que le nouvel essai après échéance reçoit 2xx. |
| P2 | Les tests actuels attendent la version de schéma 3 et ne couvrent pas le lifespan d’expiration. | Mise à jour coordonnée des attentes et test de cycle de vie, sans activer un balayeur non déterministe dans les tests existants. |

### Décisions retenues

- Expiration système indépendante de la révocation : elle ferme une autorisation temporelle et ne lit ni ne modifie un plan Android.
- Le TTL de production reste 24 h; aucun changement de champ ou de page Android.
- EXPIRED est refusé avant expires_at avec 409 PROPOSAL_NOT_EXPIRED; à l’échéance exacte, la condition est vraie.
- La précédence des ACK est : auth/schema/consentement, identité ack_id/hash, heure/état, transition. Un conflit de corps garde donc toujours le code ACK_ID_CONFLICT, y compris après échéance.
- L’audit serveur et les reçus Android sont deux journaux séparés. Il n’y a jamais de reçu client inventé; un vrai ACK EXPIRED tardif est accepté comme reçu sans second événement de transition serveur.
- L’outil de réconciliation est en dry-run par défaut et exige une sauvegarde vérifiée pour apply.

### Limites de preuve

Audit statique au BASE_SHA demandé. Pré-vol effectué avec git status --short --branch, git rev-parse HEAD, git worktree list, git --version, java -version, python --version et node --version. Environnement relevé : Git 2.43.0, JDK 21.0.12.1, Python 3.12.3 et Node 24.18.0. Profil effectif vérifié dans les métadonnées Paseo comme codex / gpt-6-luna / xhigh. Les sources et tests cités ont été lus; aucune suite n’a été exécutée (NOT_RUN, tâche de conception). Aucun service, base SQLite live, secret, ADB, Pixel ou émulateur n’a été consulté. La description du cas 2da9c5e2… provient des documents fournis pour la mission. Cette conception ne remplace pas l’implémentation, la validation des migrations sur données synthétiques, la revue indépendante ni la preuve Android.
