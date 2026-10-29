# Architecture opérationnelle ATLAS V6.3 — proposition

**Statut :** conception seule, non implémentée.
**Base lue :** commit 364a8000bbfa13f96b7fa905de8664017d7cee16, branche v63/p1-arch.
**Objet :** ajouter un chemin V4 cohérent entre Android, Core et OpenClaw sans déplacer l’autorité du téléphone.

Cette architecture complète les contrats V1/V2/V3. Elle ne les modifie pas. Les choix nouveaux sont des propositions à valider avant implémentation. Aucune recette, installation OpenClaw, modification de service, action ADB ou preuve Pixel n’a été exécutée pendant cette conception.

## 1. Autorités et flux

Android garde l’autorité sur le DayPlan approuvé, ses versions, les statuts et retours, les consentements, les permissions OS, les événements calendrier lus localement et tous les effets locaux. wger reste la source des prescriptions et performances qu’il contient. Le Core détient des projections horodatées reçues d’Android et l’inbox durable des propositions. OpenClaw raisonne sur les données consenties et peut demander une projection ou déposer une proposition. Ni OpenClaw ni le Core ne modifient le plan local.

Une projection Core est toujours une observation datée du téléphone. Un état récent signifie « dernière observation connue » ; il ne prouve pas que le Pixel est encore en ligne ni que son plan n’a pas changé après cette observation. Les données mock actuelles sont explicitement marquées MOCK et ne sont jamais substituées à un snapshot Android.

~~~mermaid
flowchart LR
  A["Android<br/>DayPlan, consentements,<br/>statuts, effets locaux"]
  R["Relais public<br/>requêtes/réponses bornées"]
  P["Relais PC<br/>opérations explicites"]
  C["ATLAS Core<br/>projections, inbox, ACK"]
  O["OpenClaw Gateway<br/>agent atlas"]
  T["atlas_core_tools<br/>six outils typés"]

  A -->|"sync V4 consenti"| R
  R --> P
  P -->|"routes Core autorisées"| C
  C -->|"requête chat"| O
  O -->|"appel d'outil"| T
  T -->|"boucle locale authentifiée"| C
  C -->|"proposition durable"| C
  C -->|"résultat de chat + identifiants"| P
  P --> R
  R -->|"réponse au poll / chat"| A
  A -->|"pull inbox WorkManager/foreground"| R
  R --> P
  P --> C
  C -->|"propositions non expirées"| P
  P --> R
  R --> A
  A -->|"validation locale A0/A1/A2"| A
  A -->|"ACK d'application/rejet/résultat incertain"| R
  R --> P
  P --> C
  C -->|"état vérifié au prochain échange"| O
~~~

Le relais actuel reste requête-réponse : aucun push PC vers Android n’est supposé. Le pull d’inbox est déclenché au retour au premier plan, après un échange chat et par WorkManager lorsque le système l’autorise. Le délai Doze peut retarder ce pull ; une proposition expire sans effet si Android ne la reçoit pas à temps. Hors réseau, Android continue à afficher et exécuter uniquement son état local déjà autorisé.

## 2. Inventaire existant et écarts

| Brique | État constaté et références | Écart V6.3 à combler |
|---|---|---|
| DayPlan Android | PlanActivity et DayPlan versionnés : android/app/src/main/java/dev/atlas/coach/plan/PlanModels.kt:66-80,146-220. Codec strict schema 3 : PlanJsonCodec.kt:6-60. Fichier distinct par date, lecture, création et écriture monotone : DayPlanRepository.kt:54-88 ; contrôleur local sans réseau : android/app/src/main/java/dev/atlas/coach/app/PlanController.kt:44-58,84-132. | Publier une projection consentie avec version et fraîcheur. Ne pas remplacer le stockage local par SQLite Core. Une extension locale devra migrer vers un codec versionné tout en conservant la lecture/quarantaine des documents schema 3. |
| Révision Android | Diff déterministe, effect IDs et scope hash : PlanRevisionEngine.kt:23-126. Vérification de plan/version, expiration, effets, confirmation et application idempotente : DayPlanRepository.kt:101-165,167-214. | Réutiliser ces barrières pour toute application locale. Ne pas réimplémenter l’application du diff dans le Core. |
| Statuts | Enum ActivityStatus : PlanModels.kt:66-73 ; transitions et règle MISSED après fin du créneau : PlanStatusPolicy.kt:12-65. PlanActivity n’a pas de provenance ni de date de décision : PlanModels.kt:146-161. | Ajouter l’événement de statut et la provenance dans un nouveau contrat local V4 ; ne pas déduire DONE de l’heure ou du timer. |
| UI de plan | Le choix de l’activité suivante exclut les états terminaux : android/app/src/main/java/dev/atlas/coach/app/PlanUiComponents.kt:52-71. Les lignes de liste passent les actions de déplacement, édition, état et suppression : PlanUiComponents.kt:125-147. | Ajouter le parcours de report et le retour coach sans créer un second état d’affichage qui diverge du plan. |
| Outbox et sync V1 | Les catégories et six event types sont dans android/app/src/main/java/dev/atlas/coach/sync/SyncModels.kt:8-68 ; l’outbox persiste l’ID avant le premier envoi. Le contrat d’enveloppe est contracts/sync-v1.schema.json:5-73 ; Core reçoit /v1/sync dans backend/atlas_backend/main.py:719-735. | V1 ne transporte pas le snapshot du DayPlan, les statuts, objectifs ou programme sport. Garder V1 inchangé et ajouter des routes V4 typées. |
| Inbox et ACK V2 | Pull Android borné, déduplication par proposal ID et idempotency key : android/app/src/main/java/dev/atlas/coach/sync/agent/inbox/AgentInboxRepository.kt:22-80. Routes /v2/agent/inbox et /v2/agent/proposals/{id}/ack : backend/atlas_backend/main.py:655-682 ; stockage et transitions ACK : backend/atlas_backend/store.py:217-305. | V2 ne porte pas le reçu versionné d’application d’une PlanRevision V4. Ajouter un espace V4 indépendant, sans modifier les enums ou champs V2. |
| Révision agent V3 | Route directe /v3/agent/plan-revision : backend/atlas_backend/main.py:619-653 ; client Android : android/app/src/main/java/dev/atlas/coach/plan/PlanRevisionApiClient.kt:216. Le schéma V3 est strict mais ne contient pas le champ capability exigé par la doc : contracts/plan-revision-v3.schema.json:308-365. | La réponse n’est pas une inbox et ne confirme pas l’application/rejet Android. V4 met la proposition dans l’inbox et exige une capability explicite. Conserver V3 pour ses clients existants. |
| Plan mock et sport | /v1/plan/today retourne un mock indépendant (backend/atlas_backend/main.py:401-416). /v1/sport/plan renvoie un état UNAVAILABLE si le mode d’auth wger disponible n’est pas pris en charge (main.py:418-459). | Les six outils ne doivent pas promouvoir le mock comme état Android. Exposer UNAVAILABLE lorsqu’aucune projection ou autorisation n’existe. |
| Core SQLite | Les tables sont créées par CREATE TABLE IF NOT EXISTS (backend/atlas_backend/store.py:22-75) ; il n’y a pas de registre de migrations versionnées. L’outbox Android possède déjà une quarantaine d’état, et le repository de plan conserve une métadonnée de quarantaine. | Ajouter des migrations Core transactionnelles et non destructives. Ne pas reconditionner les états Android comme une vérité serveur. |
| Relais | L’allowlist opérationnelle actuelle est dans integrations/relay/pc/src/protocol.ts:10-30 ; chaque payload est validé par opération à protocol.ts:165-194. Les opérations concernées sont coach.chat.request, plan.revision.request/result, plan.today.read, sport.plan.read et sync.status. Audit A4-4 : proxy local préserve le contrat 4001 ; l’amont retourne 1012, preuves 1012, relais amont hors dépôt. | Les snapshots, événements et ACK V4 exigent un protocole/capability négocié et une validation amont. Aucun HTTP proxy générique ni chemin libre. TLS/Noise et le contrat de révocation ne sont pas affaiblis. A4-4 reste BLOCKED jusqu’à correction de l’image amont ; le proxy local 4001 exige un test isolé à intégrer. |
| OpenClaw et adapter | openclaw_adapter.py:45-80 décrit le prompt V3 sans outil ; son chat construit seulement model/messages/max_tokens/temperature et lit une fois `message.content` (openclaw_adapter.py:730-755). SDK 2026.10.5 : defineToolPlugin, TypeBox et execute dans dist/plugin-sdk/tool-plugin.d.ts:32-56,86-106. Un plugin local s’installe par `openclaw plugins install <path>` ou `-l`, manifeste natif racine avec `id` et `configSchema` ; Gateway actif, installation sans redémarrage. | Installer `atlas_core_tools` et brancher une boucle client bornée au chat. `/v1/chat/completions` supporte `tools`, `tool_choice`, `message.tool_calls` et le retour `role:tool` + `tool_call_id`, mais est désactivé par défaut et suit un run d’agent normal. Les outils plugin internes ne sont utilisables que si la policy les expose. Le bearer compatible OpenAI confère un droit opérateur complet : privé, loopback/ingress privé, jamais dans le dépôt. |
| Objectifs, usage, Focus, sport | Goals locaux : android/app/src/main/java/dev/atlas/coach/goals/GoalRepository.kt:17-47,57-97. Agrégat d’usage avec consentement et couverture : android/app/src/main/java/dev/atlas/coach/usage/UsageSummaryCalculator.kt:39-111. Le modèle Focus n’a pas plan_id/activity_id : android/app/src/main/java/dev/atlas/coach/focus/FocusModels.kt:85-105. Client sport Android : android/app/src/main/java/dev/atlas/coach/training/SportPlanClient.kt:33-42 ; client wger Core en lecture : backend/atlas_backend/integrations/wger.py:1-38. | Transmettre uniquement les agrégats consentis, lier une session Focus explicitement, distinguer prescription et performance, ne rien inventer en absence de données. |
| Recherche Bridge | Plugin séparé présent dans integrations/openclaw/atlas_chatgpt_research/ ; son README décrit son contrat et ses limites. Il n’est pas installé selon l’audit. Le test actuel utilise fetch injecté et ne prouve pas une recherche réelle. | Installer/valider séparément, puis tester Bridge réel, extension, citations et provenance. Le plugin n’est pas une porte vers les six outils ATLAS. |

