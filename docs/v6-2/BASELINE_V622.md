# ATLAS V6.2.2 — baseline S0

**Date :** 20 octobre 2026  
**Branche :** `feat/atlas-v6-2-2`  
**HEAD initial et final de S0 :** `bf647cc18a142dd0f5cd5e68b21c36d511014388`

## Périmètre et état Git

S0 a relevé l'environnement, exécuté les commandes de baseline et relu les tests V6.2.1 de persistance/révocation. Aucun fichier de production ou de test n'a été modifié. Ce rapport est le seul fichier créé par S0. Aucun commit, push ou merge n'a été fait.

Le premier `git status --short` ne montrait que `prompts/Prompt_Maitre_6.2.2.md` non suivi. Aux relevés ultérieurs, `docs/v6-2/ORCHESTRATION_V622.md` puis `docs/v6-2/CONTRAT_GOAL_FOCUS_V622.md` apparaissaient aussi non suivis. Ils n'ont pas été modifiés par S0. Le HEAD est resté identique.

## Sources lues

Lecture intégrale de `AGENTS.md`, `README.md`, `prompts/Prompt_Maitre_6.2.2.md` et des documents obligatoires :

- `docs/01-produit.md`
- `docs/v6/SECURITE_ET_MANDAT.md`
- `docs/v6-2/STATUT_V621.md`
- `docs/v6-2/RECETTE_V621.md`
- `docs/v6-2/REVUE_V621.md`
- `docs/v6-2/ISSUE_1_LOCALISATION.md`
- `docs/v6-2/RAPPORT_ANDROID17.md`
- `docs/v6-2/GUIDE_PIXEL.md`
- `docs/v6-2/INDEX_PREUVES.md`

`.codegraph/` est absent à la racine; les documents et sources de tests ont donc été lus directement.

## Inventaire de l'environnement

| Élément | Valeur relevée |
| --- | --- |
| Hôte | Linux x86_64, noyau `6.8.0-146-generic` |
| JDK | OpenJDK `21.0.12.1`; `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` |
| Gradle / AGP | Wrapper Gradle `9.6.0`; Android Gradle Plugin `9.4.1` |
| Android | SDK `$HOME/Android/Sdk`; `compileSdk 37.2`, `minSdk 26`, `targetSdk 36`; émulateur installé `37.2.12`, Build Tools `37.0.0` |
| Plateformes SDK installées | Android `23`, `24`, `26`, `31`, `33`, `34`, `35`, `37.0`, `37.1`, `37.2` |
| Build Tools installés | `30.0.3`, `34.0.0`, `35.0.0`, `36.0.0`, `36.0.0-rc1`, `37.0.0` |
| Python système | `3.12.4` |
| Python du projet | `backend/.venv`, Python `3.12.3`, pytest `8.4.0` |

Le premier contrôle du venv avait été lancé depuis `android/` avec un chemin relatif incorrect. Le contrôle correct et la commande pytest ont été exécutés depuis la racine du dépôt; le venv était présent. Aucun secret n'a été consulté ou inscrit dans les journaux.

**Aucun appel `adb` ou `emulator` n'a été exécuté.** Aucun AVD ni appareil physique n'a été démarré, interrogé ou modifié. `assembleDebugAndroidTest` ci-dessous est une compilation d'APK de test, pas une exécution instrumentée.

## Baseline exécutée

Depuis `android/` :

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Résultat : `BUILD SUCCESSFUL` en 2 min 35 s; `82 actionable tasks: 82 executed`.

Le décompte a été calculé depuis les **55 rapports XML réels** de `android/app/build/test-results/testDebugUnitTest/`, en comptant chaque `testcase` et ses éléments `failure`, `error` ou `skipped` :

| Suite | PASS | FAIL | SKIP | ERROR |
| --- | ---: | ---: | ---: | ---: |
| Android `testDebugUnitTest` | **290** | **0** | **0** | **0** |

`lintDebug`, `assembleDebug` et `assembleDebugAndroidTest` ont aussi réussi. L'assemblage a signalé que `libandroidx.graphics.path.so` ne pouvait pas être stripée et serait empaquetée telle quelle. Kotlin a émis des avertissements de casts unchecked dans `V621ReviewFindingsTest`, `AtlasStateRecoveryTest` et `PersistedRepositoriesTest`; ils n'ont pas fait échouer la compilation. Aucun résultat instrumenté n'a été généré par cette commande.

Depuis la racine du dépôt :

```bash
backend/.venv/bin/python -m pytest backend/tests -q
```

Résultat : **66 passed, 1 skipped**, 0 échec, 1 avertissement de dépréciation Starlette/AnyIO, en 2,54 s.

## Tests V6.2.1 de persistance et de révocation relus

Les XML Android produits par cette exécution donnent les résultats suivants pour les classes JVM ciblées :

