# Recette native ATLAS V6.2

Exécution sur émulateur le 19 octobre 2026 UTC (20 octobre, heure de Paris). Cette recette couvre les nouveaux tests instrumentés Android, les captures natives et une tentative avec le Gateway OpenClaw configuré localement.

## Environnement

- AVD jetable `atlas_v62_disposable_api34`, série `emulator-5560`, API 34, émulateur Android 37.2.12.0, écran configuré à 480 dpi. Aucun appareil physique n’était connecté; aucun résultat sur Pixel réel n’est revendiqué.
- L’AVD API 37 a échoué avant les tests: l’image Google APIs 16 KB avec Emulator 37.2.12 puis 37.3.4 a fait planter SurfaceFlinger pendant RegionSampling et `screencap` sur l’assertion `hasReadColorBufferDma`. Les essais GPU enregistrés dans `/tmp/atlas-v62-emulator-*.log` n’ont pas rendu cet AVD utilisable. La recette a donc continué sur l’AVD jetable API 34 prévu comme repli.
- L’AVD personnel `Pixel_9_Pro_XL_2` n’a pas été utilisé.

Les valeurs synthétiques de Focus, objectifs, favoris et localisation ne représentent aucune donnée personnelle. La clef du Gateway est restée dans le fichier de configuration local à permissions `0600`; aucun secret n’a été envoyé à Android ni conservé dans les journaux.

## Compilation AndroidTest

La compilation forcée après le dernier changement des tests instrumentés a réussi:

```text
android/gradlew -p android :app:compileDebugAndroidTestKotlin --rerun-tasks --console=plain
BUILD SUCCESSFUL in 13s
```

Preuve: [compile-androidTest-after-mobility-adjustment.log](logs/recette-native/20261019T222518Z/compile-androidTest-after-mobility-adjustment.log). L’APK d’application et l’APK AndroidTest ont également été assemblés avec succès: [assemble-androidTest-after-mobility-adjustment.log](logs/recette-native/20261019T222535Z/assemble-androidTest-after-mobility-adjustment.log).

Le premier appel depuis la racine a échoué avant le démarrage de Gradle car il visait `./gradlew`; le wrapper est sous `android/`. Cet appel sans compilation est conservé dans [compile-androidTest-after-mobility-adjustment.log](logs/recette-native/20261019T222456Z/compile-androidTest-after-mobility-adjustment.log). Le lancement suivant avec le bon chemin est celui qui a réussi ci-dessus.

Le diagnostic S6 signalant `SemanticsConfiguration.containsKey` à la ligne 68 de `V62NativeUiCaptureTest.kt` ne correspond pas au fichier courant: la ligne 68 initialise `fontScale`, et le fichier ne contient ni `containsKey` ni `SemanticsConfiguration`. La compilation AndroidTest forcée ci-dessus passe.

## Interface native et captures

Les tests d’interface ont réussi sur les largeurs 360 dp et 320 dp, en paysage, ainsi que le test de l’écran Focus et des palettes. La matrice vérifie le thème sombre et AMOLED, l’accueil sans directive distante, cinq favoris, le tiroir et la recherche, les tailles de police 1,5× et 2×, les cibles tactiles d’au moins 48 dp, le pied fixe et l’absence de recouvrement. Les paires texte/fond des palettes sont calculées et vérifiées à au moins 4,5:1. TalkBack n’a pas été testé.

Les 18 captures natives sont présentes et leurs signatures PNG et dimensions ont été validées dans [capture-validation-local.log](logs/recette-native/20261019T221939Z/capture-validation-local.log): 1080×2400 pour 360 dp, 960×2400 pour 320 dp et 2400×1080 en paysage.

Exemples: [accueil sombre, Focus actif, 360 dp](captures/20261019T221939Z/v62-home-dark-focus-360dp.png), [recherche d’application, 320 dp](captures/20261019T221939Z/v62-app-search-320dp.png), [accueil paysage](captures/20261019T221939Z/v62-home-landscape.png), [écran Focus actif](captures/20261019T221939Z/v62-focus-active.png).

