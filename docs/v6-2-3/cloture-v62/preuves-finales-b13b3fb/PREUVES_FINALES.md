# Preuves finales — V6.2 `b13b3fb`

Date : 2026-10-25. Branche : `chore/cloture-v62-android17`. Code final : `b13b3fb` (`fix: account for long breaks and DST focus bounds`). Ce dossier rattache explicitement les résultats au SHA final, en complément des preuves historiques `8890046…`.

## CI Android et backend

| Vérification | Résultat | Commande / artefact |
|---|---|---|
| JVM | **332 tests, 0 échec, 0 erreur, 0 ignoré**, 57 classes | `./gradlew --offline --no-daemon --rerun-tasks :app:testDebugUnitTest` |
| Lint | **0 erreur, 47 avertissements** | `:app:lintDebug` |
| Build debug + androidTest | **BUILD SUCCESSFUL**, 81 tâches, 1 min 45 s | `:app:assembleDebug :app:assembleDebugAndroidTest` |
| Backend | **66 passed, 1 skipped, 1 warning, 2.28 s** | `cd backend && .venv/bin/python -m pytest -q` |

Le journal Gradle expurgé est [gradle-final.log](gradle-final.log). L'APK final a pour SHA-256 `2743688c830a3e3df42b9db3c4d251cbf796837160d252e5976098346b2448ac`; l'APK androidTest `4a916f12f324b4cf10574038d42750bc6fe006f8dbc856ad9903225c824afa00`; le certificat debug `da3014662eb65e50a87ef364a2e583c331398795b222e3b37b1a16553a00eeee`.

## AVD API 37 — `emulator-5554`

| Vérification | Résultat | Artefact |
|---|---|---|
| Suite locale complète | 78 méthodes : **77 PASS**, 1 échec de harnais `expected_system_font_scale`, 0 skip; 169,07 s | [avd-instrument-full.txt](avd-instrument-full.txt) |
| Retest font scale | **1 test PASS**, 3,401 s; échelle restaurée à 1,0 | [avd-fontscale-retest.txt](avd-fontscale-retest.txt) |
| Bilan effectif | **78/78 méthodes locales PASS** | 77 + retest ciblé |

L'émulateur (`Pixel_9_Pro_XL_2`, API 37) a disparu pendant une première tentative de suite; il a été redémarré à froid sur le port 5554 et la suite a été rejouée intégralement. Aucun Pixel physique n'a été utilisé.

## OpenClaw Android réel

| Élément | Résultat |
|---|---|
| Test | `dev.atlas.coach.app.V62RealGatewayContextBridgeTest#realGatewayUsesAccelerated25MinuteFocusContextAndAcceptedProposal` |
| Résultat | **OK (1 test), 17,743 s** |
| Contexte | Backend ATLAS éphémère sur port libre, atteint par l'émulateur via `10.0.2.2`; gateway OpenClaw réel `127.0.0.1:18789` |
| Preuve | [openclaw-android.txt](openclaw-android.txt) — sortie brute sans jeton |

Le test utilise une horloge Focus injectée (25 min accélérées), pas une attente réelle de 25 minutes. Il vérifie `agent_status=CONNECTED`, le contexte Focus/objectif, la proposition acceptée et `EXECUTED`, l'ACK backend, l'effet dans le journal et le retrait de `goals` dans la requête suivante. La révocation *pendant* une requête en vol reste couverte par les tests locaux de barrière/génération, pas par ce test.

## Portée et limites

- Les résultats Pixel (installation, HOME, hors ligne, Pause, notifications, accessibilité, Focus 0 → 25 → 25, annulation sans crédit) restent attachés à l'APK `48c7766e…` / source `8890046…`, conformément à la décision du propriétaire d'arrêter les tests physiques. Le correctif final ne touche que `MandateVerifier`, pas le moteur Focus ni le calcul de progression.
- Localisation/mobilité restent `NOT_RUN` et désactivées par défaut (`CAP_LOCATION=false`, `CAP_MOBILITY=false`).
- La restauration Pixel n'a pas été exécutée : état QA synthétique conservé, sauvegarde d'origine intacte.
- Les P2 sécurité (URL OpenClaw configurable, callback de pull tardif, chemins locaux) restent documentés dans [REVUE_INDEPENDANTE.md](../REVUE_INDEPENDANTE.md).
