# ATLAS V6.2.1 — recette factuelle

**Date :** 20 octobre 2026
**HEAD recetté :** `07d17d6` sur `feat/atlas-v6-2-1` (base `819cdfd`). E2E OpenClaw réel rejoué sur `46529a5` ; les commits suivants ne touchent que la quarantaine d'un document illisible (R4) et un test instrumenté.
**Hôte :** Linux, OpenJDK 21.0.12.1, Android emulator 37.2.12.0, Python 3.12 (`backend/.venv`).
**Appareil :** AVD jetable `atlas_v62_disposable_api34` (API 34, `sdk_gphone64_x86_64`, `emulator-5554`). L'AVD personnel `Pixel_9_Pro_XL_2` n'a pas été démarré. Aucun Pixel physique.
**Statut des correctifs :** [STATUT_V621.md](STATUT_V621.md) · **Revue indépendante :** [REVUE_V621.md](REVUE_V621.md).

Ce document ne rapporte que des exécutions réellement faites dans cette session. Les journaux complets sont hors dépôt ; les compteurs et résumés sont sous [preuves-v621/](preuves-v621/).

## Commandes exécutées

```bash
cd android
./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
cd ..
backend/.venv/bin/python -m pytest backend/tests -q
ANDROID_SERIAL=emulator-5554 bash scripts/v62-complete-instrumentation.sh
ATLAS_V6_E2E_OUTPUT_DIR=/tmp/v621/e2e-real-2 ANDROID_SERIAL=emulator-5554 bash scripts/v6-e2e-real.sh
# chat réel puis plan après retrait de goals : backend FastAPI éphémère (jeton aléatoire) branché sur l'OpenClaw local,
# am instrument -e class dev.atlas.coach.app.V62OpenClawClientInstrumentationTest -e backend_url … -e backend_token …
```

Les captures `docs/v6/captures` réécrites par `v6-e2e-real.sh` ont été restaurées depuis git ; aucun jeton n'a été écrit dans le dépôt (contrôle `grep -F` du script : PASS).

## Résultats

| Suite | Baseline V6.2 (`819cdfd`, rejouée le 20/10) | V6.2.1 (`07d17d6`) |
| --- | --- | --- |
| JVM `testDebugUnitTest` | 256 / 0 échec | **290 / 0 échec / 0 ignoré** |
| `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest` | OK | **OK** |
| Backend `pytest` | 66 réussis, 1 ignoré | **66 réussis, 1 ignoré** (backend inchangé) |
| Instrumentation, suite générique API 34 | 48 cas, 47 réussis, 1 échec | **64 cas, 63 réussis, 1 échec** |
| Reprises ciblées du harnais (comptées à part) | 10, toutes réussies | **10, toutes réussies** |
| Cas filtrés à préconditions dans la suite générique | 9 | 9 (dont 4 rejoués en reprise ciblée) |

L'**unique échec instrumenté**, identique avant et après, est `V62MobilityServiceInstrumentedTest.visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail` (« distance_m=8925794 », la position injectée n'est pas celle lue par l'AVD). Il est présent dans la baseline V6.2 avant tout changement : défaut d'environnement, non attribué au code V6.2.1, non corrigé. Les autres tests Android V6.1/V6.2 (launcher, Focus, consentements, usage, localisation, captures natives 320/360 dp, paysage) passent toujours.

Nouvelles classes instrumentées exécutées dans la suite générique : `AgentPersistenceFailureInstrumentationTest` (6), `ProactiveCoachNotificationInstrumentationTest` (6), `LauncherDrawerLazyInstrumentationTest` (2), `ProactiveCoachWorkerInstrumentationTest` (2).

## Scénarios E2E exigés

