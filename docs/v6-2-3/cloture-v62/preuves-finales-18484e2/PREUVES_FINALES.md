# Preuves finales — V6.2 `18484e2`

Date : 2026-10-25. Branche : `chore/cloture-v62-android17`. Code final : `18484e2` (`fix: revalidate persisted focus plans against mandate`). Ce dossier rattache les résultats au SHA final; les dossiers `preuves-finales-b13b3fb/` et `preuves-*` restent des preuves historiques.

## CI Android et backend

| Vérification | Résultat | Commande / artefact |
|---|---|---|
| JVM | **337 tests, 0 échec, 0 erreur, 0 ignoré**, 57 classes | `./gradlew --offline --no-daemon --rerun-tasks :app:testDebugUnitTest` |
| Lint | **0 erreur, 47 avertissements** | `:app:lintDebug` |
| Build debug + androidTest | **BUILD SUCCESSFUL**, 81 tâches, 1 min 26 s | `:app:assembleDebug :app:assembleDebugAndroidTest` |
| Backend | **66 passed, 1 skipped, 1 warning, 2.59 s** | `cd backend && .venv/bin/python -m pytest -q` |

Journal Gradle expurgé : [gradle-final.log](gradle-final.log).
APK final : SHA-256 `8064dc0afac476e8a081981be4abc65620e96f169b135315d17a7f5fb5621b55`.
APK androidTest : SHA-256 `4a916f12f324b4cf10574038d42750bc6fe006f8dbc856ad9903225c824afa00`.
Certificat debug : SHA-256 `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.

## AVD API 37 — `emulator-5554`

| Vérification | Résultat | Artefact |
|---|---|---|
| Suite locale complète | 78 méthodes : **76 PASS**, 2 échecs, 0 skip; 169,807 s | [avd-instrument-full.txt](avd-instrument-full.txt) |
| Échec 1 | `V62NativeUiCaptureTest#systemFontScaleTwoKeepsEmergencyControlsAccessible` : harnais `expected_system_font_scale` non fourni | connu, retest ci-dessous |
| Échec 2 | `HomeLandscapeFooterInstrumentationTest#directiveAndCtaCanScrollAboveTheFixedFooterInLandscape` : timeout Compose 10 s | flake; retest isolé PASS |
| Retest landscape | **1 test PASS**, 3,703 s | [avd-landscape-retest.txt](avd-landscape-retest.txt) |
| Retest font scale | **1 test PASS**, 2,912 s; échelle restaurée à 1,0 | [avd-fontscale-retest.txt](avd-fontscale-retest.txt) |
| Bilan effectif | **78/78 méthodes locales PASS** | 76 + 2 retests ciblés |

Le test landscape avait déjà réussi lors des runs précédents et passe isolément sur le même APK; l'échec de la suite complète est classé flake de performance Compose, pas régression démontrée. L'émulateur (`Pixel_9_Pro_XL_2`, API 37) a été redémarré à froid après un crash; aucun Pixel physique n'a été utilisé.

## OpenClaw Android réel

| Élément | Résultat |
|---|---|
| Test | `dev.atlas.coach.app.V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` |
| Résultat | **OK (1 test), 16,15 s** |
| Contexte | Backend ATLAS éphémère sur port libre, atteint par l'émulateur via `10.0.2.2`; gateway OpenClaw réel `127.0.0.1:18789` |
| Preuve | [openclaw-android.txt](openclaw-android.txt) — sortie brute sans jeton |

Le test utilise une horloge Focus injectée (25 min accélérées), pas une attente réelle de 25 minutes. Il vérifie `agent_status=CONNECTED`, le contexte Focus/objectif, la proposition acceptée et `EXECUTED`, l'ACK backend, l'effet dans le journal et le retrait de `goals` dans la requête suivante.

## Correctif R3

Le commit `f21fa8e`/`18484e2` :

- aligne la durée vérifiée sur le `FocusPlan` exécuté, y compris les pauses courtes d'au moins 1 minute, les pauses longues de 15 min et le mode `continuous`;
- revalide au démarrage `focus.start` la carte persistée contre le mandat courant : durée totale, jour, heure de début et fin de fenêtre, avant toute mutation ou `prepareStart`;
- factorise les helpers de durée et de fenêtre entre le vérificateur et le contrôleur;
- ajoute les tests pause nulle, fin de fenêtre, borne exacte et durées de carte persistée.

La transaction privée `startAgentFocusTransaction` n'a pas de test d'intégration direct; le helper est testé (100/181/160 min) et le point d'appel a été contrôlé statiquement : la lecture du mandat et les contrôles précèdent `currentSession` et `prepareStart`.

## Portée et limites

- Les résultats Pixel restent historiques, attachés à l'APK `48c7766e…` / source `8890046…`; aucun retest physique sur le SHA final.
- Localisation/mobilité restent `NOT_RUN` et désactivées par défaut.
- La restauration Pixel n'a pas été exécutée : état QA synthétique conservé.
- Les P2 sécurité (URL OpenClaw configurable, callback de pull tardif) restent documentés; les chemins locaux des preuves ont été expurgés.
