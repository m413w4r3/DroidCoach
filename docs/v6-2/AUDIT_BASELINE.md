# ATLAS V6.2 — Audit S0 de la baseline

Date de vérification : 2026-10-19, environnement `Europe/Paris`.

## Périmètre et état Git

- Base attendue vérifiée : `main`, `ee1e7b58c06531f6fdc631a42cdd7522d8ee90e1`, identique à `origin/main`.
- Le seul fichier non suivi avant S0 était `prompts/Prompt_6.2.md`. Il a été lu et laissé intact.
- `docs/v6-2/` et les futurs dossiers Android `location/` et `coach/proactive/` étaient absents au départ.
- Aucun fichier source Android/backend, manifeste, fichier Gradle, configuration locale ou AVD n’a été modifié par S0. Les tâches Gradle ont pu écrire leurs sorties ignorées sous `android/app/build/`. Aucun commit, push ou merge n’a été effectué.
- Le checkout n’avait pas de `.codegraph/`; les recherches locales ont donc utilisé `rg` après lecture du README, des docs de référence, d’`AGENTS.md` et du prompt V6.2.

Documents lus : `README.md`, `AGENTS.md`, `docs/01-produit.md`, `docs/06-direction-visuelle.md`, `docs/v6/SECURITE_ET_MANDAT.md`, `docs/v6/ANDROID_API.md`, `docs/v6-1/RECETTE.md`, `docs/v6-1/REVUE_VISUELLE.md`, `THIRD_PARTY_NOTICES.md`, `prompts/ATLAS_V6_AGENT_OPENCLAW.md` et `prompts/Prompt_6.2.md`.

## Inventaire de l’environnement

| Outil | État observé |
|---|---|
| Git | 2.43.0 |
| Java | OpenJDK 21.0.12.1 |
| Gradle Android | Wrapper 9.6.0; Kotlin 2.3.21 |
| Android SDK | `ANDROID_HOME=$HOME/Android/Sdk`; platform `android-37.2`, build-tools `37.0.0`; `minSdk=26`, `targetSdk=36` |
| SDK Manager | Binaire présent dans `cmdline-tools/latest/bin`, absent du `PATH` |
| ADB / émulateur | Binaires présents; aucune commande `adb` ou `emulator` exécutée pour cette mission |
| Python système | 3.12.3, pytest 9.1.1 |
| Venv backend | `backend/.venv`, Python 3.12.3, pytest 8.4.0; utilisé pour le résultat backend de référence |

Les tests Android demandés sont des tâches JVM/lint/assemblage et ne nécessitent aucun appareil. Aucun AVD n’a été démarré, inspecté, réinitialisé ou modifié. Aucun téléphone physique n’a été utilisé.

## Baseline de tests

Les commandes ont été exécutées sur une extraction exacte de HEAD créée par `git archive ee1e7b58c06531f6fdc631a42cdd7522d8ee90e1 | tar -x -C /tmp/atlas-ee1-baseline-20261019`; l’extraction temporaire a été séparée du checkout partagé. Les sorties sont conservées sous [`logs/`](logs/), avec redaction des motifs de credentials.

| Commande | Résultat |
|---|---|
| `backend/.venv/bin/python -m pytest backend/tests -q` depuis l’archive de HEAD | 60 passés, 1 ignoré, 1 avertissement AnyIO; succès. L’intégration réelle a été isolée en retirant `ATLAS_REAL_GATEWAY` de l’environnement du processus de test. Voir [`pytest-baseline-ee1.log`](logs/pytest-baseline-ee1.log). |
| `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` depuis l’archive de HEAD | Échec à `testDebugUnitTest`; 173 tests exécutés, 2 échecs dans `AgentContractsTest`, puis arrêt normal de Gradle. Voir [`android-baseline-ee1.log`](logs/android-baseline-ee1.log). |
| `cd android && ./gradlew lintDebug assembleDebug --console=plain` depuis l’archive de HEAD | Succès; les deux tâches passent. Voir [`android-lint-assemble-baseline-ee1.log`](logs/android-lint-assemble-baseline-ee1.log). |

Les deux assertions JVM échouées sont `first.validation.accepted` et `verify(valid, store.current()).accepted` dans `android/app/src/test/java/dev/atlas/coach/sync/AgentContractsTest.kt`. Le chemin de compatibilité V1 de `MandateVerifier.verify(ProposedAction, AgentMandate)` lit l’heure réelle; `AgentFocusWindow` utilise par défaut 08:00–20:00. Le diagnostic local à 21:27 (`Europe/Paris`) est donc hors fenêtre et explique ces refus. Le test dépend de l’heure murale et n’injecte pas d’horloge dans ce chemin. S0 ne modifie pas ces tests ni leur code applicatif.

