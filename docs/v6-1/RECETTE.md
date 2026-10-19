# ATLAS V6.1 — Recette de stabilisation

- **Date :** 2026-10-19
- **Branche :** `feat/atlas-v6-1-stabilisation` — HEAD `21b6018` au début de la recette.
- **AVD :** `Pixel_9_Pro_XL_2`, Android 17 / API 37, série ADB `emulator-5554`. Le port `emulator-5556` n’était pas connecté. Aucun téléphone physique n’a été utilisé.

## Résumé des dix demandes

| # | Périmètre | Statut | Résultat |
|---|---|---|---|
| 1 | Tests JVM `testDebugUnitTest` | **PASS** | 173 tests, 0 échec après le correctif; `--rerun-tasks` a réellement exécuté les tests. |
| 2 | Tests instrumentés applicables | **PASS** | Les trois méthodes de `V6RealAgentE2eInstrumentationTest` passent sur l’AVD. Plusieurs classes à effets destructifs restent **NON TESTÉES**; voir la liste explicite ci-dessous. |
| 3 | `lintDebug` + `assembleDebug` | **PASS** | `BUILD SUCCESSFUL`; l’APK debug a été assemblé. Avertissement de stripping natif, sans échec lint. |
| 4 | Backend pytest + contrats | **PASS** | 60 passed, 1 skipped; 4 schémas JSON Draft 2020-12 valides. |
| 5 | Révocation, TTL, rejeu, interruption | **PASS** | Les tests JVM couvrant mandat, expiration, idempotence, interruption et réconciliation sont verts; détails ci-dessous. |
| 6 | OpenClaw réel → backend → Android | **PASS** | Gateway OpenClaw réel, backend et propositions Android passent; le HOME et le démarrage Focus sont vérifiés. Les deux acquittements des propositions du run final répondent `200`. |
| 7 | Conversation réelle → directive HOME → Pomodoro local | **PASS** | La conversation fournit `home.set_directive` et `focus.propose`; l’utilisateur les accepte dans l’UI et le compte à rebours local démarre avec le proposal Focus. |
| 8 | Force-stop, relance et restauration directive/provenance/Focus | **PASS** | Après `force-stop` et relance, la directive HOME, `REMOTE_AI`, son proposal source et la session Focus `RUNNING` sont restaurés; pause, reprise et annulation passent. |
| 9 | HOME ATLAS ↔ Pixel Launcher | **PASS** | Les deux changements de rôle HOME ont été vérifiés; Pixel Launcher est restauré. |
| 10 | Fonctions locales avec backend inaccessible | **PASS** | Le backend temporaire a été arrêté par le harnais; Focus local passe et le scénario sync hors ligne garde ses événements en file. Le Gateway global n’a pas été arrêté ni reconfiguré. |

## Environnement et commandes

JDK 21.0.12.1, Gradle Wrapper 9.6.0, AGP 9.4.1, SDK Android 37.2 / Build Tools 37.0.0, Python 3.12.3 et `backend/.venv`.

| Domaine | Commande exacte | Sortie résumée et preuve |
|---|---|---|
| JVM + lint + APK | `cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug --rerun-tasks` | `173 tests, 0 échec`; `lintDebug` et `assembleDebug` passent; [journal final](logs/recette-20261019/openclaw-real-fix/android-gradle.log). |
| Backend | `backend/.venv/bin/python -m pytest -q backend` | `60 passed, 1 skipped` (test Gateway réel opt-in), 1 avertissement Starlette/AnyIO; [journal final](logs/recette-20261019/openclaw-real-fix/backend-pytest.log). |
| Contrats | `python3 -c 'import json,pathlib; from jsonschema import Draft202012Validator as V; fs=sorted(pathlib.Path("contracts").glob("*.json")); [V.check_schema(json.loads(p.read_text(encoding="utf-8"))) for p in fs]; print(f"PASS: {len(fs)} JSON Schema Draft 2020-12 documents")'` | Les quatre fichiers `contracts/*.json` passent `Draft202012Validator.check_schema`; [journal contrats](logs/recette-20261019/04-contracts.log). Le premier essai via `backend/.venv/bin/python` a échoué parce que `jsonschema` n’y est pas installé; le validateur système Python 3.12.3 (jsonschema 4.10.3) a été utilisé. |

Les tests JVM de domaine confirment notamment révocation avant effet, expiration du TTL, rejeu idempotent, limite de quota, reprise après interruption sans répéter l’effet, application de directive distante et transitions ACK backend. Ils passent tous dans le [rapport final JVM](logs/recette-20261019/openclaw-real-fix/android-gradle.log).

## Matrice complète des classes `androidTest`

