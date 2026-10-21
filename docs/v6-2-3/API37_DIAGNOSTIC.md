# Diagnostic Android 17 — ATLAS V6.2.3

Date : 21 octobre 2026. Les logs complets restent hors dépôt sous `/tmp/v623-qa/`. **La section QA-R3 ci-dessous est le résultat courant sur la branche intégrée `53ba3a629b68d87749f04a05c34b62a1ec0ab1f3`; les résultats QA-R2 plus bas sont historiques et ne qualifient pas ce code.**

## Requalification QA-R3 — branche intégrée

Le worktree a été replacé sur `53ba3a6` à la demande de l'orchestrateur. Ce commit inclut la refonte UI et le retour de `requestNotifications()` lors de l'activation des routines Focus. Aucun fichier `src/main` n'a été modifié pendant cette requalification.

### JVM, lint et APK

| Vérification | Statut | Résultat observé |
|---|---|---|
| `:app:testDebugUnitTest` | `PASS` | 321 tests, 58 XML, 0 échec, 0 erreur, 0 ignoré (317 + 4 attendus). |
| `:app:lintDebug` | `PASS` | Gradle `BUILD SUCCESSFUL`; rapport lint avec 53 avertissements et aucun arrêt de tâche. |
| `:app:assembleDebug :app:assembleDebugAndroidTest` | `PASS` | Deux APK reconstruits sur le worktree intégré; empreintes ci-dessous. |
| `./gradlew --stop` | `PASS` | Sortie réelle : `No Gradle daemons are running.` avant le boot AVD. |

SHA-256 app : `d6f8d4d9c94523a94759fe7a4343bafd12cd6c4ba143b946f8af7020e0189feb`. SHA-256 AndroidTest : `a4484069b992873a2b96f3b9663aaa0d7919b6c3b15b2a557fdd2d0d7016d382`. Les commandes, comptes et empreintes sont dans [r3-execution-summary.tsv](preuves-api37/r3-execution-summary.tsv); les sorties complètes sont sous `/tmp/v623-qa/qa-r3-53ba3a6/`.

### API 34 — suite instrumentée

Le seuil de lancement R3 est 5 GiB (`5 242 880 KiB`), tracé à la demande de l'orchestrateur. La mémoire avant boot était `7 194 960 KiB`; le surveillant, actif durant le boot de l'unique AVD API 34 (`-memory 2048`, ports 5580/5581), a relevé `4 947 044 KiB` à `2026-10-21T15:47:22Z`, sous le seuil. Le harnais a arrêté cet AVD et rendu **`NOT_RUN / HOST_ENVIRONMENT`**. Il n'a pas atteint `adb install` ni `am instrument` : XML réels = 0, tests exécutés = 0. La matrice complète API 34 n'est donc pas verte et aucune classe UI n'est déclarée réussie ou en échec. Les 29 classes présentes dans le code sont listées `NOT_RUN` avec 0 exécution dans [api34-r3-class-results.tsv](preuves-api37/api34-r3-class-results.tsv); les 94 méthodes annotées `@Test` indiquées là sont un inventaire statique, pas un compte d'exécution.

La mesure continue est dans [api34-r3-host-memory.tsv](preuves-api37/api34-r3-host-memory.tsv). Le run complet, incluant la raison d'arrêt et l'absence de tests/XML, est sous `/tmp/v623-qa/api34-20261021T154656Z/`. Aucune comparaison de `AtlasLaunchTest` contre `aa9b2da` n'a été faite, car cette classe n'a pas été exécutée sur le code intégré; aucun défaut produit n'est démontré par cette tentative.

### API 37 — tentative GPU supplémentaire

L'unique tentative demandée a utilisé l'image API 37.2 `google_apis_ps16k`, `-gpu guest`, 2048 Mo, sous `flock /tmp/v623-emulator.lock`. L'émulateur a écrit : `Your AVD has been configured with an in-guest renderer, but the system image does not support guest rendering. Falling back to 'lavapipe' mode.` Le GPU demandé n'a donc pas été appliqué. Avant le boot complet, `MemAvailable` est passé sous le seuil à `5 114 032 KiB` (`2026-10-21T15:49:19Z`); le surveillant a arrêté l'AVD. Résultat : **`NOT_RUN / HOST_ENVIRONMENT`**, 0 sondes de stabilité, 0 APK installé, 0 `am instrument`, 0 XML/test. Les scénarios API 37 restent `NOT_RUN`; il n'y a pas de nouveau diagnostic d'échec d'application.