## 3. Surfaces V4 et enveloppes

### 3.1 Routes privées du Core

Le Core ATLAS réel audité est `127.0.0.1:8100`, `atlas_backend.main:app`, Core 2.0.0, depuis le checkout principal, distinct du worktree de revue. Le port 8000 sert CTI Bulletin AutoWork et n’a aucun rapport avec ATLAS. Le ticket de diagnostic vérifie les routes, l’identité du checkout et la base de l’instance 8100 en lecture seule, ou utilise une instance isolée dédiée. Toute nouvelle route V4 écoute uniquement sur loopback et expose une authentification dédiée aux outils, indépendante du token Android, du token Gateway OpenClaw et des secrets Bridge/wger. La destination est compilée/configurée comme loopback ; aucun hôte, chemin, port, URL, header ou méthode n’est fourni par le modèle.

| Route proposée | Capability | Sémantique |
|---|---|---|
| GET /internal/v4/capabilities | tool_catalog.read | Retourne contract_version, schémas supportés, six capabilities d’outil et capability coach.chat, états activés, limites et version du service. |
| GET /internal/v4/day-plan?local_date=YYYY-MM-DD | day_plan.read | Snapshot Android du jour demandé ; date absente signifie date locale du dernier téléphone observé. |
| GET /internal/v4/activity-status/{activity_id}?plan_id=UUID | activity_status.read | État et provenance du statut. plan_id est optionnel ; s’il est fourni, il doit correspondre. |
| POST /internal/v4/plan-revision-proposals | plan_revision.propose | Valide le diff contre le dernier snapshot connu puis dépose une proposition dans l’inbox. N’applique jamais le plan. |
| GET /internal/v4/usage-summary?window_start=…&window_end=… | usage_summary.read | Retourne seulement l’agrégat consenti et sa couverture. Période ≤ 14 jours. |
| GET /internal/v4/goal-progress | goal_progress.read | Projection des objectifs actifs et sous-objectifs connus, sans écriture. |
| GET /internal/v4/training-plan?local_date=YYYY-MM-DD | training_plan.read | Projection wger en lecture et état de sync ; un adaptateur absent renvoie UNAVAILABLE. |
| GET /internal/v4/agent/proposals/by-idempotency/{key} | plan_revision.propose | Réconcilie uniquement une requête de proposition dont le résultat est incertain ; ne relance pas le modèle. |

Ces routes sont des noms exacts, pas une API de transfert. Les six outils sont chacun reliés à une route fixe. Toute route inconnue, méthode inconnue, destination non-loopback ou capability absente est refusée.

### 3.2 Sync Android → Core

Le relais authentifie l’appareil appairé hors du payload métier. Le payload ne choisit ni device_id, destination réseau ni chemin. Chaque endpoint accepte une enveloppe V4 stricte, limite taille et nombre d’items, et renvoie un reçu par item. Les catégories de consentement sont vérifiées à Android avant envoi et revérifiées côté Core avant stockage ou lecture.

| Route/opération V4 proposée | Contenu autorisé | Clé d’idempotence |
|---|---|---|
| POST /v4/sync/day-plan-snapshots | Projection du plan approuvé, catégories sélectionnées, activité sans note libre par défaut. plan_id, version, révision et date locale. | device + plan_id + version + snapshot_hash |
| POST /v4/sync/activity-status-events | Événements append-only avec status précédent/nouveau, provenance, décision/observation, consent_version et source. | event_id UUID Android, contenu canonique immuable |
| POST /v4/sync/usage-summaries | Résumé agrégé ; package/display name omis sauf permission distincte. Aucun écran, message, notification ou historique brut. | device + window_start + window_end + consent_version + summary_hash |
| POST /v4/sync/goal-progress-snapshots | Objectifs actifs, sous-objectifs, mesures et engagements confirmés explicitement nécessaires au coach. | device + goals_version + snapshot_hash |
| POST /v4/sync/training-snapshots | Référence wger, prescriptions et performances lues, chacune avec sa source et sync_state. | device + provider + observed_at + snapshot_hash |
| POST /v4/agent/proposals/pull | Pull cursor + page_size ; retourne les propositions V4 non expirées et non terminales. | cursor opaque lié à l’appareil et à la version |
| POST /v4/agent/proposals/{proposal_id}/ack | Événement Android d’application, rejet, expiration, conflit ou incertitude. | ack_id UUID + hash du corps |
| POST /v4/agent/chat | coach.chat | Message et catégories de contexte autorisées ; ne transporte pas de plan complet si le coach peut le lire via outil. request_id UUID et empreinte du corps |

Exception d’idempotence statut : l’enveloppe garde son request_id de transport, mais la clé d’idempotence métier par événement est event_id, avec contenu canonique immuable ; elle remplace une idempotency_key distincte pour cet événement. Même event_id/hash rejoué retourne le reçu existant ; event_id réutilisé avec un corps différent est rejeté.

Le relais expose des identifiants d’opération distincts, par exemple v4.plan.snapshot.upsert, v4.activity-status.events.append, v4.usage.summary.upsert, v4.goal.snapshot.upsert, v4.training.snapshot.upsert, v4.proposals.pull, v4.proposals.ack et v4.coach.chat.request. Ils ne sont utilisables qu’après négociation de atlas.remote.v2 ; atlas.remote.v1 et son contrat 4001 ne changent pas. TLS/Noise et le contrat de révocation ne sont pas affaiblis. Le proxy local préserve 4001, ce qui doit être confirmé par un test isolé à intégrer. L’image amont retourne 1012 et reste BLOCKED jusqu’à correction ; l’amont doit valider la même allowlist et les mêmes schémas. Tant que le relais amont n’accepte pas ces opérations, ces chemins restent BLOCKED ; le relais local ne doit pas prétendre les traduire.

### 3.3 Version, capability, freshness et pagination

Chaque nouvelle enveloppe V4 contient contract_version: 4, schema_version: 4, request_id, sent_at, capability et un objet data propre à la route. Les entités nouvelles sont fermées par additionalProperties: false, y compris les objets imbriqués. Le V4 Android peut ajouter continuity_id, relation d’occurrence et provenance sans changer le codec schema 3 : migration locale versionnée, ancien document conservé tant que le nouveau n’est pas validé.

Tout résultat de projection contenant des données inclut :

~~~json
{
  "source": "ANDROID_SYNC",
  "observed_at": "2026-10-29T08:02:00Z",
  "last_synced_at": "2026-10-29T08:02:04Z",
  "plan_id": "00000000-0000-4000-8000-000000000101",
  "version": 7,
  "is_stale": false,
  "freshness": "FRESH"
}
~~~

