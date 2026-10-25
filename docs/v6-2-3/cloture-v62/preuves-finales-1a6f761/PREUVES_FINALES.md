# Preuves finales — V6.2 `1a6f761`

Date : 2026-10-25. Branche : `chore/cloture-v62-android17`. Code final : `1a6f761` (`fix: reject continuous agent focus sessions`). Ce dossier rattache les résultats au SHA final; les dossiers `preuves-finales-18484e2/`, `preuves-finales-b13b3fb/` et `preuves-*` restent des preuves historiques.

## CI Android et backend

| Vérification | Résultat | Commande / artefact |
|---|---|---|
| JVM | **339 tests, 0 échec, 0 erreur, 0 ignoré**, 57 classes | `./gradlew --offline --no-daemon --rerun-tasks :app:testDebugUnitTest` |
| Lint | **0 erreur, 47 avertissements** | `:app:lintDebug` |
| Build debug + androidTest | **BUILD SUCCESSFUL**, 81 tâches, 1 min 16 s | `:app:assembleDebug :app:assembleDebugAndroidTest` |
| Backend | **66 passed, 1 skipped, 1 warning, 2.72 s** | `cd backend && .venv/bin/python -m pytest -q` |

Journal Gradle expurgé : [gradle-final.log](gradle-final.log).
APK final : SHA-256 `dc1c3e004fb7a3394c47dfc4b28407e211a06f48db51e86bbc7b665f05d62d87`.
APK androidTest : SHA-256 `4a916f12f324b4cf10574038d42750bc6fe006f8dbc856ad9903225c824afa00`.
Certificat debug : SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.

## AVD API 37 — `emulator-5554`

| Vérification | Résultat | Artefact |
|---|---|---|
| Suite locale complète | 78 méthodes : **73 PASS, 5 échecs**, 0 skip; 171,931 s | [avd-instrument-full.txt](avd-instrument-full.txt) |
| Échecs | 5 tests UI Compose : `HomeLandscapeFooterInstrumentationTest` (2 timeouts), `LauncherDrawerLazyInstrumentationTest` (SnapshotStateObserver multi-thread), `V62NativeUiCaptureTest` (font scale harness + absence de hiérarchie Compose) | ci-dessous |
| Classes agent/focus ciblées | **15 tests, 15 PASS**, 18,218 s | [avd-agent-focus-targeted.txt](avd-agent-focus-targeted.txt) |
| Retest tiroir isolé | **1 test PASS**, 20,428 s | [avd-drawer-retest.txt](avd-drawer-retest.txt) |
| Retest font scale | **1 test PASS**, 2,478 s; échelle restaurée à 1,0 | [avd-fontscale-retest.txt](avd-fontscale-retest.txt) |
| Retest landscape isolé | **1 test FAIL** (timeout Compose 10 s) sur l'état actuel de l'émulateur | sortie non conservée comme PASS |

**Classification : `UI_FLAKY_ENVIRONMENT`.** Les 5 échecs sont tous des tests d'interface Compose dépendant de l'état de l'émulateur (rotation, font scale, performance de rendu). Aucun test agent, mandat, Focus ou objectif n'a échoué dans cette suite. Le même code UI avait passé la suite complète sur `18484e2` avec 76 PASS + 2 retests ciblés (78/78 effectifs); le delta `18484e2..1a6f761` ne touche que `AgentContracts.kt`, `AtlasController.kt` (transaction agent) et `ActionDispatcherTest.kt`. Les classes agent/focus ciblées passent 15/15 sur `1a6f761`.

Les crashs du service UWB de l'image (`android.hardware.uwb-service`, absence de périphérique UWB) sont un artifact connu de l'émulateur et n'affectent pas l'application.

## OpenClaw Android réel

| Élément | Résultat |
|---|---|
| Test | `dev.atlas.coach.app.V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` |
| Résultat | **OK (1 test), 13,817 s** |
| Contexte | Backend ATLAS éphémère sur port libre, atteint par l'émulateur via `10.0.2.2`; gateway OpenClaw réel `127.0.0.1:18789` |
| Preuve | [openclaw-android.txt](openclaw-android.txt) — sortie brute sans jeton |

Le test utilise une horloge Focus injectée (25 min accélérées), pas une attente réelle de 25 minutes.

## Correctif R4

Le commit `edbb3f5`/`1a6f761` :

- refuse une carte agent `continuous` au démarrage, avant le calcul de durée et toute mutation du moteur Focus;
- marque la durée d'un plan continu comme non bornée (`Long.MAX_VALUE`) et fait échouer le contrôle de fin en sécurité;
- vérifie que `focus.propose` et `focus.plan` refusent le paramètre `continuous`;
- conserve les tests finis 100/181 min et la borne exacte.

## Portée et limites

- Les résultats Pixel restent historiques, attachés à l'APK `48c7766e…` / source `8890046…`; aucun retest physique sur le SHA final.
- La suite AVD complète sur `1a6f761` est `UI_FLAKY_ENVIRONMENT` (5 tests UI Compose), pas un PASS complet; les classes agent/focus ciblées passent 15/15 et la suite complète sur le SHA immédiatement précédent `18484e2` était 78/78 effective.
- Localisation/mobilité restent `NOT_RUN` et désactivées par défaut.
- La restauration Pixel n'a pas été exécutée : état QA synthétique conservé.
- Les P2 sécurité (URL OpenClaw configurable, callback de pull tardif) restent documentés.
