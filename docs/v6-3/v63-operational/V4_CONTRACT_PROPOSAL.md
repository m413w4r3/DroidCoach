# Proposition de contrats ATLAS V4

**Statut :** proposition documentaire, aucun schéma exécutable ajouté.
**Base de conception :** 364a8000bbfa13f96b7fa905de8664017d7cee16.
**Règle :** V4 est additif. Les contrats V1/V2/V3, leurs champs, enums et routes restent inchangés.

V4 désigne ici le protocole des outils et des projections Android→Core. Il ne signifie pas que le DayPlan local schema 3 est muté en place : l’extension locale éventuelle a sa propre migration schema 3→4 et garde un chemin de lecture/quarantaine.

## 1. Conventions communes

- JSON UTF-8, timestamps RFC 3339 en UTC avec suffixe Z ; dates locales ISO 8601 ; fuseaux IANA.
- Les objets de toutes les requêtes, réponses et structures imbriquées ont additionalProperties: false. Les versions et capabilities inconnues sont refusées.
- Les identifiants d’entités sont UUID canoniques en minuscules. event_id, request_id, proposal_id et ack_id sont stables et créés par leur propriétaire.
- Les outils sont exactement : atlas_get_day_plan, atlas_get_activity_status, atlas_propose_plan_revision, atlas_get_usage_summary, atlas_get_goal_progress, atlas_get_training_plan.
- Les permissions et consentements restent déterminés par Android. Un booléen reçu du modèle ou du Core ne peut ni donner ni agrandir un consentement.
- Les routes tools privées sont fixes, loopback seulement, authentifiées par un secret distinct du Gateway token. Le modèle ne fournit jamais une URL, un hôte, un chemin, un header, une méthode ou un nom d’opération.
- Limites communes proposées : message/tool payload ≤64 KiB ; sortie utile ≤256 KiB ; 6 appels d’outil par tour ; 1 création de proposition par tour ; délai de connexion 2 s, de réponse 5 s, total chat 20 s.
- Les erreurs sont expurgées et n’incluent ni trace, token, URL privée, nom de package privé, titre d’activité ni texte de chat.

Enveloppe de résultat de données :

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "capability": "day_plan.read",
  "request_id": "00000000-0000-4000-8000-000000000301",
  "source": "ANDROID_SYNC",
  "observed_at": "2026-10-29T08:02:00Z",
  "last_synced_at": "2026-10-29T08:02:04Z",
  "is_stale": false,
  "freshness": "FRESH",
  "data": {}
}
~~~

freshness vaut FRESH, STALE, UNAVAILABLE ou UNKNOWN. observed_at est produit par la source Android/wger ; last_synced_at est produit par Core à la réception. plan_id et version sont requis partout où un plan ou statut est retourné. Pour les autres domaines, la version propre au domaine reste distincte de plan.version. Un Core snapshot est toujours une projection, même lorsque freshness vaut FRESH.

Seuils proposés à confirmer : plan/statut 5 min ; usage 30 min ; objectifs 60 min ; wger 24 h, sous réserve de son propre updated_at. Un timestamp futur de plus de 2 min, une régression de version, un trou de séquence ou une permission inconnue conduit à UNKNOWN/STALE. STALE ne peut jamais être reformulé en état courant confirmé du Pixel. Quand la donnée manque, data est null et freshness est UNAVAILABLE ou UNKNOWN ; une liste vide ne signifie pas « aucune donnée » sauf si une source fraîche confirme réellement zéro élément.

## 2. Contrats des six outils OpenClaw

Les capabilities sont indépendantes : day_plan.read, activity_status.read, plan_revision.propose, usage_summary.read, goal_progress.read et training_plan.read. Un flag désactivé retourne CAPABILITY_UNAVAILABLE. La négociation renvoie aussi max page sizes, version de schéma et délai de fraîcheur ; aucun outil ne peut élargir cette liste.

### 2.1 atlas_get_day_plan

**Description :** lit un snapshot du jour demandé, ou du jour local du téléphone observé si local_date est omis. Renvoie activités, heures, priorités, états, plan_id, version, origin et fraîcheur. Le résultat ne prétend pas être l’état actuel du Pixel au-delà de son observed_at.

Entrée JSON stricte :

~~~json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "local_date": {"type": "string", "format": "date"},
    "page_size": {"type": "integer", "minimum": 1, "maximum": 32, "default": 32},
    "cursor": {"type": "string", "minLength": 1, "maxLength": 256}
  }
}
~~~