| Classe | PASS | FAIL | SKIP | Couverture relue |
| --- | ---: | ---: | ---: | --- |
| `GoalPersistenceTransactionTest` | 1 | 0 | 0 | Écritures Goal en échec; cache, disque et recréation restent cohérents. |
| `PersistenceSafetyTest` | 2 | 0 | 0 | Catégories d'échec stables; diagnostic borné sans payload ni texte d'exception. |
| `RevocationBarrierTest` | 5 | 0 | 0 | Barrière durable, fermeture en échec d'écriture et opt-in limité à la catégorie concernée. |
| `AgentCategoryRevocationTest` | 2 | 0 | 0 | Catégorie retirée absente du payload, y compris après échec d'écriture racine et recréation. |
| `V621ReviewFindingsTest` | 3 | 0 | 0 | Quarantaine de JSON malformé, clé JSON échappée et action Agent non appliquée si le commit racine échoue. |
| `ActionDispatcherTest` | 17 | 0 | 0 | Idempotence, état `PREPARED` sans ACK terminal après échec, réconciliation et revalidation mandat/consentement/TTL/quota. |
| **Total ciblé** | **30** | **0** | **0** | Toutes ces classes ont été exécutées dans `testDebugUnitTest`. |

`AgentPersistenceFailureInstrumentationTest` (6 cas) vérifie notamment les échecs de stockage de Goals, la purge de payload et le maintien du blocage après révocation. `ProactiveCoachWorkerInstrumentationTest` (2 cas) couvre le travail sans Activity, la déduplication, les heures calmes et l'annulation à la révocation. Les sources ont été relues et les APK instrumentés compilent, mais **ces huit cas n'ont pas été exécutés par S0** faute d'autorisation d'utiliser un émulateur dans ce lot. Les rapports V6.2.1 indiquent leur succès historique; ce n'est pas un résultat de la présente baseline.

## Instrumentation et limites de qualification

La baseline attendue par le prompt maître pour Android API 34 est `63 PASS / 1 FAIL` sur 64 cas. Les rapports V6.2.1 consultés attribuent l'échec historique à `V62MobilityServiceInstrumentedTest.visibleStartShowsStopNotificationAndServiceStopsWithoutRawTrail`, avec une position lue à environ 8 900 km du point injecté. S0 n'a pas relancé cette suite : le diagnostic API 34 est réservé à S1 et aucun émulateur ne devait être touché dans ce lot. Ce résultat historique n'est donc pas présenté comme une reproduction S0.

Android 17 n'a pas été testé par S0. Le rapport antérieur décrit des AVD API 37 bloqués par la capture graphique/ADB avant toute validation ATLAS; cela ne prouve ni compatibilité ni incompatibilité de l'application. Aucun Pixel physique, essai TalkBack complet, mesure batterie ou job GitHub Actions n'a été exécuté.

S0 établit la baseline JVM et backend du checkout `bf647cc…`; il ne valide pas l'instrumentation, Android 17, l'appareil physique ni les changements V6.2.2 à venir. L'état GitHub de l'issue #1 n'a pas été vérifié ni modifié pendant S0; les documents lus la signalent ouverte faute de recette Android 17.

## Journaux complets

Les sorties intégrales et les relevés de lecture sont conservés sous `/tmp/atlas-v622-s0/` :

- [Lecture initiale, AGENTS/README/prompt et inventaire](</tmp/atlas-v622-s0/initial_docs_inventory.log>)
- [Documents obligatoires](</tmp/atlas-v622-s0/mandatory_docs.log>)
- [Inventaire environnement initial](</tmp/atlas-v622-s0/environment_inventory.log>) et [correction du contrôle du venv](</tmp/atlas-v622-s0/environment-inventory-correction.log>)
- [Versions SDK et état Git avant rédaction](</tmp/atlas-v622-s0/sdk-and-git-final-pre-edit.log>)
- [État Git final et contrôle d'absence d'appel émulateur](</tmp/atlas-v622-s0/final-status.log>)
- [Sortie complète Gradle](</tmp/atlas-v622-s0/gradle-baseline.log>) et [synthèse des 55 XML](</tmp/atlas-v622-s0/android-junit-xml-summary.log>)
- [Sortie complète pytest](</tmp/atlas-v622-s0/pytest-baseline.log>)
- Relecture des sources JVM [Goal/persistance/révocation](</tmp/atlas-v622-s0/persistence-revocation-jvm-test-sources.log>) et [ActionDispatcher](</tmp/atlas-v622-s0/action-dispatcher-revocation-tests-source.log>)
- Relecture des sources instrumentées [Agent/persistance](</tmp/atlas-v622-s0/agent-persistence-instrumentation-source.log>) et [worker proactif](</tmp/atlas-v622-s0/proactive-worker-instrumentation-source.log>)

