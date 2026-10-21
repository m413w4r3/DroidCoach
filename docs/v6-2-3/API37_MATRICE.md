# Matrice Android 14 / Android 17 — V6.2.3

Statuts par scénario : `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN` seulement. Les preuves détaillées API 37 sont dans [API37_DIAGNOSTIC.md](API37_DIAGNOSTIC.md) et [preuves-api37/run-20261021.md](preuves-api37/run-20261021.md).

La tentative API 34 disponible a exécuté l’installation, le lancement, le choix HOME par l’interface Réglages, l’appui HOME et la restauration du launcher d’origine. La suite instrumentée a ensuite été faussement déclarée réussie par l’ancienne recette alors que Gradle signalait `emulator-5580 not found` et `XML_FILES=0 TESTS=0`; les scénarios fonctionnels restent `NOT_RUN`. Une reprise complète n’a pas été lancée : l’AVD réservé `emulator-5582` est resté actif et l’hôte n’avait que 3,5 GiB disponibles avec le swap de 2 GiB entièrement utilisé. Démarrer un second AVD dans cet état risquait de déstabiliser l’AVD d’une autre session.

| Scénario | Android 14 — API 34 | Android 17 — API 37 | Preuve / raison |
|---|---|---|---|
| Installation et lancement | PASS | BLOCKED | API 34 : `adb install` a répondu `Success`, `am start -W` a répondu `Status: ok`. API 37 : gate framework avant toute installation. |
| Rôle HOME ATLAS | PASS | BLOCKED | API 34 : rôle sélectionné dans Réglages et HOME a ouvert ATLAS. API 37 : AVD instable avant interface utilisable. |
| Retour au launcher précédent | NOT_RUN | BLOCKED | API 34 : l’ancienne recette n’a pas ouvert l’entrée « Launcher précédent ». API 37 : bloqué avant scénario HOME. |
| Focus | NOT_RUN | BLOCKED | API 34 : aucun test instrumenté n’a été exécuté après le faux vert Gradle. API 37 : aucune instrumentation démarrée. |
| Objectifs | NOT_RUN | BLOCKED | API 34 : aucun test instrumenté exécuté. API 37 : aucune instrumentation démarrée. |
| Notifications | NOT_RUN | BLOCKED | API 34 : aucune assertion notifications exécutée. API 37 : aucune instrumentation démarrée. |
| Localisation ponctuelle | NOT_RUN | BLOCKED | API 34 : aucune permission ni injection coarse tentée. API 37 : aucune installation ni injection coarse. |
| Mobilité visible | NOT_RUN | BLOCKED | API 34 : aucun démarrage de service instrumenté. API 37 : aucune installation ni injection synchronisée. |
| Permissions runtime | NOT_RUN | BLOCKED | API 34 : aucun état coarse/fine/notifications piloté par cette recette. API 37 : aucun APK installé. |
| Révocation | NOT_RUN | BLOCKED | API 34 : aucune révocation runtime/consent instrumentée. API 37 : aucune instrumentation démarrée. |
| Rotation | NOT_RUN | BLOCKED | API 34 : aucun test d’orientation exécuté. API 37 : aucune instrumentation démarrée. |
| Police système à 2× | NOT_RUN | BLOCKED | API 34 : aucun changement de police système exécuté. API 37 : aucune instrumentation démarrée. |
| Hors ligne | NOT_RUN | BLOCKED | API 34 : mode avion et parcours local non exécutés. API 37 : aucune instrumentation démarrée. |

## Détail des exécutions

- Android 14 partiel : [`/tmp/v623-qa/api34-20261021T134714Z/recipe.console.log`](/tmp/v623-qa/api34-20261021T134714Z/recipe.console.log), [`connectedDebugAndroidTest.log`](/tmp/v623-qa/api34-20261021T134714Z/connectedDebugAndroidTest.log), [installation](/tmp/v623-qa/api34-20261021T134714Z/install-launch.result.txt), [rôle HOME](/tmp/v623-qa/api34-20261021T134714Z/home-roundtrip.result.txt), [restauration](/tmp/v623-qa/api34-20261021T134714Z/home-restore.result.txt). Statut ancien de la suite : zéro XML / zéro test; classification `TEST_HARNESS_BUG`, pas `PASS`.
- Android 17 : [`scenario-matrix-api37.tsv`](preuves-api37/scenario-matrix-api37.tsv), [sondes framework](/tmp/v623-qa/api37-20261021T141112Z/framework-service-probes.log), [tombstones](/tmp/v623-qa/api37-20261021T141112Z/diagnostics-crash-buffer.stdout.log), [événements](/tmp/v623-qa/api37-20261021T141112Z/diagnostics-events.stdout.log). Les cinq services système disparaissent après 30 réponses positives; la recette s’arrête avant installation.
- [Preuve de ressources et de non-relance API 34](preuves-api37/api34-rerun-not-run-20261021.md).

Le téléphone `45301FDAS001QT` est `NOT_RUN` : absent de `adb devices -l` au moment des vérifications. Aucune commande d’installation, d’effacement, de permissions ou de rôle HOME n’a été envoyée à ce téléphone.
