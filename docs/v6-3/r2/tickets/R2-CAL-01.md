# R2-CAL-01 — Moteur d'écriture calendrier testable (fake provider)

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R2-CAL-01 / READY` |
| BASE_SHA | `5a7d4df511f6d3e758354da69b8b1ecba8afac41` |
| Profil imposé | `provider=codex`, `model=gpt-6-luna`, `effort=xhigh` |
| Worktree | `feat/r2-calendar-write` (worktree séparé) |
| Accès | écriture bornée au scope ci-dessous ; aucune action ADB, aucun réseau |
| Dépendances | aucune (le parent raccordera l'UI) |

## Objectif observable

Livrer une couche d'écriture `CalendarContract` **injectable et testable en
JVM**, qui prépare, exécute et réconcilie des opérations non récurrentes sur un
calendrier **local explicitement choisi**, sans jamais modifier un événement
étranger ou ambigu, et sans jamais fabriquer de doublon après un résultat
inconnu.

Le code doit compiler avec l'adaptateur Android réel, mais les tests JVM
utilisent un fake provider. Aucune permission manifeste, aucune UI, aucun
raccordement `PlanScreen` dans ce ticket.

## Scope d'écriture exclusif

Uniquement des fichiers **neufs** :

```text
android/app/src/main/java/dev/atlas/coach/calendar/write/**
android/app/src/test/java/dev/atlas/coach/calendar/write/**
```

Interdictions d'écriture : tout autre fichier, notamment
`android/app/src/main/AndroidManifest.xml`, `PlanScreen.kt`,
`AtlasScreens.kt`, `PlanController.kt`, `AtlasApplication.kt`,
`app/ui/**`, `build.gradle.kts`, `REFERENCES` et `contracts/**`.

Lecture utile : `calendar/CalendarModels.kt`, `calendar/CalendarProvider.kt`,
`calendar/CalendarReadService.kt`, `calendar/AndroidCalendarProvider.kt`,
`plan/PlanModels.kt`, `docs/v6-3/CONTRATS_V63.md` §3.4, §3.10,
`docs/v6-3/AUDIT_CALENDAR_WRITE.md`.

## Exigences fonctionnelles

### 1. Modèles

- `CalendarWriteOperation` (`CREATE`, `UPDATE`, `DELETE`) avec `operation_id`
  stable, `activity_id`, `plan_id`, `calendar_id`, `event_id?`,
  `payload_hash`, `idempotency_key`, `state`.
- `CalendarEventDraft` : `activity_id`, `plan_id`, `title` (1..80), début/fin
  en millisecondes UTC, `timezone` IANA, `all_day=false`.
- `CalendarEventLink` : champs du contrat V6.3 §3.4, dont
  `provenance_uri = atlas://plan/{plan_id}/activity/{activity_id}`,
  `state ∈ PENDING|CONFIRMED|MISSING|DIVERGED|ORPHANED|UNKNOWN`,
  `content_hash` SHA-256.
- Codes d'erreur stables et expurgés (`PERMISSION_DENIED`,
  `CALENDAR_NOT_LOCAL`, `PROVENANCE_AMBIGUOUS`, `DUPLICATE_URI`,
  `RECURRING_NOT_SUPPORTED`, `READ_BACK_MISMATCH`, `UNKNOWN_RESULT`,
  `FOREIGN_EVENT`, `MISSING_EVENT`, `PROVIDER_UNAVAILABLE`,
  `INVALID_WINDOW`). Aucun message d'exception brut dans les preuves.

### 2. Provider injectable

- Interface `CalendarWriteProvider` avec permission lecture/écriture, liste
  des calendriers (réutiliser `CalendarDescriptor`), insertion, mise à jour,
  suppression, lecture par `event_id`, recherche par `CUSTOM_APP_URI`.
- `AndroidCalendarContractWriteProvider` : adaptateur réel utilisant
  `ContentResolver` et `CalendarContract.Events` ; colonnes
  `CALENDAR_ID`, `DTSTART`, `DTEND`, `EVENT_TIMEZONE`, `TITLE`, `ALL_DAY`,
  `CUSTOM_APP_PACKAGE=dev.atlas.coach`, `CUSTOM_APP_URI`; vérifie
  `READ_CALENDAR` et `WRITE_CALENDAR` via `checkSelfPermission`.
- L'adaptateur ne doit jamais être invoqué par les tests JVM.

### 3. Politique de sélection

- `CalendarWriteService.selectCalendar(catalog, explicitCalendarId)` :
  - refuse si permission non `GRANTED` ;
  - exige un identifiant explicite ;
  - exige `visible`, accès ≥ `CALENDAR_ACCESS_CONTRIBUTOR`, et
    `accountType == CalendarContract.ACCOUNT_TYPE_LOCAL` ;
  - refuse un calendrier Google ou de type compte non local ;
  - ne déduit jamais le droit d'écrire du seul `accessLevel` ;
  - si aucun calendrier local : `CALENDAR_NOT_LOCAL`, aucun effet.
- Le service ne crée jamais de calendrier.

### 4. Opérations

- **CREATE** : recherche préalable par `CUSTOM_APP_URI` ; 0 résultat →
  insertion ; 1 résultat → réutilisation/idempotence, pas de second insert ;
  ≥2 → `DUPLICATE_URI`, arrêt.
- **UPDATE** : uniquement lien `CONFIRMED` avec marqueur cohérent ; relecture
  juste avant l'effet ; refus si `FOREIGN`, `AMBIGUOUS`, récurrent, calendrier
  différent, ou si `event_id` absent.
- **DELETE** : uniquement lien `CONFIRMED` marqué ; relecture ; suppression ;
  relecture d'absence. Jamais un événement étranger ou ambigu.
- **Read-back** après chaque effet : comparer `dtstart`, `dtend`,
  `timezone`, `title`, `calendar_id`, `deleted`. Différence → `DIVERGED`.
- **Résultat inconnu** (exception après envoi, timeout, crash simulé) →
  `UNKNOWN`, aucun retry automatique ; réconciliation par requête URI.
- **Réconciliation** : 0 → `MISSING` (jamais de recréation) ; 1 → comparaison
  du hash → `CONFIRMED` ou `DIVERGED` ; ≥2 → `DUPLICATE_URI`/`ORPHANED`.
- **Idempotence** : rejouer la même opération avec la même clé ne produit
  aucun second effet ; contenu différent avec la même clé → conflit explicite.
- **Récurrence** : `RRULE` non nulle → `RECURRING_NOT_SUPPORTED`, aucune
  écriture.
- **DST** : convertir date locale + minute + durée + `ZoneId` vers UTC en
  réutilisant la sémantique de `PlanModels` (trou de printemps → première
  heure valide ; chevauchement d'automne → premier offset). Tester au moins
  `Europe/Paris` 2026-03-29 et 2026-10-25.

### 5. Journal durable

- Interface `CalendarWriteJournal` avec `record`, `findByOperation`,
  `findByActivity`, `updateState` ; implémentation en mémoire pour les tests
  et un contrat documenté pour un stockage fichier atomique (l'implémentation
  Android pourra être ajoutée plus tard, sans dépendance framework dans le
  domaine).
- Une opération est journalisée `PREPARED` **avant** l'appel provider.
- État final : `APPLIED`, `NOT_APPLIED`, `UNKNOWN`, `RECONCILED`,
  `ABANDONED`.
- Aucune donnée personnelle dans le journal : identifiants, états, hashes,
  codes d'erreur uniquement.

## Oracle (obligatoire)

Créer des tests JVM nommés explicitement pour :

1. `insertCreatesOnceAndConfirmsLink`
2. `sameIdempotencyKeyDoesNotInsertTwice`
3. `sameKeyDifferentPayloadIsRejected`
4. `preexistingUriIsReusedWithoutSecondInsert`
5. `duplicateUriReconciliationStopsWithoutChoosing`
6. `crashBeforeProviderCallLeavesPreparedAndNoEffect`
7. `crashAfterProviderCommitReconcilesByUri`
8. `unknownResultNeverRetriesBlindly`
9. `readBackMismatchBecomesDiverged`
10. `foreignEventIsNeverUpdatedOrDeleted`
11. `ambiguousMarkerIsReadOnly`
12. `missingEventIsNotRecreated`
13. `recurringEventIsRejected`
14. `permissionRevokedBeforeEffectStops`
15. `nonLocalCalendarIsRejected`
16. `wrongCalendarIsRejected`
17. `dstSpringForwardAndFallBackProduceExpectedUtc`
18. `deleteRequiresConfirmedMarkedLinkAndReadsBackAbsence`

Commandes :

```bash
cd android && ./gradlew :app:testDebugUnitTest \
  --tests "dev.atlas.coach.calendar.write.*" --max-workers=1
cd android && ./gradlew :app:testDebugUnitTest --max-workers=1
cd android && ./gradlew :app:compileDebugKotlin
```

## Livrables

- Commit local dans le worktree, avec SHA de sortie.
- Liste des fichiers neufs, commandes exécutées, résultats
  `PASS/FAIL/BLOCKED/NOT_RUN`, limites.
- Aucun secret, aucune donnée personnelle, aucune capture.
- Un court `REPORT.md` dans `calendar/write/` n'est pas nécessaire ; le rapport
  est le message de fin.

## Stop conditions

- Modèle/effort non conformes, worktree partagé, conflit de fichier.
- Besoin de modifier un fichier hors scope ou d'ajouter une dépendance.
- Besoin d'ADB, d'un calendrier réel, d'un secret ou du réseau.
- Toute écriture réelle serait un échec de périmètre : le ticket est
  **JVM/fake uniquement**.

## Revue parent

Le parent inspecte le diff, vérifie l'absence de modification hors scope,
rejoue les commandes sur le SHA intégré et décide
`ACCEPTED / CORRECTION_REQUIRED / BLOCKED`.
