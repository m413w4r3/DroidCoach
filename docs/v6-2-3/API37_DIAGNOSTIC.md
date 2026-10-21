# Diagnostic Android 17 — ATLAS V6.2.3

Date : 21 octobre 2026. Worktree : `worktree/timely-fox`, HEAD initial `38c346a84d6d41a0f030bf90e0ea733ebdbb0787`, basé sur le code attendu `aa9b2daf703d7653b33d6433e3d97b915c955731` et les corrections de tests déjà commitées. Les logs complets restent hors dépôt sous `/tmp/v623-qa/`.

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

La matrice API 34 courante est [scenario-matrix-api34.tsv](preuves-api37/scenario-matrix-api34.tsv). Elle conserve deux vérifications partielles antérieures `PASS` (installation/lancement et sélection HOME) et marque `NOT_RUN` les parcours sans preuve réelle. Le faux `PASS` Gradle antérieur (`XML_FILES=0 TESTS=0`) est un `TEST_HARNESS_BUG`, pas une réussite de la suite. La reprise R2 n'a généré aucun XML et ne permet pas de déclarer API 34 vert.

Une première exécution API 37 historique avait atteint des réponses de services positives puis les avait perdues; elle est conservée dans [scenario-matrix-api37-run-raw.tsv](preuves-api37/scenario-matrix-api37-run-raw.tsv) et [run-20261021.md](preuves-api37/run-20261021.md). La campagne différentielle R2, son diagnostic et ses limites détaillées sont dans [run-20261021-r2.md](preuves-api37/run-20261021-r2.md).

## Harnais et environnement

Le script prend `/tmp/v623-emulator.lock` pour tout le cycle AVD et n'utilise que le serial jetable `emulator-5580`, les ports 5580/5581 et un serveur ADB isolé 5038. Le FD du verrou est fermé dans les enfants ADB/QEMU pour éviter un verrou retenu par le serveur détaché. Le garde hôte exige 8 GiB avant build et avant boot. Les screenshots sont facultatifs pour les tests sémantiques; le test autonome de pipeline PNG reste explicite et peut échouer. Aucun test n'a été supprimé.

Le build app et AndroidTest a réussi. APK application SHA-256 : `7c6c41a4b90eb8fa88b3ffcc6003468092c3681a8ef6cd95024e92b73ec7c1ea`. APK AndroidTest SHA-256 : `e2ccb0ec5d97a9f181c1f4ab785c3b90a2a5d287a5cd32961ad77ac284932ab4`. Les modifications restent dans les scripts QA, tests instrumentés et docs autorisés; aucun fichier produit (`src/main`, manifeste ou Gradle) n'a été modifié.

Self-test du harnais : 11/11 PASS, sans ADB, AVD ou Gradle. Les deux scripts passent `bash -n`; `git diff --check` passe. Ces contrôles de harnais ne constituent pas une suite instrumentée Android 14 ou Android 17.
