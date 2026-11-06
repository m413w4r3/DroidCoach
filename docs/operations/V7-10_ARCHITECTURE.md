# V7-10 — Architecture V4, expiration et fiabilité Android

**État documentaire au 6 novembre 2026.** Base examinée : `d2f98265b1c4ca42fafc481d7b121f5d4f2bed01`, branche d’intégration `feat/atlas-v7-10-security-reliability`. Ce document décrit le code présent dans cette base et les limites de preuve des tickets A1, A2 et A3. Il ne vaut ni autorisation de migration ni approbation de déploiement.

## 1. But, composants et frontières de confiance

V7-10 remplace l’identité V4 dérivée d’un bearer partagé par des principaux Core stables, cloisonne les propositions par propriétaire, ferme le cycle temporel des propositions et améliore la synchronisation et le chat Android. La migration conserve une période de compatibilité explicite, puis permet de couper le credential `LEGACY` sans changer le contrat HTTP V4.

| Composant | Responsabilité V7-10 | Frontière de confiance |
|---|---|---|
| **Core** (`backend/atlas_backend`) | Registre des principaux, credentials et grants; authentification V4; consentements et projections; propriété des propositions; audit d’expiration; outils du chat. | Résout `principal_id`, `device_ref`, audience et capabilities depuis le credential et, pour Relay, l’assertion vérifiée. N’accepte jamais l’identité du JSON client comme autorité. SQLite est la source d’état du registre et des propositions. |
| **Relay** (`integrations/relay/pc`) | Authentifie l’appareil Relay, transmet les opérations autorisées et choisit le credential Core de l’ID Relay présent dans la session authentifiée. | La session fournit l’ID Relay; le payload ne peut pas le choisir. La map locale est un index de provisionnement, pas une autorité serveur. Le connecteur signe l’appel Core V4 et n’envoie pas son token de service comme bearer Android. |
| **Android** (`android/app`) | Stockage local, consentements, outbox/ACK, synchronisation Core V4, inbox de propositions et opt-in V4 du chat. | En mode Relay, envoie au Relay une opération fermée, sans bearer Core, URL, méthode ni en-tête HTTP arbitraire. Le Core reste l’autorité des grants, du consentement courant, de l’identité et de l’expiration. |
| **Gateway** (adaptateur Core → OpenClaw) | Reçoit du Core les appels d’outils du chat après authentification de l’appelant Android. | Son token et son identité TOOL sont distincts des credentials Android, Relay et du token de service. Le `device_ref` du principal appelant est propagé par le Core; le modèle ne peut pas le choisir dans les arguments d’outil. |

Les frontières de transport sont : Android → session Relay authentifiée → connecteur PC → API Core V4; ou Android → API Core directe avec un credential `DIRECT`. Les routes internes `/internal/v4/*` sont réservées au principal `TOOL` et au chemin loopback prévu. Les anciennes routes V1–V3 gardent leur authentification historique par `ATLAS_TOKEN`; V7-10 ne prétend pas supprimer cet usage.

**Hors périmètre :** déploiement ou migration d’une base live, rotation/révocation réelle, appairage réel, changement d’environnement Core/Relay/Gateway, installation APK, QA AVD ou Pixel, pilote quotidien, service d’interception Android, `AccessibilityService`, Device Owner/root, installation silencieuse et mutation automatique du plan par le modèle. Ces opérations ne sont pas attestées par les tickets d’implémentation locaux.

## 2. Identité, credentials, capabilities et modes

### Principaux et audiences

- Un principal a un `principal_id` opaque et un `device_ref` unique, stable et attribué côté Core. Le `device_ref` n’est pas dérivé du bearer.
- SQLite ne conserve que le SHA-256 du credential ASCII; le bearer n’est pas stocké dans le registre. Une rotation crée un credential distinct et conserve l’identité du principal.
- Les audiences admises sont `DIRECT`, `RELAY`, `TOOL` et `LEGACY`. Le token Relay `device-token-v1`, le bearer Core, le token de service du connecteur, le token TOOL et le token Gateway sont des secrets distincts.
- Les principaux, credentials et bindings ont les états `ACTIVE` ou `REVOKED`. Les triggers et opérations de registre rendent la révocation monotone : un import ou une rotation ne réactive pas l’ancien identifiant. Le cutover doit créer une nouvelle paire de credentials si l’ancienne est révoquée.

### Capabilities allowlistées et grants