Sortie data : state vaut READY, NO_PLAN, UNAVAILABLE ou UNKNOWN. READY porte un objet plan avec plan_id, local_date, timezone, version ≥1, current_revision_id, origin, state, activités au plus 32 et la provenance d’état. Une activité comprend activity_id, kind, title, status, scheduled_local {local_date,start_minute,duration_minutes,timezone}, priority, goal_id nullable, continuity_id nullable, supersedes_activity_id nullable, status_provenance, status_decided_at nullable, status_observed_at nullable et status_sync_state. Les notes libres sont exclues. NO_PLAN est réservé à une observation fraîche qui confirme qu’aucun plan n’existe pour cette date. UNAVAILABLE/UNKNOWN portent data:null. Pagination retourne next_cursor nullable et truncated.

Erreurs : BAD_REQUEST, CAPABILITY_UNAVAILABLE, CONSENT_REQUIRED, DATA_UNAVAILABLE, CURSOR_INVALID, TIMEOUT. Idempotence : lecture pure, répétition équivalente pour le même snapshot/cursor. Fraîcheur : seuil 5 min ; toujours inclure source, observed_at, last_synced_at, plan_id, version, is_stale et freshness si le plan existe.

### 2.2 atlas_get_activity_status

**Description :** lit le statut d’une activité d’un plan Android, son horodatage de décision, sa provenance et l’état du dernier sync.

Entrée JSON stricte :

~~~json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["activity_id"],
  "properties": {
    "activity_id": {"type": "string", "format": "uuid"},
    "plan_id": {"type": "string", "format": "uuid"},
    "include_details": {"type": "boolean", "default": false}
  }
}
~~~

Sortie data : activity_id, plan_id, version, status (PLANNED, IN_PROGRESS, DONE, MISSED, CANCELLED, POSTPONED), status_provenance (DECLARED, MEASURED, CONFIRMED, UNKNOWN), status_source, status_decided_at nullable, status_observed_at nullable, last_synced_at nullable, sync_result (ACKED, PENDING, REJECTED, NOT_SYNCED, UNKNOWN), freshness, et details nullable. details ne contient que kind/title/scheduled_local/priority lorsque Coach Context l’autorise ; aucune note privée.

Erreurs : BAD_REQUEST, ACTIVITY_NOT_FOUND, PLAN_MISMATCH, CONSENT_REQUIRED, PERMISSION_REQUIRED, DATA_UNAVAILABLE, CAPABILITY_UNAVAILABLE, TIMEOUT. Idempotence : lecture pure. Fraîcheur : 5 min, avec plan_id/version de l’observation et is_stale explicite. Si l’état ou sa provenance n’est pas connu, status_provenance vaut UNKNOWN et aucune date de décision n’est inventée.

### 2.3 atlas_propose_plan_revision

**Description :** dépose une révision candidate dans l’inbox Core. Cela ne modifie jamais le DayPlan. L’application et le niveau de confirmation sont redéterminés par Android au moment de traiter la proposition.

Entrée JSON stricte :

~~~json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["plan_id", "base_version", "changes", "justification", "idempotency_key"],
  "properties": {
    "plan_id": {"type": "string", "format": "uuid"},
    "base_version": {"type": "integer", "minimum": 1},
    "changes": {
      "type": "array",
      "minItems": 1,
      "maxItems": 8,
      "items": {"oneOf": [
        {"$ref": "#/$defs/reschedule"},
        {"$ref": "#/$defs/create_activity"},
        {"$ref": "#/$defs/edit_activity"}
      ]}
    },
    "justification": {"type": "string", "minLength": 1, "maxLength": 280},
    "idempotency_key": {"type": "string", "format": "uuid"}
  },
  "$defs": {
    "reschedule": {
      "type": "object",
      "additionalProperties": false,
      "required": ["op", "activity_id", "target_start_minute"],
      "properties": {
        "op": {"const": "reschedule"},
        "activity_id": {"type": "string", "format": "uuid"},
        "target_start_minute": {"type": "integer", "minimum": 0, "maximum": 1439},
        "duration_minutes": {"type": "integer", "minimum": 1, "maximum": 1440}
      }
    },
    "create_activity": {
      "type": "object",
      "additionalProperties": false,
      "required": ["op", "client_ref", "kind", "title", "start_minute", "duration_minutes", "priority"],
      "properties": {
        "op": {"const": "create_activity"},
        "client_ref": {"type": "string", "minLength": 1, "maxLength": 40, "pattern": "^[A-Za-z0-9_-]+$"},
        "kind": {"enum": ["FOCUS", "ADMIN", "MEAL", "REST", "BUFFER", "OTHER"]},
        "title": {"type": "string", "minLength": 1, "maxLength": 80},
        "start_minute": {"type": "integer", "minimum": 0, "maximum": 1439},
        "duration_minutes": {"type": "integer", "minimum": 1, "maximum": 1440},
        "priority": {"enum": ["REQUIRED", "IMPORTANT", "OPTIONAL"]},
        "goal_id": {"type": ["string", "null"], "format": "uuid"}
      }
    },
    "edit_activity": {
      "type": "object",
      "additionalProperties": false,
      "required": ["op", "activity_id"],
      "properties": {
        "op": {"const": "edit_activity"},
        "activity_id": {"type": "string", "format": "uuid"},
        "title": {"type": "string", "minLength": 1, "maxLength": 80},
        "priority": {"enum": ["REQUIRED", "IMPORTANT", "OPTIONAL"]},
        "estimate_minutes": {"type": "integer", "minimum": 1, "maximum": 480}
      },
      "anyOf": [
        {"required": ["title"]},
        {"required": ["priority"]},
        {"required": ["estimate_minutes"]}
      ]
    }
  }
}
~~~