La version émulateur est `37.2.12.0`. Sa sortie réelle `-help-gpu` propose `swangle`, mais pas `swangle_indirect`; cette variante conditionnelle n'était donc pas disponible et n'a pas été lancée. Les éléments sont dans [r3-api37-gpu-options.txt](preuves-api37/r3-api37-gpu-options.txt), [api37-r3-host-memory.tsv](preuves-api37/api37-r3-host-memory.tsv) et `/tmp/v623-qa/api37-gpu-guest-20261021T154902Z/emulator.log`.

### Classification et périphériques

| Observation | Classification | Statut / preuve |
|---|---|---|
| Mémoire hôte sous 5 GiB pendant les deux boots | Aucun échec de test à classer | Le garde a produit `NOT_RUN / HOST_ENVIRONMENT`; le runner n'a pas démarré. |
| Rendu `guest` refusé par l'image API 37.2 puis repli lavapipe | `AVD_ENVIRONMENT_BLOCKED` | Configuration AVD non applicable; aucune assertion ATLAS exécutée. |
| Échec instrumenté `APP_BUG` | Aucun observé | 0 test instrumenté exécuté sur API 34 et API 37; aucune classification d'échec produit n'est possible. |
| Échec instrumenté `TEST_HARNESS_BUG` | Aucun observé | Aucun runner réel n'a démarré. Le convertisseur de sortie `am instrument` a passé un contrôle témoin synthétique (1 succès + 1 échec convertis en XML), qui ne vaut pas test Android; voir `/tmp/v623-qa/qa-r3-53ba3a6/parser-fixture-xml/`. |
| Tests produit sur le téléphone `45301FDAS001QT` | `NOT_RUN` | Le contrôle final a trouvé le Pixel 9 Pro XL sous Android 17/API 37, avec ATLAS déjà installé et Pixel Launcher HOME. Inventaire lecture seule seulement; aucun APK installé/remplacé, aucune app lancée et aucun réglage modifié pour préserver ses données. Voir [r3-phone-read-only-summary.txt](preuves-api37/r3-phone-read-only-summary.txt) et [r3-adb-devices.txt](preuves-api37/r3-adb-devices.txt). |

À la fin de chaque tentative, l'AVD et le serveur ADB isolé ont été arrêtés; `./gradlew --stop` avait déjà confirmé l'absence de daemon. Les ports 5582–5585 n'ont pas été utilisés. Les commandes et sorties synthétiques sont dans [r3-command-results.tsv](preuves-api37/r3-command-results.tsv); le contrôle final des processus et appareils est résumé dans [r3-execution-summary.tsv](preuves-api37/r3-execution-summary.tsv).

Les détails ci-dessous décrivent les exécutions QA-R2 sur l'ancien état et sont conservés uniquement comme historique. Ils ne remplacent aucun résultat R3.

## Résultat de la reprise QA-R2

Les six variantes Android 17 ont passé le garde hôte avant leur boot (`MemAvailable` entre 11 810 472 et 20 030 616 kB, seuil 8 388 608 kB). **Aucune n'a été stable** : chacune a présenté 2 ou 3 événements `system_server_start` avant les sondes de stabilité. Le compteur de réponses de services positives est 0 pour toutes. La suite Android 17 s'est donc arrêtée avant l'installation de l'application.

Le défaut directement reproduit est un abort de `surfaceflinger` dans `RegionSampling`, avec l'assertion GoldfishMapper `Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`. Ce crash est classé `AVD_ENVIRONMENT_BLOCKED`. Il est présent avec les deux modes GPU testés, les RAM invitées 2048/3072/4096 Mo et les images API 37.1 et 37.2. Les redémarrages de `system_server` surviennent dans les quelques secondes des crashs `surfaceflinger` sur les runs où plusieurs occurrences sont conservées. Les journaux n'établissent toutefois pas quel mécanisme tue ou redémarre `system_server` : aucun killinfo ni tombstone ne le cible. La panne AVD est précisément reproduite; son dernier lien causal reste `UNKNOWN`.

Le contrôle API 35 n'a pas atteint ses sondes. Pendant son boot, `MemAvailable` est tombé à 6 853 228 kB; l'arrêt contrôlé l'a classé `NOT_RUN / HOST_ENVIRONMENT`. Il ne départage donc pas « API 37 » de « pages 16 Ko / Goldfish ».

La campagne API 34 a également été empêchée par la mémoire hôte. Le run de 15:11:18 UTC a rendu `NOT_RUN` avant boot à 7 187 064 kB. Une nouvelle tentative a passé les gardes pré-build et pré-boot (9 181 364 et 9 234 572 kB), puis le surveillant a relevé 8 348 180 kB pendant le démarrage, sous le seuil, et arrêté l'AVD avant installation/test. XML API 34 : 0 test dans ces reprises; le critère « Android 14 reste vert » n'est pas atteint. La preuve des mesures est dans [api34-r2-host-memory.tsv](preuves-api37/api34-r2-host-memory.tsv).