L’allowlist est définie dans `backend/atlas_backend/v4_auth.py` et vérifiée dans le registre et le routeur. Elle couvre les lectures `day_plan.read`, `activity_status.read`, `usage_summary.read`, `goal_progress.read`, `training_plan.read`, le chat `coach.chat`, la proposition `plan_revision.propose`, les écritures de projections, `consent.state.write` et `proposal.ack`.

Une opération V4 passe seulement si **le flag global** et **le grant du principal** sont tous deux actifs. Le registre des grants est `principal_capabilities_v4`; les champs du payload ne peuvent pas ajouter de droits. Avec `ATLAS_V4_CAPABILITIES_JSON` absent, les lectures et `coach.chat` valent `false`, tandis que les capabilities de mutation valent `true` pour compatibilité. Un flag explicitement `false` coupe l’opération avant l’effet. Les noms hors allowlist ou les valeurs non booléennes font échouer la configuration. `tool_catalog.read` est une métadonnée TOOL authentifiée spéciale, non accordable; cette exception ne s’étend pas aux autres routes internes.

### Modes d’authentification

`ATLAS_V4_AUTH_MODE` accepte seulement `legacy`, `dual` ou `principal`; sa valeur par défaut dans le code est `legacy`.

| Mode | Résolution V4 | Compatibilité et arrêt sûr |
|---|---|---|
| `legacy` | Compare au bearer historique; conserve la dérivation historique de l’identité. | Mode transitoire et rollback explicite. Les propositions sans propriétaire restent visibles dans ce mode. Le connecteur ne prend son fallback `ATLAS_V4_ANDROID_TOKEN_FILE` que si la map est absente et que le binding n’est pas exigé. |
| `dual` | Résout les credentials actifs provisionnés et leurs audiences. | Le bearer historique n’est accepté que s’il a été importé comme credential `LEGACY`. Le connecteur exige la map, le binding et une assertion HMAC. Les lignes de proposition `device_ref IS NULL` sont accessibles uniquement au principal `LEGACY` actif désigné par `ATLAS_V4_LEGACY_PRINCIPAL_ID`. |
| `principal` | Accepte les credentials actifs `DIRECT` ou `RELAY`, liés à un principal actif. | Refuse l’audience `LEGACY`, exige `ATLAS_V4_CURSOR_SECRET` d’au moins 32 caractères et la migration SQLite v5. Le connecteur exige map et assertion. |

Une valeur de mode inconnue, un schéma requis absent, une map invalide, une audience incorrecte, un binding révoqué/inconnu ou un credential/principal révoqué ne déclenche aucun fallback silencieux. En `dual`, `ATLAS_V4_LEGACY_PRINCIPAL_ID` absent ferme les routes de proposition du principal LEGACY; une valeur qui ne désigne pas un principal `LEGACY` actif échoue. En `principal`, le bearer `LEGACY` est refusé même s’il figure encore dans SQLite.

## 3. Relay : map, binding et assertion signée

Le fichier privé pointé par `ATLAS_CORE_PRINCIPAL_MAP_FILE` associe chaque ID Relay à `{ principal_id, credential_file }`. `principal_id` décrit le provisionnement local; Core ne le prend pas comme identité d’autorité. Le connecteur sélectionne le credential avec l’ID provenant de la session Relay authentifiée. Core vérifie l’assertion, le binding actif de `relay_principal_bindings_v4`, l’audience `RELAY` et la correspondance entre le principal du credential et le binding. Un choix local incohérent (par exemple session A avec credential B) échoue avant l’effet.

Pour chaque appel V4 relayé, le connecteur signe en HMAC-SHA256 la chaîne canonique composée de la version, méthode HTTP, chemin exact, timestamp en millisecondes, nonce, `device_id` Relay et SHA-256 du corps exact. L’assertion transporte notamment `key_id`; Core accepte une fenêtre de ±120 secondes et rejette un nonce rejoué. Le cache de nonces est borné et en mémoire par processus; plusieurs workers Core nécessitent un routage évitant les rejeux inter-workers ou une protection partagée. La rotation expose au plus deux clés actives afin de permettre un chevauchement contrôlé.

En production :