Les changements de statut, d’autorisation, calendrier, wger, liens Focus, date locale ou fuseau ne sont pas des opérations valides de cet outil. Un report demandé par le coach est une proposition de révision ; le geste Android Reporter crée localement le couple POSTPONED/nouvelle occurrence. La date cible d’une révision ordinaire est le local_date du plan. Le Core refuse un changement sur un autre plan/jour ; la fonction de report cross-date exige le bundle transactionnel Android décrit dans l’architecture.

Sortie data : proposal_id nullable, status (QUEUED, STALE_VERSION, BLOCKED), plan_id, base_version, observed_version nullable, diff_hash, diff array, conflicts array, confirmation_mode (A0_USER_REVIEW, A1_ANDROID_ELIGIBLE_TO_CHECK, A2_USER_CONFIRMATION), auto_application (NONE_AT_CORE), created_at, expires_at nullable. Un diff contient op, activity_id/client_ref et les champs before/after nécessaires ; il omet les notes.

Erreurs : BAD_REQUEST, VALIDATION_FAILED, PLAN_NOT_FOUND, STALE_VERSION, CONFLICT, CONSENT_REQUIRED, CAPABILITY_UNAVAILABLE, IDEMPOTENCY_CONFLICT, REPLAY_PENDING, RESULT_UNCERTAIN, QUOTA_EXCEEDED, TIMEOUT. Idempotence : idempotency_key est obligatoire ; corps canonique identique renvoie le même proposal_id ; même clé avec un autre corps est un conflit. Timeout : lookup par même clé, jamais nouveau key. Fraîcheur : snapshot de plan doit être ≤5 min ; même s’il est frais, Android revalide base_version localement. Le Core retourne A1 comme candidat vérifiable, jamais comme autorisation effective.

### 2.4 atlas_get_usage_summary

**Description :** lit un résumé agrégé d’une période bornée avec couverture, fiabilité, consentement et fraîcheur. Aucun écran, notification, contenu de message ou historique privé brut.

Entrée JSON stricte :

~~~json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["window_start", "window_end"],
  "properties": {
    "window_start": {"type": "string", "format": "date-time"},
    "window_end": {"type": "string", "format": "date-time"},
    "include_app_labels": {"type": "boolean", "default": false},
    "app_limit": {"type": "integer", "minimum": 1, "maximum": 10, "default": 10}
  }
}
~~~

window_end doit être postérieur à window_start et l’écart ≤14 jours. include_app_labels ne donne aucun consentement ; si la catégorie de noms n’est pas consentie, les labels restent absents.

Sortie data : window_start, window_end, timezone, total_foreground_ms, apps (app_ref pseudonyme, display_name nullable, foreground_ms, launch_count, last_used_at nullable, reliability HIGH/MEDIUM/LOW), coverage {observed_ms, blind_ms, events_seen, truncated}, consent_state (GRANTED, REVOKED, UNKNOWN), consent_version, generated_at, freshness. Absence de données n’est pas zéro.

Erreurs : BAD_REQUEST, CONSENT_REQUIRED, PERMISSION_REQUIRED, CAPABILITY_UNAVAILABLE, PERIOD_TOO_LARGE, DATA_UNAVAILABLE, TIMEOUT. Idempotence : lecture pure pour une fenêtre et version de consentement données. Fraîcheur : seuil 30 min ; source Android agrégée et horodatages requis.

### 2.5 atlas_get_goal_progress

**Description :** lit les objectifs actifs, sous-objectifs, mesures disponibles, engagements confirmés, avancement connu et données manquantes. Les progrès non calculables restent inconnus.

Entrée JSON stricte :