Toutes les classes exécutées ont été sélectionnées par `class=` ou par le harnais dédié. Aucun filtre `notClass` n’a été utilisé. `BackendArgumentsAwareRunner.kt` est le runner, pas une classe de test.

| Classe Android instrumentée | Statut | Exécution / cause | Preuve |
|---|---|---|---|
| `dev.atlas.coach.sync.agent.AgentContractsInstrumentationTest` | **PASS** | 1 test round-trip. | [journal](logs/recette-20261019/androidtest-AgentContractsInstrumentationTest-direct.log) |
| `dev.atlas.coach.app.LotEUiInstrumentationTest` | **PASS** | 5 tests UI avec état isolé; le test Pomodoro local a aussi été relancé seul pendant l’arrêt du backend. | [journal classe](logs/recette-20261019/androidtest-LotEUiInstrumentationTest-direct.log), [focus hors ligne](logs/recette-20261019/10-local-focus-with-backend-offline.log) |
| `dev.atlas.coach.app.HomeLandscapeFooterInstrumentationTest` | **PASS** | 2 tests avec viewport temporaire `2992x1344`; retour à `wm size reset`. | [journal final](logs/recette-20261019/androidtest-HomeLandscapeFooterInstrumentationTest-direct.log) |
| `dev.atlas.coach.app.V61PortraitCaptureTest` | **PASS** | 1 test, fixture locale synthétique. | [journal](logs/recette-20261019/androidtest-V61PortraitCaptureTest-direct.log) |
| `dev.atlas.coach.app.V61LandscapeCaptureTest` | **PASS** | 1 test en paysage. Le XML Gradle est `1/1`; relance directe également `OK (1 test)`. | [journal direct](logs/recette-20261019/androidtest-V61LandscapeCaptureTest-direct.log), [rapport XML](logs/recette-20261019/androidtest-V61LandscapeCaptureTest-landscape-rerun.xml) |
| `dev.atlas.coach.app.AtlasPresentationTest` | **PASS** | 4 tests de présentation. | [journal](logs/recette-20261019/androidtest-AtlasPresentationTest-direct.log) |
| `dev.atlas.coach.app.LauncherPresentationTest` | **PASS après correction du harnais** | 2/2. Le premier run échouait sur un libellé long qui est abrégé à `fontScale=2`; seule l’assertion de test a été remplacée par le tag stable `launcher_emergency_pause`. | [échec initial](logs/recette-20261019/androidtest-LauncherPresentationTest.log), [relance 2/2](logs/recette-20261019/androidtest-LauncherPresentationTest-direct.log) |
| `dev.atlas.coach.sync.SyncSettingsScreenCaptureTest` | **PASS après correction du harnais** | États `disabled`, `synchronized`, `offline`; le tag `sync_status_card` et les états/counts sont vérifiés. Les anciens textes V4 ne correspondaient plus aux libellés E2. | [tentatives échouées et finale](logs/recette-20261019/v4-backend-e2e/harness.log), [suite finale](logs/recette-20261019/v4-backend-e2e-complete/harness.log) |
| `dev.atlas.coach.sync.RemoteBackendE2eInstrumentationTest` | **PASS** | Scénarios `online`, `unauthorized` (401), `disabled`, `offline`, `resume`; SQLite finit avec 3 événements et 3 UUID uniques. | [suite backend dédiée](logs/recette-20261019/v4-backend-e2e-complete/harness.log), [journal d’accès filtré](logs/recette-20261019/v4-backend-e2e-complete/backend-access.log) |
| `dev.atlas.coach.app.V6RealAgentE2eInstrumentationTest` | **PASS** | `realCoachResponseCreatesDirectiveAndStartsFocus`, `restoredFocusCanPauseResumeAndCancel` et `limitedMandatePreauthorizesFocusPlanOnAndroid` passent dans le dernier run. | [harnais E2E](logs/recette-20261019/openclaw-real-fix/harness.log), [instrumentation OpenClaw](logs/recette-20261019/openclaw-real-fix/instrument-realCoachResponseCreatesDirectiveAndStartsFocus.log), [restauration](logs/recette-20261019/openclaw-real-fix/instrument-restoredFocusCanPauseResumeAndCancel.log), [backend expurgé](logs/recette-20261019/openclaw-real-fix/backend.log) |
| `dev.atlas.coach.AtlasLaunchTest` | **NON TESTÉ** | Le `@Before` vide `atlas_local_state` du paquet cible et accorde une permission runtime; l’état de l’AVD n’a pas été déclaré jetable. | Méthodes listées dans le source; classe exclue des filtres positifs. |
| `dev.atlas.coach.FocusIntegrationInstrumentationTest` | **NON TESTÉ** | Vide les préférences de l’application cible et accorde `POST_NOTIFICATIONS`. | Classe exclue des filtres positifs. |
| `dev.atlas.coach.sync.AndroidTokenStoreInstrumentationTest` | **NON TESTÉ** | Écrit puis révoque un secret dans le stockage Android cible; pas exécuté pour préserver les identifiants locaux. | Classe exclue des filtres positifs. |
| `dev.atlas.coach.usage.UsageFixtureInstrumentationTest` | **NON TESTÉ** | Vide l’état cible, change AppOps `GET_USAGE_STATS` et accorde une permission runtime. | Classe exclue des filtres positifs. |
| `dev.atlas.coach.launcher.LauncherFavoriteOpenInstrumentationTest` | **NON TESTÉ** | Réinitialise `atlas_local_state` dans le paquet cible. | Classe exclue des filtres positifs. |
| `dev.atlas.coach.app.AtlasVisualCaptureTest` | **NON TESTÉ** | La classe efface `atlas_local_state` du paquet cible. L’AVD contenait déjà un état d’application et rien ne permettait d’affirmer qu’il était sans données utiles; la condition demandée pour cette capture n’était donc pas établie. | Voir aussi `03-audit-ux.md` et [incident AVD](04-lot-e-ux.md). |