- `ATLAS_V4_RELAY_ASSERTION_SECRET_FILE` côté connecteur et `ATLAS_V4_RELAY_ASSERTION_KEYS_FILE` côté Core doivent désigner des fichiers privés, absolus, réguliers, possédés par l’utilisateur du processus, mode `0600`, sans symlink et lus sans suivre un lien symbolique.
- Le connecteur remonte depuis son module jusqu’au candidat ATLAS le plus externe qui contient `integrations/relay/pc` et un marqueur `.git` (dossier ou fichier worktree) ou `AGENTS.md`; il refuse un secret situé dans cette racine. Un `.git` parent sans package ATLAS ne devient pas la frontière. Dans une installation standalone sans candidat ATLAS, seule cette frontière de dépôt n’est pas applicable; les vérifications de chemin absolu, propriétaire, mode, type de fichier et symlink restent actives.
- `ATLAS_V4_RELAY_ASSERTION_SECRET` est accepté uniquement pour `NODE_ENV=test`; le déclarer avec la source fichier est refusé. `ATLAS_V4_RELAY_ASSERTION_KEYS_JSON` côté Core est réservé aux tests pytest. Les contrôles `check-config` et `check-secret` n’affichent pas la valeur du secret.
- Le secret d’assertion est séparé de `ATLAS_TOKEN`, `ATLAS_TOOL_TOKEN`, `ATLAS_SERVICE_TOKEN`, des credentials Android/Relay et de `OPENCLAW_GATEWAY_TOKEN`.

## 4. Propositions : propriété, lookup et backfill

La migration v4 ajoute `device_ref` à `agent_proposals_v4`, un index par propriétaire et un trigger qui interdit tout nouvel INSERT sans propriétaire. La valeur est fixée depuis l’`AuthContext` côté Core. Pull, lookup d’idempotence et ACK filtrent par propriétaire; une proposition appartenant à un autre principal se présente comme `404 NOT_FOUND`, comme une proposition absente.

Les anciennes lignes `device_ref IS NULL` restent une exception de compatibilité. En `dual`, seul le principal `LEGACY` actif nommé par `ATLAS_V4_LEGACY_PRINCIPAL_ID` peut les tirer, les chercher et les ACKer. Les autres principaux n’obtiennent pas leur contenu et reçoivent `404` sur lookup/ACK. Le backfill `backend/scripts/atlas_v4_principals.py backfill-proposals` est offline et dry-run par défaut; `--apply` exige un chemin de backup absolu inexistant et vérifie le backup avant l’écriture. Le backfill attribue les lignes NULL uniquement au principal LEGACY explicitement ou sans ambiguïté unique. Ne pas distribuer les anciennes lignes entre plusieurs appareils par supposition.

## 5. Expiration et autorité serveur

La migration SQLite v5 ajoute l’audit append-only `agent_proposal_expiration_events_v4`, distinct des reçus ACK Android de `agent_proposal_events_v4`. Le contrat HTTP et `schema_version` restent en V4.

| État avant échéance atteinte | Comportement du Core |
|---|---|
| `PENDING_ANDROID`, `RECEIVED`, `AWAITING_USER` | Quand `expires_at <= now`, transition serveur vers `EXPIRED` et un audit dans la même transaction. |
| `UNCERTAIN` | Jamais expiré automatiquement. À échéance, il n’est plus retourné par le filtre normal du pull, mais l’état persistant reste `UNCERTAIN`; seul `RECONCILED` après résolution explicite est admis. |
| État terminal | Aucun changement du balayeur. |

Le helper `expire_due_proposals` est partagé par le pull paresseux, l’ACK paresseux et le balayeur. Il trie par `(expires_at, proposal_id)`, borne le lot à 1–500 et exécute une mise à jour conditionnelle sous `BEGIN IMMEDIATE`; l’audit et la transition sont atomiques. Le balayeur est une tâche de lifespan distincte, démarre au premier tick, isole les erreurs et n’enregistre pas de contenu de proposition ou d’identifiant personnel dans ses erreurs.

Précédence ACK après authentification, consentement et propriété :

1. Même `ack_id` et même hash : `200`, `X-Atlas-Replayed: true`, sans nouvel ACK.
2. Même `ack_id`, corps différent : `409 ACK_ID_CONFLICT`, même après expiration.
3. Nouvel ACK `APPLIED`/`REJECTED` après échéance : transition/audit `EXPIRED`, puis `409 PROPOSAL_EXPIRED`; aucun reçu Android synthétique n’est créé.
4. ACK `EXPIRED` avant échéance : `409 PROPOSAL_NOT_EXPIRED`, sans transition.
5. ACK Android `EXPIRED` réel après échéance : accepté comme reçu réel, sans dupliquer l’audit serveur.