~~~json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "page_size": {"type": "integer", "minimum": 1, "maximum": 20, "default": 20},
    "cursor": {"type": "string", "minLength": 1, "maxLength": 256},
    "include_subgoals": {"type": "boolean", "default": true}
  }
}
~~~

Sortie data : goals array avec goal_id, title, status ACTIVE/PAUSED/COMPLETED/ABANDONED, metric, progress nullable, progress_source nullable, observed_at nullable, target nullable, subgoals array optionnelle, confirmed_commitments array et missing_data enum. Progress est une mesure connue {value,unit,target_value}; null accompagné de missing_data (NO_MEASURE, STALE, CONSENT_REQUIRED, SOURCE_UNAVAILABLE). Un objectif absent du snapshot ne signifie pas qu’il n’existe pas.

Erreurs : CAPABILITY_UNAVAILABLE, CONSENT_REQUIRED, DATA_UNAVAILABLE, CURSOR_INVALID, TIMEOUT. Idempotence : lecture pure. Fraîcheur : seuil 60 min ; source Android et goals_version doivent être incluses.

### 2.6 atlas_get_training_plan

**Description :** lit les séances et prescriptions wger disponibles, avec sync_state, source, fraîcheur et absence de données. Une prescription n’est jamais une performance réalisée.

Entrée JSON stricte :

~~~json
{
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "local_date": {"type": "string", "format": "date"},
    "include_results": {"type": "boolean", "default": false},
    "page_size": {"type": "integer", "minimum": 1, "maximum": 20, "default": 20},
    "cursor": {"type": "string", "minLength": 1, "maxLength": 256}
  }
}
~~~

Sortie data : state READY/NO_DATA/UNAVAILABLE/UNKNOWN, provider wger ou null, source WGER_API/ANDROID_SYNC/DEMO, sync_state FRESH/STALE/UNAVAILABLE/MISMATCH/UNKNOWN, source_updated_at nullable, observed_at, last_synced_at, routine references, sessions prescrites, résultats réellement lus si include_results et autorisés, et freshness. Performance porte sa provenance WGER_API/USER_DECLARED/IMPORTED et vérification API_READ_BACK/USER_CONFIRMED/UNVERIFIED. Aucun poids ou résultat n’est généré par le modèle.

Erreurs : CAPABILITY_UNAVAILABLE, CONSENT_REQUIRED, CONFIG_REQUIRED, AUTH_REQUIRED, DATA_UNAVAILABLE, CURSOR_INVALID, TIMEOUT, UPSTREAM_UNAVAILABLE. Idempotence : lecture pure ; pagination cursor liée à la date et au provider. Fraîcheur : seuil proposé 24 h ou timestamp source wger plus récent.

## 3. Enveloppes V4 de sync Android → Core

Enveloppe commune :

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "request_id": "00000000-0000-4000-8000-000000000401",
  "capability": "day_plan.snapshot.write",
  "sent_at": "2026-10-29T08:02:04Z",
  "consent_version": 3,
  "idempotency_key": "00000000-0000-4000-8000-000000000402",
  "data": {}
}
~~~

device_ref vient de la session relais authentifiée, pas du JSON. capability doit appartenir à l’opération routée. Chaque catégorie de partage correspond à une autorisation Android distincte. Une révocation bloque l’émission suivante ; une valeur de consentement ancienne est refusée.

### 3.1 Snapshot de plan synthétique

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "request_id": "00000000-0000-4000-8000-000000000401",
  "capability": "day_plan.snapshot.write",
  "sent_at": "2026-10-29T08:02:04Z",
  "consent_version": 3,
  "idempotency_key": "00000000-0000-4000-8000-000000000402",
  "data": {
    "plan_id": "00000000-0000-4000-8000-000000000101",
    "local_date": "2026-10-29",
    "timezone": "Europe/Paris",
    "version": 7,
    "current_revision_id": "00000000-0000-4000-8000-000000000102",
    "state": "APPROVED",
    "origin": "LOCAL",
    "observed_at": "2026-10-29T08:02:00Z",
    "activities": [
      {
        "activity_id": "00000000-0000-4000-8000-000000000103",
        "kind": "FOCUS",
        "title": "Lecture exemple",
        "status": "PLANNED",
        "scheduled_local": {
          "local_date": "2026-10-29",
          "start_minute": 600,
          "duration_minutes": 45,
          "timezone": "Europe/Paris"
        },
        "priority": "OPTIONAL",
        "goal_id": null,
        "continuity_id": null,
        "supersedes_activity_id": null,
        "status_provenance": "UNKNOWN",
        "status_decided_at": null
      }
    ]
  }
}
~~~

