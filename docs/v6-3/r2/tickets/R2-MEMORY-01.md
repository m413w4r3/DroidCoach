# R2-MEMORY-01 — Noyau local de mémoire vérifiée

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R2-MEMORY-01 / READY (vague B)` |
| BASE_SHA | HEAD de la campagne au lancement (SHA exact fourni) |
| Profil imposé | `provider=codex`, `model=gpt-6-luna`, `effort=xhigh` |
| Worktree | `feat/r2-memory-core` |
| Accès | écriture bornée ci-dessous ; aucun ADB, aucun réseau |
| Dépendances | aucune (fichiers neufs disjoints) |

## Objectif observable

Livrer un noyau local de faits mémorisés, avec provenance, catégorie,
validation utilisateur, correction, suppression, hypothèses expirantes,
rétention et exclusion immédiate du contexte du coach après révocation.

Aucune donnée personnelle réelle, aucune migration destructive, aucune UI.

## Scope d'écriture exclusif

```text
android/app/src/main/java/dev/atlas/coach/memory/**
android/app/src/test/java/dev/atlas/coach/memory/**
```

Fichiers neufs uniquement. Interdictions : tout autre fichier, notamment
`AtlasController.kt`, `sync/**`, `app/**`, `habits/**`, manifeste,
`build.gradle.kts`, `docs/**`.

## Exigences

### Modèle

- `MemoryFact` conforme au contrat V6.3 §3.8 :
  - `fact_id` stable ;
  - `category ∈ GOAL|PREFERENCE|CONFIRMED_HABIT|HYPOTHESIS|OBSERVATION|AGGREGATE` ;
  - `statement` ≤ 280 caractères ;
  - `provenance { source, source_id?, observed_at, confidence 0..1, user_confirmed }` ;
  - `sensitivity ∈ NORMAL|SENSITIVE` ;
  - `created_at`, `expires_at?`, `last_used_at?`, `revoked`.
- `MemoryRetentionPolicy` :
  - objectifs, préférences et habitudes confirmées : persistants jusqu'à
    suppression ;
  - résultats/plans : 90 jours local ;
  - résumés d'usage détaillés : 30 jours ;
  - observations : 24 heures par défaut, configurables ;
  - agrégats : plus longs, supprimables.
- Une `OBSERVATION` seule n'est **jamais** promue automatiquement en
  `PREFERENCE` ou `CONFIRMED_HABIT`. La promotion exige une confirmation
  explicite (`user_confirmed=true`) et une provenance conservée.
- Une `HYPOTHESIS` reste non confirmée et expire.
- `SENSITIVE` n'entre pas dans un contexte partagé sans consentement de
  catégorie explicite.

### Store et codec

- `MemoryStore` : `add`, `get`, `list(includeRevoked=false)`, `confirm`,
  `correct`, `delete`, `revoke`, `purgeExpired`, `markUsed`.
- Implémentation en mémoire thread-safe.
- `MemoryJsonCodec` avec schéma versionné (`schema_version: 1`), round-trip,
  refus d'une version inconnue et des identifiants dupliqués.
- `MemoryBackend` (read/write string) injectable pour un futur stockage
  atomique ; fake en test. Aucun accès Android.
- Horloge injectable (`MemoryClock`).

### Sélection de contexte

- `MemoryContextSelector.select(facts, consent, now, limit)` :
  - exclut les faits révoqués et expirés ;
  - exclut les faits `SENSITIVE` si la catégorie n'est pas consentie ;
  - borne le nombre et la taille ;
  - renvoie un texte structuré minimal (fait, catégorie, provenance,
    confiance), sans identifiants internes inutiles.
- Une révocation prend effet **immédiatement** sur la sélection suivante.

## Oracle (JVM)

1. `addAndGetFact`
2. `rejectStatementOver280`
3. `duplicateFactIdRejected`
4. `singleObservationIsNotPromoted`
5. `explicitConfirmationPromotesWithProvenance`
6. `correctionKeepsIdentityAndUpdatesStatement`
7. `revokedFactIsExcludedImmediately`
8. `expiredHypothesisIsPurged`
9. `retentionPolicyPurgesOnlyEligibleCategories`
10. `sensitiveFactRequiresCategoryConsent`
11. `contextIsBoundedAndDeterministic`
12. `jsonRoundTripPreservesFacts`
13. `unknownSchemaVersionRejected`
14. `deleteRemovesFact`
15. `markUsedUpdatesLastUsedAt`

Commandes :

```bash
cd android && ./gradlew :app:testDebugUnitTest \
  --tests "dev.atlas.coach.memory.*" --max-workers=1
cd android && ./gradlew :app:testDebugUnitTest --max-workers=1
cd android && ./gradlew :app:assembleDebug
```

## Livrables

Commit local, SHA de sortie, fichiers, commandes, résultats, limites.
Aucune UI, aucune capture, aucune donnée personnelle.

## Stop conditions

Modèle/effort non conformes, conflit de fichier, besoin de modifier un fichier
hors scope, d'ADB, de réseau ou de données réelles.