| # | Scénario | Résultat | Preuve |
| --- | --- | --- | --- |
| 1 | Échec de `LocalStateStore.write` pendant une modification de Goal | **PASS** — aucun message de succès ; cache égal au disque ; état cohérent après recréation | `failedGoalCreateUpdateAndStatusKeepDiskCacheAndRecreatedControllerConsistent` (instrumenté), `GoalPersistenceTransactionTest` (JVM) |
| 2 | Échec du persist proactif au moment de réserver | **PASS** — aucun appel agent, aucune notification | `ProactiveCoachCoordinatorTest.failedDurableReservationMakesNoAgentCallOrNotification` (JVM, faute injectée) |
| 3 | Révocation mandat/partage pendant un échec de stockage, puis recréation | **PASS** — aucune requête autorisée, ancien consentement non restauré ; une catégorie retirée reste exclue sans bloquer les autres | `failedRevocationsRemainBlockedAfterControllerRecreation`, `failedCategoryWithdrawalKeepsOtherConsentThroughControllerRecreationAndOptIn` (instrumentés), `RevocationBarrierTest`, `AgentCategoryRevocationTest` (JVM) |
| 4 | Ancien payload local purgé/masqué après révocation et redémarrage | **PASS** | `legacySharedPayloadIsPurgedAfterCategoryRevocationAndControllerRecreation`, `failedLegacyDisclosureMigrationNeverReexposesPayloadValuesInController` (instrumentés), `AtlasControllerInitialStateRecoveryTest`, `V621ReviewFindingsTest` R2/R4 (JVM) |
| 5 | Objectif préexistant avec et sans inclusion explicite ; aucun historique antérieur au consentement | **PASS** | `existingGoalsChoicePersistsAndResetsOnGoalsRevocation` (instrumenté), `AgentContextAssemblerTest`, `AgentContextCoordinatorTest` (JVM) |
| 6 | Worker proactif sans Activity : trigger dû, quotas/silence, pas de doublon après redémarrage | **PASS** — 10 h : réservé, plan hors ligne → `REQUEST_FAILED`, sans notification ; recréation → `NO_ELIGIBLE_TRIGGERS` ; 23 h → `QUIET_HOURS` ; révocation → `CONSENT_REVOKED`, job `CANCELLED` | `ProactiveCoachWorkerInstrumentationTest` (2), `ProactiveCoachSchedulerTest`, `ProactiveCoachWorkerTimeoutTest` (JVM) |
| 7 | Tap sur la notification → proposition Coach, sans exécution automatique | **PASS** — proposition mise en avant ; plan sans proposition → Coach ; expirée → état expiré sans action ; id purgé → message neutre ; id invalide ignoré ; mandat révoqué → message neutre, aucun dispatch | `ProactiveCoachNotificationInstrumentationTest` (6) |
| 8 | HOME/tiroir 320/360 dp, fontScale 2×, 250 apps, footer accessible | **PASS** — portrait 320/360 dp 1×/2× pour 100/250 apps, paysage 2× pour 250 apps | `LauncherDrawerLazyInstrumentationTest` (2), `LauncherPresentationTest`, `HomeLandscapeFooterInstrumentationTest`, captures natives 320/360 dp du harnais |
| 9 | Focus 25/120 simulé → OpenClaw réel → proposition → ACK → révocation | **PASS (Gateway OpenClaw local réel, Focus simulé)** — adapter=openclaw ; directive réelle affichée sur HOME ; Focus restauré pause/reprise/annulation ; mandat limité qui préautorise Focus ; chat réel avec objectif 25/120 puis plan sans `goals` après retrait | [e2e-openclaw-reel-final-46529a5.txt](preuves-v621/e2e-openclaw-reel-final-46529a5.txt). Le Focus est simulé : ce n'est pas un essai de 25 min réelles. |
| 10 | Android 17 | **Non rejoué** — émulateur Android 17 non relancé dans cette session ; blocage `screencap` antérieur non attribué à ATLAS ([RAPPORT_ANDROID17.md](RAPPORT_ANDROID17.md)). Pixel physique : **non validé**. | — |

## Revue indépendante (S7)

Revue faite par une session distincte, avec des reproductions JVM ([REVUE_V621.md](REVUE_V621.md)) :

- **R1 (P0)** : action Agent annoncée appliquée malgré l'échec du commit → corrigé, test actif vert ;
- **R2 (P1)** et **R4 (P1)** : ancien payload dans la quarantaine d'un document illisible, y compris avec une clé échappée → corrigé (enveloppe sans valeurs pour tout document non parsable), tests actifs verts ;
- **R3 (P2)** : budget du worker inférieur au timeout HTTP → 99 s, plus une revérification du consentement par déclencheur avant notification.

Aucun test `@Ignore` ne subsiste dans la suite JVM finale.

## Non exécuté / non vérifié

Android 17, Pixel physique, mesure de batterie, TalkBack complet, interruption réelle de 25 minutes, mobilité mesurée (échec environnemental ci-dessus). Pas de job GitHub Actions déclenché, puisque rien n'a été poussé : la « CI verte » correspond ici à la même commande `verify` rejouée localement.
