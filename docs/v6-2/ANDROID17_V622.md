# ATLAS V6.2.2 — S4 A2 Android 17

## État de recette

Cette recette a été menée sur un AVD Android 17 jetable avec les APK présents au début de S4. Elle **ne qualifie pas le code V6.2.2 final**. L’AVD a démarré, mais Android a perdu ses services système pendant les essais instrumentés. Les comportements applicatifs dépendant de ces services restent donc bloqués ou non exécutés.

La matrice A2 demandée comporte un `PASS` (installation propre de l’APK application), douze `BLOCKED` et quatre `NOT_RUN`. Aucun scénario applicatif n’a obtenu un résultat fonctionnel `PASS` ou `FAIL`. La capture d’écran est évaluée séparément et reste `BLOCKED`.

## Environnement et garde-fous

- Branche observée au début de S4 : `feat/atlas-v6-2-2`, HEAD `bf647cc`.
- AVD neuf `atlas_v622_disposable_api37`, image `system-images;android-37.2;google_apis_ps16k;x86_64`, profil Pixel 5, stocké sous `/tmp/atlas-v622-s4/avd`.
- Identité vérifiée au démarrage : `ro.boot.qemu.avd_name=atlas_v622_disposable_api37`, `ro.build.version.sdk=37`, `ro.build.version.sdk_full=37.2`, `sys.boot_completed=1`.
- Port console `5582`, port ADB `5583`. Serveur ADB isolé sur `5038`; les commandes de cible utilisent explicitement `-s emulator-5582`. Aucun appareil physique ni AVD personnel n’a été ciblé. Aucun appel Gradle n’a été lancé par S4.
- L’AVD a été lancé avec `-wipe-data`; il est arrêté à la fin de S4. Son registre demeure sous `/tmp` pour une recette ultérieure. Le lanceur précédent observé était `com.google.android.apps.nexuslauncher`; aucun changement de rôle HOME n’a eu lieu.
- S4 n’a modifié ni le code ni le `targetSdk`; aucune migration vers 37 n’a été faite.

## Résultats techniques indépendants

| Capacité | Résultat observé |
| --- | --- |
| Création, démarrage et identification AVD | `PASS` : AVD/API conformes, démarrage terminé et services Android disponibles pendant 15 secondes au début d’un essai. |
| Installation propre de l’APK application | `PASS` : `adb install` a répondu `Success` sur l’AVD vierge. SHA-256 observé : `1993e38ae07984181b4f048c138f8cff9df339b5426b87c2f78cc010a8757d2d`. |
| Installation de l’APK d’instrumentation | `BLOCKED` lors de l’essai initial : le service package a répondu `Broken pipe`. Une installation ultérieure a réussi après que l’empreinte de l’APK de test eut changé; elle ne prouve donc pas l’installation de l’APK de test S0. |
| Lancement de l’application | `BLOCKED` : `am start` n’a pas lancé l’activité; peu après, les services `package`, `activity`, `location` et d’autres services Android ont disparu. Le message d’activité introuvable a été observé pendant cette perte de services et n’est pas classé comme défaut produit démontré. |
| Instrumentation | `BLOCKED` : les appels `am instrument` étaient ciblés par classe et méthode, avec le serial explicite. Ils se sont terminés par `INSTRUMENTATION_ABORTED: System has crashed`, ou par une instrumentation indisponible après l’échec d’installation. Aucun verdict d’assertion n’est retenu. |
| Permissions, localisation, notifications et commandes UI | `BLOCKED` si le scénario exigeait ces services : les commandes `pm`, `cmd location` et plusieurs réglages ont échoué quand Android ne fournissait plus le service correspondant. |
| HOME | Le rôle initial du launcher Android a été lu. Les sélections HOME et restauration manuelles n’ont pas été faites, puisque l’application n’était pas utilisable. Le launcher précédent n’a donc pas été modifié. |
| Capture d’écran | `BLOCKED`, indépendamment des tests applicatifs : la capture après installation et tentatives d’instrumentation a produit 72 octets, pas un PNG, avec `Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`. L’échec n’a pas servi de précondition aux essais instrumentés. |