Le téléphone `45301FDAS001QT` était absent de `adb devices -l`; aucun téléphone n'a reçu de commande. L'AVD 5582 et les ports 5582–5585 n'ont pas été ciblés.

## Expériences différentielles API 37

| Variante | Variable | `system_server_start` | Capture écran | Résultat |
|---|---|---:|---|---|
| API 37.2 Google APIs, `auto`, 2048 Mo | Référence | 3 | `adb_exit=124` | `BLOCKED / AVD_ENVIRONMENT_BLOCKED` |
| Même image, `swiftshader_indirect`, 2048 Mo | GPU | 2 | 72 octets de texte d'assertion au lieu d'un PNG | `BLOCKED / AVD_ENVIRONMENT_BLOCKED` |
| Même image, `auto`, 3072 Mo | RAM | 2 | 72 octets de texte d'assertion au lieu d'un PNG | `BLOCKED / AVD_ENVIRONMENT_BLOCKED` |
| Même image, `auto`, 4096 Mo | RAM | 3 | `adb_exit=124` | `BLOCKED / AVD_ENVIRONMENT_BLOCKED` |
| API 37.1 Play Store 16 Ko, `auto`, 2048 Mo | Version image | 2 | 72 octets de texte d'assertion au lieu d'un PNG | `BLOCKED / AVD_ENVIRONMENT_BLOCKED` |
| API 37.2 Play Store 16 Ko, `auto`, 2048 Mo | Version image | 3 | `adb_exit=124` | `NOT_RUN / HOST_ENVIRONMENT` pour le statut du variant; l'observation AVD `BLOCKED` avait été recueillie avant le passage mémoire sous seuil. |

Chaque résultat a un dossier de logs individuel, ses compteurs de boot/service, empreinte d'image, événements, crash buffer, logcat, mémoire invitée et liste des processus. Pendant le diagnostic du variant Play Store 37.2, avec son QEMU actif, `MemAvailable` est tombé à 8 352 724 kB; le statut du variant est donc `NOT_RUN / HOST_ENVIRONMENT`, conformément au seuil. Son compteur de trois redémarrages et son assertion Goldfish avaient été observés avant cette mesure. Le témoin API 35 est aussi `NOT_RUN / HOST_ENVIRONMENT` à 6 853 228 kB. Les chemins et mesures figurent dans [experiences-differentielles-api37.tsv](preuves-api37/experiences-differentielles-api37.tsv). Les 13 scénarios API 37 sont [BLOCKED](preuves-api37/scenario-matrix-api37.tsv), car les autres variantes indépendantes ont bloqué au garde Framework avant l'installation.

Sur le baseline, les événements donnent `system_server_start` rang 1 à 17:00:07.138, rang 2 à 17:00:40.701 et rang 3 à 17:00:55.985. Les aborts `surfaceflinger` sont horodatés 17:00:37.766 et 17:00:52.069. Sur API 37.2 Play Store, les aborts sont à 17:08:09.827 et 17:08:28.443, suivis de nouveaux `system_server_start` à 17:08:12.043 et 17:08:32.385. L'assertion persiste avec `swiftshader_indirect`; augmenter la RAM à 3072 ou 4096 Mo n'empêche pas les redémarrages.

Les logs enregistrent aussi `am_low_memory` et `lowmemorykiller`/`killinfo` tuant des applications préinstallées, parfois avec `min watermark is breached even after kill`. Ces faits étayent une pression mémoire **dans l'invité** sur les images observées. Ils ne prouvent pas que `system_server` a été tué par le low-memory killer : les rapports conservés ne contiennent pas d'entrée visant ce processus. Les six variantes avaient dépassé le seuil mémoire hôte au pré-boot; le manque de mémoire hôte sous 8 GiB n'est donc pas nécessaire pour reproduire la panne Goldfish.

Le HAL UWB avorte également sur des images Google APIs où `/dev/uwb0` n'existe pas. Il s'agit d'un échec d'environnement AVD, indépendant de l'application et sans preuve qu'il provoque la panne graphique ou les redémarrages. **Aucune désactivation UWB n'a été appliquée.**

## Classification des échecs

