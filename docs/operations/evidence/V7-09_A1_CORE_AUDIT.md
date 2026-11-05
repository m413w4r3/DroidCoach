# ATLAS V7-09 — A1 audit du cycle Core A0

**Rôle :** A1, audit indépendant en lecture seule
**BASE_SHA :** `efcfdcd0936ab2791f8ea14c151247257b05d19b`
**Périmètre :** propositions V4 A0, expiration, pull, ACK, consentement, transitions et idempotence.
**Contraintes appliquées :** aucun ADB, aucun redémarrage, aucune lecture/écriture SQLite live, aucune configuration, aucun push, aucune exécution de test. Seul ce document est modifié.

## Verdict exécutif

- **PROVEN —** V4 fixe `expires_at` à la création à `now + 24 h` par défaut. Le routeur de production ne transmet pas d’override.
- **PROVEN —** au pull, le backend filtre les propositions dont `expires_at <= now`, sans mise à jour d’état. Un ACK `APPLIED` ou `REJECTED` neuf après expiration reçoit `409 PROPOSAL_EXPIRED`.
- **PROVEN —** l’état ne devient `EXPIRED` en Core qu’après réception et insertion d’un ACK `EXPIRED / PROPOSAL_EXPIRED` valide. Le chemin V4 ne comporte ni expiration serveur paresseuse, ni balayeur périodique.
- **INFERRED —** d’après les faits live fournis par le parent, `2da9c5e2…` a dépassé son échéance (`2026-11-05T19:28:58Z`) d’environ 31 minutes à l’heure de référence (~20:00Z). Elle n’a jamais été tirée dans l’application. Le pull actuel l’omettra et ne changera pas sa ligne Core : elle restera `PENDING_ANDROID` sans événement.
- **NO-GO pour clôturer REJECT sur ce cas avec le flux natif actuel.** Ne pas déclarer la proposition `REJECTED`, ne pas tenter un ACK `REJECTED` tardif, et ne pas fabriquer d’ACK `EXPIRED`. La disposition honnête est : **expirée par horloge, Core toujours `PENDING_ANDROID`, scénario REJECT NOT_RUN / clôture Core bloquée par défaut de cycle de vie**.

## 1. Fixation de `expires_at`

Le constructeur de `V4ToolService` définit `proposal_ttl: timedelta = timedelta(hours=24)` (`backend/atlas_backend/v4_tool_service.py:84-101`). `create_v4_router` construit ce service sans passer `proposal_ttl` (`backend/atlas_backend/v4_router.py:552-557`). Pour une proposition V4 acceptée, le service calcule `expires_at = _format_time(now + self.proposal_ttl)` puis la sérialise dans la proposition comme `PENDING_ANDROID` (`backend/atlas_backend/v4_tool_service.py:463-485`). `_now` normalise l’horloge en UTC et `_format_time` sérialise en UTC avec suffixe `Z` (`backend/atlas_backend/v4_tool_service.py:881-902`).

**Conclusion :** TTL de production V4 = 24 heures, sans override d’environnement V4 trouvé. Le paramètre du constructeur peut être injecté par un appelant de code, mais le routeur de production utilise le défaut. À ne pas confondre avec V2 : `main.py:_proposal_ttl()` lit `ATLAS_PROPOSAL_TTL_HOURS` et borne cette valeur à 1–24 h (`backend/atlas_backend/main.py:554-559`); c’est le chemin V2, pas la création A0 V4.

La création exige aussi un consentement courant `DAY_PLAN` GRANTED, un snapshot de même version de consentement et de moins de cinq minutes, et une `base_version` égale à la version observée. Un écart de plan renvoie un résultat `STALE_VERSION` sans créer de proposition (`backend/atlas_backend/v4_tool_service.py:381-435`; fenêtre de fraîcheur : lignes 41-48 et 755-769). La proposition créée est explicitement `A0_USER_REVIEW` et `NONE_AT_CORE` (`v4_tool_service.py:475-485`).

## 2. Expiration, pull, ACK et rejeu

