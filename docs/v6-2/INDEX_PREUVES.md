# Index des preuves représentatives V6.2

Index des captures et journaux déjà suivis dans le dépôt. Aucune capture ni copie de log n'a été ajoutée pour ce lot.

## HOME et tiroir

- [Tiroir, 320 dp](captures/final/v62-app-drawer-320dp.png) et [360 dp](captures/final/v62-app-drawer-360dp.png) : rendu V6.2 avant le changement lazy, utile comme référence visuelle.
- [HOME à fontScale 2×, 320 dp](captures/final/v62-home-font-2_0-320dp.png) et [360 dp](captures/final/v62-home-font-2_0-360dp.png) : accès permanents du HOME à grande échelle de texte.
- [Validation des captures V6.2](logs/livraison/capture-validation.txt) : manifeste des captures historiques.

## Tests Android et compilation historiques

- [Résultats AndroidTest V6.2](logs/livraison/instrumentation.xml) et [sortie Gradle connectée](logs/livraison/connected-debug-android-test.log) : recette consignée avant le présent lot. Le statut distinct de la baseline orchestrateur est détaillé dans [l'addendum de livraison](LIVRAISON.md).
- [Journaux de recette Android 14](logs/recette-complete/20261019T233026Z/connected-debug-android-test.log) et [résumé des reprises](logs/recette-complete/20261019T233026Z/follow-up/validation-summary.txt).
- [Essais d'interface 320 dp](logs/recette-complete/20261019T233026Z/follow-up/native-home-matrix-320dp.log) et [classe complète à 360 dp](logs/recette-complete/20261019T233026Z/follow-up/native-full-class-360dp.log).

## Localisation

- [Refus et révocation de permission](logs/recette-complete/20261019T233026Z/follow-up/location-permission-revocation.log) ; [état refusé](logs/recette-complete/20261019T233026Z/follow-up/location-denied-state.log).
- [Limites Android 17](RAPPORT_ANDROID17.md) : aucun résultat ne revendique une validation Android 17 quand l'AVD ne permet pas la recette.
- [Matrice de l'issue #1](ISSUE_1_LOCALISATION.md) : critères, preuves actuelles et reliquats.

## Lot D

- [Test ajouté](../../android/app/src/androidTest/java/dev/atlas/coach/app/LauncherDrawerLazyInstrumentationTest.kt) : compilation confirmée par `assembleDebugAndroidTest`; l'exécution sur AVD reste à faire par l'orchestrateur.
- [Code du tiroir](../../android/app/src/main/java/dev/atlas/coach/app/LauncherHomeScreen.kt).