### Provenance des APK

Le rapport S0 confirme l’assemblage de `app-debug.apk` et `app-debug-androidTest.apk`, sans archiver leurs SHA-256. Au début de S4, les empreintes observées étaient celles indiquées dans la preuve jointe. L’APK application a gardé la même empreinte lors d’un essai suivant; l’APK de test est passé de `9b8790cf28f7ff513db8fae31604fde35d91759b85265cf806f7ca9a33404a8f` à `6e85eb32da32ef7ca68631cace7eeebce320ec76a11d71575dc3473faa173f43`. Une paire plus récente a ensuite été détectée et l’essai a été interrompu avant installation. Aucune compilation n’a été lancée par S4.

Cette absence de hash S0 empêche de démontrer cryptographiquement que les artefacts utilisés correspondent exactement à ceux compilés par S0. Les résultats d’instrumentation sur l’APK de test modifié ne sont pas présentés comme résultats de la baseline.

## Matrice obligatoire A2

| # | Scénario | Résultat | Constat et preuve |
| ---: | --- | --- | --- |
| 1 | Installation propre | **PASS** | `adb install` de l’APK application répond `Success` après démarrage avec `-wipe-data`. Provenance exacte du binaire S0 à confirmer au prochain passage par hash attendu. |
| 2 | Lancement sans rôle HOME | **BLOCKED** | Le launcher système était le détenteur HOME. Le lancement ATLAS n’a pas abouti avant la disparition des services Android. |
| 3 | Attribution volontaire du rôle HOME | **NOT_RUN** | Aucun changement HOME n’a été fait; le parcours volontaire demande une UI utilisable. |
| 4 | Retour au launcher précédent | **NOT_RUN** | Le rôle HOME n’ayant pas été modifié, aucune restauration n’était nécessaire. Le détenteur précédent est resté inchangé. |
| 5 | Localisation approximative accordée pendant l’utilisation | **BLOCKED** | L’octroi de permission n’a pas pu être vérifié par Android; aucun parcours runtime utilisable n’a été observé. |
| 6 | Refus de localisation | **BLOCKED** | Le test instrumenté ciblé a été lancé, puis interrompu par `System has crashed`; aucun résultat de refus n’est retenu. |
| 7 | Révocation de localisation | **BLOCKED** | Le test de révocation n’a pas produit de résultat exploitable après la perte des services Android. |
| 8 | Fournisseur de localisation désactivé | **BLOCKED** | La commande de désactivation a échoué et l’état désactivé n’a pas pu être confirmé; le test conditionnel n’a pas été lancé. |
| 9 | Timeout de capture | **BLOCKED** | Le test ciblé de timeout/indisponibilité a été invoqué, mais l’instrumentation n’a pas abouti. Aucun comportement de timeout n’est démontré. |
| 10 | Service de mobilité démarré explicitement | **NOT_RUN** | Le test de service était conditionné par la confirmation du fournisseur désactivé; le prérequis n’a pas été atteint. |
| 11 | Notification « Arrêter » | **NOT_RUN** | Le service et sa notification n’ont pas été démarrés; aucun contrôle Stop n’a été effectué. |
| 12 | Force-stop sans relancement silencieux | **BLOCKED** | `force-stop` a été tenté, mais le service n’avait pas été lancé dans un état Android stable. L’absence de PID après la commande ne prouve pas le comportement de relancement. |
| 13 | Rotation portrait/paysage | **BLOCKED** | Les commandes de rotation ont échoué avec les services système indisponibles; aucun changement d’orientation vérifié. |
| 14 | Police Android 2× | **BLOCKED** | Le réglage de taille de police et l’observation UI n’ont pas pu être validés. |
| 15 | Focus en cours, pause, retour à l’application | **BLOCKED** | Le test ciblé hors ligne a été invoqué, mais l’instrumentation a été interrompue par le crash système. |
| 16 | Révocation du Coach | **BLOCKED** | Le test de révocation a été invoqué, sans résultat d’assertion exploitable. |
| 17 | HOME et pause d’urgence sans backend | **BLOCKED** | Le test ciblé a été interrompu; le rôle HOME n’a pas été attribué et l’action UI n’a pas été observée. Aucun backend n’a été sollicité. |