Résultats détaillés: [native-ui-360dp.log](logs/recette-native/20261019T221939Z/native-ui-360dp.log), [native-ui-320dp.log](logs/recette-native/20261019T221939Z/native-ui-320dp.log), [native-landscape.log](logs/recette-native/20261019T221939Z/native-landscape.log) et [native-focus-screen.log](logs/recette-native/20261019T221939Z/native-focus-screen.log).

## Localisation et service de mobilité

Les preuves suivantes ont réussi sur API 34:

- Consentement explicite de partage enregistré avant la préparation de l’injection.
- Refus de la permission OS renvoyant l’erreur fonctionnelle attendue.
- Fallback simulé API 26–29 renvoyant les états timeout et indisponible attendus.
- Révocation de permission pendant une acquisition simulée recontrôlée avant retour du résultat.
- Service de mobilité lancé depuis une activité visible, notification persistante avec action « Arrêter », arrêt du service et absence de coordonnées injectées brutes dans les résumés. Le rejeu après correction du test réussit: [mobility-visible-service-after-adjustment-retry.log](logs/recette-native/20261019T222535Z/mobility-visible-service-after-adjustment-retry.log).

Les tentatives initiales sans Activity visible ont expiré après `adb emu geo fix`; elles ne permettent pas de conclure sur l’accès Android 14 while-in-use: [première tentative](logs/recette-native/20261019T221939Z/location-injected-coarse.log), [seconde tentative](logs/recette-native/20261019T222535Z/location-injected-coarse-final-retry.log).

## Reprise ciblée avec Activity visible

Le test coarse lance `ActivityScenario.launch(MainActivity)`, confirme `RESUMED` et le focus de fenêtre, puis installe une écoute réelle `LocationManager` GPS sous coarse seulement. Le test AndroidTest vérifie que coarse est accordée et fine/background refusées; il ne demande jamais automatiquement la permission fine. Après l’injection, il appelle `LocationRepository` et `LocationCaptureManager` avec le `maxAgeMs=15000` de production. Il vérifie l’accuracy OS d’au moins 2 km, la fraîcheur du fix, le timestamp exact conservé dans le résumé et l’absence de coordonnées brutes dans celui-ci.

Le script synchronise le signal de l’Activity visible avec `adb emu geo fix`; le test ne considère un callback GPS valide que si son timestamp est au moins celui de l’injection. Cette condition écarte les fixes Android déjà en cache avant le début de l’injection. La recette ciblée assemble l’APK app et l’APK AndroidTest avant l’exécution: [assemblage du dernier essai](logs/recette-native/20261019T231642Z/gradle-assemble.log). La compilation Kotlin AndroidTest du dernier état a réussi (`BUILD SUCCESSFUL in 1s`): [compile-visible-location-final.log](logs/recette-native/20261019T231642Z/compile-visible-location-final.log).

Résultat des reprises ciblées sur `atlas_v62_disposable_api34` / API 34 (AVD connecté sous `emulator-5554`; `emulator-5560` n’était pas connecté):

1. `20261019T230911Z`: 1 test, 1 erreur d’initialisation. JUnit a refusé la méthode coarse car son expression Kotlin retournait `Int` (« should be void »); aucun marqueur ni fix n’a été produit. Signature ensuite corrigée en `Unit`: [instrumentation](logs/recette-native/20261019T230911Z/location-visible-main-activity-instrumentation.log).
2. `20261019T231012Z`: 1 test, 1 échec sur une assertion trop stricte qui exigeait que le résumé soit différent du fix Android déjà coarse. Le marqueur précède l’injection de 18 163 ms à cause de l’attente de fermeture du pipeline logcat; le script a depuis été corrigé pour interrompre logcat à la réception du marqueur. [instrumentation](logs/recette-native/20261019T231012Z/location-visible-main-activity-instrumentation.log), [marqueur et injection horodatés](logs/recette-native/20261019T231012Z/location-visible-main-activity-capture-active.log).
3. `20261019T231313Z`: 1 test, 1 échec `LOCATION_TIMEOUT` après 10 000 ms. L’injection a été envoyée juste avant la demande, sans écoute GPS préalable. [instrumentation](logs/recette-native/20261019T231313Z/location-visible-main-activity-instrumentation.log), [injection](logs/recette-native/20261019T231313Z/location-visible-main-activity-geo-fix.log).
4. `20261019T231540Z`: 1 test, 1 échec `LOCATION_TIMEOUT`. Une écoute GPS réelle était active, mais le seul callback livré correspondait au fix cache antérieur; l’injection a commencé ensuite. [instrumentation](logs/recette-native/20261019T231540Z/location-visible-main-activity-instrumentation.log), [marqueur et injection](logs/recette-native/20261019T231540Z/location-visible-main-activity-activity-visible.log).
5. `20261019T231642Z`: 1 test, 1 échec « active coarse Android location listener did not receive the injected fix ». Activity visible, coarse accordée, fine/background refusées et écoute GPS active ont tous été vérifiés avant l’injection. `adb emu geo fix` a répondu `OK`, mais aucun callback GPS avec timestamp postérieur à l’injection n’est arrivé dans les 10 s; l’historique des fournisseurs confirme un ancien callback à `01:16:48.246`, avant l’injection à `01:16:48.372`, puis la fin de l’écoute à `01:16:59.041`: [instrumentation](logs/recette-native/20261019T231642Z/location-visible-main-activity-instrumentation.log), [marqueur](logs/recette-native/20261019T231642Z/location-visible-main-activity-activity-visible.log), [injection](logs/recette-native/20261019T231642Z/location-visible-main-activity-geo-fix.log), [historique sans coordonnées](logs/recette-native/20261019T231642Z/avd-location-provider-events.log).