Le test pytest marqué `real_gateway` est ignoré; aucun appel de modèle réel n’a été déclenché par la baseline. Aucun test instrumenté n’a été lancé. La tâche `assembleDebugAndroidTest`, demandée ensuite pour régénérer l’APK de tests de capture, n’a pas été exécutée après l’annonce du démarrage des validations Gradle S2; aucun client Gradle S0 ne reste actif.

## Vérification Gateway, sans secret ni changement de configuration

- `systemctl --user is-active openclaw-gateway.service` : `active`.
- `curl --silent --show-error --max-time 2 --output /dev/null --write-out 'HTTP %{http_code}' http://127.0.0.1:18789/` : statut `200`.
- `openclaw gateway status --json --require-rpc --timeout 4000` : code de sortie `0`, probe RPC réussi. La sortie JSON a été capturée en mémoire puis entièrement retenue; elle n’a pas été journalisée ou affichée.
- CLI détecté : OpenClaw 2026.10.5 (`1b58cb9`).
- Aucun fichier de configuration ou de credential n’a été ouvert manuellement par S0; la CLI a pu lire sa configuration locale en mémoire pour établir le probe RPC. Aucune configuration n’a été écrite ou redémarrée. Aucun token, URL avec credential, corps de réponse ou valeur d’environnement n’apparaît dans les logs.

Ce résultat prouve la disponibilité locale du Gateway et de son probe RPC à l’instant du contrôle; il ne constitue pas un appel fonctionnel à l’agent ATLAS. Le test backend réel reste ignoré dans la baseline.

## Références et constat d’intégration

Les références imposées ont été clonées dans les destinations prévues, avec `--filter=blob:none`, checkout détaché aux SHAs prescrits et sparse checkout des fichiers listés dans [`REEMPLOI_OSS.md`](REEMPLOI_OSS.md). Les quatre références préexistantes sont restées aux révisions inscrites dans les notices : Olauncher `f8e8d6497c457734c7a3171d36374157678e1f33`, Reef `044358fb26bf11aa85dddadf919106ca4053d926`, Lawnchair `fc100ae9095a83b8b51c447a3b0a4ce2b399ad59` et uhabits `c06b682f6b7b9c09eb278a06ffb57a42f0cddd38`.

Dans le checkout partagé, des dossiers non suivis `goals/`, `location/`, `sync/agent/context/` et leurs tests sont apparus pendant la mission; ils ne figurent pas dans l’arbre de `ee1e7b58`. S0 ne les a ni modifiés ni intégrés. Le code Android suivi dans HEAD montre encore `AgentApiClient.chat()` avec `categories: {}` et n’a pas de méthode `plan()`. Le mandat Android accepté par HEAD contient cinq catégories; le backend suivi n’accepte que les catégories historiques. Les contrats S0 précisent cette rupture sans modifier les modules S1/S2/S3.

### Coactivité observée après le démarrage

À la vérification finale, le checkout partagé contenait aussi des changements hors périmètre S0 : modifications de `backend/atlas_backend/models_v2.py`, `backend/atlas_backend/openclaw_adapter.py`, `backend/tests/test_agent_v2.py`, `docs/DECISIONS_AUTOMATIQUES.md`, des dossiers non suivis Android `goals/`, `location/`, `sync/agent/context/` et des tests `goals/`, `location/`, `sync/agent/context/`, ainsi que `docs/v6-2/captures/`. Ils n’ont pas été modifiés ou supprimés par S0. Les nouveaux types S2 `AgentContextSnapshot`/`AgentContextAssembler` et `GoalRecord`/`GoalRepository`/`GoalProgressCalculator` ont été lus uniquement pour aligner le contrat proposé; ils ne sont pas dans HEAD et restent hors de l’arbre suivi. Les résultats intitulés `*-ee1` proviennent d’une archive temporaire du commit exact et ne dépendent pas de ces changements concurrents.

Les fichiers Android de localisation et de coaching proactif ne sont pas présents dans ce baseline. Les contrats pour S2, S3 et S5 sont figés dans [`CONTRATS_INTERFACES.md`](CONTRATS_INTERFACES.md) avant leur implémentation.