| Échec / observation | Classification | Preuve et portée |
|---|---|---|
| Assertion `GoldfishMapper::readFromHost` et arrêt de `surfaceflinger` | `AVD_ENVIRONMENT_BLOCKED` | SIGABRT dans `RegionSampling`; assertion Goldfish exacte reproduite sur les images et modes ci-dessus, y compris swiftshader. Avant installation ATLAS. |
| `system_server` redémarre et le garde de services ne peut pas atteindre 30 réponses | `AVD_ENVIRONMENT_BLOCKED` pour les scénarios; mécanisme du redémarrage `UNKNOWN` | Compteurs 2/3 et horodatages dans les buffers events. Pas de crash/killinfo ciblant le serveur système. |
| HAL UWB abort sur périphérique `/dev/uwb0` absent | `AVD_ENVIRONMENT_BLOCKED` | Logs natifs AVD sur Google APIs; aucun accès UWB tenté par ATLAS. Le HAL reste activé. |
| API 35 témoin interrompu sous le seuil mémoire hôte | `NOT_RUN`, cause `HOST_ENVIRONMENT` | Mesure 6 853 228 kB pendant le boot; aucun compteur de services exploitable. |
| API 34 interrompu sous le seuil mémoire hôte | `NOT_RUN`, cause `HOST_ENVIRONMENT` | Mesures 7 187 064 kB avant boot et 8 348 180 kB pendant le boot; zéro XML/test. |
| ADB retenait le verrou après le premier AVD | `TEST_HARNESS_BUG`, corrigé | Le serveur ADB isolé avait hérité du FD 9. Les lancements de processus enfants ferment désormais ce FD; après correction, un AVD a pu être arrêté et le verrou repris par l'expérience suivante. |
| Résumé différentiel omettait les résultats écrits dans le fichier de garde Framework | `TEST_HARNESS_BUG`, corrigé | Le résumé lit désormais `framework-runtime-gate.result.txt` et les compteurs de garde, et mappe les résultats sur les statuts autorisés. Les preuves finales ont été recalculées depuis les dossiers de runs. |
| Défaut produit ATLAS API 37 | Aucun `APP_BUG` observé | L'application et l'APK AndroidTest ne sont jamais installés sur ces runs; aucune assertion ATLAS n'a échoué ou été exécutée. |

## API 34 et historique

L'ancienne matrice API 34 issue de R2 est conservée dans [scenario-matrix-api34.tsv](preuves-api37/scenario-matrix-api34.tsv). Elle comportait deux vérifications partielles `PASS` (installation/lancement et sélection HOME) et des `NOT_RUN` sans preuve réelle. Ces résultats ne sont pas repris en R3; la matrice courante, entièrement `NOT_RUN` faute de tests exécutés, est [API37_MATRICE.md](API37_MATRICE.md) et son TSV est [scenario-matrix-api34-r3.tsv](preuves-api37/scenario-matrix-api34-r3.tsv). Le faux `PASS` Gradle antérieur (`XML_FILES=0 TESTS=0`) était un `TEST_HARNESS_BUG`, pas une réussite de suite.

Une première exécution API 37 historique avait atteint des réponses de services positives puis les avait perdues; elle est conservée dans [scenario-matrix-api37-run-raw.tsv](preuves-api37/scenario-matrix-api37-run-raw.tsv) et [run-20261021.md](preuves-api37/run-20261021.md). La campagne différentielle R2, son diagnostic et ses limites détaillées sont dans [run-20261021-r2.md](preuves-api37/run-20261021-r2.md).

## Harnais et environnement

Le script prend `/tmp/v623-emulator.lock` pour tout le cycle AVD et n'utilise que le serial jetable `emulator-5580`, les ports 5580/5581 et un serveur ADB isolé 5038. Le FD du verrou est fermé dans les enfants ADB/QEMU pour éviter un verrou retenu par le serveur détaché. Le garde hôte exige 8 GiB avant build et avant boot. Les screenshots sont facultatifs pour les tests sémantiques; le test autonome de pipeline PNG reste explicite et peut échouer. Aucun test n'a été supprimé.

Le build app et AndroidTest a réussi. APK application SHA-256 : `7c6c41a4b90eb8fa88b3ffcc6003468092c3681a8ef6cd95024e92b73ec7c1ea`. APK AndroidTest SHA-256 : `e2ccb0ec5d97a9f181c1f4ab785c3b90a2a5d287a5cd32961ad77ac284932ab4`. Les modifications restent dans les scripts QA, tests instrumentés et docs autorisés; aucun fichier produit (`src/main`, manifeste ou Gradle) n'a été modifié.

Self-test du harnais : 11/11 PASS, sans ADB, AVD ou Gradle. Les deux scripts passent `bash -n`; `git diff --check` passe. Ces contrôles de harnais ne constituent pas une suite instrumentée Android 14 ou Android 17.