Reçu Core synthétique :

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "request_id": "00000000-0000-4000-8000-000000000401",
  "capability": "day_plan.snapshot.write",
  "accepted": true,
  "replayed": false,
  "stored_version": 7,
  "snapshot_hash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "last_synced_at": "2026-10-29T08:02:05Z",
  "result_code": "ACCEPTED"
}
~~~

### 3.2 Événement de statut synthétique

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "request_id": "00000000-0000-4000-8000-000000000411",
  "capability": "activity_status.write",
  "sent_at": "2026-10-29T09:00:03Z",
  "consent_version": 3,
  "data": {
    "event_id": "00000000-0000-4000-8000-000000000412",
    "sequence": 2,
    "plan_id": "00000000-0000-4000-8000-000000000101",
    "activity_id": "00000000-0000-4000-8000-000000000103",
    "continuity_id": null,
    "plan_version": 8,
    "from_status": "IN_PROGRESS",
    "to_status": "DONE",
    "provenance": "CONFIRMED",
    "source": "USER_UI",
    "decided_at": "2026-10-29T09:00:00Z",
    "observed_at": "2026-10-29T09:00:00Z",
    "focus_session_id": "00000000-0000-4000-8000-000000000413"
  }
}
~~~

La valeur DONE est acceptable ici uniquement parce que la personne a confirmé dans l’UI. FOCUS_TIMER seul produit une mesure, pas une transition DONE. Réponse d’item : ACCEPTED, DUPLICATE, STALE_EVENT ou REJECTED, avec last_synced_at et result_code. Un même event_id avec un contenu différent vaut EVENT_ID_CONFLICT.

### 3.3 Usage, goals et training

Les routes usage-summary, goal-progress-snapshot et training-snapshot emploient la même enveloppe et le même reçu. Leurs data restent des objets spécifiques stricts :

- usage : window_start/end, timezone, total_foreground_ms, apps pseudonymisées, coverage, generated_at, consent_version ; maximum 14 jours et 10 applications.
- goals : goals_version, observed_at, objectifs et sous-objectifs actifs, mesures réellement observées, confirmations ; pas de titre ou description non nécessaire.
- training : provider, source, sync_state, observed_at, source_updated_at, prescriptions et résultats réellement lus. Un état sans credential autorisé est CONFIG_REQUIRED/UNAVAILABLE, jamais un tableau déduit.

Les snapshots et événements n’étendent pas le contrat sync-v1.schema.json. Chaque route V4 possède sa capability, sa validation et son index d’idempotence.

## 4. Enveloppe de proposition, inbox et ACK

### 4.1 Proposition Core synthétique

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "proposal_id": "00000000-0000-4000-8000-000000000501",
  "capability": "plan_revision.propose",
  "idempotency_key": "00000000-0000-4000-8000-000000000502",
  "proposal_hash": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "created_at": "2026-10-29T08:10:00Z",
  "expires_at": "2026-10-30T08:10:00Z",
  "state": "PENDING_ANDROID",
  "plan_id": "00000000-0000-4000-8000-000000000101",
  "base_version": 7,
  "origin": "AGENT",
  "justification": "Proposition de déplacement dans le même jour.",
  "changes": [
    {
      "op": "reschedule",
      "activity_id": "00000000-0000-4000-8000-000000000103",
      "target_start_minute": 660,
      "duration_minutes": 45
    }
  ],
  "diff": [
    {
      "op": "reschedule",
      "activity_id": "00000000-0000-4000-8000-000000000103",
      "before": {"start_minute": 600, "duration_minutes": 45},
      "after": {"start_minute": 660, "duration_minutes": 45}
    }
  ],
  "conflicts": [],
  "confirmation_mode": "A0_USER_REVIEW",
  "auto_application": "NONE_AT_CORE"
}
~~~

Le Core fixe proposal_id et expires_at après validation ; il ne crée pas de PlanConfirmation Android. A0 est le mode par défaut. Une valeur A1_ANDROID_ELIGIBLE_TO_CHECK ne contraint pas Android à appliquer. proposal_hash ne remplace pas le scope_hash recalculé localement sur le diff et les effect IDs exacts.

Transitions de l’inbox V4 : PENDING_ANDROID → RECEIVED → AWAITING_USER → APPLIED, REJECTED, STALE_VERSION, EXPIRED ou VALIDATION_REJECTED. PENDING_ANDROID/RECEIVED peuvent aussi aller directement vers un terminal lorsque le client fournit la raison valide. UNCERTAIN peut évoluer vers RECONCILED avec résultat APPLIED ou NOT_APPLIED ; il ne devient pas APPLIED sans vérification de l’état local. Les ACK sont un historique append-only.