La dernière exécution n’a donc pas validé l’acquisition coarse: aucun nouveau fix d’émulateur n’a été fourni au listener réel après l’injection. Cela ne prouve pas un défaut de production; l’AVD n’a pas exposé le fix injecté à l’écoute coarse. Aucun changement de code de production n’a été fait. Comme le prérequis coarse a échoué, la mobilité mesurée n’a pas été lancée dans cette reprise et la suite instrumentée complète n’a pas été démarrée. Pour cette suite complète: 0 tests exécutés, 0 échec, 0 ignoré; elle est non exécutée, pas réussie.

Le récapitulatif des cinq tentatives, erreurs et étapes conditionnelles non lancées est dans [targeted-recipe-summary.txt](logs/recette-native/20261019T231642Z/targeted-recipe-summary.txt).

## Gateway réel

La preuve Gateway finale conservée utilise deux requêtes réelles `POST /v2/agent/chat` (HTTP 200 chacune). Elle valide le contexte Focus accéléré de 25 minutes simulées avec objectif 25/120, la proposition et son acceptation avec journal d’effet, une requête ultérieure après révocation Goals, puis le mode hors ligne quand le backend est arrêté. Les deux tests instrumentés finaux ont chacun `OK (1 test)`: [Gateway](logs/recette-native/gateway-final/v62-gateway-instrumentation.log), [backend](logs/recette-native/gateway-final/v62-gateway-backend.log), [hors ligne](logs/recette-native/gateway-final/v62-offline-instrumentation.log). Aucun nouvel appel au modèle n’a été fait pendant la reprise coarse.

## État restant

- Les 256 tests JVM et les vérifications lint/location sont signalés passants par S10; ils n’ont pas été relancés dans cette recette.
- L’acquisition coarse sous Activity visible reste non validée: `adb emu geo fix` n’a pas produit de callback postérieur à l’injection sur l’AVD API 34. La mobilité avec mesure réelle et la suite instrumentée complète restent conditionnées à une recette coarse réussie.
- La preuve Gateway finale et le parcours hors ligne sont enregistrés; aucun appel supplémentaire au modèle n’est demandé ici.
- La matrice de captures natives précédente comporte 18 PNG validés. Les fichiers UI disponibles de S4 étaient présents lors du dernier assemblage AndroidTest, mais la matrice UI instrumentée n’a pas été rejouée et les captures n’ont pas été rafraîchies, car le prérequis coarse a échoué. TalkBack n’a pas été validé.
- Aucune correction de code de production n’a été faite dans cette tranche; aucune cause de production n’est prouvée par l’échec d’injection de l’AVD.

Le statut global de la recette est **partiel**: compilation AndroidTest et preuves Gateway réussies; matrice de captures déjà validée avant les derniers ajustements UI; acquisition coarse et mobilité mesurée non validées; suite instrumentée complète non exécutée.
