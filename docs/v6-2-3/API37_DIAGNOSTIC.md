# Diagnostic Android 17 — ATLAS V6.2.3

Date du run : 21 octobre 2026. Base code : `aa9b2daf703d7653b33d6433e3d97b915c955731` (HEAD attendu). Le worktree est sur `worktree/timely-fox`; le nom diffère de `feat/atlas-v6-2-3`, mais le commit de base correspond exactement. Aucun rebase n’était nécessaire.

## Verdict d’exécution

**AVD_ENVIRONMENT_BLOCKED avant installation.** L’AVD API 37 a terminé son boot, puis `surfaceflinger` et le framework Android ont redémarré plusieurs fois. Les sondes des services `package`, `activity`, `location`, `notification` et `window` ont répondu `found` 30 fois, puis `not found` toutes les cinq. La recette s’est arrêtée avant l’installation de l’APK : zéro test instrumenté ATLAS lancé, zéro échec d’assertion ATLAS observé. Les scénarios de la matrice sont `BLOCKED`, pas `PASS`.

Le motif précis de chaque panne observée est classé ci-dessous. La cause racine des redémarrages de `system_server` demeure `UNKNOWN` : les événements prouvent des redémarrages et des alertes de mémoire basse, mais aucun stack fatal `system_server` ni événement de mort ciblant ce processus n’apparaît dans les buffers conservés.

| Échec observé | Classification | Preuve et portée |
|---|---|---|
| `screencap -p` avant installation expire après 5 s (`adb_exit=124`). | `AVD_ENVIRONMENT_BLOCKED` | Le dump crash contient le crash concurrent de `surfaceflinger`; la capture externe ne fournit aucun PNG. Elle n’est pas une précondition de lancement des tests. |
| `/vendor/bin/hw/android.hardware.uwb-service /dev/uwb0` abort 15 fois. | `AVD_ENVIRONMENT_BLOCKED` | Chaque tombstone indique `failed to open the serial device ... No such device or address`; c’est le HAL UWB de l’image AVD, avant installation d’ATLAS. |
| `surfaceflinger` abort 3 fois dans `GoldfishMapper::readFromHost`. | `AVD_ENVIRONMENT_BLOCKED` | Message natif exact : `Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`. La pile passe par `mapper.ranchu.so`, `GraphicBuffer::lock` et `RegionSamplingThread`. |
| Services Framework indisponibles après des réponses positives. | `AVD_ENVIRONMENT_BLOCKED` | 30 réponses `found`, puis 5 réponses `not found`; `ot-daemon` journalise `system_server is dead`. Le journal events montre quatre événements `system_server_start` et trois `am_low_memory`. |
| Cause du décès de `system_server`. | `UNKNOWN` | Le buffer `crash` contient des plantages tiers et du framework dépendant de SurfaceFlinger, mais aucun fatal de `system_server`; le buffer filtré `SystemServer:E` est vide. `am_low_memory`/`killinfo` indiquent une pression mémoire, sans prouver quel événement a tué `system_server`. |
| Exceptions d’applications préinstallées pendant le redémarrage. | `AVD_ENVIRONMENT_BLOCKED` | `com.google.android.dialer` échoue en Direct Boot lors de l’accès à WorkManager; Launcher et clavier échouent ensuite avec des erreurs liées à SurfaceFlinger/`DEAD_OBJECT`. Aucun de ces processus n’est ATLAS. |
| Exécution de tests ATLAS sur cet AVD. | `NOT_RUN` au niveau des tests individuels; scénarios de matrice `BLOCKED`. | La recette s’est arrêtée avant `adb install`; aucune commande `am instrument` n’a été émise. Aucun `APP_BUG` API 37 ne peut être déduit de ce run. |

Le résultat concorde avec les pannes AVD consignées avant V6.2.3 : même image API 37.2 révision 6, assertion GoldfishMapper identique et disparition de services après boot ([ANDROID17_V622.md](/home/nill/perso/AndroCoach/atlas-preparation_worktrees/timely-fox/docs/v6-2/ANDROID17_V622.md), [RAPPORT_ANDROID17.md](/home/nill/perso/AndroCoach/atlas-preparation_worktrees/timely-fox/docs/v6-2/RAPPORT_ANDROID17.md)). Le présent run utilise néanmoins son propre AVD `atlas_v623_qa_api37` et ses propres journaux.

## Défauts de harnais corrigés

Ces problèmes sont classés `TEST_HARNESS_BUG`; ils sont distincts du blocage AVD API 37 :