### 4.2 ACK Android synthétique

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "ack_id": "00000000-0000-4000-8000-000000000601",
  "capability": "proposal.ack",
  "proposal_id": "00000000-0000-4000-8000-000000000501",
  "sequence": 3,
  "state": "APPLIED",
  "result_code": "APPLIED",
  "occurred_at": "2026-10-29T08:12:00Z",
  "plan_id": "00000000-0000-4000-8000-000000000101",
  "applied_plan_version": 8,
  "revision_id": "00000000-0000-4000-8000-000000000602",
  "scope_hash": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
  "effect_ids": [
    "PLAN_ACTIVITY_UPDATE:00000000-0000-4000-8000-000000000103",
    "PLAN_APPLY:00000000-0000-4000-8000-000000000602"
  ],
  "consent_version": 3
}
~~~

Un ACK APPLIED requiert version, revision_id, scope_hash et effect_ids. REJECTED/STALE_VERSION/EXPIRED/VALIDATION_REJECTED requièrent un result_code stable et ne contiennent pas de texte privé. UNCERTAIN n’affirme aucun effet. occurred_at est gardé avec received_at Core ; les délais et horloges sont contrôlés, sans le confondre avec last_synced_at. Même ack_id et même corps = rejeu accepté sans nouvel effet ; même ack_id et autre corps = ACK_ID_CONFLICT. Un résultat réseau incertain reste à réconcilier ; pas de retry avec un autre ack_id pour le même événement.

## 5. Erreurs V4 et négociation

Enveloppe d’erreur stricte :

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "request_id": "00000000-0000-4000-8000-000000000701",
  "error": {
    "code": "STALE_VERSION",
    "message": "La projection de plan ne correspond plus à la version demandée.",
    "retryable": false,
    "capability": "plan_revision.propose",
    "retry_after_seconds": null
  }
}
~~~

Codes stables : BAD_REQUEST, UNKNOWN_FIELD, UNSUPPORTED_VERSION, CAPABILITY_UNAVAILABLE, AUTH_REQUIRED, CONSENT_REQUIRED, PERMISSION_REQUIRED, NOT_FOUND, PLAN_NOT_FOUND, ACTIVITY_NOT_FOUND, PLAN_MISMATCH, DATA_UNAVAILABLE, DATA_UNKNOWN, STALE_DATA, STALE_VERSION, CONFLICT, VALIDATION_FAILED, IDEMPOTENCY_CONFLICT, EVENT_ID_CONFLICT, ACK_ID_CONFLICT, ACK_CONFLICT, PROPOSAL_EXPIRED, REPLAY_PENDING, RESULT_UNCERTAIN, QUOTA_EXCEEDED, RATE_LIMITED, PERIOD_TOO_LARGE, CURSOR_INVALID, CONFIG_REQUIRED, AUTH_UPSTREAM_REQUIRED, UPSTREAM_UNAVAILABLE, TIMEOUT, PAYLOAD_TOO_LARGE, INTERNAL.

Mapping HTTP proposé : 400 pour JSON/enum invalide ; 401 auth ; 403 consentement/permission/capability interdite ; 404 route ou entité inconnue ; 409 stale/conflict/replay conflict ; 413 taille ; 422 contrainte métier ; 429 quota ; 503 source indisponible ; 504 timeout. La réponse n’inclut aucun détail interne. CAPABILITY_UNAVAILABLE est distinct de DATA_UNAVAILABLE. DATA_UNKNOWN et freshness UNKNOWN ne sont pas convertis en succès certain.

Négociation :

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "service": "atlas-core",
  "supported_contract_versions": [2, 3, 4],
  "capabilities": {
    "day_plan.read": true,
    "activity_status.read": true,
    "plan_revision.propose": true,
    "usage_summary.read": false,
    "goal_progress.read": false,
    "training_plan.read": true
  },
  "limits": {
    "max_tool_calls_per_turn": 6,
    "max_proposals_per_turn": 1,
    "max_page_size": 32,
    "max_usage_window_days": 14
  }
}
~~~

Les flags ci-dessus sont un exemple synthétique, pas un état live. Le relais V1 reste atlas.remote.v1 ; extension proposée atlas.remote.v2 ajoute la liste finie des opérations V4 et négocie la version dans le handshake. V4 n’est pas envoyé tant que les deux pairs ne l’annoncent pas. Un client V1/V2/V3 continue ses appels actuels. Il n’y a pas de downgrade silencieux si la conversation nécessite un outil ou des données V4. La route V3 directe reste inchangée mais n’est pas la voie d’inbox V4.

