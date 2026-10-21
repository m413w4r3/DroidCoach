# API 34 — raison de ne pas relancer la matrice complète

Une tentative partielle API 34 est conservée sous `/tmp/v623-qa/api34-20261021T134714Z/`. Elle prouve l’installation/lancement ATLAS, le choix du rôle HOME par Réglages, l’ouverture via HOME et la restauration de `com.google.android.apps.nexuslauncher`. L’étape Gradle qui suivait n’a lancé aucun test : `Failed to get device API level for emulator-5580`, `adb: device 'emulator-5580' not found`, `BUILD SUCCESSFUL`, `XML_FILES=0 TESTS=0`. L’ancienne recette a néanmoins rendu `suite_status=PASS`; c’est un `TEST_HARNESS_BUG` corrigé ensuite.

La reprise avec le harnais corrigé est `NOT_RUN` dans cette session. Lors du contrôle read-only du 21 octobre 2026, `adb devices -l` affichait l’AVD réservé `emulator-5582` (`atlas_v623_ui_api34`) encore actif; `free -h` indiquait 3,5 GiB disponibles et 2,0/2,0 GiB de swap utilisé. La sortie `ps` donnait 6 538 056 KiB RSS pour QEMU (environ 6,2 GiB). Le run API 37 concurrent précédent avait lui-même vu `am_low_memory` et des redémarrages répétés de `system_server`; démarrer un second AVD aurait pu perturber l’autre session. Aucun accès, arrêt ou changement n’a été envoyé à `emulator-5582`.

Commandes de contrôle : `adb devices -l`, `free -h`, `ps -eo pid,rss,args | rg 'qemu-system-x86_64-headless -avd atlas_v623'`. Aucun AVD API 34 supplémentaire n’a été démarré après ce contrôle.