### Incidents et adaptations du harnais

- **FAIL initial** `SyncSettingsScreenCaptureTest` : assertion ligne 62 attendait `Désactivé · LocalMock`, alors que l’UI affiche maintenant `Désactivée`. **FAIL** sur deux essais suivants à la ligne 78 avec les anciens libellés `Synchronisé`/présentation d’attente. Les assertions UI ont été reciblées sur `sync_status_card`; les assertions d’état et de compte restent actives. Le run final du script backend est **PASS**. Les trois échecs initiaux sont conservés dans les logs `v4-backend-e2e*`.
- **FAIL initial** `LauncherPresentationTest` : à 2×, l’UI compacte le texte, et le test cherchait toujours `Pause d'urgence · 30 min`. L’assertion utilise maintenant le tag stable existant; relance **PASS 2/2**.
- **FAIL initiaux** des deux classes paysage : le viewport était portrait alors que les tests exigent `Configuration.ORIENTATION_LANDSCAPE`. Avec `wm size 2992x1344`, les deux classes passent; la taille a été réinitialisée ensuite.
- **FAIL initiaux corrigés** de l’E2E OpenClaw : le premier run reproduisait un refus de directive au cooldown; les runs suivants ont identifié puis corrigé le self-cooldown `PREPARED`, l’ancienne session Focus terminée et l’ordre d’ACK backend. Le dernier run réel passe, voir la section ci-dessous.
- Cette mission a modifié le dispatcher/contrôleur Android, l’écran Focus, le client ACK, les tests JVM/instrumentés et le chemin de sortie du script E2E. Les adaptations antérieures des sélecteurs `LauncherPresentationTest` et `SyncSettingsScreenCaptureTest` restent présentes.
- Après une relance Gradle paysage, ADB a déclaré `device offline` au nettoyage. Le même AVD a été relancé sans `-wipe-data`, puis `adb reboot` a réussi. Le premier démarrage n’a pas chargé le snapshot rapide à cause d’un renderer différent; aucun wipe ni recréation n’a eu lieu.
- Les sorties capturées par les scripts ont été placées sous `docs/v6-1/logs/`. Les captures historiques de `docs/v6/captures/` ont été restaurées après le scénario réel et comparées au backup de session : [SHA-256 final](logs/recette-20261019/openclaw-real-fix/capture-restore.sha256). La préservation antérieure de `docs/v4/captures/` est documentée [ici](logs/recette-20261019/13-capture-preservation.log).

## Scénario OpenClaw réel — échec non masqué

Commande exécutée depuis la racine du dépôt : `ATLAS_V6_E2E_OUTPUT_DIR=docs/v6-1/logs/recette-20261019/openclaw-real-fix ./scripts/v6-e2e-real.sh` (sortie terminal conservée dans `harness.log`). Le chemin de sortie relatif est maintenant ancré à la racine avant le `cd android` du script.