### 5.1 Contrat du chat V4

Le chat Android passe par POST /v4/agent/chat, en plus des routes historiques qui restent inchangées. L’application transmet uniquement le texte et les catégories explicitement partagées. Le Core lit les projections via les six outils quand c’est nécessaire ; le plan entier n’est pas copié dans le message.

Entrée stricte :

~~~json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["contract_version", "schema_version", "request_id", "conversation_id", "message", "consent_version", "shared_categories"],
  "properties": {
    "contract_version": {"const": 4},
    "schema_version": {"const": 4},
    "request_id": {"type": "string", "format": "uuid"},
    "conversation_id": {"type": "string", "minLength": 1, "maxLength": 80},
    "message": {"type": "string", "minLength": 1, "maxLength": 4000},
    "consent_version": {"type": "integer", "minimum": 1},
    "shared_categories": {
      "type": "array",
      "maxItems": 5,
      "uniqueItems": true,
      "items": {"enum": ["DAY_PLAN", "ACTIVITY_STATUS", "USAGE_SUMMARY", "GOAL_PROGRESS", "TRAINING_PLAN"]}
    }
  }
}
~~~

shared_categories ne donne pas de permission : Core vérifie la version et les consentements reçus d’Android. Le message utilisateur, ses titres/URLs et les données contextuelles sont des entrées non fiables. request_id est idempotent sur le corps canonique ; réutiliser le même ID avec un autre contenu donne IDEMPOTENCY_CONFLICT. Limites : 4 000 caractères, 20 secondes total, 6 appels outil, 1 proposition.

Sortie stricte :

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "request_id": "00000000-0000-4000-8000-000000000901",
  "reply": "Le déplacement est proposé pour validation dans ATLAS.",
  "proposal_ids": ["00000000-0000-4000-8000-000000000501"],
  "agent_status": "ok",
  "tool_state": "completed",
  "tool_call_count": 2
}
~~~

reply est une chaîne de 1 à 4 000 caractères ; proposal_ids contient au plus un UUID de proposal déjà persistée ; agent_status vaut ok, mock ou unavailable ; tool_state vaut none, completed, partial ou uncertain ; tool_call_count est entre 0 et 6. MOCK est visible et ne représente pas une réponse OpenClaw réelle. Un outil UNKNOWN/timeout donne tool_state uncertain/partial et la réponse ne peut pas prétendre qu’une action a été appliquée. Aucune donnée brute du tool n’est recopiée dans le résultat final.

## 6. Persistance Core et migrations conceptuelles

Les tables existantes V1/V2 restent intactes. Tables conceptuelles V4 :