Configuration du balayeur dans `backend/atlas_backend/main.py` : `ATLAS_V4_PROPOSAL_EXPIRY_SWEEPER_ENABLED` vaut `true` par défaut (`0` le désactive); `ATLAS_V4_PROPOSAL_EXPIRY_INTERVAL_SECONDS` vaut 30 (bornes 1–3600); `ATLAS_V4_PROPOSAL_EXPIRY_BATCH_SIZE` vaut 100 (bornes 1–500). `STALE_DATA` du chemin chat est renvoyé en HTTP 409 avec `error.code=STALE_DATA`, sans arguments d’outil, prompt ou détails provider dans l’enveloppe.

La réconciliation `backend/scripts/atlas_v4_expiration_reconcile.py` est une commande offline : dry-run par défaut, schéma/checksum v5 requis, backup vérifié obligatoire avec `--apply`, traitement par lots transactionnels. Elle n’est pas globalement atomique; un rollback après arrêt du Core doit conserver la base affectée et restaurer un snapshot vérifié après contrôles. Aucun dry-run ou apply sur une base réelle n’est attesté.

## 6. Fiabilité Android

- « Synchroniser maintenant » dans Coach, Plan et Sync Settings appelle `CoreV4SyncScheduler.requestNow`, qui utilise `ExistingWorkPolicy.KEEP` et la barrière locale contre les taps rapprochés. Le travail périodique et le suivi interne conservent leurs politiques distinctes. WorkManager exige le réseau connecté et utilise un backoff; cela ne garantit pas une livraison éventuelle.
- Le worker choisit le transport V4 d’après le mode courant. En `RELAY`, `RelayCoreV4HttpTransport` retire le bearer Core, n’accepte que des routes fermées et délègue au poster Relay; aucune requête HTTP directe V4 n’est émise depuis ce chemin. L’action ne modifie ni consentement ni plan. Le worker conserve consentements, outbox, ACK et contrôles d’autorisation.
- « Nouvelle conversation » archive le fil local dans l’état versionné `agentConversationArchivesV1`, crée un nouvel UUID et refuse l’action pendant `SENDING`. Les clés historiques `agentConversation` et `agentConversationId` restent lues.
- L’opt-in V4 du chat est persisté sous `v4_chat_opt_in_v1`, avec défaut `false`. L’état demandé est distinct de `v4ChatNegotiated` pour la session Relay. Si la capability V4 n’est pas négociée, le chat ne part pas et ne retombe pas silencieusement en V2. Les consentements serveur sont indépendants de cet opt-in UI.
- L’interface affiche le texte utilisateur; les détails d’outil/identifiants restent masqués dans « Détails ». `409 STALE_DATA` devient un conflit avec message stable; l’actualisation et le nouvel essai restent une décision manuelle, sans retry silencieux.

## 7. Variables d’environnement et défauts

Les valeurs ci-dessous proviennent de `backend/atlas_backend/v4_router.py`, `main.py` et `integrations/relay/pc/src/connector.ts`/`scripts/lib/config.mjs`. « Absent » n’est pas remplacé par une valeur d’exemple secrète.