**PASS sur `Pixel_9_Pro_XL_2` / `emulator-5554` avec OpenClaw réel; aucun mock ne remplace le Gateway.** Le Gateway local, `GET /v2/agent/status`, `GET /v2/agent/inbox` et `POST /v2/agent/chat` répondent `200`. L’acceptation UI applique `home.set_directive` avec provenance `REMOTE_AI`; le test accepte `focus.propose`, démarre le Pomodoro local et vérifie le journal d’effet. Après `force-stop` et relance, directive, proposal source et Focus `RUNNING` sont restaurés; pause/reprise/annulation passent. Le mandat limité est aussi vérifié séparément. Les propositions de ce run reçoivent `200` pour chaque ACK `accepted` puis `executed` ([résumé ACK](logs/recette-20261019/openclaw-real-fix/e2e-backend-summary.log)). 95 `404` concernent des ACK persistés de runs antérieurs, absents de la base SQLite temporaire de ce run; aucun `409` ne reste pour les propositions courantes.

**Cause racine du défaut initial :** comparaison avec `21b6018` montre que le dispatcher précédent n’écrivait pas de ligne `PREPARED` avant son effet. Lot C a ajouté cette écriture puis la revalidation immédiate. `AtlasController.agentVerificationContext()` prenait l’horodatage de chaque entrée du journal pour le cooldown, y compris cette entrée `PREPARED` de la proposition qui était en train d’être révalidée. Elle se refusait donc elle-même au cooldown; ce refus devenait `COMPLETE/REJECTED` et pouvait bloquer les tentatives suivantes. Le cooldown lit maintenant uniquement les effets `COMPLETE/EXECUTED`; les effets effectivement exécutés gardent leur protection. Le test JVM reproduit le pull → accept → dispatch → directive persistée avec source/provenance distante et couvre aussi la règle de cooldown. Le journal continue de protéger les clés d’idempotence, la quarantaine, la révocation et le TTL.

Deux défauts de scénario trouvés après la correction sont également couverts : après une ancienne session Focus terminée persistée, l’action explicite « Nouvelle session » reprend maintenant la proposition Focus du Coach; et le client envoie l’ACK selon la transition backend `accepted → executed`, avec reprise idempotente si le serveur a déjà avancé. Le test réel attend les cooldowns HOME, `focus.propose` et `focus.start`; la limite de changements reste bornée et réserve cinq actions au-dessus de l’historique réussi du jour présent sur l’AVD persistant. La valeur de jeton Gateway n’a pas été affichée ni modifiée; le script a confirmé l’absence des jetons du dépôt et nettoyé son backend/jeton temporaires.

## Sécurité, Git et limites restantes

- Les logs backend sont expurgés; le Gateway a fourni les propositions réelles et leurs textes de directive apparaissent dans les preuves E2E. Le script confirme que les jetons Gateway et ATLAS temporaires sont absents des fichiers du dépôt : [vérifications de secrets](logs/recette-20261019/openclaw-real-fix/harness.log). `grep` récursif et `zipgrep` n’avaient trouvé aucun motif de jeton ou clef dans la recette antérieure : [scan antérieur](logs/recette-20261019/15-secret-scan.log). Le probe documente que `jsonschema` manque dans `backend/.venv`; le validateur système a validé les quatre schémas : [probe venv](logs/recette-20261019/04-contracts-venv-probe.log).
- `git diff --check` : **PASS**, aucune erreur; [journal final](logs/recette-20261019/openclaw-real-fix/git-diff-check.log). `git status --short --branch` reste non propre car il contient les changements intégrés Lots A–F/E2 présents au début et les correctifs/logs de cette mission; aucun fichier de configuration ou jeton n’a été ajouté. Branche et HEAD inchangés : [état Git final](logs/recette-20261019/openclaw-real-fix/git-final-state.log).
- Le checkout était déjà modifié avant la recette (Lots A–F/E2 non commités). Aucune branche n’a été changée; aucun commit, push, merge ou publication n’a été fait. Les fichiers modifiés pour cette correction sont non commités.
- L’incident antérieur où l’état local de l’AVD a été réinitialisé est documenté dans [04-lot-e-ux.md](04-lot-e-ux.md). Pendant cette recette, `pm path` a cessé de trouver l’application après les tests connectés; l’APK a été réinstallé via les scripts avec `adb install -r`. Aucun `pm clear`, wipe ou recréation d’AVD n’a été exécuté. L’ancien état local n’a pas été sauvegardé et sa restauration ne peut pas être affirmée.
- CI GitHub : **NON TESTÉE** — la branche n’est pas publiée; publication interdite sans autorisation. Aucun appareil physique n’a été testé. TalkBack, petits écrans et matériels autres que cet AVD restent hors périmètre.
- État final AVD vérifié après retour à HOME : Pixel Launcher comme activité et rôle HOME, `fontScale=1.0`, rotation automatique activée, rotation manuelle `0`, taille physique `1344x2992`; [preuve finale](logs/recette-20261019/openclaw-real-fix/avd-final-state.log).
