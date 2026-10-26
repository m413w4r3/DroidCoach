# R2-USAGE-01 — Observation globale, résumé minimisé et quotas locaux

| Clé | Valeur |
|---|---|
| TASK_ID / statut | `R2-USAGE-01 / READY (vague C)` |
| BASE_SHA | HEAD de la campagne au lancement (SHA exact fourni) |
| Profil imposé | `provider=codex`, `model=gpt-6-luna`, `effort=xhigh` |
| Worktree | `feat/r2-usage-summary` |
| Accès | écriture bornée ci-dessous ; aucun ADB, aucun réseau |
| Dépendances | aucune (le parent teste l'émulateur) |

## Objectif observable

Ajouter une couche d'observation **globale** fondée sur `UsageStatsManager`,
un calcul de durées avec fiabilité, un `UsageSummary` structuré et minimisé,
des quotas/fenêtres locales et un consentement global révocable. Aucune
capture de contenu, aucun blocage d'application, aucun `AccessibilityService`.

Le chemin existant à allowlist par package reste inchangé et opérationnel.

## Scope d'écriture exclusif

```text
android/app/src/main/java/dev/atlas/coach/usage/**
android/app/src/test/java/dev/atlas/coach/usage/**
```

Interdictions : `rules/**`, `AtlasController.kt`, `app/**`, manifeste,
`build.gradle.kts`, `docs/**`, tout autre package.

## Exigences

### 1. Modèles globaux

- `GlobalUsageConsent` : `granted`, `granted_at`, `revoked_at`,
  `consent_version`, `purpose` explicite. Pas d'allowlist par package.
- `UsageInterval` : `appRef`, `startUtc`, `endUtc`, `reliability
  ∈ HIGH|MEDIUM|LOW`, `source`.
- `UsageAppSummary` : `appRef` (pseudonyme stable local), `displayName?`
  seulement si la catégorie `package_names` est consentie, `foregroundMs`,
  `launchCount`, `lastUsedAt?`, `reliability`.
- `UsageSummary` conforme au contrat V6.3 §3.7 :
  - `windowStart`, `windowEnd`, `timezone` ;
  - `totalForegroundMs` ;
  - `apps` borné ;
  - `coverage { observedMs, blindMs?, eventsSeen, truncated }` ;
  - `generatedAt`, `consentVersion`.
- `UsageSummaryJson` : sérialisation minimisée ; refuse d'inclure un nom de
  package ou un libellé si `package_names` n'est pas consentie.

### 2. Calcul des durées

- Appariement `ACTIVITY_RESUMED` → `ACTIVITY_PAUSED` par package, avec
  correction des événements hors fenêtre.
- Une pause manquante est fermée au prochain événement du même package, à la
  fin de fenêtre, ou à la fin observée ; fiabilité `LOW` et `blindMs` ajusté.
- Les événements consécutifs du même package ne créent pas de doublon.
- Fenêtres : journée locale, fenêtre de quota, fenêtre bornée maximale
  (≤ 24 h par défaut, ≤ 31 jours en agrégat).
- Fuseau explicite (`ZoneId`) ; les transitions DST ne doivent pas produire de
  durée négative ni de double comptage.
- Aucun chevauchement de durées pour un même package.

### 3. Consentement global

- `GlobalUsageConsentStore` (interface + fake).
- `GlobalUsageObserver` :
  - sans consentement → `DISABLED`, aucune lecture ;
  - sans Usage Access → `PERMISSION_REQUIRED`, aucun curseur conservé ;
  - consentement explicite → baseline au moment du grant, pas de rétro-remplissage ;
  - révocation → purge du curseur, des agrégats et des noms, effet immédiat ;
  - perte de permission → arrêt et purge du curseur.
- Le chemin existant `ObservationConsent`/`UsageObserver` ne doit pas être
  cassé ; les nouveaux types sont additifs.

### 4. Quotas et fenêtres

- `UsageQuota` : `quotaId`, `appRef?` (global si absent), `window
  ∈ CALENDAR_DAY|ROLLING_24H|WEEK`, `limitMs`, `warningRatio`, `enabled`.
- `QuotaEvaluation` : `OK|WARNING|EXCEEDED|UNKNOWN`, minutes observées,
  limite, fiabilité.
- Pause d'urgence et sortie volontaire restent disponibles ; aucun blocage
  système, aucune modification d'un autre package.
- Aucune écriture réseau ; le backend ne reçoit qu'un `UsageSummary` minimisé
  si un partage est explicitement consenti (hors scope de ce ticket).

## Oracle (JVM)

1. `pairingComputesExactDuration`
2. `consecutiveResumesDoNotDoubleCount`
3. `missingPauseIsCappedAndMarkedLowReliability`
4. `eventsOutsideWindowAreTrimmed`
5. `midnightWindowSplitsCorrectly`
6. `dstSpringAndFallProduceNonNegativeDurations`
7. `emptyWindowProducesZeroSummary`
8. `largeWindowIsBoundedAndTruncated`
9. `pseudonymousAppRefIsStable`
10. `packageNamesOmittedWithoutConsent`
11. `revocationClearsSummaryAndCursorImmediately`
12. `permissionDeniedStopsObservation`
13. `consentBaselineDoesNotBackfill`
14. `quotaWarningAndExceededThresholds`
15. `globalQuotaAggregatesAcrossApps`
16. `summaryJsonContainsNoContentOrPrivateData`

Commandes :

```bash
cd android && ./gradlew :app:testDebugUnitTest \
  --tests "dev.atlas.coach.usage.*" --max-workers=1
cd android && ./gradlew :app:testDebugUnitTest --max-workers=1
cd android && ./gradlew :app:assembleDebug
```

## Livrables

Commit local, SHA de sortie, fichiers, commandes, résultats, limites.
Aucun test instrumenté (le parent teste Usage Access sur l'émulateur).

## Stop conditions

Modèle/effort non conformes, conflit de fichier, besoin de `rules/**`, d'ADB,
de réseau ou d'une permission non déclarée. Aucun `AccessibilityService`,
Device Owner, kiosque ou blocage.