| Opération après expiration | Comportement V4 observé dans le code |
|---|---|
| Pull | Requête SQL limitée aux états actifs **et** à `expires_at > now`; aucune instruction `UPDATE` de la proposition. Elle est omise de la page. `backend/atlas_backend/v4_router.py:1588-1596`. |
| Nouvel ACK `APPLIED` | La route renvoie `409`, code `PROPOSAL_EXPIRED`, avant l’insertion d’événement. L’état Core ne change pas. `v4_router.py:1673-1695`; mapping HTTP : lignes 288-293. |
| Nouvel ACK `REJECTED` | Même résultat : `409 PROPOSAL_EXPIRED`; un refus tardif ne clôt pas la proposition. Le test existant couvre directement le cas tardif `APPLIED`, pas `REJECTED` (`backend/tests/test_v4_proposals.py:342-355`). |
| ACK `EXPIRED / PROPOSAL_EXPIRED` | Accepté si schéma, consentement et transition sont valides. L’insertion de l’événement puis l’`UPDATE agent_proposals_v4 SET state=?` fixent l’état Core à `EXPIRED` (`v4_projection_store.py:802-815`, validation : lignes 819-839). L’ACK sans application ne peut porter aucune preuve. |
| Rejeu exact d’un ACK déjà stocké | Le même `ack_id` et le même hash du corps sont acceptés sans nouvel événement; la réponse porte `X-Atlas-Replayed: true` (`v4_router.py:1676-1680, 1695-1700`; `v4_projection_store.py:768-780`). |
| Même `ack_id`, corps différent | En état non expiré, `409 ACK_ID_CONFLICT`. Après expiration, pour un corps différent qui n’est ni `EXPIRED` ni `RECONCILED`, le routeur renvoie d’abord `409 PROPOSAL_EXPIRED`, avant la comparaison transactionnelle qui aurait renvoyé `ACK_ID_CONFLICT`. |

Il n’existe pas de transition automatique déclenchée par le temps, par un pull, par un ACK tardif refusé, ou par un rejeu exact. La colonne `state` reste celle déjà stockée jusqu’à un ACK accepté; le schéma SQLite indexe `state, expires_at` mais ne définit pas de trigger d’expiration (`backend/atlas_backend/store.py:25-33`). Le chemin V2 a un `UPDATE ... state='expired'` dans son inbox (`store.py:472-475`), mais cela ne concerne pas la table `agent_proposals_v4` ni A0.

**Conséquence pour `2da9c5e2…` :** les deux ACK `APPLIED` et `REJECTED` tardifs seraient invalides (sous réserve que le consentement et le snapshot passent les contrôles exécutés avant le contrôle d’expiration). Le seul événement terminal adapté est `EXPIRED / PROPOSAL_EXPIRED`; aucun appel serveur actuellement accessible au client ne le génère pour une proposition absente de l’inbox locale.

## 3. Transitions, versions, consentements et preuve

`append_proposal_ack` valide le couple état/code, vérifie la séquence et la transition depuis l’état Core, insère un événement append-only puis met à jour l’état de la proposition. Transitions A0 utiles :

- `PENDING_ANDROID -> APPLIED`, `REJECTED`, `EXPIRED`, ainsi que `STALE_VERSION`, `VALIDATION_REJECTED` ou `UNCERTAIN`.
- `UNCERTAIN -> RECONCILED` seulement.
- La table de transitions énumère aussi `RECEIVED` et `AWAITING_USER` comme états source/destination, mais le validateur ACK n’accepte pas ces deux états comme ACK terminaux (`v4_projection_store.py:794-801, 819-830`). Côté Android, `RECEIVED` et `AWAITING_USER` sont des états locaux de revue.

**Version de plan :** à la création, le backend refuse fonctionnellement une proposition fondée sur une version différente de son snapshot frais (`v4_tool_service.py:400-435`). À l’application, Android vérifie que le plan chargé correspond au `plan_id`, qu’il est approuvé, que sa version égale `base_version`, et que l’échéance n’est pas atteinte; une version divergente génère `STALE_VERSION` au lieu d’appliquer (`android/app/src/main/java/dev/atlas/coach/app/PlanController.kt:517-543`). La preuve Android lie le plan et exige `appliedPlanVersion > baseVersion` (`InboxV4Repository.kt:424-427`).

**Limite serveur :** le validateur Core exige pour `APPLIED` les cinq champs `plan_id`, `applied_plan_version`, `revision_id`, `scope_hash`, `effect_ids`, puis en vérifie la forme (`backend/atlas_backend/v4_projection_store.py:832-882`). Il n’effectue pas de comparaison avec le `plan_id` / `base_version` de la proposition stockée ni avec un snapshot courant : la lecture de la proposition pour append ne récupère que `state` (`v4_projection_store.py:781-801`). La liaison sémantique de preuve est donc faite par Android; le Core assure présence et forme. C’est une limite de validation du reçu, pas une preuve qu’un reçu forgé ait été envoyé.

Pour `REJECTED`, le code exige `USER_REJECTED` et interdit tous les champs de preuve d’application, dont `applied_plan_version` (`v4_projection_store.py:821-839`). Android construit aussi les ACK `REJECTED` sans preuve (`InboxV4Models.kt:282-311`).

