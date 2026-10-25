# ATLAS V6.3 — Contrats de domaine et frontières d'autorité

Version de contrat : **3** (additif). Les contrats V1 (`directive`, `sync`) et V2
(`proposal-v2.schema.json`, mandat V2, inbox V2) restent **inchangés et valides**.
Un client V6.2 doit continuer à fonctionner sans connaître `schema_version: 3`.

Statut : spécification de référence des tickets V6.3. Les entités marquées
« implémentée » le sont dans le lot indiqué ; les autres sont des cibles, pas des
capacités annoncées.

## 1. Autorités

| Décision | Autorité finale | Le modèle peut | Le modèle ne peut pas |
|---|---|---|---|
| Mandat, consentements, permissions | Android local | proposer une explication | accorder, élargir, révoquer à sa place |
| `DayPlan` approuvé et révision courante | Android local | proposer une révision | appliquer sans validation locale |
| Événements calendrier | Android (`CalendarContract`) | proposer un changement de plan | écrire directement, supprimer un événement non marqué ATLAS |
| Performance wger | wger (source de vérité) + confirmation Android | proposer une adaptation | inventer une séance, augmenter une charge seul |
| Contenu / recherche | Bridge + validation locale | demander une recherche, proposer un contenu | exécuter une instruction trouvée dans le contenu |
| Effets externes | ATLAS Core après autorisation Android | — | contourner l'enveloppe `ExternalOperation` |

Règle générale : **`ATLAS → OpenClaw → proposition`** puis
**`Android → validation/autorisation → effet`**. Aucune proposition n'est une commande.

## 2. Versionnement

- Chaque document persistant porte `schema_version`. Les documents V6.3 utilisent
  `schema_version: 3` pour les nouvelles entités et ne modifient pas le document
  global `schemaVersion: 1` existant.
- Les propositions agent conservent `schema_version: 2` tant qu'une action V2 est
  utilisée. Une proposition V6.3 porte `schema_version: 3` et un `capability`
  explicite ; un client qui ne connaît pas la version la **refuse**, il ne l'ignore
  pas.
- Une migration inconnue est mise en quarantaine sans perte : l'état précédent
  reste lisible, aucune écriture destructive n'est faite.
- Les schémas machine-lisibles vivent dans `contracts/` ; le document présent fait
  foi pour la sémantique.

## 3. Entités

### 3.1 `Goal` / `SubGoal` (extension de l'existant)

```text
Goal      { goal_id, title, description?, horizon: SHORT|LONG, status,
            target_minutes?, created_at, updated_at, source, version }
SubGoal   { sub_goal_id, goal_id, title, status, target_minutes?,
            due_date?, created_at, updated_at }
```

Règles : `goal_id`/`sub_goal_id` stables (UUID) ; suppression logique avant purge ;
un sous-objectif appartient à un seul objectif ; un objectif archivé ne crée plus
d'activités nouvelles. L'existant `dev.atlas.coach.goals` reste la base ; le
`DayPlan` référence `goal_id` sans dupliquer le titre comme source de vérité.

### 3.2 `Activity`

```text
Activity {
  activity_id: UUID
  plan_id: UUID
  kind: FOCUS|TRAINING|APPOINTMENT|ADMIN|MEAL|REST|BUFFER|OTHER
  title: string (1..80)
  status: PLANNED|IN_PROGRESS|DONE|MISSED|CANCELLED|POSTPONED
  scheduled_local: { local_date: ISO-8601, start_minute: 0..1439,
                     duration_minutes: 1..1440, timezone: IANA }
  priority: REQUIRED|IMPORTANT|OPTIONAL
  goal_id?: UUID, sub_goal_id?: UUID
  focus?: { focus_minutes, break_minutes, cycles }
  training_ref?: TrainingPlanReference
  calendar_link?: CalendarEventLink
  estimate_minutes?: int
  actual_minutes?: int
  created_revision_id: UUID
  last_revision_id: UUID
  notes?: string (<= 280)
}
```

Sémantique **distincte et non interchangeable** :
`DONE` exige une confirmation explicite ou une mesure locale autorisée ;
`MISSED` est un constat après la fenêtre, jamais une déduction automatique d'échec ;
`CANCELLED` est volontaire et terminal ; `POSTPONED` référence la révision qui
replanifie. **L'heure passée ne prouve jamais `DONE`.**

