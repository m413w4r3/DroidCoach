# Matrice Android 14 / Android 17 — V6.2.3, requalification R3

Révision testée : `53ba3a629b68d87749f04a05c34b62a1ec0ab1f3` (`worktree/timely-fox`). Les statuts de scénario sont strictement `PASS`, `FAIL`, `BLOCKED` ou `NOT_RUN`. Aucun scénario non exécuté n'est déclaré `PASS`.

| Scénario | Android 14 — API 34 | Android 17 — API 37 | Preuve / cause |
|---|---|---|---|
| Installation et lancement | NOT_RUN | NOT_RUN | API 34 : le garde mémoire a arrêté l'AVD avant `adb install` (`MemAvailable=4 947 044 KiB`). API 37 : arrêt avant boot complet, sans installation. |
| Rôle HOME | NOT_RUN | NOT_RUN | Aucun parcours HOME exécuté. |
| Retour au launcher précédent | NOT_RUN | NOT_RUN | Aucun parcours HOME exécuté; aucune modification de rôle sur téléphone. |
| Focus | NOT_RUN | NOT_RUN | Aucun test instrumenté exécuté. |
| Objectifs | NOT_RUN | NOT_RUN | Aucun test instrumenté exécuté. |
| Notifications | NOT_RUN | NOT_RUN | Aucun test instrumenté exécuté. |
| Localisation ponctuelle | NOT_RUN | NOT_RUN | Aucun test instrumenté exécuté. |
| Mobilité visible | NOT_RUN | NOT_RUN | Aucun test instrumenté exécuté. |
| Permissions runtime | NOT_RUN | NOT_RUN | Aucun test instrumenté exécuté. |
| Révocation | NOT_RUN | NOT_RUN | Aucun test instrumenté exécuté. |
| Rotation | NOT_RUN | NOT_RUN | Aucun test instrumenté exécuté. |
| Police 2× | NOT_RUN | NOT_RUN | Aucun test instrumenté exécuté. |
| Hors ligne | NOT_RUN | NOT_RUN | Aucun test instrumenté exécuté. |

## API 34

Le seuil de boot était `5 242 880 KiB`, avec une mesure pré-boot de `7 194 960 KiB`. Pendant le démarrage de l'AVD jetable `atlas_v623_qa_api34` (`-memory 2048`, console/ADB 5580/5581), le moniteur a mesuré `4 947 044 KiB` à `2026-10-21T15:47:22Z`; il a arrêté l'AVD selon la règle mémoire R3. `adb install` et `am instrument` n'ont pas été lancés. XML réel : 0 fichier, 0 test. Le critère « Android 14 reste vert » n'est pas atteint.

Le détail par classe est dans [api34-r3-class-results.tsv](preuves-api37/api34-r3-class-results.tsv) : 29 classes et 94 annotations `@Test` présentes dans le code, mais 0 test exécuté par classe, donc tous `NOT_RUN`. Les 94 annotations sont un inventaire statique et ne sont pas présentées comme résultats réels. Les mesures et compteurs sont dans [api34-r3-host-memory.tsv](preuves-api37/api34-r3-host-memory.tsv) et [r3-execution-summary.tsv](preuves-api37/r3-execution-summary.tsv). La tentative UI signalée sur les anciens commits n'a pas été reproduite ni comparée à `aa9b2da`, car aucun test UI n'a démarré.

## API 37

La tentative additionnelle `-gpu guest` a utilisé l'image API 37.2 `google_apis_ps16k`, 2048 Mo et le même seuil 5 GiB. L'émulateur a refusé le rendu invité et s'est rabattu sur lavapipe; ensuite `MemAvailable=5 114 032 KiB`, sous le seuil. L'AVD a été arrêté avant le boot complet : aucune sonde de stabilité, installation ou suite instrumentée n'a été exécutée, et il n'y a aucun XML réel.

`emulator -help-gpu` liste `swangle` et ne liste pas `swangle_indirect`; cette variante conditionnelle n'était pas disponible. La sortie du contrôle, la version émulateur, le téléphone visible via adb et les mesures sont conservés dans les fichiers `r3-*` de [preuves-api37/r3-execution-summary.tsv](preuves-api37/r3-execution-summary.tsv) et les logs bruts restent sous `/tmp/v623-qa/`.

## Autres validations de la révision

La suite JVM a exécuté 321 tests dans 58 XML, avec 0 échec, 0 erreur et 0 test ignoré (`PASS`). `lintDebug` et la reconstruction des APK app/AndroidTest ont aussi fini `BUILD SUCCESSFUL`. Ces résultats ne remplacent pas la matrice instrumentée Android.

Le contrôle initial ne voyait aucun appareil, mais le contrôle final a trouvé `45301FDAS001QT`, Pixel 9 Pro XL sous Android 17/API 37. ATLAS est déjà installé et le HOME actuel est Pixel Launcher. La recette téléphone est restée en lecture seule pour préserver l'installation et ses données : aucun APK installé/remplacé, aucune app lancée, aucun rôle ou permission modifié. Les scénarios produit sur téléphone restent `NOT_RUN`; les faits sont dans [r3-phone-read-only-summary.txt](preuves-api37/r3-phone-read-only-summary.txt) et [r3-adb-devices.txt](preuves-api37/r3-adb-devices.txt).

Les résultats API 34/API 37 des reprises précédentes sont historiques; voir [API37_DIAGNOSTIC.md](API37_DIAGNOSTIC.md). Ils ne sont pas reportés comme résultats de cette révision intégrée.