| Variable | Défaut / exigence | Portée |
|---|---|---|
| `ATLAS_V4_AUTH_MODE` | `legacy` | Core et connecteur; enum exacte `legacy`, `dual`, `principal`. |
| `ATLAS_V4_LEGACY_PRINCIPAL_ID` | absent | En dual, requis pour les propositions NULL; doit désigner un principal LEGACY actif. |
| `ATLAS_V4_CURSOR_SECRET` | absent en legacy/dual; requis en principal, au moins 32 caractères | Secret de signature des curseurs V4; sa rotation invalide les curseurs existants. Injecter par le gestionnaire de secrets, jamais en argument/log. |
| `ATLAS_V4_TOOL_PRINCIPAL_ID` | absent | Principal `TOOL` choisi explicitement pour les routes internes concernées; une configuration absente/invalide ferme ces routes. |
| `ATLAS_V4_CAPABILITIES_JSON` | flags par défaut : lectures/chat `false`, mutations `true` | Overrides booléens de l’allowlist globale; ils ne remplacent pas le grant du principal. |
| `ATLAS_V4_RELAY_ASSERTION_KEYS_FILE` | absent | Fichier de trousseau Core en production; privé et validé. |
| `ATLAS_V4_RELAY_ASSERTION_KEYS_JSON` | absent | Trousseau inline réservé aux tests pytest. |
| `ATLAS_V4_ENABLED` | `0` (désactivé) | Opt-in V4 du connecteur; `1` active le chemin V4 si sa configuration est valide. |
| `ATLAS_REQUIRE_PRINCIPAL_BINDING` | `0` si absent | Accepte `0`/`1`; `1` interdit le fallback legacy. `dual`/`principal` exigent la map et le binding. |
| `ATLAS_CORE_PRINCIPAL_MAP_FILE` | absent | Map privée Relay → principal/credential; requise en mode principal-bound. |
| `ATLAS_V4_RELAY_ASSERTION_KEY_ID` | absent | ID non secret de la clé active côté connecteur; requis avec la map. |
| `ATLAS_V4_RELAY_ASSERTION_SECRET_FILE` | absent | Source fichier de production côté connecteur; mode `0600`, propriétaire courant, hors checkout Git. |
| `ATLAS_V4_RELAY_ASSERTION_SECRET` | absent | Source de test seulement avec `NODE_ENV=test`; refusée avec la source fichier. |
| `ATLAS_V4_ANDROID_TOKEN_FILE` | absent | Fallback de compatibilité connecteur uniquement en legacy sans map et sans binding exigé. |
| `ATLAS_V4_PROPOSAL_EXPIRY_SWEEPER_ENABLED` | `true` | `0` désactive le balayeur; pull/ACK paresseux et réconciliation offline restent distincts. |
| `ATLAS_V4_PROPOSAL_EXPIRY_INTERVAL_SECONDS` | `30` | Entier borné à 1–3600 secondes. |
| `ATLAS_V4_PROPOSAL_EXPIRY_BATCH_SIZE` | `100` | Entier borné à 1–500. |
| `ATLAS_TOKEN`, `ATLAS_TOOL_TOKEN`, `ATLAS_SERVICE_TOKEN`, `OPENCLAW_GATEWAY_TOKEN[_FILE]` | Valeurs fournies par le déploiement, aucune valeur publiée ici | Secrets existants et distincts; V1–V3 et les connexions service/Gateway ne sont pas remplacés par le credential principal V4. |

## 8. Compatibilité et migrations

| Contrat / stockage | Version et compatibilité |
|---|---|
| SQLite Core | Migration v4 : registre principaux/credentials/grants, binding Relay, propriétaire des propositions et garde des nouveaux INSERT. Migration v5 : audit d’expiration append-only. `dual` et `principal` exigent v5; `legacy` garde le mode historique. Les migrations sont additives; ne pas rétrograder en supprimant tables/colonnes. |
| API Core V4 | `contract_version=4`, `schema_version=4`; les routes de succès gardent leur enveloppe. `PROPOSAL_NOT_EXPIRED` est ajouté au contrat d’erreur et reconnu par Relay. |
| Auth Relay → Core | Map privée et binding `relay_principal_bindings_v4`; chaque opération V4 porte une assertion signée. Les credentials locaux par session remplacent le bearer commun du connecteur en modes dual/principal. |
| Android conversation | `agentConversationArchivesV1` et préférence `v4_chat_opt_in_v1`; lecture conservée des champs historiques de conversation. Défaut opt-in V4 `false`. |
| Expiration | Les ACK Android restent dans `agent_proposal_events_v4`; l’audit serveur v5 est séparé et ne fabrique aucun ACK. `UNCERTAIN` nécessite une réconciliation explicite. |

Chemin de migration : sauvegarde/intégrité → preview et essais sur copie → import LEGACY explicite et création des principaux/credentials/grants → configuration/binding Relay en dual → backfill des lignes NULL sur copie vérifiée → vérification A/A, B/B et refus A/B → installation in-place de l’APK de même signature → cutover explicite en principal → révocation/rotation contrôlée → clôture indépendante. La séquence opérateur et ses arrêts sont détaillés dans [V7-10_PHYSICAL_MIGRATION_HANDOFF.md](V7-10_PHYSICAL_MIGRATION_HANDOFF.md).

Rollback avant révocation : arrêt contrôlé, sauvegarde vérifiée, retour explicite du mode à `legacy`, sans downgrade SQLite; cela réouvre volontairement le risque du bearer partagé. Après révocation, le credential révoqué ne doit jamais redevenir actif. Une restauration de données doit préserver/réappliquer le tombstone, émettre un credential neuf et mettre à jour le binding/la map; si l’identité Relay elle-même a été révoquée, il faut réappairer avec une nouvelle identité. Voir le détail dans le handoff.

## 9. Implémentation, preuves et limites