### 3.3 `DayPlan` / `PlanRevision`

```text
DayPlan {
  schema_version: 3
  plan_id: UUID (stable)
  local_date: ISO-8601
  timezone: IANA
  version: int >= 1 (monotone)
  current_revision_id: UUID
  state: DRAFT|APPROVED|SUPERSEDED|ARCHIVED
  activities: [Activity]
  created_at, updated_at, approved_at?
  origin: LOCAL|AGENT|MIGRATED|DEMO
}

PlanRevision {
  revision_id: UUID
  plan_id: UUID
  base_version: int
  new_version: int = base_version + 1
  origin: USER|AGENT|RECONCILIATION
  changes: [SlotAdded|SlotRemoved|SlotMoved|SlotEdited|StatusChanged]
  summary: string (<= 240)
  created_at
  status: PROPOSED|APPROVED|APPLIED|REJECTED|EXPIRED|SUPERSEDED
  confirmation?: PlanConfirmation
}
```

Règles :
1. `plan_id` stable ; `version` strictement croissante ; une révision ne s'applique
   que si `base_version == version` courante, sinon `STALE_REVISION`.
2. Une révision déjà appliquée (`revision_id` connu) est un **no-op idempotent**.
3. Une révision approuvée produit un `PlanConfirmation` qui énumère exactement les
   `effect_id` autorisés et un `scope_hash`. Tout effet hors de cet ensemble exige
   une nouvelle autorisation.
4. Les changements sont calculés et affichés (avant/après) ; une révision sans
   diff compréhensible est refusée.
5. Une révision expirée (`expires_at`, défaut 24 h) ne s'applique pas.
6. Une activité terminée ne peut pas être supprimée silencieusement par une
   révision ; elle peut être archivée avec un changement explicite.
7. Le plan approuvé est intégralement lisible hors ligne et après redémarrage.

### 3.4 `CalendarEventLink`

```text
CalendarEventLink {
  link_id: UUID
  activity_id: UUID
  plan_id: UUID
  calendar_id: long
  event_id: long?
  provenance_uri: "atlas://plan/{plan_id}/activity/{activity_id}"
  state: PENDING|CONFIRMED|MISSING|DIVERGED|ORPHANED|UNKNOWN
  content_hash: sha256
  last_verified_at?
  last_error?: string (<= 120, sans donnée personnelle)
}
```

Règles d'écriture :
- Seuls les événements portant `CUSTOM_APP_PACKAGE = dev.atlas.coach` **et**
  `CUSTOM_APP_URI` correspondant à l'activité sont modifiables/supprimables.
- Un événement sans marqueur est `PROVENANCE_AMBIGUOUS` : **lecture seule**.
- Avant création, recherche par `CUSTOM_APP_URI` : si présent, réutilisation ;
  jamais de second insert pour la même activité.
- Après écriture, relecture du provider et comparaison (`dtstart`, `dtend`,
  `timezone`, `title`, `calendar_id`, `deleted`) ; sinon `DIVERGED`.
- Résultat inconnu (crash, timeout) : `UNKNOWN` puis réconciliation par requête.
  **Aucun retry aveugle.**
- Les événements récurrents ne sont pas écrits par ATLAS au premier lot ; ils sont
  lus et signalés. Un événement ATLAS non récurrent peut être déplacé.
- Suppression : uniquement un lien `CONFIRMED` avec marqueur valide, après
  confirmation ; jamais un événement d'un autre calendrier/compte.

### 3.5 `TrainingPlanReference` / `WorkoutResult`

```text
TrainingPlanReference {
  provider: "wger"
  base_url: string (https)
  routine_id: int, day_id: int?, slot_id: int?
  plan_id: UUID, activity_id?: UUID
  fetched_at, source_updated_at?, etag?
  sync_state: FRESH|STALE|UNAVAILABLE|MISMATCH
}

WorkoutResult {
  result_id: UUID
  source: WGER_API|USER_DECLARED|IMPORTED
  external_id?: string
  routine_id?, day_id?, slot_id?
  performed_at, duration_minutes?
  exercises: [{ exercise_id, sets: [{ reps, weight_kg?, rir? }] }]
  notes? (<= 280)
  verification: API_READ_BACK|USER_CONFIRMED|UNVERIFIED
  synced_at?
}
```