Valeurs freshness : FRESH, STALE, UNAVAILABLE, UNKNOWN. observed_at est l’horodatage de l’observation Android ; last_synced_at est la réception Core, jamais une date inventée. Pour le plan et les statuts, proposition de seuil de fraîcheur : 5 minutes. Usage : 30 minutes ; objectifs : 60 minutes ; wger : 24 heures, sous réserve de l’horodatage source wger. Un timestamp futur de plus de 2 minutes, une lacune de séquence, une version régressive ou un consentement inconnu force UNKNOWN ou STALE. Les seuils sont des valeurs de contrat à valider ; ils n’accordent aucun droit.

Un résultat STALE reste marqué comme projection et ne peut pas être décrit comme l’état courant du Pixel. Si la source manque, l’outil retourne data: null, freshness: UNAVAILABLE ou UNKNOWN, avec un code explicatif. Il ne retourne pas un tableau vide comme s’il prouvait « aucune activité ». Pagination par curseur opaque, liée à l’appareil, capability, filtre et snapshot ; page_size maximal 32 pour activités et 20 pour objectifs. Un curseur inconnu, expiré ou réutilisé avec d’autres filtres est refusé.

## 4. Modèle Core/SQLite, migrations et vérité unique

Le Core garde des projections et des reçus, jamais un DayPlan éditable parallèle. Les nouvelles tables sont additives et séparées des tables V1/V2 :

~~~text
schema_migrations(
  version INTEGER PRIMARY KEY,
  name TEXT UNIQUE,
  checksum TEXT,
  applied_at TEXT
)

day_plan_snapshots_v4(
  device_ref TEXT, plan_id TEXT, local_date TEXT, timezone TEXT,
  plan_version INTEGER, revision_id TEXT, schema_version INTEGER,
  observed_at TEXT, received_at TEXT, consent_version INTEGER,
  snapshot_hash TEXT, projection_json TEXT,
  PRIMARY KEY(device_ref, plan_id, plan_version)
)

activity_status_events_v4(
  event_id TEXT PRIMARY KEY, device_ref TEXT, plan_id TEXT,
  activity_id TEXT, continuity_id TEXT, sequence INTEGER,
  old_status TEXT, new_status TEXT, provenance TEXT,
  decided_at TEXT, observed_at TEXT, plan_version INTEGER,
  consent_version INTEGER, event_hash TEXT
)

usage_summaries_v4(
  device_ref TEXT, window_start TEXT, window_end TEXT, timezone TEXT,
  generated_at TEXT, received_at TEXT, consent_version INTEGER,
  summary_hash TEXT, summary_json TEXT,
  PRIMARY KEY(device_ref, window_start, window_end, consent_version)
)

goal_progress_snapshots_v4(
  device_ref TEXT, goals_version INTEGER, observed_at TEXT,
  received_at TEXT, consent_version INTEGER, snapshot_hash TEXT,
  projection_json TEXT, PRIMARY KEY(device_ref, goals_version)
)

training_snapshots_v4(
  device_ref TEXT, provider TEXT, observed_at TEXT, received_at TEXT,
  sync_state TEXT, source_version TEXT, snapshot_hash TEXT,
  projection_json TEXT, PRIMARY KEY(device_ref, provider, observed_at)
)

agent_proposals_v4(
  proposal_id TEXT PRIMARY KEY, idempotency_key TEXT UNIQUE,
  body_hash TEXT, plan_id TEXT, base_version INTEGER,
  capability TEXT, created_at TEXT, expires_at TEXT,
  state TEXT, proposal_json TEXT
)

agent_proposal_events_v4(
  ack_id TEXT PRIMARY KEY, proposal_id TEXT, sequence INTEGER,
  state TEXT, result_code TEXT, applied_plan_version INTEGER,
  revision_id TEXT, scope_hash TEXT, occurred_at TEXT,
  received_at TEXT, body_hash TEXT,
  UNIQUE(proposal_id, sequence)
)

tool_audit_v4(
  call_id TEXT PRIMARY KEY, tool_name TEXT, capability TEXT,
  request_hash TEXT, result_code TEXT, started_at TEXT,
  completed_at TEXT, latency_ms INTEGER
)
~~~

projection_json est une copie minimale et expurgée du dernier état envoyé ; elle n’est jamais une écriture canonique. Le snapshot peut être remplacé par une version Android plus récente, mais l’historique d’événements et les ACK restent append-only selon la rétention. Le Core recalcule is_stale au moment de la lecture.

Migration proposée : enregistrer chaque étape dans schema_migrations et PRAGMA user_version sous transaction SQLite ; effectuer un backup cohérent via l’API SQLite, valider checksum, tables, contraintes et integrity_check, puis appliquer uniquement des créations/colonnes/tables additives. N’effacer ni convertir les tables V1/V2. Un schema_version inconnu ou un payload invalide va en quarantaine avec bytes/hachage et code d’erreur ; la version précédente reste lisible, sans écriture destructive.

Rollback : désactiver V4 par capability flag et route, laisser les nouvelles tables en place afin que l’ancienne version les ignore, puis revenir au code antérieur. Ne pas exécuter de down-migration destructive. Une restauration du backup est réservée à une corruption démontrée et vérifiée ; les opérations externes inconnues passent d’abord en réconciliation. Sur Android, le remplacement de plusieurs fichiers de jour utilise un journal de transaction durable et des copies préimage ; aucune lecture ne sert une transaction incomplète.

## 5. Cycle complet de proposition et résultat incertain

1. L’utilisateur déclenche le chat Android. Le téléphone vérifie la catégorie de partage locale et ne transmet que le message et les références de contexte consenties.
2. Le Core construit le contexte minimisé. Si le modèle a besoin du plan ou d’un statut, OpenClaw appelle l’outil correspondant ; le Core répond depuis un snapshot en indiquant fraîcheur, version et source. MOCK, STALE et UNKNOWN ne deviennent jamais « confirmé sur le téléphone ».
3. Si l’utilisateur demande une adaptation, OpenClaw appelle atlas_propose_plan_revision avec plan_id, base_version, changements typés, justification et idempotency key. Le Core valide la forme et l’état projeté, stocke une proposition V4 dans l’inbox et renvoie proposal_id, diff, conflits connus, expiration et mode requis. C’est une proposition, pas un ordre.
4. La réponse de chat revient par le chemin requête-réponse. Android tire l’inbox via le relais, déduplique par proposal_id/idempotency_key, persiste localement et accuse seulement la réception si nécessaire.
5. Android recharge le plan canonique du jour, vérifie le plan_id, chaque base_version, l’expiration, le consentement courant, les capacités et les permissions au moment de traiter. Il recalcule le diff, les conflits, le scope_hash et les effect IDs à partir de ses propres données.
6. A0 demande une confirmation compréhensible ; A1 peut s’appliquer uniquement si toutes les règles locales bornées sont valides et le consentement d’auto-révision est toujours actif ; A2 s’arrête devant une confirmation spécifique.
7. Une application crée une révision locale versionnée, persiste atomiquement son journal et son DayPlan, puis renvoie un ACK V4 contenant l’état, la version réellement appliquée, le revision_id, le scope_hash et les effect IDs observés. Un refus inclut un code non personnel.
8. Le Core append l’ACK, passe l’inbox à l’état correspondant et expose ce résultat au prochain tour OpenClaw. OpenClaw peut alors expliquer « appliqué version 8 », « refusé » ou « non confirmé », selon l’ACK réel.

**Conflit/stale_version :** ne jamais rebaser silencieusement. Android répond STALE_VERSION avec sa version actuelle, conserve la proposition pour diagnostic, et demande une nouvelle lecture/proposition. Une version Core fraîche ne remplace pas cette vérification locale.

**Répétition :** même idempotency key + même corps canonique renvoie le même proposal_id/résultat. Même clé + corps différent donne IDEMPOTENCY_CONFLICT. Un ACK répété au même hash est un no-op ; un ACK contradictoire sur la même séquence est refusé et audité.

**Résultat incertain :** si la connexion tombe après application locale, Android conserve l’ACK en outbox et le rejoue avec le même ack_id. Le Core ne déduit pas l’application d’un timeout. En cas de timeout pendant une proposition, le plugin interroge la clé existante ; il ne recommence jamais avec une nouvelle clé. Si le serveur indique encore PENDING ou si le lookup échoue, le résultat reste UNKNOWN et aucun nouveau proposal ne se crée.

