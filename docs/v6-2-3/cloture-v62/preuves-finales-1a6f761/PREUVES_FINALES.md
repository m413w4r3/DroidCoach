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

**Run final après redémarrage à froid — PASS effectif.**

| Vérification | Résultat | Artefact |
|---|---|---|
| Suite locale complète (cold boot) | 78 méthodes : **77 PASS, 1 échec de harnais**, 0 skip; 169,232 s | [avd-instrument-coldboot.txt](avd-instrument-coldboot.txt) |
| Échec unique | `V62NativeUiCaptureTest#systemFontScaleTwoKeepsEmergencyControlsAccessible` : harnais `expected_system_font_scale` non fourni | connu |
| Retest font scale (cold boot) | **1 test PASS**, 2,866 s; échelle restaurée à 1,0 | [avd-fontscale-coldboot-retest.txt](avd-fontscale-coldboot-retest.txt) |
| Bilan effectif | **78/78 méthodes locales PASS** | 77 + retest ciblé |
| Classes agent/focus ciblées | **15 tests, 15 PASS**, 18,218 s | [avd-agent-focus-targeted.txt](avd-agent-focus-targeted.txt) |

Un premier run sur l'émulateur déjà utilisé avait produit 5 échecs UI Compose (landscape/font scale/tiroir), classés `UI_FLAKY_ENVIRONMENT`; après redémarrage à froid de `Pixel_9_Pro_XL_2`, la suite complète ne conserve que l'échec de harnais font scale, puis le retest ciblé passe. Les sorties des deux runs sont conservées : [avd-instrument-full.txt](avd-instrument-full.txt) (run flaky) et [avd-instrument-coldboot.txt](avd-instrument-coldboot.txt) (run final). Aucun test agent, mandat, Focus ou objectif n'a échoué.

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
- La suite AVD complète sur `1a6f761` est **78/78 effective après redémarrage à froid** (77 PASS + retest font scale); un premier run sur l'émulateur déjà utilisé avait produit 5 flakes UI Compose, conservés comme fait historique.
- Localisation/mobilité restent `NOT_RUN` et désactivées par défaut.
- La restauration Pixel n'a pas été exécutée : état QA synthétique conservé.
- Les P2 sécurité (URL OpenClaw configurable, callback de pull tardif) restent documentés.
