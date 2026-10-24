# Reprise API 34 non exécutée — 21 octobre 2026

La reprise complète demandée a atteint la porte hôte avant le build et avant tout démarrage d’émulateur. Statut : `NOT_RUN`, cause `HOST_ENVIRONMENT`. Dernière commande `scripts/v623-android-qa.sh api34` (14:44:03 UTC), sortie :

```text
stage=pre_build memavailable_kib=7498600 threshold_kib=8388608 memory_gate=NOT_RUN
NOT_RUN reason=host_memavailable_below_8GiB memavailable_kib=7498600 threshold_kib=8388608 cause=HOST_ENVIRONMENT
NOT_RUN reason=host_memavailable_below_8GiB matrix=/tmp/v623-qa/api34-20261021T144403Z/scenario-matrix.tsv
```

Logs : `/tmp/v623-qa/api34-20261021T144403Z/recipe.console.log`; résultat : `/tmp/v623-qa/api34-20261021T144403Z/recipe.result.txt`; matrice complète du gate : [scenario-matrix-api34-host-gate.tsv](scenario-matrix-api34-host-gate.tsv). Aucun AVD n’a été créé ou démarré et aucun XML n’existe dans cette reprise; `TESTS=0` n’est pas un succès.

## Vérifications API 34 partielles antérieures

La tentative `/tmp/v623-qa/api34-20261021T134714Z/` a bien prouvé l’installation/lancement, le choix HOME dans Réglages, l’ouverture d’ATLAS via HOME et la restauration de `com.google.android.apps.nexuslauncher`. Ce sont les deux `PASS` de portée limitée conservés dans [API37_MATRICE.md](../API37_MATRICE.md).

Sa tâche Gradle n’avait exécuté aucun test : `adb: device 'emulator-5580' not found`, `BUILD SUCCESSFUL`, `XML_FILES=0 TESTS=0`; l’ancienne recette avait malgré tout écrit `suite_status=PASS`. Classification : `TEST_HARNESS_BUG`, corrigée dans le harnais courant par ADB isolé, `ANDROID_SERIAL` explicite et validation des XML réels. Cette tentative ne prouve pas que la matrice Android 14 reste verte.

## État hôte et ressources

Au gate API 34 : `MemAvailable=7498600 KiB`, inférieur à `8388608 KiB`; au contrôle de 14:44 UTC, swap observé à `2.0/2.0 GiB`. `adb devices -l` n’affichait que `emulator-5582`, propriété d’une autre session. Son serial n’a pas reçu de commande; aucune opération n’a été envoyée sur l’AVD ou le téléphone.

Les sept mesures de la campagne différentielle API 37/API 35 restent sous 8 GiB; elles figurent dans [experiences-differentielles-api37.tsv](experiences-differentielles-api37.tsv). La commande `env GRADLE_USER_HOME=/tmp/v623-qa/gradle-home ./gradlew --no-daemon --project-cache-dir /tmp/v623-qa/project-cache --stop` a répondu `1 Daemon stopped`, mais n’a pas porté la mémoire disponible au seuil. Il n’est donc pas sûr de lancer un autre AVD dans cet état.