## Rejouer après intégration du code V6.2.2

Après que le code V6.2.2 et ses APK auront été produits par l’étape qui en a la charge, fournir les chemins et empreintes exacts afin que la recette refuse une paire inattendue :

```bash
ATLAS_V622_APP_APK=/chemin/app-debug.apk \
ATLAS_V622_TEST_APK=/chemin/app-debug-androidTest.apk \
ATLAS_V622_EXPECTED_APP_SHA256=REMPLACER_SHA256_APP \
ATLAS_V622_EXPECTED_TEST_SHA256=REMPLACER_SHA256_TEST \
./scripts/v622-android17-recipe.sh
```

Le script n’exécute pas Gradle. Il recrée un état propre de l’AVD et conserve les sorties complètes sous `/tmp/atlas-v622-s4`. Rejouer la matrice avec les APK finaux, y compris l’installation #1. Après lancement fonctionnel, effectuer manuellement le choix volontaire d’ATLAS dans **Paramètres → Applications → Applications par défaut → Application d’accueil**, vérifier HOME, puis restaurer `com.google.android.apps.nexuslauncher`. Rejouer séparément les cas de localisation, notification Stop, force-stop, rotations, police 2×, Focus, révocation Coach, pause d’urgence et capture d’écran.

Les sorties de cette recette sont un diagnostic de l’environnement Android 17, pas une qualification V6.2.2 finale ni une validation sur Pixel physique.

## Preuves

- [Résumé minimal et commandes observées](preuves-v622/android17-s4-snapshot.txt)
- [Log complet — premier essai avec l’APK de test observé au départ](</tmp/atlas-v622-s4/run-20261020T192820Z/recipe.console.log>)
- [Log complet — installation réussie, instrumentation interrompue et capture bloquée](</tmp/atlas-v622-s4/run-20261020T193739Z/recipe.console.log>)
- [Log complet de démarrage de l’émulateur correspondant](</tmp/atlas-v622-s4/run-20261020T193739Z/emulator.log>)
- [Résultat texte de la capture post-essais](</tmp/atlas-v622-s4/run-20261020T193739Z/screencap-after-tests.result.txt>)
- [Sortie invalide de la capture (72 octets)](</tmp/atlas-v622-s4/run-20261020T193739Z/screencap-after-tests.invalid-output.txt>)

## Corrections après revue S4 — vérification statique uniquement

Le rapport d’exécution ci-dessus reste le constat historique accepté : `1 PASS / 12 BLOCKED / 4 NOT_RUN`, à titre de diagnostic seulement. Les corrections qui suivent ont été vérifiées sans démarrer d’AVD et sans Gradle; elles ne changent aucun résultat Android observé et ne qualifient pas le code V6.2.2 final.