Les faits d’architecture renvoient au code de la base examinée et aux rapports ci-dessous. Les rapports locaux ne prouvent pas un déploiement.

| Domaine | Code / preuve | Statut de preuve |
|---|---|---|
| Principal, audiences, grants, modes, fail-closed, binding et propriété | `backend/atlas_backend/{store.py,v4_auth.py,v4_assertion.py,v4_router.py,v4_projection_store.py}`; `backend/scripts/atlas_v4_principals.py`; rapport [A1](evidence/V7-10_A1_IMPLEMENTATION_EVIDENCE.md) et correctifs [A1-FIX](evidence/V7-10_A1_FIX_EVIDENCE.md). | Implémenté et vérifié localement sur bases/testeurs synthétiques; migration, backfill et révocation live `NOT_RUN`. Commits intégrés : `4c50a13`, `8dd31a7`, `acccd0c`, `6d8688d`, `440b0a5`, `0890fd2`, `d96810b`, `77dc105`, `739ad87`, `d2f9826`. |
| Expiration, audit, concurrence, ACK et réconciliation | `backend/atlas_backend/{main.py,store.py,v4_projection_store.py,v4_router.py}`; `backend/scripts/atlas_v4_expiration_reconcile.py`; [preuve A2](evidence/V7-10_A2_IMPLEMENTATION_EVIDENCE.md) et [cycle de vie](V7-10_EXPIRATION_LIFECYCLE.md). | Implémenté et tests locaux sur SQLite temporaire; dry-run/apply de réconciliation sur base réelle et déploiement Core `NOT_RUN`. Commits intégrés : `a566503`, `70beee9`, `7b1004b`, `c0eb437`, `549f658`. |
| Fiabilité Android | `CoreV4SyncNowAction.kt`, `CoreV4SyncWorker.kt`, `RelayCoreV4HttpTransport.kt`, `RelaySettingsStore.kt`, `RelaySettingsController.kt`, écrans Coach/Plan/Sync; [rapport A3](V7-10_ANDROID_RELIABILITY.md), cartographie [S3](evidence/V7-10_S3_ANDROID_MAP.md). | JVM/lint/build et compilation des tests instrumentés sont rapportés localement en A3; tests instrumentés non exécutés, aucun APK V7-10 installé, aucun Pixel Android 17/API 37 qualifié pour ces changements. Commits intégrés : `2ff9fa1`, `a43f115`, `298fafe`, `5cdbc80`, `fa82af3`, `614b639`, `ffcd43e`. |
| Consolidation finale | A2 et A3 sont présents ci-dessus. | Rapports nommés A5, A4-delta indépendant et A6 absents de ce checkout; résultats correspondants **à consolider dans le rapport final**. Le correctif documenté dans A1-FIX n’est pas un rapport de revue indépendante A4-delta. |

À cette base, aucune migration de base/service live, aucun test E2E Relay/Core live, aucun test AVD/Pixel V7-10, aucune installation APK V7-10 et aucune CI distante ne sont attestés. Les gates `GO_PRODUCT_INSTALL`, `GO_LIVE_SECURITY` et `GO_DAILY_PILOT` restent **NO_GO / NOT_RUN**. Les preuves historiques V7-07/V7-09 ne remplacent pas la preuve de cette migration et ne rendent pas le pilote autorisé.

## 10. Références principales

- [Migration de l’authentification](V7-10_SECURITY_MIGRATION.md), [cycle d’expiration](V7-10_EXPIRATION_LIFECYCLE.md), [fiabilité Android A3](V7-10_ANDROID_RELIABILITY.md).
- [Conception S1](evidence/V7-10_S1_AUTH_DESIGN.md), [conception S2](evidence/V7-10_S2_EXPIRATION_DESIGN.md), [cartographie Android S3](evidence/V7-10_S3_ANDROID_MAP.md).
- [Preuve A1](evidence/V7-10_A1_IMPLEMENTATION_EVIDENCE.md), [correctifs A1-FIX](evidence/V7-10_A1_FIX_EVIDENCE.md), [preuve A2](evidence/V7-10_A2_IMPLEMENTATION_EVIDENCE.md).
- Contexte antérieur : [rapport V7-09](V7-09_FINAL_REPORT.md), [handoff V7-08](V7-08_HANDOFF.md), [exécution V7-07](V7-07_PHYSICAL_EXECUTION.md), [backup/rollback V7-07](V7-07_BACKUP_AND_ROLLBACK.md).