**Offline :** Android affiche le plan local, garde ses gestes locaux et place les événements consentis en outbox. Aucune panne réseau ne convertit une proposition en commande. Une proposition expirée hors ligne est marquée EXPIRED au prochain contact et ne s’applique pas.

## 6. A0, A1 bornée et A2

**Défaut : A0 — proposition et confirmation Android.** Une préférence conversationnelle ou un mandat réseau n’équivaut pas à une autorisation A1. Le consentement A1 est un enregistrement Android distinct, révocable, horodaté et lié à une policy_version et aux catégories.

**Candidat A1 — application locale bornée.** Le type de catégorie vient d’un champ local explicite, jamais d’une classification LLM du titre. Catégories autorisées : lecture, apprentissage du chinois, loisir et activité personnelle marquée comme telle par l’utilisateur. Sont toujours protégés : sommeil/repos, travail, famille, priorité REQUIRED, rendez-vous non ATLAS et toute activité liée à un calendrier. Les activités TRAINING/wger sont exclues de ce premier périmètre.

- Ne s’applique qu’à un élément en état PLANNED et à une révision locale, jamais à un statut ni à un effet externe.
- Jour local inchangé ; déplacement maximal de 90 minutes ; durée comprise entre 10 et 120 minutes et variation maximale de 15 minutes ; pas de création ou suppression automatique dans le premier lot.
- Au plus deux activités modifiées par révision et trois auto-révisions par journée locale. Le plafond est vérifié par Android dans une transaction atomique, même si le Core annonce A1_CANDIDATE.
- Le validateur Android vérifie priorité, catégorie explicite, consent_version, état terminal, limites horaires, collisions avec REQUIRED/rendez-vous et collisions entre activités. Tout doute, donnée absente ou conflit donne A0 ou un refus.
- Verrou optimiste sur plan_id + base_version + revision_id ; scope_hash lie le diff canonique. Aucun succès n’est montré avant écriture durable.
- Notification discrète immédiatement après application, sans titre personnel sur écran verrouillé par défaut. Annulation disponible pendant 10 minutes comme révision compensatoire ; journal non effacé.
- Révocation bloque tout nouvel auto-apply, invalide les propositions A1 en attente et n’annule pas rétroactivement un effet déjà localement appliqué.

**A2 — confirmation spécifique obligatoire.** Cela inclut écriture CalendarContract, écriture wger, demande ou élargissement d’un consentement, communication externe, changement d’engagement professionnel/familial, activité REQUIRED, règle de restriction d’application ou toute opération qui ne satisfait pas chaque prédicat A1. L’écran Android montre la cible, les valeurs avant/après, la conséquence et l’autorisation exacte. Une permission OS est demandée seulement par une action Android explicite ; OpenClaw ne peut pas la demander ni l’accorder.

## 7. Invariant de report : option (b), recommandée à valider

Recommandation : l’occurrence initiale reçoit le statut terminal POSTPONED et une nouvelle occurrence est créée dans la même révision atomique. Les deux portent le même continuity_id ; la nouvelle occurrence possède un activity_id distinct et supersedes_activity_id pointant vers l’ancienne. La chaîne conserve l’historique, même si l’activité est reportée plusieurs fois.

L’action « Reporter » constitue un seul PlanRevisionBundle : StatusChanged(ancienne, active→POSTPONED) + SlotAdded(nouvelle, PLANNED, ids liés). Le scope_hash couvre les deux plans si la date change. Les écritures locales sont préparées dans un journal, vérifiées par hash puis validées ensemble ; au redémarrage, une transaction PREPARED est complétée ou restaurée depuis ses préimages avant de rendre le plan lisible. Si cette garantie multi-date n’est pas encore disponible, le report vers une autre date retourne CROSS_DATE_UNSUPPORTED et reste une proposition A0 ; aucune demi-écriture.

Pour un report le même jour, PlanUiLogic.nextActivity ignore déjà les statuts terminaux (PlanUiComponents.kt:67-71) : l’ancienne occurrence POSTPONED disparaît du choix « prochaine activité » et la nouvelle PLANNED devient candidate après recalcul. Un report vers une autre date n’est pas éligible au classement du jour courant. La liste conserve l’ancienne ligne comme « Reportée » avec lien vers la suite ; la nouvelle ligne porte l’heure, le statut discret et un menu de retour/annulation.

Les UUID sont produits localement à l’application, pas par l’agent. L’idempotency key et le revision_id stabilisent les retries ; réutiliser une clé avec un autre diff est refusé. La provenance du report est USER_CONFIRMED (action explicite) ou AGENT_PROPOSED tant qu’Android n’a pas validé. Une annulation est une révision compensatoire, jamais l’effacement de POSTPONED ou de l’audit. Option (b) est une recommandation de conception, pas un changement d’enum autorisé sans migration locale schema 4.

## 8. Provenance et transitions des statuts

Le modèle local V4 ajoute un journal d’événements avec status_event_id, plan_id, activity_id, continuity_id, from_status, to_status, provenance, source, decided_at, observed_at, plan_version, consent_version et éventuellement focus_session_id. La projection expose last_synced_at et la réponse de sync distinctement de la décision.

Provenance : DECLARED (la personne a choisi le statut), MEASURED (un capteur ou timer a observé une mesure), CONFIRMED (confirmation explicite de la personne après observation) ou UNKNOWN. Le système conserve aussi la source concrète : USER_UI, FOCUS_TIMER, USAGE_STATS, WGER_READ, CORE_IMPORT.

- DONE est permis uniquement par confirmation utilisateur ou déclaration explicite de la personne. Un timer Focus terminé, un emplacement détecté ou l’heure de fin ne suffisent pas.
- Une mesure Focus peut lier les durées/cycles observés, mais ne change pas automatiquement le statut de l’activité. L’écran peut demander « La séance est-elle terminée ? ».
- MISSED n’est disponible qu’après le créneau et par décision utilisateur explicite ; le passage du temps seul ne suffit pas.
- CANCELLED est une action volontaire et terminale.
- POSTPONED n’est créé que par le bundle atomique de report et garde une occurrence suivante liée.
- Un événement en retard ou dupliqué ne remplace pas un statut plus récent : séquence, version et event_id sont contrôlés. Une provenance absente retourne UNKNOWN, pas une valeur par défaut.

La session Focus V6.3 doit persister son lien plan_id/activity_id dès le démarrage. Le modèle Focus actuel contient un directiveId mais aucun de ces liens (FocusModels.kt:85-105), donc une session existante ne doit pas être rétroactivement présentée comme une preuve exacte d’activité.

## 9. Plugin OpenClaw, allowlist et rollback

Créer un plugin tool-only atlas_core_tools avec defineToolPlugin, Node/TypeScript ESM et TypeBox. L’API stable est exactement : atlas_get_day_plan, atlas_get_activity_status, atlas_propose_plan_revision, atlas_get_usage_summary, atlas_get_goal_progress, atlas_get_training_plan. Chaque paramètre et résultat est fermé par additionalProperties:false, et chaque capability renvoie UNAVAILABLE si elle est désactivée.

Manifest openclaw.plugin.json : id/name/version/description, configSchema strict, contracts.tools contenant ces six noms, toolMetadata statique, activation au démarrage seulement lorsque le flag ATLAS est activé. Un flag global interne désactive tout le plugin ; les capabilities lecture/proposition ont aussi leur propre flag. Le manifest ne contient aucun secret.

Les handlers vérifient le signal d’annulation, les limites, le schéma et le nom, puis appellent une route Core fixe en loopback. L’auth du plugin vers Core utilise un bearer dédié et scoped, distinct du Gateway token. Bind Core 127.0.0.1 (et ::1 si nécessaire), compare le token en temps constant, rejette les en-têtes forwarding et applique quotas par tool/conversation. Ne jamais appeler l’URL fournie par le modèle ni /tools/invoke.