**Consentement :** ces routes A0 dépendent de `DAY_PLAN`, pas de `ACTIVITY_STATUS`. Pull et ACK exigent le consentement courant GRANTED et la même version que la projection plan synchronisée; version de consentement ancienne → `409 STALE_VERSION`, version future/non synchronisée ou consentement absent/révoqué → `403 CONSENT_REQUIRED`, projection absente/divergente → `409 STALE_DATA` (`v4_router.py:627-679`, pull : lignes 1569-1577 et 1621-1629, ACK : 1654-1662 et 1684-1693). La route sérialise l’effet dans une transaction et refait les gardes pour ordonner les courses avec révocation (`v4_router.py:485-510`). Les tests consentement/révocation sont `test_consent_revocation_tombstone_blocks_reads_proposals_pull_and_ack_until_regrant` (`backend/tests/test_v4_proposals.py:387-535`), `test_day_plan_consent_absent_or_revoked_blocks_chat_pull_and_ack` (`test_v7_08_a0_protocol.py:555-627`) et les tests de course de révocation (`test_v4_proposals.py:693-816, 819-895`).

Le contexte live fourni indique `DAY_PLAN v4 GRANTED` et `ACTIVITY_STATUS v3 REVOKED`; cette révocation d’activité n’est pas le consentement utilisé par l’ACK A0. Si la projection DAY_PLAN n’est pas elle-même synchronisée en v4, `STALE_DATA`/409 peut toutefois précéder le résultat `PROPOSAL_EXPIRED`.

## 4. Contrat exact du pull et état attendu de l’inbox Android

La requête POST `/v4/agent/proposals/pull` impose exactement les clés `contract_version`, `schema_version`, `request_id`, `capability`, `consent_version`, `page_size`, `cursor`; versions 4, capability `proposal.ack`, UUID de requête, page de 1 à 32 et curseur chaîne ou `null` (`backend/atlas_backend/v4_router.py:1536-1564`). Android envoie une taille de 32 (`InboxV4HttpTransport.kt:104-117, 343-355`).

La réponse contient exactement `contract_version`, `schema_version`, `request_id`, `capability`, `proposals`, `next_cursor` (`v4_router.py:1597-1631`; validateur Android : `InboxV4HttpTransport.kt:118-141, 353-355`). Le curseur est lié au couple `(created_at, proposal_id)`, signé HMAC à partir du bearer Android et lié au `device_ref` (`v4_router.py:1608-1612, 1756-1791`). Le backend lit `page_size + 1`, ordonne par `(created_at, proposal_id)` et ne renvoie comme propositions que les états `PENDING_ANDROID`, `RECEIVED`, `AWAITING_USER`, `UNCERTAIN` non expirés (`v4_router.py:1588-1607`). Pas de champ `truncated`; la présence de `next_cursor` indique une page suivante. Le commentaire du test A0 confirme qu’aucun schéma d’enveloppe pull n’est publié dans le contrat à ce jour (`backend/tests/test_v7_08_a0_protocol.py:264-275`).

Pour une proposition déjà tirée avant échéance, Android peut créer et persister un ACK `EXPIRED` pour chaque entrée locale active échue (`InboxV4Repository.kt:326-350`). Le worker appelle `inbox.expireDue(consentVersion)` au début du cycle, puis rejoue l’outbox durable avant le pull (`CoreV4SyncWorker.kt:378-402`). L’action UI Refuser vérifie aussi l’horloge : après échéance elle génère `EXPIRED`, sinon `REJECTED` (`PlanController.kt:460-479`).

Pour une proposition que le backend n’a jamais envoyée, ce mécanisme ne s’applique pas. Le pull Core l’exclut; si un serveur renvoyait quand même la ligne active `PENDING_ANDROID` déjà échue, `mergePulled` la compterait comme expirée puis la jetterait sans créer d’entrée ni d’ACK (`InboxV4Repository.kt:94-106, 135-138`). Une proposition renvoyée directement avec un état terminal serait rejetée par l’invariant de page non terminale (`InboxV4Repository.kt:94-99`). **L’inbox Android doit donc recevoir aujourd’hui zéro proposition `2da9…`; l’app n’a pas d’objet local depuis lequel produire son ACK d’expiration.** La route HTTP ACK peut enregistrer un ACK `EXPIRED` fourni par un client, mais aucun appel natif ne le crée pour une proposition jamais reçue; aucun appel de pull ne transitionne sa ligne Core.

## 5. Idempotence de REJECT : preuves existantes et trous

Le hash est SHA-256 du JSON canonique à clés triées (`backend/atlas_backend/v4_projection_store.py:63-68`). `ack_id` est la clé primaire des événements, chaque événement conserve `body_hash`, et `(proposal_id, sequence)` est unique (`backend/atlas_backend/store.py:27-33`). À l’append, même ID + même hash donne `replayed=true` sans insert; même ID + hash différent lève `AckIdConflict`, mappé vers HTTP 409 `ACK_ID_CONFLICT` (`v4_projection_store.py:768-780`; mapping : `v4_router.py:448-467`).