Règles : une **prescription** (routine/slot) n'est pas une **performance**
(`WorkoutResult`). Une séance planifiée n'est jamais marquée réalisée sans
`WorkoutResult` ou confirmation utilisateur. Aucune augmentation automatique de
charge ; une proposition de progression cite les performances observées.
Écritures wger : `ExternalOperation` + confirmation + relecture API + gestion du
conflit avec une modification locale non synchronisée de l'APK.

### 3.6 `ContentRecommendation`

```text
ContentRecommendation {
  recommendation_id: UUID
  goal_id?, sub_goal_id?, topic
  title, url (https) | local_ref
  source_name
  published_at?, retrieved_at
  relevance_reason (<= 240)
  verification: VERIFIED_SEARCH|PROMPT_INSTRUCTED_UNVERIFIED|CURATED_LOCAL
  provenance_mode: ui_tool|prompt_instructed|local
  proposed_action (<= 120), estimated_minutes?
  status: PROPOSED|OPENED|DISMISSED|EXPIRED
}
```

`PROMPT_INSTRUCTED_UNVERIFIED` n'est **jamais** présenté comme une recherche
récente vérifiée. Toute page/article est une donnée non fiable ; aucune
instruction qu'elle contient ne modifie le mandat.

### 3.7 `ContextSignal` / `UsageSummary`

```text
ContextSignal {
  signal_id, type, value_ref, observed_at, expires_at,
  confidence: 0..1, provenance, consent_category, redacted: bool
}

UsageSummary {
  window_start, window_end, timezone
  total_foreground_ms
  apps: [{ app_ref, display_name?, foreground_ms, launch_count,
           last_used_at?, reliability: HIGH|MEDIUM|LOW }]
  coverage: { observed_ms, blind_ms?, events_seen, truncated: bool }
  generated_at, consent_version
}
```

Règles : observation **globale** après `Usage Access` + consentement ATLAS
révocable, sans allowlist par package pour l'observation. Les noms de package/les
libellés ne sont transmis que si la catégorie `package_names` est consentie ;
sinon `app_ref` pseudonyme stable local. Pas de contenu d'écran, de notifications
privées ni de messages. Une durée est une estimation avec intervalle de fiabilité,
pas une vérité à la milliseconde. `UsageStatsManager` ne bloque pas les apps.

### 3.8 `MemoryFact`

```text
MemoryFact {
  fact_id: UUID
  category: GOAL|PREFERENCE|CONFIRMED_HABIT|HYPOTHESIS|OBSERVATION|AGGREGATE
  statement (<= 280)
  provenance: { source, source_id?, observed_at, confidence, user_confirmed }
  sensitivity: NORMAL|SENSITIVE
  created_at, expires_at?, last_used_at?, revoked: bool
}
```

Politique de rétention initiale : objectifs/préférences/habitudes confirmés =
persistants jusqu'à suppression ; plans/résultats = 90 jours local ; résumés
d'usage détaillés = 30 jours ; signaux de localisation = 24 h ; agrégats et
habitudes confirmées = plus longs, supprimables ; audit technique = politique
bornée séparée. Une hypothèse n'est pas une préférence ; elle expire et reste
marquée non confirmée.

### 3.9 `Consent` / `AgentMandate`

```text
ConsentRecord {
  consent_id, capability: CALENDAR_READ|CALENDAR_WRITE|WGER_READ|WGER_WRITE|
              USAGE_OBSERVATION|COACH_CONTEXT|BRIDGE_RESEARCH|LOCATION_CONTEXT|
              PROACTIVE,
  granted: bool, granted_at?, revoked_at?, scope_hash, policy_version
}

AgentMandate v3 = AgentMandate v2 +
  planning: { max_revisions_per_day, max_calendar_effects_per_day,
              require_grouped_confirmation: bool (défaut true),
              allow_calendar_write: bool (défaut false),
              allow_wger_write: bool (défaut false) }
```