Pour l’agent atlas, la policy effective doit exposer uniquement les six noms exacts. Configuration candidate à vérifier en instance isolée : `profile:"minimal"`, `deny:["gateway","session_status"]`, `alsoAllow:["atlas_get_day_plan","atlas_get_activity_status","atlas_propose_plan_revision","atlas_get_usage_summary","atlas_get_goal_progress","atlas_get_training_plan"]`. `deny` l’emporte sur toute autorisation ; `deny:["*"]` bloque tout, y compris les outils listés. Il n’existe pas de syntaxe `!outil`, et `allow` et `alsoAllow` ne se combinent pas dans une même portée. Ne pas inclure les outils de recherche dans atlas. Après activation, vérifier la liste réellement exposée avec `/tools verbose` dans une session atlas. Le workspace n’est pas un sandbox et le stockage d’un plugin peut être partagé ; la policy filtre les outils mais ne constitue pas une isolation runtime. Les 42 plugins restent chargés globalement et ne deviennent pas une allowlist atlas ; une isolation runtime complète nécessite un Gateway séparé.

L’installation locale proposée est `openclaw plugins install <path>` (ou `-l`) depuis un checkout contenant le manifeste natif à la racine avec `id` et `configSchema`. Avec le Gateway actif, l’installation s’applique sans redémarrage ; ne pas redémarrer les services live sans autorisation ciblée. Le plugin `atlas_core_tools` déclare précisément les six outils avec `defineToolPlugin`, TypeBox et `execute`. Les droits effectifs restent dans la policy `agents.entries.atlas.tools`, pas dans les instructions du workspace.

Les outils ont timeout connect 2 s, réponse 5 s, taille requête 64 KiB, réponse utile 256 KiB, 6 appels maximum par tour et un seul appel proposal par tour. Les lectures peuvent être retentées une fois. Une proposition utilise la même clé d’idempotence et un lookup avant toute répétition. Le journal technique ne garde que tool_name, capability, hash de requête, result_code, durée et call_id ; il ne stocke pas args, titres ou contenu de chat.

Avant toute future modification active, sauvegarder dans un emplacement privé horodaté ~/.openclaw/openclaw.json, le workspace Atlas, ses fichiers d’identité AGENTS.md/IDENTITY.md/SOUL.md/USER.md/TOOLS.md/MEMORY.md/HEARTBEAT.md/DREAMS.md et l’agentDir contenant openclaw-agent.sqlite. Sauvegarder la base via SQLite backup API ou arrêt contrôlé autorisé, conserver permissions et vérifier hashes/restaurabilité. Préserver chaque fichier ; ne jamais remplacer l’identité avec un template. Avec le disque audité à 96 % et le swap plein, vérifier l’espace de sauvegarde d’abord ; sinon état BLOCKED, sans purge automatique.

AGENTS.md doit avoir une section ## Tools qui décrit les six capacités, leur fraîcheur, le fait qu’une proposition n’est pas une commande, les données non fiables et la confirmation Android. AGENTS.md est injecté ; TOOLS.md n’est pas un fichier bootstrap reconnu et reste une annexe opérateur ; HEARTBEAT.md est retiré/non lu dans OpenClaw 2026.10.5. Les instructions du prompt ne remplacent pas les restrictions exécutées par le plugin et la policy.

Rollback : désactiver le flag atlas_core_tools, enlever le plugin de l’allowlist atlas et restaurer atomiquement les fichiers de configuration depuis la sauvegarde vérifiée. Ne pas écraser les fichiers de workspace ni la base agent. Contrôler ensuite que le chat local/mock fonctionne sans réseau et qu’aucun des six outils n’est exposé à atlas.

## 10. Orchestration du chat et preuve de bout en bout

openclaw_adapter.py doit envoyer au Gateway la liste complète et stricte des six fonctions avant le premier message de modèle, avec tool_choice: "auto" pour permettre une réponse directe lorsque l’outil n’est pas utile. Le schéma des fonctions est généré depuis une définition V4 unique et comparé au TypeBox/manifest à la construction. Un appel tool_calls est décodé par un dispatch fermé nom exact → route Core fixe ; tout autre nom ou JSON invalide reçoit un résultat d’erreur borné. Aucun nom ne devient segment d’URL.

Pour chaque outil demandé, l’adapter appelle le Core par son interface loopback dédiée, ajoute le résultat au message role=tool avec le tool_call_id correspondant et reprend la boucle. Maximum 6 appels/1 proposal, deadline totale 20 s, taille totale limitée ; un refus, timeout ou contenu périmé est retourné comme résultat typé et n’est pas transformé en succès. La réponse finale garde le contrat structuré V4 (reply, proposal_ids, agent_status, schema_version), validé strictement côté backend. V1/V2/V3 gardent leur forme actuelle.

Deux voies sont à évaluer. (a) Le client fournit les fonctions à `/v1/chat/completions`; l’adapter exécute chaque appel vers une route Core fixe, voie recommandée pour garantir le chemin Android. (b) L’agent appelle les outils internes du plugin exposés par sa policy ; cette voie n’est admissible qu’après preuve runtime. La route supporte `tools`, `tool_choice`, `message.tool_calls` et le retour `role:"tool"` avec `tool_call_id`, mais elle est désactivée par défaut et suit un run normal d’agent. Le chemin chat reste BLOCKED jusqu’à preuve sur une instance isolée. L’adapter actuel n’envoie pas `tools` et lit une seule fois `message.content` (openclaw_adapter.py:730-755). Dans les deux voies, exiger une boucle bornée : validation stricte des noms/arguments, quotas, timeouts, idempotence, exécution Core, continuation, puis parseur JSON final. Recommander l’installation du plugin pour la visibilité OpenClaw et la boucle client pour le dispatch déterministe. Prouver la découverte des outils et l’absence de double dispatch. Le bearer OpenAI compatible a un droit opérateur complet : privé, loopback/ingress privé, jamais dans le dépôt.

La preuve exigée est le trajet lancé depuis l’UI Android : **Android → Internet public → relais → PC/Core → Gateway OpenClaw réel → appel de l’un des six outils → résultat/proposition → Android**. Un test du plugin, du Core local, du mock OpenClaw ou du relais sur loopback ne couvre qu’un segment. Android doit ensuite afficher l’ACK réellement reçu. Répéter un segment hors ligne puis après reconnexion, sans transformer une projection mock en preuve réelle.

## 11. Recherche Bridge

atlas_chatgpt_research reste séparé des six outils requis. Le plugin existant doit être installé et validé pour OpenClaw 2026.10.5, Node 24.18, manifest, dépendances verrouillées, schéma et tests ; il n’est actuellement pas installé. L’état sain Bridge 127.0.0.1:8001 et l’extension connectée est un état audité fourni, pas une preuve de recherche réelle ni d’exécution du plugin.

Expérience minimale de délégation sur une instance OpenClaw isolée : agent `atlas-research` séparé avec `profile:"minimal"`, `deny:["gateway","session_status"]`, `alsoAllow:["atlas_chatgpt_research"]`, `subagents.allowAgents:["atlas-research"]` et `maxSpawnDepth:1`. Pour ce seul essai, la policy d’atlas peut exposer `sessions_spawn` et `sessions_yield` en plus des six outils, avec `agentId:"atlas-research"` seulement ; la policy de l’agent cible s’applique puis les restrictions de sous-agent refusent `gateway` et `session_status`. Vérifier avec `/tools verbose` l’absence d’héritage d’outils et la liste effective. Envoyer une requête synthétique bornée, sans contexte utilisateur, puis vérifier résultat, provenance, citations et journal expurgé. Le workspace n’est pas un sandbox et cette délégation n’isole pas le système. Si elle ne peut pas être bornée ainsi, désactiver la capacité ; elle reste désactivée par défaut.

Transmettre uniquement une question abstraite minimisée, sans DayPlan, statut, objectif nominatif, historique d’usage ou conversation brute. Retourner à atlas un résumé et des citations comme données non fiables.

Vérifier /health, /ready, la soumission et le polling du run, les citations visibles et le mode de recherche. N’étiqueter VERIFIED_SEARCH que si la métadonnée Bridge confirme le mode ui_tool ; prompt_instructed devient PROMPT_INSTRUCTED_UNVERIFIED. Les citations restent non vérifiées comme contenu et ne sont jamais des instructions. Si la clé Bridge est absente, renvoyer UNAVAILABLE, sans navigation générale de secours.

## 12. UX Android