**Tests qui prouvent le chemin demandé :**

- `backend/tests/test_v7_08_a0_protocol.py::test_a0_chat_pull_apply_ack_restart_and_reject_no_mutation` crée un ACK `REJECTED`, redémarre l’application, rejoue le même corps et vérifie `200`, `X-Atlas-Replayed: true`, plan inchangé et exactement un événement par proposition (`lignes 517-550`). C’est la preuve existante directe pour même `ack_id` + même corps → pas de second événement.
- `backend/tests/test_v4_proposals.py::test_inbox_cursor_pagination_ack_states_and_replay_conflicts` prouve ACK `REJECTED` accepté et prouve `ACK_ID_CONFLICT` sur corps modifié avec le même `ack_id` pour un ACK `APPLIED` (`lignes 312-329`). Le conflit s’appuie sur la même fonction générique de hash/append, mais ce n’est pas un test spécifique REJECT.
- `test_inbox_cursor_pagination_ack_states_and_replay_conflicts` couvre aussi le cas d’un ACK `APPLIED` après expiration → `409 PROPOSAL_EXPIRED`, puis un ACK `EXPIRED` accepté et rejoué sans nouveau traitement (`lignes 342-358`).

**Tests manquants identifiés :**

1. même `ack_id` + corps REJECTED modifié (par exemple `occurred_at`) → assertion explicite `409 ACK_ID_CONFLICT`, événement toujours unique;
2. ACK REJECTED neuf après échéance → `409 PROPOSAL_EXPIRED`, aucun événement, état Core inchangé;
3. proposition V4 expirée mais jamais tirée → pull vide **et** état restant `PENDING_ANDROID` (test de régression du défaut de clôture);
4. `EXPIRED` avant `expires_at` refusé (actuellement la garde route exempte inconditionnellement les états `EXPIRED` et `RECONCILED` : `v4_router.py:1680-1683`; le store n’a pas de contrôle temporel);
5. TTL 24 h V4 et absence d’override de production;
6. preuve APPLIED dont le plan/version ne correspond pas à la proposition stockée;
7. corps REJECTED portant `applied_plan_version` rejeté sans insertion.

Le test `test_proposal_ttl_expiry_and_ack_idempotence` (`backend/tests/test_agent_v2.py:615-637`) est V2 : il ne prouve ni TTL ni transition d’expiration de `agent_proposals_v4`.

## Risques et clôture administrative de `2da9c5e2…`

| Gravité | Constat | Effet |
|---|---|---|
| **P1** | Proposition Core expirée jamais tirée : pull l’omet; aucune expiration serveur; ACK tardif REJECTED refusé. | REJECT physique et clôture terminale Core non réalisables par le flux actuel; l’état Core peut rester `PENDING_ANDROID` indéfiniment. |
| **P2** | La route accepte l’état `EXPIRED` sans exiger que `expires_at <= now`. | Un client autorisé peut clôturer prématurément une proposition encore valide. |
| **P2** | Après expiration, un corps différent sous un `ack_id` déjà utilisé est intercepté par la garde `PROPOSAL_EXPIRED` avant la détection `ACK_ID_CONFLICT`. | Code de conflit dépend de l’expiration; contrat d’idempotence moins précis sur les rejeux tardifs. |
| **P2** | Le Core vérifie la forme de la preuve APPLIED mais ne la relie pas au plan/version de la proposition stockée. | L’intégrité sémantique dépend du client Android; couverture serveur insuffisante. |

**Action recommandée pour le registre V7-09 :** clôturer le volet temporel en notant explicitement `2da9… = EXPIRED_BY_CLOCK / Core state remains PENDING_ANDROID / aucun ACK`, et laisser le résultat REJECT à `NOT_RUN` (pas `REJECTED`). Marquer la procédure V7-08_HANDOFF étapes 4–7 comme périmée pour cette proposition : le pull ne la tirera plus (`docs/operations/V7-08_HANDOFF.md:78-111`). Garder la ligne en l’état jusqu’à une correction ou une procédure administrative d’expiration supportée, qui devra enregistrer l’événement sans inventer un reçu Android. Ne pas utiliser de SQL manuel, ne pas antidater, ne pas produire d’ACK synthétique.

## Méthode et limites de preuve

Audit statique au HEAD initial `efcfdcd0936ab2791f8ea14c151247257b05d19b`; `.codegraph/` absent. Les fichiers README, directives, workflow, contrat, docs V7-08, code backend V4 et tests pertinents ont été lus. Les tests sont cités depuis leur source, **pas exécutés**. La base live et l’appareil n’ont pas été relus : l’état `2da9…`, les consentements, l’ACK APPLIED `e867…` et l’heure de référence sont les faits fournis par le parent. Aucune conclusion ne prétend à une nouvelle observation Pixel ni SQLite.