Niveaux : **A0** analyse/proposition ; **A1** modification locale préautorisée
dans des bornes vérifiables ; **A2** écriture calendrier/wger après confirmation
spécifique ou groupée revalidée à l'exécution. Le défaut reste `proposals_only` ;
A2 n'est jamais implicite. Une révocation agit avant tout nouvel effet ; elle ne
peut pas annuler rétroactivement une requête déjà partie.

### 3.10 `AuthorizedEffect` / `ExternalOperation`

```text
AuthorizedEffect {
  effect_id: UUID
  kind: CALENDAR_CREATE|CALENDAR_UPDATE|CALENDAR_DELETE|WGER_WRITE|
        PLAN_APPLY|LOCAL_NOTIFICATION
  target_ref
  payload_hash: sha256
  confirmation_id: UUID
  state: PREPARED|APPLIED|NOT_APPLIED|UNKNOWN|RECONCILED|ABANDONED
  attempts: int
  created_at, updated_at
  observed_result_hash?, last_error?
}

ExternalOperation {
  operation_id: UUID
  kind
  target: { provider, base_url?, resource_type, resource_id? }
  payload (minimisé)
  authorization: { confirmation_id, plan_revision_id?, mandate_id,
                   mandate_version, approved_at, scope_hash }
  idempotency_key (stable)
  expected_version?
  state: PREPARED|SENT|VERIFIED|FAILED|UNKNOWN|RECONCILED
  result?: observed read-back
  audit: sans secret ni donnée intime
}
```

Une opération externe n'est valide que si : intention structurée + autorisation
valide + identité stable + politique de retry + vérification du résultat +
réconciliation après résultat inconnu + trace d'audit expurgée.

## 4. Invariants de planification (mapping implémentation)

| # | Invariant | Mécanisme prévu | Test oracle |
|---|---|---|---|
| 1 | `plan_id` stable + version | `DayPlan.plan_id`, `version` | round-trip JVM |
| 2 | Plan approuvé lisible hors ligne | fichier local atomique | restart sans réseau |
| 3 | Replanification = diff compréhensible | `PlanRevision.changes` + renderer | diff before/after |
| 4 | Confirmation groupée fluide | `PlanConfirmation` multi-effets | 1 confirmation, N effets |
| 5 | Ensemble d'effets exact | `scope_hash` + `effect_ids` | effet supplémentaire refusé |
| 6 | Proposition périmée refusée | `base_version` | `STALE_REVISION` |
| 7 | Pas de doublons sur retry | idempotency + lookup URI | 2 apply = 1 effet |
| 8 | Résultat inconnu → réconciliation | `UNKNOWN` + query | crash simulé |
| 9 | Fuseaux, DST, chevauchements, récurrences, redémarrage | `ZoneId`, tests dédiés | DST spring/fall |
| 10 | Statuts distincts | `ActivityStatus` | DONE ≠ MISSED ≠ CANCELLED |
| 11 | Divergence plan/calendrier représentable | `CalendarEventLink.state` | `DIVERGED` visible |
| 12 | Heure passée ≠ activité faite | aucun auto-`DONE` | plan passé reste `PLANNED/MISSED` explicite |

## 5. Compatibilité et flags

Nouvelles capabilities, toutes **désactivées par défaut** :
`CAP_DAY_PLAN`, `CAP_CALENDAR_READ`, `CAP_CALENDAR_WRITE`, `CAP_WGER_READ`,
`CAP_WGER_WRITE`, `CAP_BRIDGE_RESEARCH`, `CAP_MEMORY_FACTS`.
Une capability absente ou fausse conserve le comportement V6.2. Les nouvelles
actions agent sont refusées si la capability correspondante n'est pas active.

## 6. Ce que ce contrat n'autorise pas

- Aucune permission OS n'est demandée par une proposition.
- Aucun package système/vital n'est restreint.
- Aucun `AccessibilityService`, Device Owner, root, kiosk ou shell privilégié.
- Aucune écriture wger ou calendrier réelle sans autorisation ciblée et sauvegarde.
- Aucun contenu externe n'est une instruction.
- Aucune donnée personnelle réelle dans les fixtures ou les preuves.