- **Disponibilité des services** : le contrôle exige maintenant exactement `Service <nom>: found`. `not found`, réponse vide, texte inattendu et timeout ont des classifications distinctes. Chaque interrogation ajoute son résultat, sa sortie et son code ADB à `framework-service-probes.log`; un timeout reste `BLOCKED_TIMEOUT`.
- **Paire d’APK** : avant toute installation, le script copie les deux APK dans le répertoire unique de la recette, compare les empreintes source avant/après copie et vérifie l’empreinte attendue sur chaque copie. Il rend les copies non inscriptibles et n’utilise ensuite que ces chemins de snapshot pour `adb install`.
- **Délais bornés** : toutes les commandes ADB, dont instrumentation et capture, passent par GNU `timeout` avec des limites configurables. Les timeouts sont inscrits dans `adb-timeouts.log`; les étapes, captures et résultats instrumentés les classent `BLOCKED`, jamais `PASS`. Un contrôle synthétique fait expirer `sleep` et vérifie le code 124 ainsi que la ligne de journal.
- **Audit FQCN des appels** : la revue a trouvé que la liste de validation avait le bon `dev.atlas.coach.AtlasLaunchTest`, alors que l’appel `run_instrumented` utilisait encore `dev.atlas.coach.app.AtlasLaunchTest`. L’appel est corrigé : le fichier [`AtlasLaunchTest.kt`]($HOME/perso/AndroCoach/atlas-preparation/android/app/src/androidTest/java/dev/atlas/coach/AtlasLaunchTest.kt:1) déclare `package dev.atlas.coach`. La validation statique extrait maintenant chaque cible `run_instrumented`, exige sa présence dans l’inventaire, puis vérifie le chemin de fichier dérivé du FQCN, le `package`, la classe et la méthode. Les 7 cibles réellement invoquées et les 2 cibles mobilité conservées pour audit sont vérifiées; un test synthétique rejette explicitement l’ancien FQCN erroné.
- **Résultat d’instrumentation** : un verdict `PASS` exige la méthode demandée, `numtests=1`, `STATUS_CODE=0`, `OK (1 test)` et `INSTRUMENTATION_CODE=-1`. `OK (0 tests)`, une autre méthode, un échec, un abort ou un timeout ne peuvent pas passer.
- **Mobilité et fournisseur** : le script n’éteint plus le fournisseur Android avant d’appeler l’assertion mobilité. Le comportement de fournisseur désactivé dans le test API 26–29 est limité à son faux adaptateur; faute de test Android dédié, le cas fournisseur système désactivé est signalé `NOT_RUN`. Le démarrage mobilité et la notification Stop sont aussi `NOT_RUN` avec cause : le test réclame un nouveau fix coarse horodaté après injection hôte synchronisée, parcours que cette recette ne pilote pas. Aucun emplacement réel ou synthétique n’a été injecté et aucun état du fournisseur système n’a été changé.
- **Capture indépendante** : son statut est calculé séparément de celui de l’instrumentation; un échec/timeout de capture ne modifie pas le classement des essais instrumentés.

Vérifications réellement exécutées après revue :

```text
bash -n scripts/v622-android17-recipe.sh                         PASS
./scripts/v622-android17-recipe.sh --self-test                  PASS, 28 assertions / 0 échec
AVD lancé / ADB appelé / Gradle appelé pendant --self-test       non / non / non
```

`ShellCheck` n’est pas installé dans cet environnement.

- [Journal complet des vérifications statiques et synthétiques](</tmp/atlas-v622-s4/run-20261020T195821.158565078/recipe.console.log>)
- Les tests synthétiques couvrent le helper complet de service (`found` donne `PASS`; `not found`, réponse absente et timeout restent bloquants), réponse inattendue, l’audit de chaque invocation FQCN, le rejet de `OK (0 tests)`, abort, timeout, copies SHA-256 concordantes et empreinte incorrecte rejetée.

### Reliquat pour la reprise avec les APK finaux

Après stabilisation du code et fourniture d’une paire d’APK finale identifiée par ses SHA-256, rejouer le script avec ces empreintes. Refaire toute la matrice sur l’AVD API 37, en conservant les statuts `PASS`/`FAIL`/`BLOCKED`/`NOT_RUN` et les preuves de chaque étape. La synchronisation d’injection coarse reste nécessaire pour exécuter le cas mobilité : préparer le consentement et l’Activity visible, injecter un fix synthétique avec `adb emu geo fix` après le marqueur prévu par le test, puis lancer l’assertion de mesure/notification/arrêt; cette orchestration n’est pas encore intégrée à la recette. Valider le cas réel fournisseur désactivé séparément avec un test Android dont l’objectif explicite est cet état. Répéter la capture de façon indépendante et suivre manuellement le changement puis la restauration volontaire de HOME uniquement si le parcours HOME est tenté.

## Reprise runtime des APK candidat S5 — 21 octobre 2026

Cette section est distincte du diagnostic historique S4. Elle suit les APK candidat S5 C+B2 gelés; les résultats antérieurs et les contrôles statiques ne sont pas reportés comme résultats runtime du candidat.

### Artefacts et environnement