- Ligne compacte : heure locale, titre, priorité discrète et état ; une action principale contextuelle (Démarrer, Terminer/confirmer, Reporter) et une action secondaire par menu.
- Menu contextuel : expliquer l’état et sa provenance, modifier, reporter, annuler volontairement. Une action terminale ou externe ouvre une confirmation qui nomme l’effet.
- Bottom sheet de report : créneau suivant, durée, conflit, priorité, catégorie, date/fuseau ; ancien/nouvel horaire visibles et conséquences expliquées. Le bouton final crée le bundle atomique. Aucune confirmation ne se déduit d’une fermeture de sheet.
- « Dire quelque chose au coach… » transmet la phrase uniquement après consentement de contexte ; la réponse sépare faits observés, proposition et résultat réellement appliqué.
- Focus montre le lien plan/activity et ses mesures, puis demande une confirmation avant de déclarer l’activité DONE.
- Notification sobre avec option de report ou d’ouverture ; contenu personnel masqué sur écran verrouillé par défaut. Pause d’urgence et arrêt du suivi toujours accessibles hors ligne.
- Accessibilité : cibles tactiles ≥48 dp, libellés TalkBack avec heure/titre/état/actions, police système à 2× sans troncature des actions, paysage, contraste suffisant, ordre de focus stable, absence d’action destructive sur double tap. Vérifier aussi l’état offline et le retour au launcher.

## 13. Calendrier, Etar, wger et UsageStats

**Calendrier/Etar :** CalendarContract reste l’interface, pas la base privée d’Etar ni son UI automatisée. Consentements CALENDAR_READ et CALENDAR_WRITE séparés. Lecture des événements nécessaires, avec origine fournisseur, dernière observation, sync_state et conflits ; aucun détail superflu vers le Core. Une écriture est A2, limitée à un événement marqué ATLAS et relu après effet. Un événement étranger est lecture seule. Si le provider ou la permission manque : UNAVAILABLE, jamais une URL ou un outil générique de calendrier. Il n’y a pas de septième outil calendrier dans le présent contrat.

**wger :** atlas_get_training_plan est lecture seule par défaut. Distinguer prescription (routine/slot) et WorkoutResult réellement enregistré ; source, sync_state, source_updated_at, observed_at, last_synced_at. Si token/config/API manque, l’adaptateur existant retourne UNAVAILABLE/CONFIG_REQUIRED ; ne pas fabriquer routine, charge, répétitions ou résultat. Toute future écriture wger est A2 avec confirmation, idempotence et read-back.

**UsageStats :** consentement OS Usage Access et consentement ATLAS séparés ; transmettre un agrégat borné, couverture/blind time, fiabilité, consent_version et fraîcheur. Aucun écran, texte d’app, notification privée ou détail package sans catégorie distincte. UsageStatsManager est best effort : aucune promesse d’interception instantanée ou exacte. En absence de permission, UNAVAILABLE, pas zéro minute.

## 14. Sécurité et vie privée

- Minimise les champs à la source et au relais. Titres/notes ne sont transmis que si Coach Context le permet ; notes exclues par défaut. Les noms de package exigent un consentement distinct.
- Consentements catégoriels révoqués localement immédiatement ; l’outbox et le Core rejettent les nouveaux envois après révocation. Effacer/expirer les projections concernées selon la politique et garder uniquement un tombstone non personnel nécessaire à l’audit.
- Par défaut, seuls les six outils exacts et les opérations relay explicitement négociées sont acceptés. Pas de shell, FS, ADB, navigateur, URL libre, envoi de message ni outil universel. `sessions_spawn`/`sessions_yield` restent désactivés sauf expérience de délégation opt-in ci-dessus, avec seul agent `atlas-research`, profondeur 1 et outils cibles bornés.
- Les messages, titres, URLs, calendrier, wger et citations sont des données non fiables. L’injection ne change ni capability, ni consentement, ni route, ni politique.
- Auth loopback du plugin distincte et route allowlistée ; le bearer des endpoints OpenAI compatibles, qui donne un droit opérateur complet, reste privé, limité à loopback/ingress privé et n’est jamais transmis au plugin ou au modèle. Aucun secret dans le dépôt, exemples, journaux, captures ou rapport.
- Limites, timeout, idempotence, expiration, version optimiste, hash de scope et refus explicite sont vérifiés côté serveur et à nouveau sur Android.
- Toute option possède un flag de capability indépendant. Désactivation : l’outil répond CAPABILITY_UNAVAILABLE ; elle ne bascule pas vers mock ou effet.
- Rétention proposée : projections de plan/statut 30 jours, usage agrégé 30 jours, objectifs selon consentement et suppression locale, journal technique 30 jours sans contenu. Le propriétaire doit confirmer ces durées avant l’implémentation.
- Les packages de plugins OpenClaw sont du code natif chargé dans le Gateway. Réduire leur surface au strict nécessaire, verrouiller les dépendances et vérifier le manifest/runtime avant activation.
- Incident de secret d’infrastructure : rotation hors dépôt recommandée ; aucune valeur n’est consignée dans ces documents ni dans les exemples.

## 15. Plan de tickets, scopes exclusifs et intégration

Chaque ticket sera relancé depuis un commit complet explicitement inscrit (la base de cette conception est 364a8000bbfa13f96b7fa905de8664017d7cee16 ; les commits parent déjà intégrés doivent être réconciliés avant de lancer les tickets). Worktree séparé, un seul propriétaire par fichier, permissions écriture limitées à son scope, oracle et preuves liés à son SHA. Le parent inspecte et intègre localement ; aucun push. Au plus trois tickets d’implémentation simultanés.

Règles obligatoires pour les tests d’intégration et leurs oracles : utiliser une instance Core isolée avec port et base dédiés, ou contacter `127.0.0.1:8100` uniquement avec authentification explicite pour une vérification en lecture seule ; aucune écriture dans la base live. Ne jamais arrêter ni redémarrer sans autorisation ciblée les services live 8100, 4400, 4401, 4402 ou 18789. Utiliser des mocks/faux services pour les cas négatifs. Une autorisation ciblée est requise avant toute preuve impliquant OpenClaw réel, relais réel ou Pixel physique. Les tickets et matrice ci-dessous héritent de ces règles.

