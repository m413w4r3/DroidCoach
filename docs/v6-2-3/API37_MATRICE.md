# Matrice Android 14 / Android 17 — V6.2.3

Statuts utilisés : `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN` uniquement. Cette matrice décrit le résultat le plus récent disponible par scénario, avec les preuves partielles antérieures conservées dans la colonne de raison.

La requalification demandée a atteint le **gate hôte** avant tout démarrage AVD : `MemAvailable` est resté entre 6,85 et 6,96 GiB pour les sept variantes, sous le seuil de 8 GiB. Le run API 34 distinct a également rendu `NOT_RUN` avant installation et sans XML. Le critère d’acceptation « Android 14 reste vert » n’est donc pas atteint par une suite complète. Deux vérifications API 34 partielles déjà exécutées restent des `PASS` valides pour leurs seuls scénarios.

| Scénario | Android 14 — API 34 | Android 17 — API 37 | Preuve / raison |
|---|---|---|---|
| Installation et lancement | PASS | NOT_RUN | API 34 partiel : `adb install` → `Success`, `am start -W` → `Status: ok`. La nouvelle reprise a été arrêtée au gate mémoire. API 37 : aucune variante n’a booté. |
| Rôle HOME ATLAS | PASS | NOT_RUN | API 34 partiel : choix via Réglages et ouverture par HOME vérifiés. API 37 : aucune variante n’a booté. |
| Retour au launcher précédent | NOT_RUN | NOT_RUN | API 34 précédent : cette action n’a pas été tentée. Aucun run récent n’a dépassé le gate hôte. |
| Focus | NOT_RUN | NOT_RUN | Aucun résultat instrumenté XML dans les reprises. API 37 : aucune configuration lancée. |
| Objectifs | NOT_RUN | NOT_RUN | Aucun résultat instrumenté XML dans les reprises. API 37 : aucune configuration lancée. |
| Notifications | NOT_RUN | NOT_RUN | Aucun résultat instrumenté XML dans les reprises. API 37 : aucune configuration lancée. |
| Localisation ponctuelle | NOT_RUN | NOT_RUN | Aucune permission ni injection de fix exécutée dans les reprises. |
| Mobilité visible | NOT_RUN | NOT_RUN | Aucun service de mobilité démarré dans les reprises. |
| Permissions runtime | NOT_RUN | NOT_RUN | Aucun état runtime piloté dans les reprises. |
| Révocation | NOT_RUN | NOT_RUN | Aucune révocation testée dans les reprises. |
| Rotation | NOT_RUN | NOT_RUN | Aucun résultat XML instrumenté dans les reprises. |
| Police système à 2× | NOT_RUN | NOT_RUN | Aucun changement de police exécuté dans les reprises. |
| Hors ligne | NOT_RUN | NOT_RUN | Aucun parcours hors ligne exécuté dans les reprises. |

## Preuves et historique

- La reprise API 34 la plus récente est `NOT_RUN` : [matrice du gate API 34](preuves-api37/scenario-matrix-api34-host-gate.tsv), log `/tmp/v623-qa/api34-20261021T144403Z/recipe.console.log`, sortie `MemAvailable=7498600 KiB`, seuil `8388608 KiB`. Elle n’a ni démarré l’AVD ni produit d’XML.
- La campagne différentielle API 37/API 35 est consignée dans [experiences-differentielles-api37.tsv](preuves-api37/experiences-differentielles-api37.tsv) et `/tmp/v623-qa/android17-differentials-20261021T144352Z/`. Les sept variantes sont `NOT_RUN` au gate mémoire, donc il n’existe pas de mesure de stabilité des services pour ces variantes.
- Une tentative API 37 antérieure a bien démarré l’AVD, puis perdu les services Framework avant installation. Elle est conservée séparément dans [la matrice brute de cette exécution](preuves-api37/scenario-matrix-api37-run-raw.tsv), le [diagnostic](API37_DIAGNOSTIC.md) et les logs `/tmp/v623-qa/api37-20261021T141112Z/`. Son statut environnemental historique est `BLOCKED`; il ne prouve pas le résultat de la campagne différentielle courante.
- La tentative API 34 partielle ayant fourni les deux `PASS` ci-dessus est sous `/tmp/v623-qa/api34-20261021T134714Z/` : [installation/lancement](/tmp/v623-qa/api34-20261021T134714Z/install-launch.result.txt), [rôle HOME](/tmp/v623-qa/api34-20261021T134714Z/home-roundtrip.result.txt) et [restauration du launcher](/tmp/v623-qa/api34-20261021T134714Z/home-restore.result.txt). Sa suite Gradle n’a exécuté aucun test (`XML_FILES=0 TESTS=0`) et avait été faussement marquée `PASS`; cette erreur est `TEST_HARNESS_BUG`.

Le téléphone `45301FDAS001QT` était absent de `adb devices -l` au dernier contrôle; statut `NOT_RUN`. Aucun changement de rôle HOME ou de données ne lui a été envoyé. L’AVD d’une autre session sur 5582 est resté intact.