- Branche `feat/atlas-v6-2-2`, HEAD observé `bf647cc18a142dd0f5cd5e68b21c36d511014388`.
- App `/tmp/atlas-v622-final-candidate/app-debug.apk`, SHA-256 `dfb85688a98d6175395e87f25249dcd265b2802723a708a8205b2e3029e2c5a9`.
- AndroidTest `/tmp/atlas-v622-final-candidate/app-debug-androidTest.apk`, SHA-256 `353a8d0e4cd89658d528bd7da457447c0b11fb482511f395661659779cbaeeb7`.
- Les deux copies read-only de la recette ont passé les comparaisons SHA source avant/après copie et SHA attendu. L’installation n’a pas commencé.
- AVD `atlas_v622_disposable_api37`, API 37.2, empreinte `google/sdk_gphone16k_x86_64/emu64xa16k:17/CP41.260831.007/16416850`, `sys.boot_completed=1`; ADB isolé `5038`; console/ADB `5582/5583`; serial explicite `emulator-5582`. `ANDROID_ADB_SERVER_PORT=5038` est exporté pour l’auto-enregistrement interne d’Emulator.
- Le processus `atlas_v622_s1_api34` était visible sur 5580 pendant la collecte; S4 ne l’a ni ciblé ni arrêté et n’a lancé aucune commande Gradle. Aucun appareil physique n’a été ciblé.
- Les cinq sondes de service Android ont d’abord répondu exactement `Service <nom>: found`, puis sont retombées simultanément sur `Service <nom>: not found`. Les sondes n’ont pas obtenu une fenêtre stable; la recette a été arrêtée avant installation et instrumentation. Aucune permission, UI, HOME, injection coarse ou désactivation de fournisseur n’a été exécutée. L’AVD API37 et son serveur ADB 5038 sont maintenant arrêtés.

### Cause plateforme observée

Le paquet ATLAS n’était pas installé; aucun test ATLAS ne tournait au moment des disparitions. Le journal principal contient six messages `ot-daemon: system_server is dead` entre 00:43:38 et 00:45:00, suivis de redémarrages partiels où `SystemServiceRegistry` signale des services non publiés. Le buffer crash contient 35 signaux fatals, dont :

- `/vendor/bin/hw/android.hardware.uwb-service` abort après échec d’ouverture de `/dev/uwb0` (`No such device or address`);
- `surfaceflinger` abort dans `GoldfishMapper::readFromHost` sur `Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`.

`lmkd` a aussi tué `com.google.android.cellbroadcastreceiver` et `com.google.android.dialer` au watermark mémoire. Les buffers `AndroidRuntime:E`, `SystemServer:E`, `Watchdog:E` sont vides; aucun `am_crash` ou stack Java fatal de `system_server` n’a été trouvé. Ces preuves établissent l’instabilité native/framework de l’AVD, mais pas la cause racine de chaque mort de `system_server`; S4 ne l’attribue pas à ATLAS. Aucun verdict produit n’en découle.

### Matrice A2 du candidat final

Tous les scénarios sont `NOT_RUN` : la copie SHA a réussi, mais le gate framework a bloqué le run avant installation. Les statuts indiquent l’exécution réelle de chaque scénario candidat.

| # | Scénario | Résultat | Constat et preuve |
| ---: | --- | --- | --- |
| 1 | Installation propre | **NOT_RUN** | SHA snapshots conformes; `adb install` non exécuté. |
| 2 | Lancement sans rôle HOME | **NOT_RUN** | Installation non atteinte. |
| 3 | Attribution volontaire du rôle HOME | **NOT_RUN** | Settings UI non ouverte, rôle non modifié. |
| 4 | Retour au launcher précédent | **NOT_RUN** | Le rôle HOME n’a pas changé. |
| 5 | Localisation approximative accordée pendant l’utilisation | **NOT_RUN** | Aucune permission ou capture applicative exercée. |
| 6 | Refus de localisation | **NOT_RUN** | Méthode AndroidTest non exécutée. |
| 7 | Révocation de localisation | **NOT_RUN** | Aucune révocation runtime ni assertion applicative. |
| 8 | Fournisseur de localisation désactivé | **NOT_RUN** | Fournisseur non touché; test non atteint. |
| 9 | Timeout de capture | **NOT_RUN** | Aucun test timeout/fallback invoqué. |
| 10 | Service de mobilité démarré explicitement | **NOT_RUN** | App non installée; aucun consentement/injection. |
| 11 | Notification « Arrêter » | **NOT_RUN** | Aucun service ATLAS démarré. |
| 12 | Force-stop sans relancement silencieux | **NOT_RUN** | Aucun processus ATLAS créé. |
| 13 | Rotation portrait/paysage | **NOT_RUN** | UI non exercée. |
| 14 | Police Android 2× | **NOT_RUN** | UI non exercée. |
| 15 | Focus en cours, pause, retour hors ligne | **NOT_RUN** | AndroidTest non exécuté. |
| 16 | Révocation proactive du Coach | **NOT_RUN** | Tests controller/publisher non exécutés. |
| 17 | HOME et pause d’urgence sans backend | **NOT_RUN** | AndroidTest non exécuté. |