- Une exécution antérieure API 34 avec `connectedDebugAndroidTest` avait affiché `BUILD SUCCESSFUL` alors que Gradle ne joignait pas `emulator-5580` sur le serveur ADB qu’il utilisait; aucun XML de résultat n’existait. La recette V6.2.3 cible maintenant un seul serial explicite avec `adb shell am instrument`, exige une méthode et `numtests=1` pour un `PASS`, et traite zéro test ou runner incomplet comme `NOT_RUN`.
- `nativeHomeMatrixAtRequestedWidth` exigeait `expected_width_dp` même dans la suite générique. Le test prend maintenant la largeur réelle quand l’argument optionnel manque et garde la comparaison exacte quand il est fourni. Une valeur mal formée reste une erreur explicite.
- Les captures natives échouaient avant les assertions de disposition lorsqu’un screenshot était indisponible. Les tests sémantiques journalisent les erreurs PNG sans être interrompus; un test dédié `screenshotCapturePipelineProducesPng` échoue si la chaîne PNG ne produit rien, et le script expose aussi le compte des marqueurs `CAPTURE_OK` / `CAPTURE_BLOCKED`. La capture reste vérifiable séparément.
- `SyncSettingsScreenCaptureTest` retournait un succès implicite si `capture_state` manquait. Son runner filtre maintenant ce scénario conditionnel en inscrivant une raison explicite; les états invalides échouent explicitement.
- Le test de préparation du consentement mobilité pouvait s’exécuter sans son injection hôte, sans vérifier le parcours visible. Il requiert maintenant `location_fix_injection`; le run générique journalise le filtrage. Aucun test n’a été supprimé.

Preuve détaillée du faux `PASS` API 34 précédent : [log Gradle `connectedDebugAndroidTest`](/tmp/v623-qa/api34-20261021T134714Z/connectedDebugAndroidTest.log), [résultat de recette](/tmp/v623-qa/api34-20261021T134714Z/recipe.result.txt) et [résumé de tests](/tmp/v623-qa/api34-20261021T134714Z/test-counts.txt). Gradle affichait `Running tests on devices: atlas_v623_qa_api34(AVD) - 14`, puis `adb: device 'emulator-5580' not found`; il terminait pourtant `BUILD SUCCESSFUL`, `XML_FILES=0 TESTS=0`, et l’ancienne recette écrivait `suite_status=PASS`. Classification : `TEST_HARNESS_BUG` (aucun test n’a passé). Le rôle HOME et sa restauration y avaient été vérifiés séparément; ces résultats n’établissent pas un passage de la suite.

Une instrumentation ciblée supplémentaire vérifie les états runtime Android refusé/accordé/révoqué, coarse sans fine, et la porte de `LocationCaptureManager`. Les parcours avec fix réel ne seront déclarés exécutés que par le harnais synchronisé; ils n’ont pas été atteints sur API 37.

## Environnement et artefacts

- Commande : `scripts/v623-android-qa.sh api37`.
- AVD jetable : `atlas_v623_qa_api37`, serial `emulator-5580`, ports 5580/5581; image `system-images;android-37.2;google_apis_ps16k;x86_64`, révision 6, profil Pixel 5, ABI x86_64, pages de 16 Ko.
- Identité lue après boot : API `37`, `sdk_full=37.2`, Android `17`, fingerprint `google/sdk_gphone16k_x86_64/emu64xa16k:17/CP41.260831.007/16416850:userdebug/dev-keys`, `sys.boot_completed=1`.
- Outils : émulateur 37.2.12.0 (build 16428233), ADB SDK 35.0.2-12147458, JDK 21.0.12.1, Python 3.12.3. Lancement QEMU avec `-memory 2048`, `-gpu auto`, `-wipe-data`.
- APK application SHA-256 : `7c6c41a4b90eb8fa88b3ffcc6003468092c3681a8ef6cd95024e92b73ec7c1ea`.
- APK AndroidTest SHA-256 : `26c38036265ca599c695bbc4fe58b4cc3ca5b6c50a0c31b03996c9ce2255dbb2`.
- Après cette tentative, l’ajout du contrôle autonome `screenshotCapturePipelineProducesPng` a changé l’APK AndroidTest final compilé : SHA-256 `e2ccb0ec5d97a9f181c1f4ab785c3b90a2a5d287a5cd32961ad77ac284932ab4`. Cette dernière APK n’a pas été réinstallée sur API 37; le gate environnemental avait arrêté le run avant toute installation. L’APK application n’a pas changé.
- Le build a réussi. Aucun APK ATLAS n’a été installé sur API 37. L’AVD dédié a été arrêté par la recette. L’AVD `emulator-5582` d’une autre session était visible dans l’inventaire, sans commande envoyée à son serial; le téléphone `45301FDAS001QT` n’y figurait pas.
- Tous les logs détaillés sont hors dépôt sous [`/tmp/v623-qa/api37-20261021T141112Z/recipe.console.log`](/tmp/v623-qa/api37-20261021T141112Z/recipe.console.log). Les signatures et compteurs résumés sont versionnés dans [preuves-api37/run-20261021.md](preuves-api37/run-20261021.md); la matrice brute API 37 est dans [preuves-api37/scenario-matrix-api37.tsv](preuves-api37/scenario-matrix-api37.tsv).

Le self-test du script a passé 9 assertions sur 9, sans appeler ADB, démarrer d’AVD ou lancer Gradle. Le build API app + AndroidTest a passé. Ces deux vérifications ne constituent pas une exécution instrumentée API 37.
