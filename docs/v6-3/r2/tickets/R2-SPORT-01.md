# R2-SPORT-01 — Projection sportive wger en lecture seule

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R2-SPORT-01 / READY` |
| BASE_SHA | `5a7d4df511f6d3e758354da69b8b1ecba8afac41` |
| Profil imposé | `provider=codex`, `model=gpt-6-luna`, `effort=xhigh` |
| Worktree | `feat/r2-sport-projection` (worktree séparé) |
| Accès | écriture bornée au scope ci-dessous ; aucun réseau, aucun ADB, aucun secret |
| Dépendances | aucune |

## Objectif observable

Transformer des données wger **déjà lues** en une projection sportive bornée,
strictement en lecture seule, avec un contrat JSON versionné pour Android, des
fixtures synthétiques et des modèles de présentation Kotlin qui distinguent
une **prescription** d'une **performance réalisée**.

Aucune écriture wger, aucun appel réseau, aucun refresh token, aucune
modification du connecteur `wger.py` existant.

## Scope d'écriture exclusif

Fichiers **neufs** uniquement :

```text
backend/atlas_backend/integrations/wger_plan.py
backend/tests/test_wger_plan.py
contracts/sport-plan-v1.schema.json
android/app/src/main/java/dev/atlas/coach/training/**
android/app/src/test/java/dev/atlas/coach/training/**
```

Interdictions d'écriture : `backend/atlas_backend/integrations/wger.py`,
`backend/atlas_backend/main.py`, `backend/atlas_backend/config.py`,
`backend/atlas_backend/models_v2.py`, `backend/tests/test_wger.py`,
`android/app/src/main/java/dev/atlas/coach/plan/**`,
`android/app/src/main/java/dev/atlas/coach/app/**`, manifeste,
`build.gradle.kts`, `docs/**`.

Lecture utile : `backend/atlas_backend/integrations/wger.py`,
`backend/tests/test_wger.py`, `docs/v6-3/CONTRATS_V63.md` §3.5,
`docs/v6-3/preuves/l5-01-wger.txt`.

## Contrat de sortie `sport-plan-v1`

```json
{
  "schema_version": 1,
  "local_date": "2026-10-26",
  "timezone": "Europe/Paris",
  "source": "WGER_READ",
  "sync_state": "FRESH",
  "routine_id": "12",
  "day_id": "34",
  "day_name": "Séance A",
  "planned": true,
  "exercises": [
    {
      "exercise_id": "56",
      "name": "Squat",
      "sets": 3,
      "reps": 8,
      "weight_kg": 40.0,
      "rest_seconds": 90,
      "last_performance": {
        "performed_at": "2026-10-22T18:00:00Z",
        "sets": [{"reps": 8, "weight_kg": 40.0}]
      }
    }
  ],
  "performed": [
    {
      "result_id": "log-1",
      "performed_at": "2026-10-26T18:30:00Z",
      "duration_minutes": 45,
      "source": "WGER_API",
      "verification": "API_READ_BACK"
    }
  ],
  "generated_at": "2026-10-26T15:00:00Z",
  "limits": {"max_exercises": 50, "truncated": false}
}
```

Règles :

- `schema_version` strictement `1` ; l'Android refuse une version inconnue.
- Identifiants wger traités comme **opaques et convertis en chaînes** (les IDs
  de `workoutlog`/`workoutsession` réels sont des chaînes).
- `source ∈ WGER_READ|FIXTURE` ; `sync_state ∈ FRESH|STALE|UNAVAILABLE|MISMATCH`.
- `planned` décrit une prescription ; `performed` décrit uniquement des
  résultats réellement déclarés/lus. Une absence de résultat ne signifie jamais
  « fait ».
- Bornes : ≤ 50 exercices, ≤ 10 séries par exercice, ≤ 120 caractères par
  nom, ≤ 200 caractères pour les erreurs. `truncated=true` si une borne coupe.
- Aucune donnée personnelle, aucun token, aucune URL secrète, aucun champ libre
  non borné.
- Dates ISO-8601 avec fuseau ; `local_date` au format `YYYY-MM-DD`.

## Backend — `wger_plan.py`

Fonctions pures, sans I/O :

- `project_sport_plan(...)` prenant des mappings synthétiques :
  - `routine: Mapping | None` (`id`, `name`) ;
  - `day: Mapping | None` (`id`, `name`, `routine`) ;
  - `day_exercises: Sequence[Mapping]` — chaque entrée contient au minimum
    `exercise` (id ou objet avec `id`/`name`), `sets`, `reps`, et
    éventuellement `weight_kg`/`weight` et `rest_seconds` ;
  - `workout_logs: Sequence[Mapping]` — `id`, `date`/`datetime`, `exercise`,
    `reps`, `weight` éventuel ;
  - `workout_sessions: Sequence[Mapping]` — `id`, `date`, `duration` éventuel ;
  - `local_date`, `timezone`, `source`, `sync_state`, `generated_at`.
- `empty_sport_plan(...)` pour un jour sans séance, avec `planned=false`.
- Gestion défensive : champs manquants/invalides → `None` ou omission, jamais
  d'exception non contrôlée ; une entrée invalide ne doit pas faire échouer
  toute la projection.
- Aucun appel `httpx`, aucun accès fichier, aucun logging de données.
- `wger_error_to_sync_state(code)` : mapping stable
  `CONFIG_REQUIRED/UNAVAILABLE/FORBIDDEN → UNAVAILABLE`,
  `SCHEMA_ERROR → MISMATCH`, `RATE_LIMITED/SERVER_ERROR/TIMEOUT → STALE`.

## Contrat JSON Schema

`contracts/sport-plan-v1.schema.json` : JSON Schema draft 2020-12, strict
(`additionalProperties: false` aux niveaux pertinents), avec bornes de tailles,
énumérations et champs requis. Doit valider la fixture de test.

## Android — `training/**`

- `SportPlanModels.kt` : data classes immuables, IDs `String`, dates ISO
  conservées en `String` (pas de conversion implicite de fuseau), `SyncState`.
- `SportPlanJson.kt` : parseur strict sur `org.json` (déjà disponible en test),
  borné, refuse `schema_version != 1`, champs inconnus critiques, tailles
  excessives, types incorrects ; retourne un résultat typé
  `SportPlanParseResult.Valid/Invalid(reason)` sans exception.
- `SportPlanFixture.kt` : fixture synthétique déterministe correspondant au
  contrat, avec une séance prévue et **aucune** performance réalisée.
- Aucun accès Android framework ; testable en JVM pur.

## Oracle (obligatoire)

Backend :

1. `test_projects_planned_session_with_exercises_and_last_performance`
2. `test_empty_day_is_not_a_performed_session`
3. `test_missing_and_malformed_fields_are_omitted_not_fatal`
4. `test_ids_are_strings_even_when_input_is_numeric`
5. `test_truncates_at_bounds_and_sets_flag`
6. `test_error_mapping_never_claims_fresh`
7. `test_output_validates_against_contract`
8. `test_projection_has_no_io_and_no_write_fields`

Android :

1. `parseValidFixture`
2. `rejectUnknownSchemaVersion`
3. `rejectInvalidJson`
4. `rejectMissingRequiredField`
5. `rejectOversizedStrings`
6. `fixtureHasNoPerformedSession`
7. `plannedIsNotPerformed`

Commandes :

```bash
cd backend && .venv/bin/python -m pytest tests/test_wger_plan.py -q
cd backend && .venv/bin/python -m pytest -q
cd android && ./gradlew :app:testDebugUnitTest \
  --tests "dev.atlas.coach.training.*" --max-workers=1
```

## Livrables

- Commit local, SHA de sortie.
- Fichiers neufs, commandes, résultats `PASS/FAIL/BLOCKED/NOT_RUN`.
- Aucune donnée réelle, aucun secret.
- Note des limites : aucun appel wger réel dans ce ticket ; l'écran et le
  raccordement au plan sont faits par le parent en vague C.

## Stop conditions

- Modèle/effort non conformes, conflit de fichier, besoin de réseau/ADB.
- Toute modification hors scope, toute écriture wger.
- Toute dépendance nouvelle.

## Revue parent

Diff inspecté, tests rejoués sur le SHA intégré, validation du schéma, puis
`ACCEPTED / CORRECTION_REQUIRED / BLOCKED`.