~~~text
schema_migrations(
  version INTEGER PRIMARY KEY, name TEXT UNIQUE,
  checksum TEXT, applied_at TEXT
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
  old_status TEXT, new_status TEXT, provenance TEXT, source TEXT,
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

projection_json est une lecture minimisée, jamais une source canonique. Les clés doivent inclure device_ref issu de l’appairage et non d’un champ libre. Les événements de statut/ACK sont immuables ; les snapshots acceptent uniquement des versions monotones. Les index couvrent expires_at, state, observed_at et les clés d’idempotence.

Migration : insérer un enregistrement schema_migrations par transaction, contrôler la checksum, effectuer une sauvegarde SQLite cohérente avant toute écriture, appliquer des tables/colonnes additives, puis vérifier contraintes et integrity_check. PRAGMA user_version peut servir de contrôle secondaire. Une migration inconnue ou une version de payload future va en quarantaine sans perte ; les octets d’origine et un hash sont conservés, sans convertir, supprimer ni écrire dans une table V1/V2.

Rollback : désactiver les routes et flags V4 ; l’ancien code ignore les nouvelles tables. Ne pas supprimer les tables et ne pas exécuter de down-migration destructive. Si restauration requise, vérifier la sauvegarde et les opérations externes UNKNOWN avant de restaurer. Un rollback Core ne change jamais le plan Android. La migration locale du DayPlan conserve le document schema 3 intact jusqu’à validation du schema 4 et peut revenir en lecture seule à l’ancien document.

## 7. Validation et refus

| Cas | Résultat requis |
|---|---|
| JSON malformé, mauvais type, propriété supplémentaire ou enum inconnue | BAD_REQUEST/UNKNOWN_FIELD ; aucune écriture ou génération d’ID suivie d’effet. |
| contract/schema version inconnue | UNSUPPORTED_VERSION ; conserver/quarantainer sans perte, ne pas ignorer le champ. |
| Capability inactive ou permission/consentement absent/révoqué | CAPABILITY_UNAVAILABLE ou CONSENT_REQUIRED/PERMISSION_REQUIRED ; ne pas servir une ancienne projection comme résultat frais. |
| Snapshot absent, mock ou source non reconnue | DATA_UNAVAILABLE ou DATA_UNKNOWN, source explicitement MOCK/UNKNOWN ; jamais inventer un plan vide. |
| base_version différente de l’observation Core ou Android | STALE_VERSION ; aucune rebase automatique ; relire et demander nouvelle proposition. |
| Conflit d’horaire/catégorie, activité terminale ou changement non autorisé | CONFLICT/VALIDATION_FAILED avec IDs minimaux et aucun effet. |
| Rejeu exact d’une clé | Retour de la réponse persistée, sans seconde proposition/événement/effet. |
| Même clé et corps différent | IDEMPOTENCY_CONFLICT ; l’entrée initiale reste inchangée. |
| Timeout d’une lecture | TIMEOUT ou retry borné ; résultat UNKNOWN s’il n’est pas vérifiable. |
| Timeout pendant proposition/ACK | RESULT_UNCERTAIN ; interrogation de la même clé/event, jamais de nouvelle clé. |
| Nom d’outil ou route non allowlisté, URL/champ de ciblage ajouté | Refus avant dispatch ; aucun fallback HTTP. |
| Pagination avec cursor expiré, autre filtre ou autre appareil | CURSOR_INVALID ; recommencer une lecture limitée sans modifier l’état. |
| Quota, nombre d’activités, période ou taille dépassés | QUOTA_EXCEEDED/PERIOD_TOO_LARGE/PAYLOAD_TOO_LARGE, valeur max annoncée. |

Un refus local Android n’est pas une erreur de transport : ACK REJECTED/STALE_VERSION est durable et revient au Core. Une réponse non reçue n’est jamais assimilée à un rejet ou à une application.

## 8. Compatibilité et sécurité du modèle

V1 sync et ses six types d’événements ne changent pas ; les snapshots/statuts/usage/goals/training utilisent les nouvelles routes V4. V2 inbox et ACK restent valides pour leurs propres proposals ; leur enum n’est pas étendue. La route V3 reste directe et conserve son schéma strict, même si elle n’offre pas l’inbox V4. Aucun client ancien n’a à connaître contract_version 4.

Le nouveau client compare les capabilities avant l’appel. Sans V4, il continue uniquement une interaction V2 qui n’a pas besoin des outils ou du DayPlan V4 ; sinon il affiche service incompatible/indisponible. Il ne transforme pas un échec V4 en appel V3 sans consentement explicite du flux, et ne prend jamais le mock /v1/plan/today pour un plan Android.

Chaque handler refuse les destinations hors loopback et utilise la même route fixe. Les outils de lecture ne changent rien. Le seul outil de mutation crée une entrée de proposition ; le Core n’a aucun endpoint tool qui applique une PlanRevision. L’application passe exclusivement par Android et les règles locales A0/A1/A2. Les appels sont limités, les paramètres/retours expurgés, les données externes non fiables et les journaux ne conservent pas le contenu.

## 9. Cas synthétiques de compatibilité et d’erreur

Une réponse connue peut indiquer un état frais sans prétendre à un état Pixel actuel :

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "capability": "activity_status.read",
  "request_id": "00000000-0000-4000-8000-000000000801",
  "source": "ANDROID_SYNC",
  "observed_at": "2026-10-29T09:00:00Z",
  "last_synced_at": "2026-10-29T09:00:04Z",
  "is_stale": false,
  "freshness": "FRESH",
  "data": {
    "activity_id": "00000000-0000-4000-8000-000000000103",
    "plan_id": "00000000-0000-4000-8000-000000000101",
    "version": 8,
    "status": "DONE",
    "status_provenance": "CONFIRMED",
    "status_source": "USER_UI",
    "status_decided_at": "2026-10-29T09:00:00Z",
    "status_observed_at": "2026-10-29T09:00:00Z",
    "sync_result": "ACKED",
    "details": null
  }
}
~~~

Un appareil sans snapshot ne ressemble pas à une journée vide :

~~~json
{
  "contract_version": 4,
  "schema_version": 4,
  "request_id": "00000000-0000-4000-8000-000000000802",
  "error": {
    "code": "DATA_UNAVAILABLE",
    "message": "Aucun snapshot Android disponible pour cette date.",
    "retryable": true,
    "capability": "day_plan.read",
    "retry_after_seconds": 60
  }
}
~~~

Aucun exemple ci-dessus ne contient de donnée personnelle réelle ; UUIDs, dates, titres, hash et timestamps sont synthétiques.