| Ticket | Objectif/dépendances | Scope d’écriture exclusif proposé | Oracle et preuves attendues |
|---|---|---|---|
| V63-P1-ENV-CORE (diagnostic, 0 impl.) | Vérifier l’instance ATLAS Core réelle `127.0.0.1:8100` (`atlas_backend.main:app`, Core 2.0.0), depuis le checkout principal, distinct du worktree de revue ; vérifier ses routes et l’emplacement/configuration de sa base, ou qualifier une instance isolée dédiée. Le port 8000 est CTI Bulletin AutoWork, hors périmètre ATLAS. Lecture seule sur l’instance live. | Aucun fichier ; lecture seule. | Confirmer identité/routes/base sans lire ni exposer de secrets et sans écrire dans la base live ; aucune relance/arrêt de service. Pour les tests en écriture, instance isolée avec port et base dédiés. |
| V63-P1-OPENCLAW-PROOF (preuve documentaire, lecture seule) | Établir les faits OpenClaw 2026.10.5 sur SDK plugin, policy allow/deny, outils `tools`/`tool_calls`, installation locale, délégation et bootstrap. | Aucun fichier ; lecture seule de la documentation, des sources SDK et de la documentation CLI. | Oracle sourcé fichier:ligne : le SDK expose `defineToolPlugin`/TypeBox/`execute` (`dist/plugin-sdk/tool-plugin.d.ts:32-56,86-106`, `docs/plugins/tool-plugins.md:12-16,74-80`) ; `deny` l’emporte (`docs/gateway/config-tools/tool-policy.md:184-203`) ; le catalogue cible est exactement les six noms (`V4_CONTRACT_PROPOSAL.md:14`) ; `/v1/chat/completions` accepte tools/tool_calls mais est désactivé par défaut (`docs/gateway/openai-http-api.md:8,176-229`) ; l’installation locale et son manifest `id`/`configSchema` sont documentés (`docs/cli/plugins.md:44`, `docs/plugins/building-plugins.md:91-105,121-124`, `docs/plugins/tool-plugins.md:553-559`, `docs/tools/slash-commands.md:543-549`) ; la délégation explicite utilise `sessions_spawn`/`allowAgents` et `maxSpawnDepth` (`docs/tools/subagents/thread-bound-sessions.md:72-97`, `docs/tools/subagents/nesting.md:20-39,72-73`) ; `AGENTS.md` est chargé au démarrage, `TOOLS.md` est retiré/non-bootstrap et `HEARTBEAT.md` n’est plus lu (`docs/concepts/agent-workspace.md:73-86`, `docs/reference/templates/TOOLS.md:8-14`, `docs/reference/templates/HEARTBEAT.md:8-10`) ; `/tools verbose` affiche les outils effectifs (`docs/tools/slash-commands.md:303,412-419`). Runtime live non prouvé reste BLOCKED/NOT_RUN. **Statut : ACCEPTED pour la preuve documentaire ; runtime BLOCKED/NOT_RUN.** |
| V63-P1-V4-CONTRACTS | Rendre la proposition machine-readable. Dépend de l’architecture et audit P0 accepté. | contracts/v4/** ; nouveaux tests de schéma seulement sous backend/tests/test_v4_contracts.py. | Formaliser en JSON Schema tous les champs requis et types de toutes les réponses, enveloppes sync, propositions/ACK et entrées/sorties des six outils, avec `additionalProperties:false` à chaque objet imbriqué. Tests positifs/négatifs, versions/capabilities, compatibilité V1/V2/V3 inchangée ; exemples seuls insuffisants. Cas négatifs par mocks/faux services. |
| V63-P2-CORE-PROJECTIONS | Stocker/servir les snapshots typés et erreurs de fraîcheur, ainsi que les migrations V4 additives. Dépend des contrats V4. | backend/atlas_backend/store.py (ownership exclusif de cette ligne), nouveaux backend/atlas_backend/v4_projection_store.py et v4_tool_service.py ; tests backend/tests/test_v4_projection_store.py et test_v4_migrations.py. Aucun main.py. | Tests snapshot version monotone, idempotence, consentement, TTL, UNKNOWN/UNAVAILABLE, migration et quarantaine sur SQLite isolée dédiée ; jamais écrire dans la base live. Cas négatifs mockés. |
| V63-P2-CORE-ROUTES | Enregistrer les routes V4 et propositions/ACK. Dépend des contrats et service stable. | backend/atlas_backend/main.py, nouveau backend/atlas_backend/v4_router.py, backend/tests/test_v4_routes.py et backend/tests/test_v4_proposals.py. | Auth loopback, routes fixes, pagination, replay, stale, transition ACK et erreurs vérifiés sur Core isolé/DB dédiée ; aucune écriture live, aucun arrêt/redémarrage de service sans autorisation ciblée ; négatifs mockés. |
| V63-P2-ANDROID-SYNC | Synchroniser snapshots consentis/outbox vers Core via opérations autorisées. Dépend du contrat et relais amont accepté. | Nouveau package android/app/src/main/java/dev/atlas/coach/sync/v4/** ; tests sous android/app/src/test/java/dev/atlas/coach/sync/v4/**. | Offline, replay event_id, révocation, quota, ordre, réponse inconnue et compatibilité client V1 ; JVM puis Core isolé/relay simulé. Relay réel seulement après autorisation ciblée ; aucune écriture base live. |
| V63-P3-ANDROID-PLAN-V4 | Migration local schema 3→4, événement status, report atomique et provenance. Dépend des contrats et audit local accepté. | PlanModels.kt, PlanJsonCodec.kt, PlanRevisionEngine.kt, DayPlanRepository.kt, PlanStatusPolicy.kt, PlanController.kt, PlanUiComponents.kt, PlanScreen.kt ; tests uniquement android/app/src/test/java/dev/atlas/coach/plan/v4/**. | Round-trip 3→4, rollback/quarantaine, status terminal, no timer DONE, report b/cross-date atomic, scope_hash et nextActivity. |
| V63-P3-ANDROID-INBOX-V4 | Pull persistant d’inbox et journal ACK sans toucher au plan. Dépend de routes V4. | Nouveau android/app/src/main/java/dev/atlas/coach/sync/agent/inbox/v4/** ; tests correspondants dans android/app/src/test/java/dev/atlas/coach/sync/agent/inbox/v4/**. | Pull cursor, limite de capacité, déduplication, expiration, ACK offline/replay, APPLIED vs UNKNOWN. Aucun fichier du ticket Plan V4. |
| V63-P4-OPENCLAW-PLUGIN | Ajouter six outils natifs typés. Dépend des routes Core/auth, schémas et preuve documentaire V63-P1-OPENCLAW-PROOF. | Nouveau integrations/openclaw/atlas_core_tools/** seulement. | plugin:build/validate, manifest, TypeBox positif/négatif, timeout, abort, auth et route fixe ; Gateway isolé, policy effective vérifiée avec `/tools verbose` ; OpenClaw réel seulement après autorisation ciblée. |
| V63-P4-CHAT-ORCHESTRATION | Ajouter tools/function loop au chat structuré. Dépend du plugin/contrat et du comportement Gateway confirmé par V63-P1-OPENCLAW-PROOF au niveau documentaire. | backend/atlas_backend/openclaw_adapter.py ; nouveaux tests backend/tests/test_openclaw_adapter_v4.py. | tool_calls exacts, role=tool, final strict, appel inconnu refusé, 1 proposal, timeout incertain/replay ; instance Core/DB isolée, runtime Gateway isolé après autorisation ciblée, découverte et absence de double dispatch. |
| V63-P4-RELAY-V4 | Négocier les opérations V4 sans proxy générique. Dépend du propriétaire amont et du contrat 4001/1012 réconcilié. | Nouveaux integrations/relay/pc/src/v4/**, tests integrations/relay/pc/test/v4/** ; si le routeur historique doit changer, ticket distinct avant partage de fichiers. | Payload positif/négatif via faux service, test proxy local isolé préservant 4001 ; service live jamais arrêté/redémarré. Relais réel seulement après autorisation ciblée. BLOCKED jusqu’à correction amont, voir risque A4-4. |
| V63-P5-BRIDGE-RESEARCH | Installer/valider la recherche isolée. Dépend du plugin existant et délégation sûre. | integrations/openclaw/atlas_chatgpt_research/** seulement. | Test de délégation sur Gateway isolé, allowlists explicites, `/tools verbose`, absence d’héritage, puis Bridge réel /health, /ready, citations, mode ui_tool vs prompt_instructed après autorisation ciblée. |
| V63-P6-E2E | Qualifier le chemin complet réel et offline/reconnect. Dépend des tickets intégrés. | Harnais/preuves seulement sous nouveau dossier de tests V4 ; aucun code produit ni config active. | UI Android→Internet public→PC/Core isolé avec port/DB dédiés→OpenClaw→tool→ACK Android ; aucun écrit base live. Autorisations ciblées requises pour OpenClaw/relais réels. Mock/local plugin ne vaut pas PASS. |
| V63-P7-PIXEL | Qualifier API 37 Pixel sur SHA/APK figés. Dépend E2E, pré-vol, backup/restauration et consentements ciblés. | Dossier de preuves dédié, aucun code. | APK hashé, Pixel connecté effectivement, parcours, état final et limites, selon skill android17-device-qa ; autorisation ciblée requise. À ce jour NOT_RUN. |

Séquence proposée : (1) diagnostics Core/relais et contrats ; (2) Core projections, Android sync et définition du modèle local, au plus 3 implémentations ; (3) Core routes, inbox Android et plugin, au plus 3 ; (4) orchestration chat, UI Android et relais si l’amont est ouvert, au plus 3 ; (5) Bridge en ticket isolé ; (6) E2E ; (7) Pixel ; revue parent et intégration locale. Tout ticket doit confirmer son SHA de base, worktree, chemins exclusifs et modèle/effort avant exécution.

## 16. Matrice T01–T12

Tous les statuts ci-dessous sont NOT_RUN. Les tests de contrat, JVM/backend, plugin et relais ne qualifient pas à eux seuls OpenClaw réel, AVD ou Pixel. Aucun test Android 14 n’est prévu. Pour chaque test d’intégration : Core isolé avec port/DB dédiés, ou accès explicite à 8100 strictement en lecture seule ; jamais d’écriture dans la DB live. Ne pas arrêter/redémarrer 8100/4400/4401/4402/18789 sans autorisation ciblée. Cas négatifs par mocks/faux services. OpenClaw, relais réel et Pixel nécessitent une autorisation ciblée.

| ID | Oracle positif et négatif | Environnement requis | Ticket(s) |
|---|---|---|---|
| T01 | Six schémas valides ; propriétés inconnues, versions 5, capability absente, tailles et curseurs invalides refusés ; V1/V2/V3 byte-for-byte inchangés. | Contrats + tests backend, CI locale | V63-P1-V4-CONTRACTS |
| T02 | Snapshot Android monotone, déduplication et reprise ; plan absent, version régressive, révocation et timestamp futur deviennent UNKNOWN/refus. | JVM Android + backend SQLite isolé, port/DB dédiés pour intégration | V63-P2-ANDROID-SYNC, V63-P2-CORE-PROJECTIONS |
| T03 | Six outils exacts découverts/appelés ; auth invalide, tool inconnu, schéma invalide, permission manquante, timeout/quota refusés ; aucun shell/FS/URL ; vérifier `/tools verbose`. | Plugin unit ; Gateway 2026.10.5 isolé, accès réel sur autorisation ciblée | V63-P4-OPENCLAW-PLUGIN |
| T04 | Révision sans changement refusée ; version fraîche appliquée selon A0/A1/A2 ; stale, conflit, quota, consentement révoqué et scope_hash altéré refusés. | JVM Android, Core isolé/DB dédiée, puis AVD API 37 | V63-P3-ANDROID-PLAN-V4 |
| T05 | Pull inbox, réception, ACK applied/rejected/expired/uncertain, replay idempotent ; conflit de séquence ou même clé/autre corps refusé. | Backend/DB isolée, JVM Android, relais simulé ; relais réel sur autorisation ciblée | V63-P2-CORE-ROUTES, V63-P3-ANDROID-INBOX-V4 |
| T06 | Prompt appelle un outil requis, callback reçoit result tool_call_id puis final structuré ; nom dupliqué, double dispatch, réponse non JSON et timeout proposal restent sans succès inventé. `/v1/chat/completions` doit être activé et prouvé runtime ; sinon BLOCKED. | Mock/faux service négatifs ; Gateway + Core/DB isolés, OpenClaw réel sur autorisation ciblée | V63-P4-CHAT-ORCHESTRATION |
| T07 | Fil rouge UI Android→Internet public→PC/Core isolé→OpenClaw→outil→ACK Android ; refus si route publique/protocole/capability absents. Jamais d’écriture DB live ; aucune relance service live sans autorisation ciblée. | Réseau public et relais réel sur autorisation ciblée ; AVD API 37 puis Pixel (autorisation ciblée) | V63-P4-RELAY-V4, V63-P6-E2E, V63-P7-PIXEL |
| T08 | Résumé agrégé, période ≤14 jours, couverture/blind time et fiabilité ; sans usage consent/permission, noms d’apps non consentis, zéro synthétique ou stale déclarés à tort refusés. | JVM usage + backend + Android 17/API 37 | V63-P2-ANDROID-SYNC, V63-P2-CORE-PROJECTIONS |
| T09 | Goals sans progression inventée ; training distingue prescription/performance ; calendrier étranger reste lecture seule ; sans API/credential/permission, UNAVAILABLE au lieu de données fabriquées. | Backend, JVM, wger autorisé synthétique, fournisseur calendrier Android 17 | V63-P2-CORE-PROJECTIONS, tickets intégration dédiés |
| T10 | Bridge réel prêt, run unique, citations visibles, verified_search selon métadonnée ; extension absente, 401, timeout, prompt_instructed, injection ou citations invalides ne deviennent pas recherche vérifiée ; test delegation vérifie allowlists et absence d’héritage. | Gateway isolé ; Bridge 127.0.0.1:8001/extension après autorisation ciblée | V63-P5-BRIDGE-RESEARCH |
| T11 | Ligne, report, retour coach, undo compensatoire ; doublon/double tap, TalkBack, 48 dp, police 2×, contraste, paysage et offline fonctionnent sans action accidentelle. | JVM UI/domain puis AVD/Pixel Android 17/API 37 | V63-P3-ANDROID-PLAN-V4, V63-P7-PIXEL |
| T12 | Migration additive depuis DB/plan schema 3, quarantaine inconnue, rollback flag/config, fichiers identité conservés, reprise après crash ; backup manquant ou corruption bloque. | Backend SQLite isolée/DB dédiée, test store local, Gateway séparé/config isolée ; Pixel seulement après autorisation ciblée | V63-P2-CORE-ROUTES, V63-P3-ANDROID-PLAN-V4, V63-P7-PIXEL |

## 17. Risques, hypothèses et BLOCKED

| Priorité | Risque/état | Question ou preuve requise |
|---|---|---|
| P0 / BLOCKED | Le Core ATLAS réel est `127.0.0.1:8100`, `atlas_backend.main:app`, Core 2.0.0, depuis le checkout principal, distinct du worktree de revue ; port 8000 = CTI Bulletin AutoWork, hors ATLAS. Routes effectives et base exacte à vérifier sans écrire dans la base live. | Ticket diagnostic lecture seule sur 8100 ou instance dédiée isolée avec port/DB dédiés ; toute intégration avec écriture reste sur instance isolée. |
| P0 / BLOCKED | Le registre de campagne consulté cite la base 1dc5c528c66b9b3fc821ab7d90ffd1fa7c79766a, tandis que ce worktree de tâche est à 364a8000bbfa13f96b7fa905de8664017d7cee16. | Le parent réconcilie la base de campagne avant intégration et fixe un SHA complet pour chaque ticket ; cette tâche ne modifie pas le registre. |
| P0 / BLOCKED | Pixel 9 Pro XL API 37 détecté puis transport ADB perdu ; aucune preuve Pixel actuelle. | Reconnecter et qualifier seulement dans le ticket Pixel, après skill, pré-vol, backup et consentement ciblé. |
| P0 / BLOCKED | A4-4 relay upstream est hors dépôt ; proxy local préserve 4001, image `atlas-paseo-relay:r3a` transforme en 1012. TLS/Noise et révocation restent inchangés. | L’amont doit corriger l’image et accepter V4 ; intégrer un test proxy isolé qui prouve la préservation de 4001. |
| P0 / BLOCKED | `/v1/chat/completions` est désactivé par défaut ; aucune preuve runtime que les outils sont découverts et qu’il n’y a pas de double dispatch. | Test discriminant sur Gateway isolé des deux voies, avec `/tools verbose`, puis preuve finale depuis Android ; scout V63-P1-OPENCLAW-PROOF pour détails complémentaires. |
| P0 / BLOCKED | 42 plugins chargés globalement ; la policy filtre les outils visibles, mais workspace n’est pas sandbox et stockage plugin peut être partagé. | Garder atlas minimal/allowlisté ; Gateway séparé requis pour isolation runtime complète. Ne pas supposer une isolation système par agent. |
| P0 / SECURITY | Le bearer des endpoints OpenAI compatibles donne un droit opérateur complet. Incident de secret d’infrastructure signalé ; rotation hors dépôt recommandée, aucune valeur consignée. | Garder bearer privé et limité à loopback/ingress privé ; réaliser la rotation hors dépôt par le propriétaire opérationnel. |
| P1 | Disque à 96 %, swap plein : sauvegardes workspace/config, builds et SQLite peuvent manquer d’espace. | Mesurer l’espace avant toute sauvegarde/build ; pas de purge implicite. |
| P1 | Le snapshot Core peut être récent sans être l’état courant du Pixel. | Toujours exposer observed_at/last_synced_at/version ; stale/unknown explicites ; vérification locale finale. |
| P1 | Une application multi-date interrompue peut laisser deux fichiers de jour partiellement remplacés. | Implémenter et tester le journal transactionnel et sa récupération avant d’autoriser le report cross-date. |
| P1 | La provenance d’un DONE, d’un timer et d’une séance wger peut être confondue. | Tests discriminants et UI demandant confirmation ; aucune inférence automatique. |
| P1 | Révocation entre l’appel modèle et l’application locale. | Android revalide consent_version et permission juste avant commit ; propositions expirent/bloquent. |
| P1 | Extension Bridge active ne prouve pas une recherche vérifiée ; le test actuel est simulé. | Exécution réelle du plugin, du bridge, du navigateur et provenance/citations. |
| P2 | Les seuils de fraîcheur, quotas A1/rétention et limites de durée sont des valeurs de départ. | Confirmer par recette/accessibilité et consigner une décision avant implémentation. |
| P2 | Etar, fournisseur calendrier et credentials wger exacts non confirmés ici. | Identifier le fournisseur et le mode d’auth autorisé ; sinon adaptateur unsupported/UNAVAILABLE. |

Hypothèses réversibles de cette proposition : V4 est un namespace additif ; A0 reste le défaut ; aucune capability n’est activée avant ses tests ; seuils de fraîcheur et quotas ci-dessus sont des bornes initiales ; report recommandé option (b). Ces choix ne modifient pas REGISTRY.md, DECISIONS_V63.md ou RECETTE_V63.md et n’autorisent aucune opération externe.
