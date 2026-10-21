# Matrice Android 14 / Android 17 — V6.2.3

Statuts employés : `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN` uniquement. Un scénario non exécuté n'est jamais `PASS`. Pour API 34, deux vérifications partielles d'un ancien run restent `PASS`; elles ne certifient pas la suite complète. Pour API 37, tous les scénarios sont `BLOCKED` avant installation, car aucune configuration n'a gardé `system_server` stable jusqu'aux sondes.

| Scénario | Android 14 — API 34 | Android 17 — API 37 | Preuve / raison |
|---|---|---|---|
| Installation et lancement | PASS | BLOCKED | API 34 partiel : `adb install` → `Success`, `am start -W` → `Status: ok` dans `/tmp/v623-qa/api34-20261021T134714Z/`. API 37 : les six variantes redémarrent `system_server` avant installation. |
| Rôle HOME | PASS | BLOCKED | API 34 partiel : sélection ATLAS via Réglages et ouverture par HOME vérifiées. API 37 : aucun variant stable avant installation. |
| Retour au launcher précédent | NOT_RUN | BLOCKED | Le parcours menu « Launcher précédent » n'a pas été exécuté sur API 34. API 37 bloqué au garde Framework. |
| Focus | NOT_RUN | BLOCKED | Aucun XML de suite API 34 complète. Aucun test ATLAS exécuté en API 37. |
| Objectifs | NOT_RUN | BLOCKED | Aucun XML de suite API 34 complète. Aucun test ATLAS exécuté en API 37. |
| Notifications | NOT_RUN | BLOCKED | Aucun XML de suite API 34 complète. Aucun test ATLAS exécuté en API 37. |
| Localisation ponctuelle | NOT_RUN | BLOCKED | Aucun fix de localisation synchronisé dans les reprises API 34. API 37 bloqué avant installation. |
| Mobilité visible | NOT_RUN | BLOCKED | Aucun parcours visible de mobilité exécuté en API 34. API 37 bloqué avant installation. |
| Permissions runtime | NOT_RUN | BLOCKED | Aucun résultat instrumenté API 34. API 37 bloqué avant installation. |
| Révocation | NOT_RUN | BLOCKED | Aucune révocation observée dans un test API 34. API 37 bloqué avant installation. |
| Rotation | NOT_RUN | BLOCKED | Aucun résultat XML instrumenté API 34. API 37 bloqué avant installation. |
| Police système à 2× | NOT_RUN | BLOCKED | Aucun changement de police exécuté en API 34. API 37 bloqué avant installation. |
| Hors ligne | NOT_RUN | BLOCKED | Aucun parcours hors ligne exécuté en API 34. API 37 bloqué avant installation. |

## Exécution API 34

Deux reprises dédiées API 34 se sont arrêtées au garde mémoire. À 15:11:18 UTC, `MemAvailable=7187064 kB`, donc aucun build/boot. À 15:14:36 UTC, le pré-boot était à `9234572 kB`; pendant le démarrage, le surveillant a relevé `8348180 kB`, inférieur au seuil `8388608 kB`, et la recette a arrêté l'AVD via ADB. Les deux reprises ont 0 test XML. Cette campagne n'atteint pas le critère « Android 14 reste vert ».

Les deux `PASS` ci-dessus proviennent d'un run partiel précédent, pas d'une suite instrumentée complète. La tentative Gradle qui avait été marquée `PASS` avec `XML_FILES=0 TESTS=0` reste classée `TEST_HARNESS_BUG`; elle ne valide aucun autre scénario. Voir [scenario-matrix-api34.tsv](preuves-api37/scenario-matrix-api34.tsv), [api34-r2-host-memory.tsv](preuves-api37/api34-r2-host-memory.tsv) et [le rapport de reprise R2](preuves-api37/run-20261021-r2.md).

## Exécution API 37

Les variantes API 37.2 Google APIs, GPU SwiftShader, RAM 3072/4096 Mo et API 37.1/37.2 Play Store ont chacune atteint le boot Android, puis observé 2 ou 3 événements `system_server_start` avant la fenêtre de sondes. Les 30 réponses positives exigées ne sont jamais atteintes; aucun APK ATLAS n'a été installé, aucun test instrumenté n'a été lancé et aucun XML n'a été produit. Les 13 lignes API 37 sont donc `BLOCKED / AVD_ENVIRONMENT_BLOCKED`, et non `PASS` ou `FAIL` produit.

Pendant le diagnostic du variant Play Store API 37.2, `MemAvailable` est aussi tombé à `8352724 kB` avec son QEMU actif. Le statut de ce variant est `NOT_RUN / HOST_ENVIRONMENT`; son blocage AVD avait été capturé avant cette baisse. Les cinq autres variantes ont chacune fourni un échec de stabilité indépendant et restent `BLOCKED / AVD_ENVIRONMENT_BLOCKED`. Le témoin API 35 s'est arrêté avant les sondes lorsque la mémoire hôte est descendue à `6853228 kB`; son statut est `NOT_RUN / HOST_ENVIRONMENT`. Ni ce témoin ni les suites API 37 ne permettent de trancher complètement API 37 contre pages 16 Ko / Goldfish. Le détail par variante est dans [la table différentielle](preuves-api37/experiences-differentielles-api37.tsv); les 13 lignes API 37 sont dans [la matrice de scénarios](preuves-api37/scenario-matrix-api37.tsv).

Le téléphone réel `45301FDAS001QT` n'apparaissait pas dans `adb devices -l`; ses scénarios sont `NOT_RUN`. Aucune commande destructive ou de changement de rôle HOME n'a été envoyée à un téléphone.

Les captures, événements et journaux complets sont hors dépôt sous `/tmp/v623-qa/`. Leur synthèse horodatée est dans [run-20261021-r2.md](preuves-api37/run-20261021-r2.md). L'historique de la matrice d'un ancien run API 37 reste dans [scenario-matrix-api37-run-raw.tsv](preuves-api37/scenario-matrix-api37-run-raw.tsv).