La capture d’écran indépendante est également `NOT_RUN`, car le gate a arrêté la recette avant la capture. Les 7 méthodes GoalFocus et 4 méthodes controller/publisher figurent dans l’audit statique des 22 invocations, mais leur présence FQCN ne vaut pas exécution runtime ni qualification Android 17.

### Vérifications et reprise

La dernière version du script a passé `bash -n` et `--self-test` avec **37 assertions / 0 échec**. Les contrôles synthétiques couvrent le parsing positif exact des services, l’arrêt sur disparition des cinq services après réponses positives, les snapshots SHA, timeouts, refus de `OK (0 tests)`, audit FQCN/fichier/package/classe/méthode des 22 invocations, sélection HOME synthétique et 17 lignes `NOT_RUN` de matrice sur gate bloqué. Le self-test n’a lancé ni AVD, ni ADB, ni Gradle. Le garde framework et le trap corrigé après la tentative runtime ont été validés statiquement; aucun autre run AVD n’a été lancé.

Commande candidat utilisée :

```bash
ATLAS_V622_APP_APK=/tmp/atlas-v622-final-candidate/app-debug.apk \
ATLAS_V622_TEST_APK=/tmp/atlas-v622-final-candidate/app-debug-androidTest.apk \
ATLAS_V622_EXPECTED_APP_SHA256=dfb85688a98d6175395e87f25249dcd265b2802723a708a8205b2e3029e2c5a9 \
ATLAS_V622_EXPECTED_TEST_SHA256=353a8d0e4cd89658d528bd7da457447c0b11fb482511f395661659779cbaeeb7 \
./scripts/v622-android17-recipe.sh
```

Le runtime candidat est archivé dans [la preuve de reprise S4](preuves-v622/android17-v622-final-candidate.txt). Reprendre les 17 scénarios et les 11 tests GoalFocus/révocation après stabilisation de l’image et des ressources Android 17. Ne pas recycler le diagnostic S4 historique ni les tests fake comme qualification finale; aucune validation sur Pixel physique n’est revendiquée.

## Preuves du run candidat

- [Résumé runtime, causes observées et matrice A2](preuves-v622/android17-v622-final-candidate.txt)
- [Console complète](</tmp/atlas-v622-s4/run-20261020T224158.158124016/recipe.console.log>)
- [Sondes framework](</tmp/atlas-v622-s4/run-20261020T224158.158124016/framework-service-probes.log>)
- [Buffer crash natif](</tmp/atlas-v622-s4/run-20261020T224158.158124016/early-framework-crash-buffer.log>)
- [Buffer system_server filtré (aucune erreur)](</tmp/atlas-v622-s4/run-20261020T224158.158124016/early-framework-system-errors.log>)
- [Journal principal framework](</tmp/atlas-v622-s4/run-20261020T224158.158124016/main-buffer.log>)
- [Événements Android](</tmp/atlas-v622-s4/run-20261020T224158.158124016/events-buffer.log>)
- [Liste de services Binder](</tmp/atlas-v622-s4/run-20261020T224158.158124016/system-service-list.log>)
- [Vérifications statiques finales, 37 assertions](</tmp/atlas-v622-s4/run-20261020T224942.410051952/recipe.console.log>)
